package com.elfmcys.ysmvr;

import com.elfmcys.yesstevemodel.capability.ModelInfoCapability;
import com.elfmcys.yesstevemodel.capability.PlayerCapability;
import com.elfmcys.yesstevemodel.client.entity.LivingAnimatable;
import com.elfmcys.yesstevemodel.client.entity.PlayerPreviewEntity;
import com.elfmcys.yesstevemodel.client.renderer.ModelPreviewRenderer;
import com.elfmcys.yesstevemodel.client.renderer.RendererManager;
import com.elfmcys.yesstevemodel.geckolib3.core.builder.Animation;
import com.elfmcys.yesstevemodel.geckolib3.core.builder.ILoopType;
import com.elfmcys.yesstevemodel.geckolib3.core.controller.IAnimationController;
import com.elfmcys.yesstevemodel.geckolib3.core.controller.PredicateBasedController;
import com.elfmcys.yesstevemodel.geckolib3.core.controller.controllers.PlayerAnimationController;
import com.elfmcys.yesstevemodel.geckolib3.core.enums.AnimationState;
import com.elfmcys.yesstevemodel.geckolib3.core.molang.util.StringPool;
import com.elfmcys.yesstevemodel.geckolib3.core.processor.IBone;
import com.elfmcys.yesstevemodel.geckolib3.geo.animated.AnimatedGeoModel;
import com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoBone;
import com.elfmcys.yesstevemodel.network.NetworkHandler;
import com.elfmcys.yesstevemodel.network.message.C2SPlayAnimationPacket;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.lang.reflect.Field;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ModernYSM/OpenYSM（modId=yes_steve_model，2.6.x 逆向重建版）桥接。
 * <p>
 * 注意：ModernYSM 的类名是社区重建的，与官方混淆 2.6.5 jar 不互通——
 * 本桥接只能配 ModernYSM 构建产物运行。
 */
@OnlyIn(Dist.CLIENT)
public final class ModernYsmBridge implements Bridge {
    public static final String CONTROLLER_NAME = "ysmvr_pose";

    public static final ModernYsmBridge INSTANCE = new ModernYsmBridge();

    private static final Map<UUID, PoseOverrideStore> STORES = new ConcurrentHashMap<>();
    private static final Map<UUID, ModernVrPoseController> CONTROLLERS = new ConcurrentHashMap<>();

    /** LivingAnimatable.armorBoneOffset（YSM 头部追踪的“上一帧保存值”），反射直改。 */
    private static final Field ARMOR_BONE_OFFSET;

    private static int syncLogCounter;
    private static int forceLogCounter;

    static {
        Field f = null;
        try {
            f = LivingAnimatable.class.getDeclaredField("armorBoneOffset");
            f.setAccessible(true);
        } catch (Throwable ignored) {
        }
        ARMOR_BONE_OFFSET = f;
    }

    private ModernYsmBridge() {
    }

    @Override
    public String controllerName() {
        return CONTROLLER_NAME;
    }

    @Override
    public void attach(LocalPlayer player) {
        if (player == null) {
            return;
        }
        var store = STORES.computeIfAbsent(player.getUUID(), uuid -> new PoseOverrideStore());
        var controller = CONTROLLERS.computeIfAbsent(player.getUUID(),
                uuid -> new ModernVrPoseController(store));

        PlayerCapability.get(player).ifPresent(animatable -> {
            // 直接遍历列表判重（getAnimationControllerByName 的缓存 Map 不会包含新挂载的 controller）
            var controllers = animatable.getAnimationData().getAnimationControllers();
            IAnimationController<?> existing = null;
            for (var c : controllers) {
                if (CONTROLLER_NAME.equals(c.getName())) {
                    existing = c;
                    break;
                }
            }
            if (existing == null) {
                animatable.addAnimationController(controller);
                YsmVrMod.LOGGER.info("[ysmvr] ModernYSM pose controller attached to {}",
                        player.getGameProfile().getName());
            } else {
                // 确保我们的 controller 始终位于所有 YSM 内建 controller 之后。
                // 否则内建动画（如 cap/idle）可能在最后覆盖 Head，导致 store 丢失。
                controllers.remove(existing);
                controllers.add(existing);
            }
            StringBuilder order = new StringBuilder();
            for (var c : controllers) {
                if (order.length() > 0) {
                    order.append(", ");
                }
                order.append(c.getName());
            }
            YsmVrMod.LOGGER.info("[ysmvr] animation controller order: {}", order);
        });
    }

    @Override
    public void forceAnimationActive(LocalPlayer player) {
        if (VivecraftPoseTracker.nativeHeadMode) {
            return;
        }
        PlayerCapability.get(player).ifPresent(cap -> {
            cap.wasAnimationActiveLastTick = true;

            // 关键：把 YSM 的 armorBoneOffset 直接同步成我们当前 Head 的 store 值。
            // 在 z2=false 的“空转帧”里，applyHeadTracking 不会从骨骼重新捕获，
            // 而是沿用这个私有偏移；不修正它就会继续写旧污染值 store±85°。
            if (ARMOR_BONE_OFFSET == null) {
                if (VrBodyConfig.debugLog && syncLogCounter++ % 200 == 0) {
                    YsmVrMod.LOGGER.info("[ysmvr][debug] armorSync: offsetField=null");
                }
                return;
            }
            var model = cap.getCurrentModel();
            if (model == null || model.headBones().isEmpty()) {
                return;
            }
            var store = store(player);
            if (store == null) {
                return;
            }
            var headBone = model.headBones().get(model.headBones().size() - 1);
            var pose = store.get(headBone.getBoneId());
            if (pose == null) {
                if (VrBodyConfig.debugLog && syncLogCounter++ % 200 == 0) {
                    YsmVrMod.LOGGER.info("[ysmvr][debug] armorSync: headBone={} pose=null",
                            headBone.getName());
                }
                return;
            }
            try {
                var offset = (Vector2f) ARMOR_BONE_OFFSET.get(cap);
                if (offset != null) {
                    offset.set(pose.rx(), pose.ry());
                    if (VrBodyConfig.debugLog && syncLogCounter++ % 200 == 0) {
                        YsmVrMod.LOGGER.info("[ysmvr][debug] armorSync: cap={} bone={} pose=({},{}) offset=({},{})",
                                System.identityHashCode(cap), headBone.getName(),
                                pose.rx(), pose.ry(), offset.x, offset.y);
                    }
                }
            } catch (Throwable t) {
                YsmVrMod.LOGGER.warn("[ysmvr] 同步 armorBoneOffset 失败", t);
            }
        });
    }

    @Override
    public void forceHeadBoneToStore(LocalPlayer player) {
        if (VivecraftPoseTracker.nativeHeadMode || isPlayingYsmAnimation(player)) {
            return;
        }
        PlayerCapability.get(player).ifPresent(cap -> {
            var model = cap.getCurrentModel();
            if (model == null || model.headBones().isEmpty()) {
                return;
            }
            var store = store(player);
            if (store == null) {
                return;
            }
            var headBone = model.headBones().get(model.headBones().size() - 1);
            var pose = store.get(headBone.getBoneId());
            if (pose == null) {
                return;
            }
            headBone.setRotationX(pose.rx());
            headBone.setRotationY(pose.ry());
            headBone.setRotationZ(pose.rz());
            if (ARMOR_BONE_OFFSET != null) {
                try {
                    var offset = (Vector2f) ARMOR_BONE_OFFSET.get(cap);
                    if (offset != null) {
                        offset.set(pose.rx(), pose.ry());
                        if (VrBodyConfig.debugLog && forceLogCounter++ % 200 == 0) {
                            YsmVrMod.LOGGER.info("[ysmvr][debug] forceHead: bone={} set=({},{},{})",
                                    headBone.getName(), pose.rx(), pose.ry(), pose.rz());
                        }
                    }
                } catch (Throwable t) {
                    YsmVrMod.LOGGER.warn("[ysmvr] 同步 armorBoneOffset 失败", t);
                }
            }
        });
    }

    @Override
    public boolean isPlayingYsmAnimation(LocalPlayer player) {
        if (player == null) {
            return false;
        }
        return PlayerCapability.get(player)
                .map(cap -> cap.isModelSwitching())
                .orElse(false);
    }

    @Override
    public boolean isYsmAnimationHolding(LocalPlayer player) {
        if (player == null) {
            return false;
        }
        var cap = PlayerCapability.get(player).orElse(null);
        if (cap == null || !cap.isModelSwitching()) {
            return false;
        }
        String id = cap.getSelectedModelId();
        if (id == null || id.isEmpty()) {
            return false;
        }
        Animation animation = cap.getAnimation(id);
        if (animation == null || animation.loop != ILoopType.EDefaultLoopTypes.HOLD_ON_LAST_FRAME) {
            return false; // 循环/一次性动画不用主动结束
        }
        // 刚请求切换、新动画还没真正开始时会残留上一部的 isAnimationFinished=true，
        // 必须要求 CAP 处于 RUNNING，避免把刚开始的动画误判成"已播完"。
        if (cap.getAnimationState(PlayerAnimationController.CAP_CONTROLLER_KEY)
                != AnimationState.RUNNING) {
            return false;
        }
        for (var controller : cap.getAnimationData().getAnimationControllers()) {
            if (PlayerAnimationController.CAP_CONTROLLER_KEY.equals(controller.getName())
                    && controller instanceof PredicateBasedController<?> based) {
                // isPlaying() 实际返回 isAnimationFinished()：hold_on_last_frame 播完为 true
                return based.isPlaying();
            }
        }
        return false;
    }

    @Override
    public void endYsmAnimation(LocalPlayer player) {
        if (player == null) {
            return;
        }
        PlayerCapability.get(player).ifPresent(cap -> {
            if (!cap.isModelSwitching()) {
                return;
            }
            cap.clearModelSwitch();
            if (NetworkHandler.isClientConnected()) {
                NetworkHandler.sendToServer(C2SPlayAnimationPacket.createDefault());
            }
            if (VrBodyConfig.debugLog) {
                YsmVrMod.LOGGER.info(
                        "[ysmvr] YSM 定格动画(hold_on_last_frame)播放完毕：主动结束，恢复 VR 骨骼覆盖");
            }
        });
    }

    @Override
    public List<String> animationDiagnostics(LocalPlayer player) {
        var out = new ArrayList<String>();
        var cap = PlayerCapability.get(player).orElse(null);
        if (cap == null) {
            out.add("PlayerCapability 不可用");
            return out;
        }
        var model = cap.getCurrentModel();
        if (model == null) {
            out.add("模型未加载");
            return out;
        }
        var store = store(player);
        float[] scale = getModelScale(player);
        out.add(String.format(java.util.Locale.ROOT, "模型缩放: %s",
                scale == null ? "?" : String.format(java.util.Locale.ROOT, "%.3f/%.3f", scale[0], scale[1])));
        // 酒狐诊断重点：FOX=第二形态/坐骑（parallel1 用 scale 隐藏）、
        // Background1/gui/GuiRoot=模型选择 GUI 边框（pre_parallel4 用 scale 隐藏）。
        for (String name : new String[]{"FOX", "AllBody", "Background1", "Backgrounds", "gui",
                "curtain", "MRoot", "Mask", "AllHead"}) {
            int id = resolveBone(player, name);
            if (id < 0) {
                out.add(name + ": 模型无此骨骼");
                continue;
            }
            var bone = model.bones().get(id);
            if (bone == null) {
                out.add(name + ": 骨骼对象缺失");
                continue;
            }
            var pose = store == null ? null : store.get(id);
            boolean rotOnly = store != null && store.isRotationOnly(id);
            out.add(String.format(java.util.Locale.ROOT,
                    "%s: liveScale=(%.2f,%.2f,%.2f) liveRot=(%.1f,%.1f,%.1f)° livePos=(%.2f,%.2f,%.2f) rotOnly=%s store=%s",
                    name, bone.getScaleX(), bone.getScaleY(), bone.getScaleZ(),
                    Math.toDegrees(bone.getRotationX()), Math.toDegrees(bone.getRotationY()),
                    Math.toDegrees(bone.getRotationZ()),
                    bone.getPositionX(), bone.getPositionY(), bone.getPositionZ(), rotOnly,
                    pose == null ? "null" : String.format(java.util.Locale.ROOT,
                            "S(%.2f,%.2f,%.2f) R(%.1f,%.1f,%.1f)°",
                            pose.sx(), pose.sy(), pose.sz(),
                            Math.toDegrees(pose.rx()), Math.toDegrees(pose.ry()), Math.toDegrees(pose.rz()))));
        }
        var sb = new StringBuilder("controllers: ");
        for (var controller : cap.getAnimationData().getAnimationControllers()) {
            sb.append(controller.getName()).append('=').append(controller.getCurrentAnimation()).append(" | ");
        }
        out.add(sb.toString());
        var roaming = cap.getServerVarContainer();
        if (roaming == null) {
            out.add("v.roaming 容器为空（变量按 0 处理）");
        } else {
            int key = StringPool.getName("a");
            if (key == StringPool.NONE) {
                out.add("v.roaming.a: StringPool 无 'a'");
            } else {
                out.add("v.roaming.a=" + roaming.getProperty(key));
            }
        }
        return out;
    }

    @Override
    public void detach(UUID playerId) {
        CONTROLLERS.remove(playerId);
        STORES.remove(playerId);
    }

    @Override
    public void detachAll() {
        CONTROLLERS.clear();
        STORES.clear();
    }

    @Override
    public PoseOverrideStore store(LocalPlayer player) {
        return player == null ? null : STORES.get(player.getUUID());
    }

    @Override
    public int resolveBone(LocalPlayer player, String boneName) {
        var cap = PlayerCapability.get(player).orElse(null);
        if (cap == null) {
            return -1;
        }
        var model = cap.getCurrentModel();
        if (model == null) {
            return -1;
        }
        int pooled = StringPool.getName(boneName);
        if (pooled == StringPool.NONE) {
            return -1;
        }
        return model.bones().containsKey(pooled) ? pooled : -1;
    }

    @Override
    public List<String> listBones(LocalPlayer player) {
        var cap = PlayerCapability.get(player).orElse(null);
        if (cap == null || cap.getCurrentModel() == null) {
            return List.of();
        }
        var names = new ArrayList<String>();
        for (var bone : cap.getCurrentModel().bones().values()) {
            names.add(bone.getName());
        }
        return names;
    }

    @Override
    public boolean isAttached(LocalPlayer player) {
        var cap = PlayerCapability.get(player).orElse(null);
        if (cap == null) {
            return false;
        }
        for (var controller : cap.getAnimationData().getAnimationControllers()) {
            if (CONTROLLER_NAME.equals(controller.getName())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isModelLoaded(LocalPlayer player) {
        var cap = PlayerCapability.get(player).orElse(null);
        return cap != null && cap.getCurrentModel() != null;
    }

    @Override
    public List<BoneRow> boneRows(LocalPlayer player) {
        var cap = PlayerCapability.get(player).orElse(null);
        if (cap == null || cap.getCurrentModel() == null) {
            return List.of();
        }
        var model = cap.getCurrentModel();
        // bones() 只有顶层骨骼的实时值；非顶层骨骼数值显示为 0
        var live = model.bones();
        var store = store(player);
        var rows = new ArrayList<BoneRow>();
        for (var geoBone : model.getGeoModel().bones) {
            var liveBone = live.get(geoBone.getBoneId());
            rows.add(new BoneRow(geoBone.getBoneId(),
                    geoBone.getName(),
                    geoBone.parentName == null ? "" : geoBone.parentName,
                    liveBone == null ? 0 : liveBone.getRotationX(),
                    liveBone == null ? 0 : liveBone.getRotationY(),
                    liveBone == null ? 0 : liveBone.getRotationZ(),
                    liveBone == null ? 0 : liveBone.getPositionX(),
                    liveBone == null ? 0 : liveBone.getPositionY(),
                    liveBone == null ? 0 : liveBone.getPositionZ(),
                    liveBone == null ? 1 : liveBone.getScaleX(),
                    liveBone == null ? 1 : liveBone.getScaleY(),
                    liveBone == null ? 1 : liveBone.getScaleZ(),
                    geoBone.getPivotX(),
                    geoBone.getPivotY(),
                    geoBone.getPivotZ(),
                    store != null && store.get(geoBone.getBoneId()) != null));
        }
        return rows;
    }

    @Override
    public List<GroupRow> groups(LocalPlayer player) {
        var cap = PlayerCapability.get(player).orElse(null);
        if (cap == null || cap.getCurrentModel() == null) {
            return List.of();
        }
        var geo = cap.getCurrentModel().getGeoModel();
        var groups = new ArrayList<GroupRow>();
        addGroup(groups, "头 Head", geo.headIds);
        addGroup(groups, "左手 LeftHand", geo.leftHandIds);
        addGroup(groups, "右手 RightHand", geo.rightHandIds);
        addGroup(groups, "鞘翅 Elytra", geo.elytraIds);
        addGroup(groups, "背包 Backpack", geo.backpackIds);
        addGroup(groups, "左肩 LeftShoulder", geo.leftShoulderIds);
        addGroup(groups, "右肩 RightShoulder", geo.rightShoulderIds);
        addGroup(groups, "左腰 LeftWaist", geo.leftWaistIds);
        addGroup(groups, "右腰 RightWaist", geo.rightWaistIds);
        addGroup(groups, "刃 Blade", geo.bladeIds);
        addGroup(groups, "鞘 Sheath", geo.sheathIds);
        addGroup(groups, "战术手枪 TacPistol", geo.tacPistolIds);
        addGroup(groups, "战术步枪 TacRifle", geo.tacRifleIds);
        return groups;
    }

    @Override
    public float[] getBoneModelPosition(LocalPlayer player, String boneName) {
        var cap = PlayerCapability.get(player).orElse(null);
        if (cap == null || cap.getCurrentModel() == null) {
            return null;
        }
        var model = cap.getCurrentModel();
        var byName = new HashMap<String, GeoBone>();
        for (var geoBone : model.getGeoModel().bones) {
            byName.put(geoBone.getName(), geoBone);
        }
        var target = byName.get(boneName);
        if (target == null) {
            return null;
        }

        // 沿 parentName 收集祖先链（目标 → 根），再反转成根 → 目标
        var chain = new ArrayList<GeoBone>(8);
        var guard = new HashSet<String>();
        for (var cur = target; cur != null && guard.add(cur.getName()); ) {
            chain.add(cur);
            cur = cur.parentName == null || cur.parentName.isEmpty()
                    ? null : byName.get(cur.parentName);
        }
        Collections.reverse(chain);

        // 返回目标骨骼【枢轴点】（旋转中心）在模型根坐标系中的位置（模型单位，
        // 与 PoseOverrideStore 的 R 系约定一致：x 取反）。枢轴点 = 祖先链实时
        // 变换 M_parent 作用到 (pivot − animPos)/16。
        //
        // 旧实现逐骨骼累加“绝对 pivot”，把同一枢轴上的 dummy 骨骼重复叠加
        // （默认模型 MAllBody/AllBody/UpBody/UpperBody 同枢轴 y=20.65，肩膀被算到
        // 9m 高），手部瞄准方向因此错误（T 字型手臂不指侧面）。必须按渲染器
        // calculateBoneMatrix 那样逐骨骼 T(+pivot)·R·S·T(−pivot) 相消后取最终平移。
        var live = model.bones();
        // store 里的覆盖值就是"渲染管线该骨骼的最终绝对局部值"（ModernVrPoseController
        // 提交后 bone 的 rotation/position/scale）。异步/分阶段动画管线里 live bone
        // 可能还没应用本帧 store——尤其"强制模型大小"写的是缩放通道；若这里只读
        // live，缩小玩家后模型肩膀旋转轴仍停在现实高度，而手柄位置已按世界缩放降低，
        // 手臂就会向下过长。故这里把 store 中存在的通道覆盖到 live 值上。
        var store = store(player);
        Matrix4f m = new Matrix4f();
        for (int i = 0; i < chain.size() - 1; i++) {
            var geoBone = chain.get(i);
            int boneId = geoBone.getBoneId();
            var liveBone = live.get(boneId);
            var pose = store == null ? null : store.get(boneId);
            boolean rotationOnly = pose != null && store.isRotationOnly(boneId);
            boolean scaleOnly = pose != null && store.isScaleOnly(boneId);
            boolean positionOnly = pose != null && store.isPositionOnly(boneId);
            boolean rotationPositionOnly = pose != null && store.isRotationPositionOnly(boneId);

            float tx = liveBone == null ? 0 : liveBone.getPositionX();
            float ty = liveBone == null ? 0 : liveBone.getPositionY();
            float tz = liveBone == null ? 0 : liveBone.getPositionZ();
            float rx = liveBone == null ? 0 : liveBone.getRotationX();
            float ry = liveBone == null ? 0 : liveBone.getRotationY();
            float rz = liveBone == null ? 0 : liveBone.getRotationZ();
            float sx = liveBone == null ? 1 : liveBone.getScaleX();
            float sy = liveBone == null ? 1 : liveBone.getScaleY();
            float sz = liveBone == null ? 1 : liveBone.getScaleZ();
            if (pose != null) {
                if ((!scaleOnly && !positionOnly) || (rotationPositionOnly && !scaleOnly)) {
                    rx = pose.rx();
                    ry = pose.ry();
                    rz = pose.rz();
                }
                if (!rotationOnly && ((!positionOnly && !rotationPositionOnly) || scaleOnly)) {
                    sx = pose.sx();
                    sy = pose.sy();
                    sz = pose.sz();
                }
                if (!rotationOnly && (!scaleOnly || positionOnly || rotationPositionOnly)) {
                    tx = pose.px();
                    ty = pose.py();
                    tz = pose.pz();
                }
            }
            // 与 NativeModelRenderer.calculateBoneMatrix 相同的合成顺序
            var local = new Matrix4f()
                    .translate((geoBone.getPivotX() - tx) * 0.0625f,
                            (geoBone.getPivotY() + ty) * 0.0625f,
                            (geoBone.getPivotZ() + tz) * 0.0625f)
                    .rotateZ(rz)
                    .rotateY(ry)
                    .rotateX(rx);
            if (sx != 1f || sy != 1f || sz != 1f) {
                local.scale(sx, sy, sz);
            }
            local.translate(-geoBone.getPivotX() / 16f,
                    -geoBone.getPivotY() / 16f,
                    -geoBone.getPivotZ() / 16f);
            m.mul(local);
        }
        int targetId = target.getBoneId();
        var tb = live.get(targetId);
        var targetPose = store == null ? null : store.get(targetId);
        boolean targetRotationOnly = targetPose != null && store.isRotationOnly(targetId);
        boolean targetScaleOnly = targetPose != null && store.isScaleOnly(targetId);
        float ttx = tb == null ? 0 : tb.getPositionX();
        float tty = tb == null ? 0 : tb.getPositionY();
        float ttz = tb == null ? 0 : tb.getPositionZ();
        if (targetPose != null && !targetRotationOnly && !targetScaleOnly) {
            ttx = targetPose.px();
            tty = targetPose.py();
            ttz = targetPose.pz();
        }
        var pivot = new Vector3f(
                (target.getPivotX() - ttx) * 0.0625f,
                (target.getPivotY() + tty) * 0.0625f,
                (target.getPivotZ() + ttz) * 0.0625f);
        m.transformPosition(pivot);
        // 返回值用 PoseOverrideStore 的存储约定（stateBuffer 约定：x 取反）
        return new float[]{-pivot.x * 16f, pivot.y * 16f, pivot.z * 16f};
    }

    @Override
    public String getItemAnchorBone(LocalPlayer player, String boneName) {
        var cap = PlayerCapability.get(player).orElse(null);
        if (cap == null) {
            return null;
        }
        AnimatedGeoModel model = cap.getCurrentModel();
        if (model == null) {
            return null;
        }
        boolean left = boneName != null
                && boneName.toLowerCase(java.util.Locale.ROOT).contains("left");
        List<IBone> path = left ? model.leftHandBones() : model.rightHandBones();
        if (path == null || path.isEmpty()) {
            return null;
        }
        IBone last = path.get(path.size() - 1);
        return last == null ? null : last.getName();
    }

    @Override
    public String getModelId(LocalPlayer player) {
        // 优先 PlayerCapability#getModelId（GeoEntity 当前实际渲染的模型 id），
        // ModelInfoCapability 在部分客户端环境可能不可用/为空，会导致所有模型都落到
        // "default" key，单模型配置互相覆盖。
        var cap = PlayerCapability.get(player).orElse(null);
        String id = cap == null ? null : cap.getModelId();
        if (id == null || id.isBlank()) {
            id = ModelInfoCapability.get(player)
                    .map(ModelInfoCapability::getModelId)
                    .orElse(null);
        }
        return id;
    }

    @Override
    public String getCurrentAnimationKey(LocalPlayer player) {        var cap = PlayerCapability.get(player).orElse(null);
        if (cap == null || cap.getAnimationData() == null) {
            return null;
        }
        // 稳定 key：优先只取 hold/post_hold 控制器，避免走路/待机切换 main 动画时
        // 已保存的持武器位置偏移失配；没有 hold 再退回 main。
        java.util.TreeMap<String, String> selected = new java.util.TreeMap<>();
        String mainName = null;
        String mainAnim = null;
        for (var controller : cap.getAnimationData().getAnimationControllers()) {
            if (controller == null) {
                continue;
            }
            String name = controller.getName();
            String anim = controller.getCurrentAnimation();
            if (name == null || anim == null || anim.isBlank()) {
                continue;
            }
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("hold")) {
                selected.put(name, anim);
            } else if (lower.contains("main") && !lower.contains("hold")) {
                mainName = name;
                mainAnim = anim;
            }
        }
        if (selected.isEmpty() && mainName != null) {
            selected.put(mainName, mainAnim);
        }
        if (selected.isEmpty()) {
            return null;
        }
        var sb = new StringBuilder();
        for (var e : selected.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append(';');
        }
        return sb.toString();
    }

    @Override
    public String getRawAnimationKey(LocalPlayer player) {
        var cap = PlayerCapability.get(player).orElse(null);
        if (cap == null || cap.getAnimationData() == null) {
            return null;
        }
        var sb = new StringBuilder();
        for (var controller : cap.getAnimationData().getAnimationControllers()) {
            if (controller == null) {
                continue;
            }
            sb.append(controller.getName()).append('=')
                    .append(controller.getCurrentAnimation()).append(';');
        }
        return sb.toString();
    }

    @Override
    public void suppressAttackAnimation(LocalPlayer player) {
        var cap = PlayerCapability.get(player).orElse(null);
        if (cap == null || cap.getAnimationData() == null) {
            return;
        }
        for (var controller : cap.getAnimationData().getAnimationControllers()) {
            if (controller == null) {
                continue;
            }
            String name = controller.getName() == null ? ""
                    : controller.getName().toLowerCase(java.util.Locale.ROOT);
            String anim = controller.getCurrentAnimation() == null ? ""
                    : controller.getCurrentAnimation().toLowerCase(java.util.Locale.ROOT);
            boolean attack = name.contains("swing") || name.contains("attack")
                    || anim.contains("swing") || anim.contains("attack")
                    || anim.contains("sword") || anim.contains("melee")
                    || anim.contains("hit");
            if (!attack) {
                continue;
            }
            if (controller instanceof com.elfmcys.yesstevemodel.geckolib3.core.controller.AnimationControllerRuntime<?> runtime) {
                runtime.reset();
            } else if (controller instanceof com.elfmcys.yesstevemodel.geckolib3.core.controller.AnimationControllerInstance inst) {
                inst.setAnimation(null);
            }
        }
    }

    @Override
    public void zeroLowerBodyY(LocalPlayer player) {
        var cap = PlayerCapability.get(player).orElse(null);
        if (cap == null || cap.getCurrentModel() == null) {
            return;
        }
        var poseStore = store(player);
        for (var bone : cap.getCurrentModel().bones().values()) {
            if (bone == null) {
                continue;
            }
            if (poseStore != null && poseStore.overrides().containsKey(bone.getBoneId())) {
                continue;
            }
            String n = bone.getName() == null ? "" : bone.getName().toLowerCase(java.util.Locale.ROOT);
            if (n.contains("head") || n.contains("arm") || n.contains("hand")
                    || n.contains("locator")) {
                continue;
            }
            boolean lower = n.contains("downbody") || n.contains("leg") || n.contains("foot")
                    || n.contains("thigh") || n.contains("calf") || n.contains("knee")
                    || n.contains("ankle") || n.contains("hip") || n.contains("waist");
            if (lower) {
                bone.setRotationY(0f);
            }
        }
    }
    @Override
    public void zeroAttackBodyRotation(LocalPlayer player) {
        var cap = PlayerCapability.get(player).orElse(null);
        if (cap == null || cap.getCurrentModel() == null) {
            return;
        }
        var poseStore = store(player);
        for (var bone : cap.getCurrentModel().bones().values()) {
            if (bone == null) {
                continue;
            }
            if (poseStore != null && poseStore.overrides().containsKey(bone.getBoneId())) {
                continue;
            }
            String n = bone.getName() == null ? "" : bone.getName().toLowerCase(java.util.Locale.ROOT);
            boolean body = n.contains("root") || n.contains("allbody") || n.contains("upbody")
                    || n.contains("upperbody") || n.contains("downbody");
            if (body) {
                bone.setRotationY(0f);
            }
        }
    }
    @Override
    public void zeroArmAnimation(LocalPlayer player) {
        var cap = PlayerCapability.get(player).orElse(null);
        if (cap == null || cap.getCurrentModel() == null) {
            return;
        }
        var store = store(player);
        for (var bone : cap.getCurrentModel().bones().values()) {
            if (bone == null) {
                continue;
            }
            String n = bone.getName() == null ? "" : bone.getName().toLowerCase(java.util.Locale.ROOT);
            if (!n.contains("arm") && !n.contains("hand")) {
                continue;
            }
            if (n.contains("locator")) {
                continue;
            }
            if (store != null && store.overrides().containsKey(bone.getBoneId())) {
                // VR/IK 已驱动的骨骼保持
                continue;
            }
            var initial = bone.getInitialRotation();
            bone.setRotationX(initial.x);
            bone.setRotationY(initial.y);
            bone.setRotationZ(initial.z);
        }
    }

    @Override
    public void restoreAttackPose(LocalPlayer player) {
        var cap = PlayerCapability.get(player).orElse(null);
        if (cap == null || cap.getCurrentModel() == null) {
            return;
        }
        var poseStore = store(player);
        for (var bone : cap.getCurrentModel().bones().values()) {
            if (bone == null) {
                continue;
            }
            if (poseStore != null && poseStore.overrides().containsKey(bone.getBoneId())) {
                continue;
            }
            var initial = bone.getInitialRotation();
            bone.setRotationX(initial.x);
            bone.setRotationY(initial.y);
            bone.setRotationZ(initial.z);
        }
    }

    @Override
    public void suppressAnimationsMatching(LocalPlayer player, String... keywords) {
        if (keywords == null || keywords.length == 0) {
            return;
        }
        var cap = PlayerCapability.get(player).orElse(null);
        if (cap == null || cap.getAnimationData() == null) {
            return;
        }
        for (var controller : cap.getAnimationData().getAnimationControllers()) {
            if (controller == null) {
                continue;
            }
            String name = controller.getName() == null ? ""
                    : controller.getName().toLowerCase(java.util.Locale.ROOT);
            String anim = controller.getCurrentAnimation() == null ? ""
                    : controller.getCurrentAnimation().toLowerCase(java.util.Locale.ROOT);
            String both = name + " " + anim;
            boolean hit = false;
            for (String keyword : keywords) {
                if (keyword != null && both.contains(keyword.toLowerCase(java.util.Locale.ROOT))) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                continue;
            }
            if (controller instanceof com.elfmcys.yesstevemodel.geckolib3.core.controller.AnimationControllerRuntime<?> runtime) {
                runtime.reset();
            } else if (controller instanceof com.elfmcys.yesstevemodel.geckolib3.core.controller.AnimationControllerInstance inst) {
                inst.setAnimation(null);
            }
        }
    }

    @Override
    public void restoreNonDrivenPose(LocalPlayer player) {
        restoreAttackPose(player);
    }

    @Override
    public float[] getBoneParentRotation(LocalPlayer player, String boneName) {
        var cap = PlayerCapability.get(player).orElse(null);
        if (cap == null || cap.getCurrentModel() == null) {
            return null;
        }
        var model = cap.getCurrentModel();
        var byName = new java.util.HashMap<String, GeoBone>();
        for (var b : model.getGeoModel().bones) {
            byName.put(b.getName(), b);
        }
        var target = byName.get(boneName);
        if (target == null) {
            return null;
        }
        java.util.List<GeoBone> chain = new java.util.ArrayList<>();
        var cur = target;
        while (cur != null) {
            chain.add(cur);
            cur = cur.parentName == null || cur.parentName.isEmpty()
                    ? null : byName.get(cur.parentName);
        }
        if (chain.size() < 2) {
            return null;
        }
        java.util.Collections.reverse(chain);
        var live = model.bones();
        var store = store(player);
        Matrix3f abs = new Matrix3f();
        for (int i = 0; i < chain.size() - 1; i++) {
            var b = chain.get(i);
            int id = b.getBoneId();
            var lb = live.get(id);
            var pose = store == null ? null : store.get(id);
            boolean rotationOnly = pose != null && store.isRotationOnly(id);
            boolean scaleOnly = pose != null && store.isScaleOnly(id);
            boolean positionOnly = pose != null && store.isPositionOnly(id);
            boolean rotationPositionOnly = pose != null && store.isRotationPositionOnly(id);
            float rx = lb == null ? 0f : lb.getRotationX();
            float ry = lb == null ? 0f : lb.getRotationY();
            float rz = lb == null ? 0f : lb.getRotationZ();
            if (pose != null && ((!scaleOnly && !positionOnly)
                    || (rotationPositionOnly && !scaleOnly))) {
                rx = pose.rx();
                ry = pose.ry();
                rz = pose.rz();
            }
            abs.mul(new Matrix3f().rotateZ(rz).rotateY(ry).rotateX(rx));
        }
        Quaternionf q = new Quaternionf().setFromNormalized(abs);
        return new float[]{q.x, q.y, q.z, q.w};
    }

    @Override
    public float[] getBoneLocalRotation(LocalPlayer player, String boneName) {
        var cap = PlayerCapability.get(player).orElse(null);
        if (cap == null || cap.getCurrentModel() == null) {
            return null;
        }
        int id = resolveBone(player, boneName);
        if (id < 0) {
            return null;
        }
        var bone = cap.getCurrentModel().bones().get(id);
        if (bone == null) {
            return null;
        }
        return new float[]{bone.getRotationX(), bone.getRotationY(), bone.getRotationZ()};
    }

    @Override
    public float[] getBonePivot(LocalPlayer player, String boneName) {
        var cap = PlayerCapability.get(player).orElse(null);
        if (cap == null || cap.getCurrentModel() == null) {
            return null;
        }
        for (var b : cap.getCurrentModel().getGeoModel().bones) {
            if (b.getName().equals(boneName)) {
                return new float[]{b.getPivotX(), b.getPivotY(), b.getPivotZ()};
            }
        }
        return null;
    }

    @Override
    public float[] getModelScale(LocalPlayer player) {
        var cap = PlayerCapability.get(player).orElse(null);
        if (cap == null) {
            return null;
        }
        return new float[]{cap.getHeightScale(), cap.getWidthScale()};
    }

    @Override
    public String modelScaleBone(LocalPlayer player) {
        if (player == null || PlayerCapability.get(player).isEmpty()) {
            return null;
        }
        // 优先用最近实际解析过的头部骨骼，其次常见名字；沿父链上溯到根。
        for (String head : new String[]{
                VrViewConfig.resolvedHeadBone(), "Head", "AllHead", "MHead"}) {
            if (head == null || head.isEmpty() || resolveBone(player, head) < 0) {
                continue;
            }
            String current = head;
            Set<String> visited = new HashSet<>();
            while (current != null && visited.add(current)) {
                String parent = getParentBoneName(player, current);
                if (parent == null || parent.isEmpty()) {
                    return current;
                }
                current = parent;
            }
        }
        // 兜底：没有可用的头部骨骼时，用第一个根骨骼
        List<String> roots = rootBoneNames(player);
        return roots.isEmpty() ? null : roots.get(0);
    }

    @Override
    public List<String> rootBoneNames(LocalPlayer player) {
        var cap = PlayerCapability.get(player).orElse(null);
        if (cap == null || cap.getCurrentModel() == null) {
            return List.of();
        }
        var names = new ArrayList<String>(2);
        for (var geoBone : cap.getCurrentModel().getGeoModel().bones) {
            if (geoBone.parentName == null || geoBone.parentName.isEmpty()) {
                names.add(geoBone.getName());
            }
        }
        return names;
    }

    @Override
    public String getParentBoneName(LocalPlayer player, String boneName) {
        var cap = PlayerCapability.get(player).orElse(null);
        if (cap == null || cap.getCurrentModel() == null) {
            return null;
        }
        var model = cap.getCurrentModel();
        var bakedBones = model.getGeoModel().bakedBones;
        if (bakedBones == null) {
            return null;
        }
        int parentIdx = -1;
        for (var bb : bakedBones) {
            if (bb.name.equals(boneName)) {
                parentIdx = bb.parentIdx;
                break;
            }
        }
        if (parentIdx < 0 || parentIdx >= bakedBones.size()) {
            return null;
        }
        return bakedBones.get(parentIdx).name;
    }

    @Override
    public boolean setBoneHidden(LocalPlayer player, String boneName, boolean hidden) {
        var cap = PlayerCapability.get(player).orElse(null);
        if (cap == null || cap.getCurrentModel() == null) {
            return false;
        }
        var model = cap.getCurrentModel();
        // 优先使用模型自带 AllHead（整个头部组，含头发/头饰）；
        // 没有 AllHead 的模型退回配置的头部骨骼。
        // 注意：headIds 是“根→Head”的祖先路径，旧实现把它全部隐藏，
        // 会连 Root/UpBody 一起隐藏，表现为整个模型消失。
        var allHead = model.allHeadBone();
        int id = allHead != null ? allHead.getBoneId() : resolveBone(player, boneName);
        if (id < 0) {
            return false;
        }
        var bone = model.bones().get(id);
        if (bone == null) {
            return false;
        }
        // hidden=false 时必须同时清掉“隐藏子骨骼”标记，否则恢复后会一直不可见。
        // 同时直接写 live scale，保证本 pass 模型绘制时立即生效，不依赖 store
        // 覆盖要到下一次动画 tick 才提交（否则多 pass hide/show 会闪烁）。
        float s = hidden ? 0f : 1f;
        bone.setHidden(hidden, hidden);
        bone.setScaleX(s);
        bone.setScaleY(s);
        bone.setScaleZ(s);
        // 轮盘/额外动画期间不写 store：setScale 会顺带创建一个 rotation=0 的完整
        // 覆盖，把动画里 AllHead 的动作压掉；live scale 已足够让本 pass 立即生效。
        var store = store(player);
        if (store != null && !isPlayingYsmAnimation(player)) {
            store.setScale(id, s, s, s);
        }
        return true;
    }

    @Override
    public float[] rootToParentLocal(LocalPlayer player, String boneName,
                                     float x, float y, float z) {
        var cap = PlayerCapability.get(player).orElse(null);
        if (cap == null || cap.getCurrentModel() == null) {
            return null;
        }
        var model = cap.getCurrentModel();
        var byName = new HashMap<String, GeoBone>();
        for (var geoBone : model.getGeoModel().bones) {
            byName.put(geoBone.getName(), geoBone);
        }
        var target = byName.get(boneName);
        if (target == null) {
            return null;
        }
        // 根骨骼（无父）：父系即模型根，输入点就是结果
        if (target.parentName == null || target.parentName.isEmpty()) {
            return new float[]{x, y, z};
        }
        // 沿 parentName 收集祖先链（目标 → 根），反转成根 → 目标，去掉目标自身
        var chain = new ArrayList<GeoBone>(8);
        var guard = new HashSet<String>();
        for (var cur = target; cur != null && guard.add(cur.getName()); ) {
            chain.add(cur);
            cur = cur.parentName == null || cur.parentName.isEmpty()
                    ? null : byName.get(cur.parentName);
        }
        Collections.reverse(chain);
        // 与 calculateBoneMatrix 相同的合成顺序取逆：
        // p_parent = T + R·S·p_child → p_child = S⁻¹·R⁻¹·(p_parent − T)
        var live = model.bones();
        var p = new Vector3f(x * 0.0625f, y * 0.0625f, z * 0.0625f);
        var invRot = new Matrix3f();
        for (int i = 0; i < chain.size() - 1; i++) {
            var geoBone = chain.get(i);
            var liveBone = live.get(geoBone.getBoneId());
            float tx = liveBone == null ? 0 : liveBone.getPositionX();
            float ty = liveBone == null ? 0 : liveBone.getPositionY();
            float tz = liveBone == null ? 0 : liveBone.getPositionZ();
            p.sub((geoBone.getPivotX() - tx) * 0.0625f,
                    (geoBone.getPivotY() + ty) * 0.0625f,
                    (geoBone.getPivotZ() + tz) * 0.0625f);
            if (liveBone != null) {
                var rot = new Matrix3f()
                        .rotateZ(liveBone.getRotationZ())
                        .rotateY(liveBone.getRotationY())
                        .rotateX(liveBone.getRotationX());
                rot.invert(invRot);
                p = invRot.transform(p);
                // 缩放取倒数（隐藏骨骼 scale 0 时该分量退化，跳过）
                float sx = liveBone.getScaleX();
                float sy = liveBone.getScaleY();
                float sz = liveBone.getScaleZ();
                if (Math.abs(sx) > 1e-6f) {
                    p.x /= sx;
                }
                if (Math.abs(sy) > 1e-6f) {
                    p.y /= sy;
                }
                if (Math.abs(sz) > 1e-6f) {
                    p.z /= sz;
                }
            }
        }
        return new float[]{p.x * 16f, p.y * 16f, p.z * 16f};
    }

    @Override
    public float[] getBoneInitialRotation(LocalPlayer player, String boneName) {
        var cap = PlayerCapability.get(player).orElse(null);
        if (cap == null || cap.getCurrentModel() == null) {
            return null;
        }
        int pooled = resolveBone(player, boneName);
        if (pooled < 0) {
            return null;
        }
        var bone = cap.getCurrentModel().bones().get(pooled);
        if (bone == null) {
            return null;
        }
        var initial = bone.getInitialRotation();
        return new float[]{initial.x, initial.y, initial.z};
    }

    private static void addGroup(List<GroupRow> groups, String name, it.unimi.dsi.fastutil.ints.IntList ids) {
        if (ids.isEmpty()) {
            return;
        }
        var boxed = new ArrayList<Integer>(ids.size());
        for (var id : ids) {
            boxed.add(id);
        }
        groups.add(new GroupRow(name, boxed));
    }

    /** GUI 模型预览用（PlayerPreviewEntity 实现 IPreviewAnimatable，模型/纹理变化时重建）。 */
    private PlayerPreviewEntity previewEntity;
    private String previewModelId = "";
    private String previewTexture = "";

    /**
     * 绑定/静止姿态预览用（姿势校准 GUI 的目标纸娃娃）：独立实体 + 一份把
     * 全部骨骼旋转冻结到初始旋转的 controller（旋转-only，位置/缩放仍由
     * 模型动画提供，隐藏件不会显形）。
     * <p>
     * {@code bindPreviewForceTPose=true} 时额外把上臂强制水平（T 字目标，
     * v0.2.15–v0.2.17 校准）；false 时保持模型自带的自然绑定/站立姿态
     * （v0.2.18"自然站立"校准，接近 v0.2.13 前不强制 T 字的流程）。
     */
    private PlayerPreviewEntity bindPreviewEntity;
    private String bindPreviewModelId = "";
    private String bindPreviewTexture = "";
    private PoseOverrideStore bindPreviewStore;
    private ModernVrPoseController bindPreviewController;
    /** 当前绑定姿态预览对应的模型对象（模型重载后重建 store）。 */
    private Object bindPreviewModelRef;
    /** 当前绑定预览是否附加了强制 T 字（切换校准模式时需要重建 store）。 */
    private boolean bindPreviewForceTPose = true;

    @Override
    public boolean renderModelPreview(LocalPlayer player, float x, float y, float scale,
                                      float pitch, float yaw, float partialTick) {
        return renderModelPreviewInternal(player, x, y, scale, pitch, yaw, partialTick, false, false);
    }

    @Override
    public boolean renderModelPreview(LocalPlayer player, float x, float y, float scale,
                                      float pitch, float yaw, float partialTick,
                                      boolean bindPose) {
        // 兼容旧调用：bindPose=true 仍按 v0.2.15–0.2.17 的 T 字目标处理；
        // 校准"自然站立"模式请用带 forceTPose=false 的 9 参重载。
        return renderModelPreviewInternal(player, x, y, scale, pitch, yaw, partialTick,
                bindPose, bindPose);
    }

    @Override
    public boolean renderModelPreview(LocalPlayer player, float x, float y, float scale,
                                      float pitch, float yaw, float partialTick,
                                      boolean bindPose, boolean forceTPose) {
        return renderModelPreviewInternal(player, x, y, scale, pitch, yaw, partialTick,
                bindPose, forceTPose);
    }

    private boolean renderModelPreviewInternal(LocalPlayer player, float x, float y, float scale,
                                               float pitch, float yaw, float partialTick,
                                               boolean bindPose, boolean forceTPose) {
        var cap = PlayerCapability.get(player).orElse(null);
        if (cap == null || cap.getCurrentModel() == null) {
            return false;
        }
        var renderer = RendererManager.getPlayerRenderer();
        if (renderer == null) {
            return false;
        }
        var modelId = cap.getModelId();
        var texture = cap.getCurrentTextureName();
        if (modelId == null || texture == null) {
            return false;
        }

        PlayerPreviewEntity entity = bindPose
                ? prepareBindPreview(modelId, texture, forceTPose)
                : preparePreview(modelId, texture);
        // 模型装配可能异步，未就绪时本帧跳过
        if (entity == null || entity.getCurrentModel() == null) {
            return false;
        }
        try {
            ModelPreviewRenderer.renderEntityPreview(
                    x, y, scale, pitch, yaw, partialTick, entity, renderer, false);
            return true;
        } catch (Throwable t) {
            // 保险：若方法在 push 后异常漏掉 pop，补偿一次恢复 GUI 矩阵栈
            try {
                RenderSystem.getModelViewStack().popPose();
                RenderSystem.applyModelViewMatrix();
            } catch (Throwable ignored) {
            }
            // renderEntityPreview 在异常路径可能漏掉 setPreviewMode(false)，
            // 残留 true 会让头部隐藏（依赖 isPreview 判断纸娃娃）永久失效。
            try {
                ModelPreviewRenderer.setPreviewMode(false);
            } catch (Throwable ignored) {
            }
            YsmVrMod.LOGGER.warn("[ysmvr] 模型预览渲染失败", t);
            return false;
        }
    }

    private PlayerPreviewEntity preparePreview(String modelId, String texture) {
        if (previewEntity == null || !modelId.equals(previewModelId) || !texture.equals(previewTexture)) {
            if (previewEntity == null) {
                previewEntity = new PlayerPreviewEntity();
            }
            previewEntity.initModelWithTexture(modelId, texture);
            previewModelId = modelId;
            previewTexture = texture;
        }
        return previewEntity;
    }

    /**
     * 绑定姿态预览实体：初始化模型并保证冻结 controller 的 store 与当前模型骨骼对应。
     *
     * @param forceTPose true = 绑定姿态 + 上臂强制水平（T 字目标）；
     *                   false = 仅冻结到绑定/自然站立姿态（手臂自然下垂）
     */
    private PlayerPreviewEntity prepareBindPreview(String modelId, String texture, boolean forceTPose) {
        if (bindPreviewEntity == null || !modelId.equals(bindPreviewModelId)
                || !texture.equals(bindPreviewTexture)) {
            if (bindPreviewEntity == null) {
                bindPreviewEntity = new PlayerPreviewEntity();
            }
            bindPreviewEntity.initModelWithTexture(modelId, texture);
            bindPreviewModelId = modelId;
            bindPreviewTexture = texture;
        }
        var model = bindPreviewEntity.getCurrentModel();
        if (model == null) {
            return bindPreviewEntity;
        }
        if (bindPreviewStore == null) {
            bindPreviewStore = new PoseOverrideStore();
        }
        if (bindPreviewController == null) {
            bindPreviewController = new ModernVrPoseController(bindPreviewStore);
        }
        // 保证冻结 controller 在列表里且排在最后（模型重载可能重建 controller 列表）
        var controllers = bindPreviewEntity.getAnimationData().getAnimationControllers();
        if (!controllers.contains(bindPreviewController)) {
            controllers.removeIf(c -> ModernYsmBridge.CONTROLLER_NAME.equals(c.getName()));
            controllers.add(bindPreviewController);
        }
        if (model != bindPreviewModelRef || forceTPose != bindPreviewForceTPose) {
            bindPreviewModelRef = model;
            bindPreviewForceTPose = forceTPose;
            // 1) 所有骨骼旋转冻结到初始值 = 模型绑定/静止姿态（校准后的目标姿势）
            bindPreviewStore.clearAll();
            for (var bone : model.bones().values()) {
                var initial = bone.getInitialRotation();
                bindPreviewStore.setFrozenRotation(bone.getBoneId(), initial.x, initial.y, initial.z);
            }
            // 2) 仅 T 字模式才强制上臂水平：YSM 模型手臂在绑定姿态一般自然下垂，
            //    直接冻结会得到"手放下来"，这正好是"自然站立"校准需要的目标姿势。
            //    T 字模式的手臂网格通常朝 -Y 建模：绕 Z 转 ±90° 即水平。
            if (forceTPose) {
                float quarterTurn = (float) (Math.PI / 2.0);
                for (var entry : model.bones().int2ReferenceEntrySet()) {
                    String name = StringPool.getString(entry.getIntKey());
                    if (name == null || name.isEmpty()) {
                        continue;
                    }
                    String lower = name.toLowerCase(java.util.Locale.ROOT);
                    boolean isArm = lower.contains("arm") || lower.contains("shoulder");
                    boolean isLowerArm = lower.contains("fore") || lower.contains("lower")
                            || lower.contains("elbow") || lower.contains("hand");
                    if (!isArm || isLowerArm) {
                        continue;
                    }
                    float sign;
                    // 实机反馈：按 -90°(右)/+90°(左) 会变成向内交叉，反向才是向外水平
                    if (lower.contains("left")) {
                        sign = -1f;
                    } else if (lower.contains("right")) {
                        sign = 1f;
                    } else {
                        continue; // 分不清左右就保持绑定姿态
                    }
                    bindPreviewStore.setFrozenRotation(
                            entry.getValue().getBoneId(), 0f, 0f, sign * quarterTurn);
                }
            }
        }
        return bindPreviewEntity;
    }
}
