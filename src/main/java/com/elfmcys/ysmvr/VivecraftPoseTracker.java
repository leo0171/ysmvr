package com.elfmcys.ysmvr;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.vivecraft.api.client.Tracker;
import org.vivecraft.api.client.VRClientAPI;
import org.vivecraft.api.data.VRBodyPart;
import org.vivecraft.api.data.VRBodyPartData;
import org.vivecraft.api.data.VRPose;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Vivecraft {@link Tracker}：每帧把 VR 追踪数据写入 {@link PoseOverrideStore}，
 * 由 {@link VrPoseController} 转换为骨骼动画队列。
 * <p>
 * 驱动方式（按优先级）：
 * <ol>
 * <li><b>原版姿态复制</b>（{@link VrBodyConfig#vanillaCopyEnabled}，默认开）：
 * 每帧摆好 Vivecraft 原版 VR 玩家模型（{@link VanillaVrPoseSource}），把
 * 身体/上臂/腿等部位的三轴旋转直接换算到绑定的 YSM 骨骼——与原版 VR
 * 模型一致的追踪质量。手部与头部不复制：VR 手部零件是自定义坐标系
 * （手指朝 −Z），逐分量符号映射无法同时满足伸直与 T字型（实测伸直时
 * 新旧映射分别指左/右）；头部见直驱说明。部位数据不可用时逐部位回退</li>
 * <li>主手/副手：<b>位置定向瞄准</b>——用控制器世界坐标计算骨骼指向，
 * 手臂可以举过头顶（方位模式无法表达位置）；上臂未被其它部位驱动时
 * 施加一半瞄准角度（3 点追踪时肘部自然前倾）</li>
 * <li>其余部位：方位映射（pitch/yaw/roll → 模型空间 Euler），
 * 未被 Vivecraft 分配角色的 SteamVR 追踪器由 {@link VrRawTrackers} 按位置
 * 自识别补充驱动（常见于 6 点 SlimeVR 套件的大腿/小腿）</li>
 * </ol>
 * <p>
 * 身体朝向：有腰部数据（FBT 已校准）用腰部 yaw，否则用 HMD yaw
 * （3 点追踪下身体跟随头部），指数平滑防抖。每帧把 {@code yBodyRotO/yBodyRot}
 * 覆盖为该朝向——Vivecraft 会把实体 yRot 设成手柄指针方向、yBodyRot 缓慢漂移
 * 过去（ModernYSM 模型根旋转用 lerpBodyRot），不覆盖的话整个模型会跟着
 * 指针转（表现是"后脑勺追着指针"）。原版模型自带的 bodyYaw（ARMS_ONLY 下
 * 手柄位置参与解算）只用于摆原版模型；复制到 YSM 时按两朝向之差旋转补偿
 * （见 {@link #applyVanillaBone}），渲染根始终用平滑身体朝向——头部不再被
 * 手臂/指针拖动，身体也不随手臂摇摆。
 * <p>
 * 视角绑定（{@link VrViewConfig}）：每帧通过 {@link VrViewHook} 平移渲染
 * VRData 的 origin，把第一人称视角粘到模型头部骨骼的动画实时位置。
 * 身高适配（{@link VrBodyConfig}）：调用 Vivecraft 的世界缩放功能，使玩家
 * 在世界中的身高 ≈ YSM 模型身高（不动模型，视角绑定自动适配）。
 * 姿势校准（{@link VrCalibration}）：把当前写入的旋转取反并入绑定偏移。
 * <p>
 * 本类只有在 Vivecraft 存在时才被实例化（见 {@link ModBusSetup}），
 * 开关状态保存在 ClientSetup 中，避免 Vivecraft 缺失时的类加载失败。
 */
public final class VivecraftPoseTracker implements Tracker {

    /** 视角平移平滑状态（米，世界系）。 */
    private double smoothedX, smoothedY, smoothedZ;
    private long lastSmoothNanos;
    /** 平滑收敛速率（1/秒）。 */
    private static final double SMOOTH_RATE = 12.0;
    private static final double SMOOTH_EPS = 0.0005;

    /** 身体朝向平滑（弧度）：有腰用腰、无腰用 HMD，指数平滑防抖。 */
    private double smoothedBodyYaw;
    private boolean bodyYawInit;
    /** 覆盖前的原始 yHeadRot（度，调试用）：验证 Vivecraft 是否把它设为指针/相机朝向。 */
    private float lastRawYHeadRot;
    /** 最近一次写入的 bodyYaw（度），渲染帧开始时再强写回，防止 Vivecraft 之后覆盖。 */
    static float latestBodyYawDeg = Float.NaN;
    /** 原生头部模式：不覆盖头部/身体朝向，让 YSM 使用 Vivecraft 原生 yHeadRot/yBodyRot。 */
    static boolean nativeHeadMode = false;
    /** 上一帧 Head 局部 yaw（弧度，未归一化），用于跨 ±180° 时解缠，避免反向插值。 */
    private float lastHeadRy = Float.NaN;
    /** 上一帧补偿后的目标头世界角（弧度，未归一化），避免补偿公式自身跨 ±180°。 */
    private float lastDesiredHead = Float.NaN;

    /**
     * 上一帧被"动画冻结"写入 store 的非驱动骨骼（池化 ID）。行走释放身体动画时，
     * 只把上一帧冻结、本帧不再冻结的骨骼从 store 清除，交还 YSM 动画系统；
     * 不用整表清除，避免误删头部隐藏的 scale 覆盖与调试指令的覆盖。
     */
    private final Set<Integer> frozenBones = new HashSet<>();
    private final Set<Integer> armStretchBones = new HashSet<>();
    private final Set<Integer> armExtendedBones = new HashSet<>();
    private boolean footTrackingSuppressed;
    private int footTrackingRestoreTicks;

    /**
     * VR 覆盖挂起状态：0=正常，1=YSM 轮盘/额外动画播放中，2=本地玩家攻击挥击动画中。
     * 挂起期间撤掉 VR 骨骼覆盖，让 YSM 动画完整播放（攻击时头部仍跟 HMD）。
     */
    private byte vrSuspendedKind;

    /** 强制模型大小当前写入的骨骼（池化 ID），用于改值/关闭时清理旧覆盖。 */
    private int forcedScaleBoneId = -1;
    /** v0.2.36：模型整体位置偏移当前写入的根骨骼。 */
    private int modelOffsetBoneId = -1;
    /** v0.2.41：主/副手物品锚点偏移当前写入的 locator。 */
    private int mainItemOffsetBoneId = -1;
    private int offItemOffsetBoneId = -1;
    /** v0.2.42：移动中锁定的中间骨骼（Root 等）位置覆盖，停止移动时清理。 */
    private final java.util.Set<Integer> movementLockedBones =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> lyingRootBones = new java.util.HashSet<>();
    private static int lyingLogCounter;
    private static int fullBodyRootLog;

    private final org.joml.Matrix3f lyingRootMatrix = new org.joml.Matrix3f();
    private boolean lyingRootActive;
    private final java.util.Set<Integer> lyingRootIds = new java.util.HashSet<>();    private final Map<String, Vector3f> trackedArmDirs = new HashMap<>();
    private final Map<String, Vec3> trackedElbowPos = new HashMap<>();
    private VRBodyPartData pendingElbowData;
    private VRBodyPartData pendingWaistData;
    private VRBodyPartData pendingHeadData;
    private Vec3 armPoleOverride;
    /** v0.2.49：持械时锁定的下半身骨骼。 */
    private final java.util.Set<Integer> holdLowerBodyLockedBones =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** v0.2.50：持 TACZ 时锁定的手部骨骼。 */
    private final java.util.Set<Integer> taczHandLockedBones =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** GUI/状态用：当前强制缩放骨骼名（未启用/不可用时为 null）。 */
    static volatile String latestModelScaleBone;

    /**
     * 本帧手柄世界位置 → 模型比例位置的缩放比（模型世界头部高度 / 玩家世界眼高）。
     * 玩家大小/模型大小改变后模型与玩家世界尺寸可能不一致，直接用手柄世界位置做
     * 瞄准会让模型手臂过矮/过高；按该比例把手柄位置缩放到模型比例再算方向。
     */
    private float handWorldScaleRatio = 1f;

    /** 骨骼/动画诊断日志限流计数（debugLog 开启时约每 1.5 秒输出一次）。 */
    private static int animDiagCounter;

    /** 手柄比例换算诊断日志限流计数。 */
    private static int handScaleLogCounter;

    /** 手臂瞄准原点/手柄位置诊断日志限流计数。 */
    private static int handAimLogCounter;
    private static int armDirectLogCounter;
    private static int handAlignLogCounter;
    /** 最近一次手持物品对齐的中间状态（/ysmvr status 显示，便于定位不生效原因）。 */
    public static volatile String latestHandAlignState = "未运行";
    /** 每根手臂骨骼当前平滑后的自动对齐修正（模型单位，binding.pos 之上的运行时增量）。 */
    private static final Map<Integer, float[]> handAlignCorrection = new java.util.concurrent.ConcurrentHashMap<>();

    // ── worldRotation 现场只读（诊断 90° 偏移用）──
    private static final Method WR_GET_INSTANCE;
    private static final Field WR_SETTINGS_FIELD;
    private static final Field WR_ROTATION_FIELD;
    private static Object wrSettings;
    private static final Field THIRD_CAM_MODULE;
    private static final Field THIRD_CAM_POS;

    static {
        Method getInstance = null;
        Field settings = null;
        Field rotation = null;
        Field thirdModule = null;
        Field thirdPos = null;
        try {
            Class<?> holder = Class.forName("org.vivecraft.client_vr.ClientDataHolderVR");
            getInstance = holder.getMethod("getInstance");
            settings = holder.getField("vrSettings");
            rotation = Class.forName("org.vivecraft.client_vr.settings.VRSettings")
                    .getField("worldRotation");
            thirdModule = holder.getField("thirdCamModule");
            thirdPos = Class.forName("org.vivecraft.client_vr.gameplay.interact_modules.ThirdPersonCameraModule")
                    .getDeclaredField("camPos");
            thirdPos.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            // 版本不匹配：保持 null，日志输出 "?" 占位
        }
        WR_GET_INSTANCE = getInstance;
        WR_SETTINGS_FIELD = settings;
        WR_ROTATION_FIELD = rotation;
        THIRD_CAM_MODULE = thirdModule;
        THIRD_CAM_POS = thirdPos;
    }

    /** 读取 Vivecraft 第三人称相机位置；不可用返回 null。 */
    private static Vec3 readThirdCamPos() {
        if (THIRD_CAM_MODULE == null || THIRD_CAM_POS == null) {
            return null;
        }
        try {
            Object holder = WR_GET_INSTANCE.invoke(null);
            if (holder == null) {
                return null;
            }
            Object module = THIRD_CAM_MODULE.get(holder);
            if (module == null) {
                return null;
            }
            return (Vec3) THIRD_CAM_POS.get(module);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** 读取 Vivecraft 第三人称相机相对玩家的方向（弧度）；不可用返回 null。 */
    private static Float readThirdCamYawRad(LocalPlayer player) {
        Vec3 pos = readThirdCamPos();
        if (pos == null || player == null) {
            return null;
        }
        double dx = pos.x - player.getX();
        double dz = pos.z - player.getZ();
        if (Math.abs(dx) < 1.0e-4 && Math.abs(dz) < 1.0e-4) {
            return null;
        }
        return (float) Math.atan2(-dx, dz);
    }

    /** 读取 Vivecraft 世界旋转角度（度）；不可用返回 NaN。 */
    private static float readWorldRotationDeg() {
        if (WR_GET_INSTANCE == null || WR_SETTINGS_FIELD == null || WR_ROTATION_FIELD == null) {
            return Float.NaN;
        }
        try {
            if (wrSettings == null) {
                wrSettings = WR_SETTINGS_FIELD.get(WR_GET_INSTANCE.invoke(null));
            }
            return (Float) WR_ROTATION_FIELD.get(wrSettings);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Float.NaN;
        }
    }

    /** 玩家身高适配：世界缩放平滑值 + 接管状态 + 玩家原有设置值。 */
    private double smoothedScale = 1.0;
    private boolean worldScaleActive;
    private float savedUserWorldScale = 1f;

    // ── 供 GUI（身高/玩家大小面板）显示的实时诊断值 ──
    /** 房间系真实眼高（米）；数据未就绪为 NaN。 */
    static volatile float latestEyeHeight = Float.NaN;
    /** 模型头部实时高度（米）；数据未就绪为 NaN。 */
    static volatile float latestModelHeadY = Float.NaN;
    /** 本帧目标 worldScale；未接管/无数据为 NaN。 */
    static volatile float latestTargetWorldScale = Float.NaN;
    /** 手柄位置→模型比例的缩放比（模型世界头高 / 玩家缩放后世界眼高）。 */
    static volatile float latestHandScaleRatio = 1f;
    /** 接管前保存的玩家原有 Vivecraft worldScale；未接管为 NaN。 */
    static volatile float latestSavedUserWorldScale = Float.NaN;

    @Override
    public ProcessType processType() {
        return ProcessType.PER_FRAME;
    }

    @Override
    public boolean isActive(LocalPlayer player) {
        // sim 模拟模式不依赖 Vivecraft 追踪开关，桌面调试时单独可用
        return (ClientSetup.isTrackingEnabled() || VrSimulator.isEnabled()) && player != null;
    }

    @Override
    public void activeProcess(LocalPlayer player) {
        VRClientAPI api = null;
        try {
            api = VRClientAPI.instance();
        } catch (Throwable t) {
            // Vivecraft 未安装或不可用
        }

        boolean simEnabled = VrSimulator.isEnabled();
        VRPose pose = null;
        if (simEnabled) {
            pose = VrSimulator.getPose(player);
        } else if (api != null && api.isVRActive()) {
            pose = api.getWorldRenderPose();
        }
        if (pose == null) {
            return;
        }
        var bridge = ActiveBridge.get();
        if (bridge == null) {
            return;
        }
        var store = bridge.store(player);
        if (store == null) {
            return;
        }
        // 轮盘/额外动画（变身、乱舞、舞蹈等）播放期间，或本地玩家挥击（攻击动画）
        // 期间：撤掉 VR 姿态覆盖，让 YSM 动画完整播放。检测与结束由 YSM 自身的
        // isModelSwitching 状态 / 挥击状态决定。攻击时头部仍由 VR（HMD）驱动，
        // 见下方 suspendKind==2 的处理。
        boolean ysmAnimation = bridge.isPlayingYsmAnimation(player);
        if (ysmAnimation && bridge.isYsmAnimationHolding(player)) {
            // hold_on_last_frame 动画已播完正在定格（如酒狐鼓掌 extra2）：
            // v0.2.17 起不再循环播放（用户明确要求"鼓掌之类的不要循环了"），
            // 主动结束动画切换、立刻恢复 VR 骨骼覆盖。
            bridge.endYsmAnimation(player);
            ysmAnimation = false;
        }
        boolean attackAnimation = !ysmAnimation
                && VrBodyConfig.attackAnimationEnabled && isAttacking(player);
        byte suspendKind = ysmAnimation ? (byte) 1 : attackAnimation ? (byte) 2 : (byte) 0;
        if (suspendKind != vrSuspendedKind) {
            if (!store.overrides().isEmpty()) {
                store.clearAll();
            }
            frozenBones.clear();
            if (suspendKind == 1) {
                YsmVrMod.LOGGER.info("[ysmvr] YSM 轮盘/额外动画开始：暂停 VR 骨骼覆盖，直到动画结束");
            } else if (suspendKind == 2) {
                YsmVrMod.LOGGER.info("[ysmvr] 攻击动画开始：除头部（HMD）外暂停 VR 骨骼覆盖，直到挥击结束");
            } else {
                YsmVrMod.LOGGER.info("[ysmvr] YSM 轮盘/攻击动画结束：恢复 VR 骨骼覆盖");
            }
            vrSuspendedKind = suspendKind;
        } else if (suspendKind == 1 && !store.overrides().isEmpty()) {
            // 轮盘期间渲染层（头部隐藏 scale 等）可能重新写入，持续清掉
            store.clearAll();
        }
        // 强制模型大小（不依赖 v.player_size；所有模型可用）。放在挂起清空之后，
        // 保证轮盘/攻击动画期间也不会被 clearAll 冲掉。
        applyForcedModelScale(player, store, bridge);
        applyForcedModelOffset(player, store, bridge);
        applyForcedItemOffsets(player, store, bridge);
        applyMovementPositionLock(player, store, bridge, null);
        // 强制 YSM 每帧都走完整动画管线：异步 Worker 以 isFirstPerson=true 处理本地
        // 模型时，wasAnimationActiveLastTick 会被写成 false，导致 z2=false 的帧上我们的
        // controller 不执行，而 applyHeadTracking 仍用旧 armorBoneOffset 覆盖 Head。
        // 轮盘/攻击动画期间仍需要它，否则动画不会推进/收尾。
        bridge.forceAnimationActive(player);
        if (suspendKind != 1) {
            // 攻击动画期间头部仍要写 store（下一帧由 forceHeadBoneToStore 保持 VR 头）
            bridge.forceHeadBoneToStore(player);
        }

        // 模型整体缩放（渲染器根 poseStack.scale(heightScale,widthScale,heightScale)）：
        // 模型单位换算世界米的系数。默认模型 0.7/0.7；官方 YSM 拿不到时按 1。
        float[] modelScale = bridge.getModelScale(player);
        float scaleXZ = modelScale == null ? 1f : modelScale[0];
        float scaleY = modelScale == null ? 1f : modelScale[1];

        long now = System.nanoTime();
        double dt = lastSmoothNanos == 0 ? 1.0 / 90.0 : (now - lastSmoothNanos) / 1e9;
        lastSmoothNanos = now;
        dt = Math.max(0.005, Math.min(dt, 0.1));

        // ── 原版模型姿态捕获：vpose.bodyYaw 在 ARMS_ONLY 下用控制器位置+头部方向
        // 求解（getBodyYawRad()），随手臂/指针晃动（"头随指针/手臂转动的异常晃动"的
        // 根源），故不再作为身体朝向源。部位复制（applyVanillaBone）保持禁用：
        // Δ 补偿与自定义 bodyYaw 冲突会引发身体部位异常旋转。──
        VanillaVrPoseSource.Pose vpose = VanillaVrPoseSource.capture(player);

        var headData = pose.getBodyPartData(VRBodyPart.HEAD);

        // ── 身体朝向：有腰用腰；无腰用 HMD（平滑稳定，避免 vpose.bodyYaw 在
        // 正面→左侧过渡时因手臂姿态变化产生跳变，导致头部翻转）。──
        float bodyYaw;
        var waistData = pose.getBodyPartData(VRBodyPart.WAIST);
        Float bodyTargetYaw = null;
        if (waistData != null) {
            bodyTargetYaw = (float) waistData.getYaw();
        } else if (headData != null) {
            bodyTargetYaw = (float) headData.getYaw();
        }
        if (bodyTargetYaw != null) {
            if (!bodyYawInit) {
                smoothedBodyYaw = bodyTargetYaw;
                bodyYawInit = true;
            }
            double rawYaw = bodyTargetYaw;
            double delta = rawYaw - smoothedBodyYaw;
            delta = Math.IEEEremainder(delta, 2 * Math.PI);
            smoothedBodyYaw += delta * (1.0 - Math.exp(-SMOOTH_RATE * dt));
            bodyYaw = (float) smoothedBodyYaw;
            // 覆盖 yBodyRot：模型根旋转（lerpBodyRot）与所有骨骼换算都以身体朝向为基准，
            // 彻底脱离手柄指针（yBodyRot 原本会每 tick 漂移向 yRot=指针方向）
            float deg = (float) Math.toDegrees(bodyYaw);
            player.yBodyRotO = deg;
            player.yBodyRot = deg;
            latestBodyYawDeg = deg;
            // 关键：同步覆盖 yHeadRot。Vivecraft 的 doPermanentLookOverride 每 tick 把
            // yHeadRot 设为准星方向（瞄准时=手柄指针方向，第三人称相机/手柄侧指时与
            // 身体朝向差 90°~180°），ModernYSM 内置头部追踪用
            // netHeadYaw = yHeadRot − yBodyRot 在动画处理器之后覆盖 Head 骨骼——
            // 这就是"第三人称头部恒偏 90°（±85° 截断）"的根源。覆盖后 netHeadYaw = 0，
            // 头部 yaw 只由下方 applyHeadDirect 锁定 HMD 朝向。
            lastRawYHeadRot = player.yHeadRot; // 覆盖前的原始值（调试用）
            player.yHeadRotO = deg;
            player.yHeadRot = deg;
        } else {
            bodyYaw = (float) Math.toRadians(
                    Mth.rotLerp(Minecraft.getInstance().getFrameTime(), player.yBodyRotO, player.yBodyRot));
        }
        // 原版模型身体朝向 − 渲染根朝向：复制部位的世界姿态补偿角（rad）
        float deltaYaw = 0f;
        if (vpose != null) {
            deltaYaw = vpose.bodyYaw - bodyYaw;
            deltaYaw = (float) Math.IEEEremainder(deltaYaw, 2 * Math.PI);
        }

        // 头部骨骼实时模型位置（模型单位）：视角绑定与身高适配共用
        float[] headModelPos = null;
        if (VrViewConfig.enabled || VrBodyConfig.scaleEnabled) {
            headModelPos = bridge.getBoneModelPosition(player, VrViewConfig.resolvedHeadBone());
            if (headModelPos == null) {
                headModelPos = bridge.getBoneModelPosition(player, "Head");
            }
        }

        // 手柄世界位置 → 模型比例位置：模型世界头部高度 / 玩家世界眼高。
        // 玩家大小/模型大小改完后模型与玩家世界尺寸可能不一致，直接用手柄世界
        // 位置做瞄准会让模型手臂过矮/过高（校准时尤其明显），这里按比例缩放。
        //
        // 玩家世界眼高按 Vivecraft 语义反推 = 真实房间眼高 / 当前世界缩放。
        // 不能直接用 headData.getPos().y - player.getY()：改玩家大小时 Vivecraft
        // 会平移房间原点保持视线位置，实体脚底 Y 与世界姿态的差值不再等于缩放后的
        // 玩家身高；用它会把控制器位置按"现实玩家高度"缩放——玩家缩小到模型高度后，
        // 手臂瞄准原点仍停在现实高度，模型手臂就会向下过长（校准还会把该偏差写入
        // 绑定偏移，且每次校准都重复放大）。
        float ratio = 1f;
        float[] headForRatio = headModelPos;
        if (headForRatio == null) {
            headForRatio = bridge.getBoneModelPosition(player, VrViewConfig.resolvedHeadBone());
            if (headForRatio == null) {
                headForRatio = bridge.getBoneModelPosition(player, "Head");
            }
        }
        if (headForRatio != null && headData != null && headData.getPos() != null) {
            double modelHeadWorld = headForRatio[1] / 16.0 * scaleY;
            double roomEyeHeight = Double.NaN;
            float worldScale = Float.NaN;
            // 兜底：未取到 API 数据时仍用"世界姿态头 Y − 玩家脚底 Y"
            double playerWorldEye = headData.getPos().y - player.getY();
            if (api != null && api.isVRActive()) {
                try {
                    VRPose roomPose = api.getLatestRoomPose();
                    if (roomPose != null) {
                        VRBodyPartData roomHead = roomPose.getBodyPartData(VRBodyPart.HEAD);
                        if (roomHead != null && roomHead.getPos() != null) {
                            roomEyeHeight = roomHead.getPos().y;
                        }
                    }
                    worldScale = api.getWorldScale();
                    // Vivecraft 语义（en_us 设置提示 & VRPlayerRenderer 均确认）：
                    // worldScale > 1 玩家变大、< 1 变小，玩家世界身高 = 真实眼高 × worldScale。
                    // 之前按"除以 worldScale"是反的：世界缩放 0.588 时玩家实际约 0.93m，
                    // 旧公式却算成 2.67m，于是手柄位置被额外往脚下缩 0.31 倍 → 手臂向下过长。
                    if (Float.isFinite(worldScale) && worldScale > 0.05f
                            && Double.isFinite(roomEyeHeight) && roomEyeHeight > 0.3) {
                        playerWorldEye = roomEyeHeight * worldScale;
                    }
                } catch (Throwable t) {
                    // API 数据不可用：退回世界姿态差值
                }
            }
            if (modelHeadWorld > 0.05 && playerWorldEye > 0.3) {
                ratio = (float) Mth.clamp(modelHeadWorld / playerWorldEye, 0.2, 5.0);
            }
            if (VrBodyConfig.debugLog && ++handScaleLogCounter % 180 == 0) {
                YsmVrMod.LOGGER.info(
                        "[ysmvr][debug] handScale: modelHead={}m roomEye={} worldScale={} playerEye={}m ratio={}",
                        String.format(java.util.Locale.ROOT, "%.3f", modelHeadWorld),
                        Double.isFinite(roomEyeHeight)
                                ? String.format(java.util.Locale.ROOT, "%.3f", roomEyeHeight) : "?",
                        Float.isFinite(worldScale)
                                ? String.format(java.util.Locale.ROOT, "%.3f", worldScale) : "?",
                        String.format(java.util.Locale.ROOT, "%.3f", playerWorldEye),
                        String.format(java.util.Locale.ROOT, "%.3f", ratio));
            }
        }
        handWorldScaleRatio = ratio;
        latestHandScaleRatio = ratio;

        // 自识别（原始追踪器分类）结果：采集一次供本帧两处使用
        Map<VRBodyPart, float[]> rawClassified = VrRawTrackers.classify(pose, bodyYaw);

        // 追踪模式检测：有肘/膝追踪数据（含自识别的原始追踪器）→ 扩展默认
        // 绑定（手柄→前臂、脚→小腿、肘→上臂、膝→大腿）；否则简单默认
        // （手柄→整臂、脚→大腿，不绑肘/膝）。只切换未被自定义的部位。
        // 手动锁定（按键/ /ysmvr mode）时跳过自动检测。
        if (VrBindings.modeAuto()) {
            boolean hasElbowKnee = pose.getBodyPartData(VRBodyPart.RIGHT_ELBOW) != null
                    || pose.getBodyPartData(VRBodyPart.LEFT_ELBOW) != null
                    || pose.getBodyPartData(VRBodyPart.RIGHT_KNEE) != null
                    || pose.getBodyPartData(VRBodyPart.LEFT_KNEE) != null
                    || rawClassified.containsKey(VRBodyPart.RIGHT_ELBOW)
                    || rawClassified.containsKey(VRBodyPart.LEFT_ELBOW)
                    || rawClassified.containsKey(VRBodyPart.RIGHT_KNEE)
                    || rawClassified.containsKey(VRBodyPart.LEFT_KNEE);
            VrBindings.setMode(hasElbowKnee ? VrBindings.Mode.EXTENDED : VrBindings.Mode.SIMPLE);
        }

        // 轮盘/攻击动画期间：撤销全部 VR 骨骼写入（store 已清空），
        // 只保留朝向覆盖、视角绑定与身高适配；攻击时额外把头部写回 VR（跟 HMD）。
        if (VrBodyConfig.debugLog && ++animDiagCounter % 90 == 0) {
            for (String line : bridge.animationDiagnostics(player)) {
                YsmVrMod.LOGGER.info("[ysmvr][debug] animdiag {}", line);
            }
        }
        if (suspendKind != 0) {
            if (suspendKind == 2 && headData != null) {
                // 攻击动画：除头部方向（HMD）外全部交还 YSM 挥击动画
                var headBindings = VrBindings.get(VRBodyPart.HEAD);
                if (headBindings != null) {
                    for (var binding : headBindings) {
                        if (applyHeadDirect(player, store, bridge, binding, headData, bodyYaw, waistData,
                                null)) {
                            break;
                        }
                    }
                }
            }
            VrCalibration.endFrameCapture();
            applyView(headData, bodyYaw, headModelPos, dt, scaleXZ, scaleY);
            applyScale(headModelPos, dt, scaleY);
            return;
        }

        // 先收集本帧所有"将被驱动"的骨骼名：上臂虚拟半程驱动只在这些骨骼
        // 没有被别的部位驱动时补位（避免与已绑定的上臂追踪数据冲突）
        var snapshot = VrBindings.snapshot();
        int modeValue = Math.max(0, Math.min(4, VrBodyConfig.trackingMode));
        VrBindings.applyTrackingLayout(modeValue);
        final int trackMode = modeValue;
        // 移动时暂停脚追，交还 YSM walk/run；停止且移动过渡结束后恢复脚追。
        boolean footMode = trackMode >= 1;
                boolean movingNow = player.getDeltaMovement().horizontalDistanceSqr() > 2.5e-3
                || Math.abs(player.zza) > 1.0e-4f || Math.abs(player.xxa) > 1.0e-4f;        if (VrBodyConfig.moveAnimationEnabled && footMode) {
            if (movingNow) {
                if (!footTrackingSuppressed) {
                    footTrackingSuppressed = true;
                    clearLowerBodyOverrides(player, store, bridge);
                    bridge.forceAnimationActive(player);
                    debugLog("移动动画：暂停脚追，交还 YSM 移动动画");
                }
                footTrackingRestoreTicks = 0;
            } else if (footTrackingSuppressed) {
                footTrackingRestoreTicks++;
                if (footTrackingRestoreTicks >= 5) {
                    footTrackingSuppressed = false;
                    debugLog("移动动画过渡结束：恢复脚追");
                }
            }
            if (footTrackingSuppressed) {
                snapshot.entrySet().removeIf(e -> e.getKey() == VRBodyPart.RIGHT_FOOT
                        || e.getKey() == VRBodyPart.LEFT_FOOT
                        || e.getKey() == VRBodyPart.RIGHT_KNEE
                        || e.getKey() == VRBodyPart.LEFT_KNEE);
                rawClassified.entrySet().removeIf(e -> e.getKey() == VRBodyPart.RIGHT_FOOT
                        || e.getKey() == VRBodyPart.LEFT_FOOT
                        || e.getKey() == VRBodyPart.RIGHT_KNEE
                        || e.getKey() == VRBodyPart.LEFT_KNEE);
            }
        }        snapshot.entrySet().removeIf(e -> !trackingPartAllowed(e.getKey(), trackMode));
        rawClassified.entrySet().removeIf(e -> !trackingPartAllowed(e.getKey(), trackMode));
        Set<String> drivenBones = new HashSet<>();
        for (var entry : snapshot.entrySet()) {
            VRBodyPart part = entry.getKey();
            if (!part.availableInMode(pose.getFBTMode()) || pose.getBodyPartData(part) == null) {
                continue;
            }
            for (var b : entry.getValue()) {
                drivenBones.add(b.boneName);
            }
        }
        for (var rawEntry : rawClassified.entrySet()) {
            var rawBindings = VrBindings.get(rawEntry.getKey());
            if (rawBindings != null) {
                for (var b : rawBindings) {
                    drivenBones.add(b.boneName);
                }
            }
        }

        // 共享祖先：被 ≥2 个不同部位的绑定链共用的骨骼（典型：SIMPLE 模式下
        // 左右臂共同的躯干父骨骼）。上臂补位若写这种骨骼，半旋会顺着层级
        // 污染另一条链（左手被右手补位拧转），必须跳过。
        Set<String> sharedBones = computeSharedAncestors(bridge, player, snapshot);

        // 动画冻结 / 移动动画：
        // - 待机：本帧不被任何部位驱动的骨骼旋转覆盖为初始值（绑定姿态），
        //   模型把 VR 姿态当作唯一旋转源；位置/缩放通道不覆盖，留给模型动画
        //   （酒狐等用它隐藏第二形态/坐骑，完整覆盖会把隐藏件强制 scale=1）；
        // - 行走且开启移动动画：只冻结手臂/头部子树（它们仍由 VR 驱动，不能被
        //   walk 动画摆走），其余骨骼从 store 释放，交还 YSM 的 walk/run 动画。
        Set<Integer> frozenThisFrame = new HashSet<>();
        // 攻击动画关闭时：把非 VR 驱动骨骼全部冻结，彻底压掉 YSM 挥击动画。
        String attackRaw = bridge.getRawAnimationKey(player);
        String attackRawLower = attackRaw == null ? ""
                : attackRaw.toLowerCase(java.util.Locale.ROOT);
        boolean attackActive = isAttacking(player)
                || attackRawLower.contains("swing") || attackRawLower.contains("attack")
                || attackRawLower.contains("sword") || attackRawLower.contains("melee");
        boolean attackSuppress = !VrBodyConfig.attackAnimationEnabled && attackActive;
        if (attackSuppress) {
            // 无攻击动画：直接取消 YSM 的挥击 controller，比冻结骨骼更彻底。
            bridge.suppressAttackAnimation(player);
        }
        boolean lowerAnimationOff = !VrBodyConfig.moveAnimationEnabled;
        if (VrBodyConfig.freezeModelAnimations || attackSuppress || lowerAnimationOff) {
            for (String boneName : bridge.listBones(player)) {
                if (drivenBones.contains(boneName)) {
                    continue;
                }
                if (lowerAnimationOff && !VrBodyConfig.freezeModelAnimations && !attackSuppress
                        && !isLowerBodyBone(boneName)) {
                    continue;
                }
                int id = bridge.resolveBone(player, boneName);
                if (id < 0) {
                    continue;
                }
                float[] initial = bridge.getBoneInitialRotation(player, boneName);
                if (initial != null) {
                    // 仅旋转冻结：位置/缩放通道留给模型动画，否则酒狐等用
                    // scale=0 隐藏的第二形态/坐骑会被强制 scale=1 在待机时显形。
                    store.setFrozenRotation(id, initial[0], initial[1], initial[2]);
                    frozenThisFrame.add(id);
                }
            }
        }
        // 上一帧冻结、本帧不再冻结的骨骼：清除 store 覆盖，交还 YSM 动画
        //（待机→行走释放身体；关闭冻结时全部恢复）。
        if (!frozenBones.isEmpty()) {
            for (int id : frozenBones) {
                if (!frozenThisFrame.contains(id)) {
                    store.clear(id);
                }
            }
            frozenBones.clear();
        }
        frozenBones.addAll(frozenThisFrame);

        // 冻结/释放循环可能把"仅缩放"的强制模型大小覆盖清掉（待机→行走时
        // 根骨骼被释放，store.clear 会连 scale 一起删）。这里补写一次，确保
        // 本帧 applyHandAim 读 getBoneModelPosition（会叠加 store 覆盖）和后续
        // 渲染都带上缩小后的模型缩放——否则肩膀旋转轴仍停在现实高度。
        applyForcedModelScale(player, store, bridge);
        applyForcedModelOffset(player, store, bridge);
        applyForcedItemOffsets(player, store, bridge);
        applyMovementPositionLock(player, store, bridge, null);

        // ── 按绑定配置驱动骨骼（一个 VR 部位可绑多根骨骼）──
        Set<String> copiedBones = new HashSet<>();
        for (var entry : snapshot.entrySet()) {
            VRBodyPart part = entry.getKey();
            // 手部不走原版复制：Vivecraft 的 VR 手部零件是自定义坐标系（手指朝 −Z，
            // "ModelParts are rotated 90°"，见 vivecraft ModelUtils.pointAtModel），
            // 逐分量符号映射在伸直（vanilla 纯 z 滚动）与 T字型之间无法同时成立
            // （实测：rz=−pz 伸直指左、rz=+pz 伸直指右）。手部固定走下方方位
            // 瞄准路径（方向+位置定向，长期实测验证，过 90° 正常）
            boolean hand = part == VRBodyPart.MAIN_HAND || part == VRBodyPart.OFF_HAND;
            VanillaVrPoseSource.Part vpart = vpose != null ? vanillaPart(part) : null;
            // 原版复制会让前臂浮空，先回到自研 aim。
            boolean copyPart = false;
            for (var binding : entry.getValue()) {
                if (nativeHeadMode && part == VRBodyPart.HEAD) {
                    // 原生模式：不写 Head，完全交给 YSM 原生头部追踪
                    continue;
                }
                if (copyPart && applyVanillaBone(player, store, bridge, binding, vpose, vpart,
                        deltaYaw, bodyYaw)) {
                    copiedBones.add(binding.boneName);
                    continue;
                }
                if (part == VRBodyPart.HEAD && headData != null
                        && applyHeadDirect(player, store, bridge, binding, headData, bodyYaw,
                        waistData, drivenBones)) {
                    copiedBones.add(binding.boneName);
                    continue;
                }
            }
            if (!part.availableInMode(pose.getFBTMode())) {
                continue;
            }
            var data = pose.getBodyPartData(part);
            if (data == null) {
                continue;
            }
            String armOverride = hand
                    ? ModelProfileStore.armBoneOverride(player, bridge, part) : null;
            for (var binding : entry.getValue()) {
                if (armOverride == null && copiedBones.contains(binding.boneName)) {
                    continue;
                }
                VrBindings.Binding useBinding = binding;
                if (armOverride != null) {
                    var existing = VrBindings.findBone(part, armOverride);
                    useBinding = existing != null ? existing : new VrBindings.Binding(armOverride);
                }
                VRBodyPart kneePart = part == VRBodyPart.RIGHT_FOOT
                        ? VRBodyPart.RIGHT_KNEE : VRBodyPart.LEFT_KNEE;
                if ((part == VRBodyPart.RIGHT_FOOT || part == VRBodyPart.LEFT_FOOT)
                        && trackMode >= 2 && pose.getBodyPartData(kneePart) != null
                        && applyTrackedLeg(player, store, bridge, useBinding, data,
                        pose.getBodyPartData(kneePart), bodyYaw, scaleXZ, scaleY,
                        drivenBones, vpose)) {
                    copiedBones.add(useBinding.boneName);

                    String thighName = bridge.getParentBoneName(player, useBinding.boneName);
                    if (thighName != null) {
                        copiedBones.add(thighName);
                    }                    continue;
                }
                var kneeBindings = VrBindings.get(kneePart);
                boolean hasKneeBinding = kneeBindings != null && !kneeBindings.isEmpty();
                if ((part == VRBodyPart.RIGHT_FOOT || part == VRBodyPart.LEFT_FOOT)
                        && !hasKneeBinding
                        && applyFootIk(player, store, bridge, useBinding, data, bodyYaw,
                        scaleXZ, scaleY, drivenBones)) {
                    copiedBones.add(useBinding.boneName);
                    continue;
                }
                if (hand) {
                    VRBodyPart elbowPart = useBinding.boneName.toLowerCase(java.util.Locale.ROOT).contains("left")
                            ? VRBodyPart.LEFT_ELBOW : VRBodyPart.RIGHT_ELBOW;
                    if (trackMode >= 4 && !VrBodyConfig.armDirectRotation && pose.getBodyPartData(elbowPart) != null
                            && applyTrackedArm(player, store, bridge, useBinding, data,
                            pose.getBodyPartData(elbowPart), bodyYaw, scaleXZ, scaleY,
                            drivenBones, vpose, trackMode >= 4)) {
                        copiedBones.add(useBinding.boneName);
                        copiedBones.add(useBinding.boneName.replace("ForeArm", "Arm"));
                        continue;
                    }
                    pendingElbowData = trackMode >= 3 ? pose.getBodyPartData(elbowPart) : null;
boolean armIsLeft = useBinding.boneName.toLowerCase(java.util.Locale.ROOT).contains("left");
                    int armJointMode = armIsLeft ? VrBodyConfig.leftJointMode : VrBodyConfig.rightJointMode;
                    if (pendingElbowData != null && armJointMode == 5) {
                        pendingElbowData = null;
                    }                    pendingWaistData = waistData;
                    pendingHeadData = headData;
                    applyHandAim(player, store, bridge, useBinding, data, bodyYaw, drivenBones, sharedBones,
                            scaleXZ, scaleY, vpose, vpart, deltaYaw);
                    pendingElbowData = null;
                    pendingWaistData = null;
                    pendingHeadData = null;
                    armPoleOverride = null;
                } else {
                    applyBone(player, store, bridge, useBinding,
                            data.getPitch(), data.getYaw(), data.getRoll(), bodyYaw);
boolean lyingNow = VrBodyConfig.fullBodyLyingMode
                            && (player.isCrouching() || player.isSwimming()
                            || player.isVisuallySwimming() || player.isFallFlying()
                            || Math.abs((headData != null ? headData : data).getPitch()) > Math.toRadians(50.0)
                            || Math.abs((headData != null ? headData : data).getRoll()) > Math.toRadians(50.0)
                            || (waistData != null && waistData.getPos() != null
                            && waistData.getPos().y - player.getY() < 0.6
                            && headData != null && headData.getPos() != null
                            && headData.getPos().y - player.getY() < 0.9));                    if (part == VRBodyPart.WAIST && VrBodyConfig.trackingMode == 5
                            && FullBodyCalibration.valid() && headData != null) {
                        applyFullBodyRoot(player, store, bridge, headData, waistData, pose,
                                bodyYaw, scaleXZ, scaleY, drivenBones);
                    } else if (part == VRBodyPart.WAIST && lyingNow) {
                        applyLyingRoots(player, store, bridge, data, headData, pose, bodyYaw, scaleXZ, scaleY, drivenBones);
                    } else if (part == VRBodyPart.WAIST
                            && (VrBodyConfig.fullBodyLyingMode || VrBodyConfig.trackingMode == 5)) {
                        clearLyingRoots(player, store, bridge);
                    }                }
            }
        }

        // 腰部兜底：即使绑定/过滤漏掉 WAIST，也直接驱动 UpBody。
        if (!drivenBones.contains("UpBody")) {
            var waistFallback = pose.getBodyPartData(VRBodyPart.WAIST);
            if (waistData != null) {
                debugLogKey("waist-fallback", "waist fallback apply UpBody");
                applyBone(player, store, bridge, new VrBindings.Binding("UpBody"),
                        waistData.getPitch(), waistData.getYaw(), waistData.getRoll(), bodyYaw);
                drivenBones.add("UpBody");
            }
        }
        // 原始追踪器回退：方位模式驱动缺失部位（已被复制的骨骼跳过）
        for (var rawEntry : rawClassified.entrySet()) {
            var rawBindings = VrBindings.get(rawEntry.getKey());
            if (rawBindings == null) {
                continue;
            }
            float[] e = rawEntry.getValue();
            for (var binding : rawBindings) {
                if (copiedBones.contains(binding.boneName)) {
                    continue;
                }
                applyBone(player, store, bridge, binding, e[0], e[1], e[2], bodyYaw);
            }
        }

        // v0.2.43：所有骨骼驱动写完后最后再锁移动根位置，防止中间骨骼
        // 被 applyBone/applyHandAim 的 setRotation 清掉 position-only。
        applyLyingRootCompensation(player, store, bridge);
        applyMovementPositionLock(player, store, bridge, drivenBones);

        if (VrCalibration.isCapturing() && VrBodyConfig.trackingMode == 5) {
            captureFullBodyCalibration(player, bridge, headData, waistData, bodyYaw, scaleXZ, scaleY);
        }
        // 姿势校准采集帧结束（未采集到数据则继续等下一帧，超时在 tick 侧）
        VrCalibration.endFrameCapture();

        // 视角绑定：与骨骼驱动同帧进行，顺序无耦合
        applyView(headData, bodyYaw, headModelPos, dt, scaleXZ, scaleY);

        // 玩家身高适配：调用 Vivecraft 世界缩放，使玩家身高 ≈ YSM 模型身高
        applyScale(headModelPos, dt, scaleY);
    }

    @Override
    public void inactiveProcess(LocalPlayer player) {
        // 追踪停用时保留最后一帧姿态，不主动清理覆盖
    }

    /** 方位模式：世界姿态 → 模型空间（弧度），叠加绑定旋转偏移。 */
    /** 仅把腰 pitch/roll 写到 Root 链（绕脚底根枢轴），避免绕躯干中心转。 */
    /** 是否腿部骨骼名。 */
    private static boolean isLegBoneName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.contains("leg") || n.contains("thigh") || n.contains("shin")
                || n.contains("calf") || n.contains("foot") || n.contains("knee")
                || n.contains("ankle") || n.contains("toe");
    }

    /** 是否躯干骨骼名。 */
    private static boolean isTorsoBoneName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.contains("upbody") || n.contains("upperbody") || n.contains("spine")
                || n.contains("chest") || n.contains("torso") || n.contains("waist")
                || n.contains("hip") || n.contains("pelvis") || n.contains("body");
    }

    /** 躺下时把躯干骨骼恢复 rest local，让躯干继承 Root 水平，而不是和 Root 对折。 */
    private void restoreLyingTorso(LocalPlayer player, PoseOverrideStore store, Bridge bridge) {
        var bones = bridge.listBones(player);
        if (bones == null) return;
        for (String name : bones) {
            if (name == null || !isTorsoBoneName(name)) continue;
            int id = bridge.resolveBone(player, name);
            if (id < 0) continue;
            float[] init = bridge.getBoneInitialRotation(player, name);
            store.setRotation(id, init == null ? 0f : init[0], init == null ? 0f : init[1], init == null ? 0f : init[2]);
        }
    }
    /**
     * 躺下时只把腿部链的根（大腿/上腿）恢复 rest local，
     * 让整条腿继承 Root 水平躺下；小腿/脚的膝盖弯曲仍来自 tracker。
     */
    private void restoreLyingLegRoots(LocalPlayer player, PoseOverrideStore store, Bridge bridge) {
        var bones = bridge.listBones(player);
        if (bones == null) return;
        for (String name : bones) {
            if (name == null || !isLegBoneName(name)) continue;
            String parent = bridge.getParentBoneName(player, name);
            if (parent != null && isLegBoneName(parent)) continue; // 链根才处理
            int id = bridge.resolveBone(player, name);
            if (id < 0) continue;
            float[] init = bridge.getBoneInitialRotation(player, name);
            store.setRotation(id, init == null ? 0f : init[0], init == null ? 0f : init[1], init == null ? 0f : init[2]);
        }
    }
    /** 躺下 Root 旋转补偿：让 store 驱动的四肢/头保持世界朝向，避免被 Root 二次旋转折叠。 */
    private void applyLyingRootCompensation(LocalPlayer player, PoseOverrideStore store, Bridge bridge) {
        if (!lyingRootActive || lyingRootIds.isEmpty()) {
            return;
        }
        Matrix3f inv = new Matrix3f(lyingRootMatrix).invert();
        java.util.Set<Integer> exclude = new java.util.HashSet<>(lyingRootIds);
        var bones = bridge.listBones(player);
        if (bones != null) {
            for (String name : bones) {
                if (name == null) continue;
                if (isTorsoBoneName(name) || isLegBoneName(name)) {
                    int id = bridge.resolveBone(player, name);
                    if (id >= 0) exclude.add(id);
                }
            }
        }
        store.counterRotateAll(exclude, inv);
        restoreLyingTorso(player, store, bridge);
        restoreLyingLegRoots(player, store, bridge);
    }
    private void clearLyingRoots(LocalPlayer player, PoseOverrideStore store, Bridge bridge) {
        if (!lyingRootBones.isEmpty()) {
            for (String name : lyingRootBones) {
                int id = bridge.resolveBone(player, name);
                if (id >= 0) {
                    store.clear(id);
                }
            }
            lyingRootBones.clear();
        }
        lyingRootActive = false;
        lyingRootIds.clear();
    }
    private String findFirstRootBone(LocalPlayer player, Bridge bridge) {
        var roots = bridge.rootBoneNames(player);
        if (roots != null) {
            for (String r : roots) {
                if (r != null && !r.isBlank()) return r;
            }
        }
        var bones = bridge.listBones(player);
        if (bones != null) {
            for (String n : bones) {
                if (n != null && n.toLowerCase(java.util.Locale.ROOT).contains("root")) return n;
            }
        }
        return null;
    }

    /** T 字校准时记录：模型 Root 世界位置、实体/HMD 偏移、Root 模型坐标。 */
    private void captureFullBodyCalibration(LocalPlayer player, Bridge bridge,
                                            VRBodyPartData headData, VRBodyPartData waistData,
                                            float bodyYaw, float scaleXZ, float scaleY) {
        String root = findFirstRootBone(player, bridge);
        if (root == null || headData == null || headData.getPos() == null) {
            return;
        }
        float[] rp = bridge.getBoneModelPosition(player, root);
        if (rp == null) return;
        Vec3 rootWorld = boneWorldPos(player, rp, bodyYaw, scaleXZ, scaleY);
        Vec3 entity = player.getPosition(Minecraft.getInstance().getFrameTime());
        Vec3 head = headData.getPos();
        Vec3 rootM = new Vec3(-rp[0], rp[1], rp[2]);
        org.joml.Quaternionfc refRot = waistData != null && waistData.getRotation() != null
                ? waistData.getRotation() : headData.getRotation();
        FullBodyCalibration.record(rootWorld, entity, head, rootM, refRot);
        YsmVrMod.LOGGER.info("[ysmvr][debug] fullBodyCal root={} rootWorld={} entity={} head={} rootM={}",
                root, rootWorld, entity, head, rootM);
    }

    /** 全身模式 Root：站立跟实体，身体水平时跟 HMD；水平时叠加躺下旋转。 */
    private void applyFullBodyRoot(LocalPlayer player, PoseOverrideStore store, Bridge bridge,
                                   VRBodyPartData headData, VRBodyPartData waistData, VRPose pose,
                                   float bodyYaw, float scaleXZ, float scaleY,
                                   java.util.Set<String> drivenBones) {
        if (!FullBodyCalibration.valid() || headData == null || headData.getPos() == null) {
            return;
        }
        Vec3 head = headData.getPos();
        Vec3 entity = player.getPosition(Minecraft.getInstance().getFrameTime());
        var lfPart = pose.getBodyPartData(VRBodyPart.LEFT_FOOT);
        var rfPart = pose.getBodyPartData(VRBodyPart.RIGHT_FOOT);
        Vec3 lf = lfPart == null ? null : lfPart.getPos();
        Vec3 rf = rfPart == null ? null : rfPart.getPos();
        Vec3 feet = lf != null && rf != null ? lf.add(rf).scale(0.5) : (lf != null ? lf : rf);
        Vec3 base = feet != null ? feet : (waistData != null ? waistData.getPos() : head);
        Vec3 upWorld = head.subtract(base);
        boolean horizontal = upWorld.length() > 0.35 && Math.abs(upWorld.y) < 0.45;
        boolean moving = player.walkAnimation.speed() > 0.05f
                || player.getDeltaMovement().horizontalDistanceSqr() > 2.5e-3;
        Vec3 rootWorld;
        if (feet != null && (horizontal || !moving)) {
            rootWorld = feet;
        } else {
            Vec3 off = FullBodyCalibration.entityOffset();
            rootWorld = entity.add(off == null ? Vec3.ZERO : off);
        }
        if (rootWorld == null) return;
        Vec3 desiredM = worldToModelUnits(rootWorld, entity, bodyYaw, scaleXZ, scaleY);
        Vec3 delta = desiredM.subtract(FullBodyCalibration.rootModelCal());
        if ((++fullBodyRootLog % 60) == 0) {
            YsmVrMod.LOGGER.info("[ysmvr][debug] fullBodyRoot horizontal={} moving={} entity={} feet={} head={} rootWorld={} desiredM={} delta={}",
                    horizontal, moving, entity, feet, head, rootWorld, desiredM, delta);
        }
        Vector3f e = new Vector3f();
        if (horizontal) {
            Vector3f upM = worldToModelDir(upWorld.normalize(), bodyYaw).normalize();
            Quaternionf q = new Quaternionf().rotationTo(0f, 1f, 0f, upM.x, upM.y, upM.z);
            Vector3f desiredF = worldToModelDir(headData.getDir(), bodyYaw);
            Vector3f desiredPerp = new Vector3f(desiredF).sub(new Vector3f(upM).mul(desiredF.dot(upM)));
            Vector3f currentF = q.transform(new Vector3f(0f, 0f, -1f));
            Vector3f currentPerp = new Vector3f(currentF).sub(new Vector3f(upM).mul(currentF.dot(upM)));
            if (desiredPerp.lengthSquared() > 1.0e-6f && currentPerp.lengthSquared() > 1.0e-6f) {
                desiredPerp.normalize();
                currentPerp.normalize();
                float angle = (float) Math.atan2(upM.dot(new Vector3f(currentPerp).cross(desiredPerp)),
                        currentPerp.dot(desiredPerp));
                if (Float.isFinite(angle)) {
                    q = new Quaternionf().rotationAxis(angle, upM).mul(q).normalize();
                }
            }
            e.set(new Matrix3f().set(q).getEulerAnglesZYX(new Vector3f()));
            bridge.suppressAnimationsMatching(player, "sneak", "sneaking", "swim", "swimming", "crawl");
        }
        if (!Double.isFinite(delta.x) || !Double.isFinite(delta.y) || !Double.isFinite(delta.z)) {
            return;
        }
        var bones = bridge.listBones(player);
        if (bones == null) return;
        lyingRootBones.clear();
        lyingRootIds.clear();
        lyingRootMatrix.set(new Matrix3f().rotateZ(e.z).rotateY(e.y).rotateX(e.x));
        boolean any = false;
        for (String name : bones) {
            if (name == null) continue;
            String n = name.toLowerCase(java.util.Locale.ROOT);
            if (!n.contains("root")) continue;
            int id = bridge.resolveBone(player, name);
            if (id < 0) continue;
            store.setRotationPositionOnly(id, e.x, e.y, e.z,
                    (float) -delta.x, (float) delta.y, (float) delta.z);
            lyingRootBones.add(name);
            lyingRootIds.add(id);
            if (drivenBones != null) drivenBones.add(name);
            any = true;
        }
        lyingRootActive = any;
    }
    private void applyLyingRoots(LocalPlayer player, PoseOverrideStore store, Bridge bridge,
                                 VRBodyPartData waistData, VRBodyPartData headData, VRPose pose,
                                 float bodyYaw, float scaleXZ, float scaleY,
                                 java.util.Set<String> drivenBones) {
        Vec3 head = headData == null ? null : headData.getPos();
        var leftFoot = pose.getBodyPartData(VRBodyPart.LEFT_FOOT);
        var rightFoot = pose.getBodyPartData(VRBodyPart.RIGHT_FOOT);
        Vec3 lf = leftFoot == null ? null : leftFoot.getPos();
        Vec3 rf = rightFoot == null ? null : rightFoot.getPos();
        Vec3 feet = lf != null && rf != null ? lf.add(rf).scale(0.5) : (lf != null ? lf : rf);
        Vec3 waist = waistData == null ? null : waistData.getPos();
        Vec3 top = head != null ? head : waist;
        Vec3 base = feet != null ? feet : waist;

        boolean lyingLog = (++lyingLogCounter % 60 == 0);
        if (lyingLog) {
            YsmVrMod.LOGGER.info("[ysmvr][debug] lying-detect head={} waist={} feet={} top={} base={}", head, waist, feet, top, base);
        }        if (top == null || base == null) {
            return;
        }
        Vec3 upWorld = top.subtract(base);
        if (upWorld.length() < 0.35 || Math.abs(upWorld.y) > 0.45) {
            if (lyingLog) {
                YsmVrMod.LOGGER.info("[ysmvr][debug] lying-detect skip len={} y={}", upWorld.length(), upWorld.y);
            }
            return;
        }
        bridge.suppressAnimationsMatching(player, "sneak", "sneaking", "swim", "swimming", "crawl");
        Vector3f upM = worldToModelDir(upWorld.normalize(), bodyYaw);
        if (!Float.isFinite(upM.x) || !Float.isFinite(upM.y) || !Float.isFinite(upM.z)
                || upM.lengthSquared() < 1.0e-6f) {
            return;
        }
        upM.normalize();
        Quaternionf q = new Quaternionf().rotationTo(0f, 1f, 0f, upM.x, upM.y, upM.z);
        // roll 参考优先用 HMD 视线（躺下看天/看地可区分仰卧/俯卧），否则用玩家水平朝向。
        Vector3f desiredF;
        Vec3 faceWorld = headData == null ? null : headData.getDir();
        if (faceWorld != null && faceWorld.lengthSqr() > 0.01) {
            desiredF = worldToModelDir(faceWorld, bodyYaw);
        } else {
            float yawRad = (float) Math.toRadians(player.getYRot());
            desiredF = worldToModelDir(new Vec3(-Math.sin(yawRad), 0.0, Math.cos(yawRad)), bodyYaw);
        }        Vector3f desiredPerp = new Vector3f(desiredF).sub(new Vector3f(upM).mul(desiredF.dot(upM)));
        Vector3f currentF = q.transform(new Vector3f(0f, 0f, -1f));
        Vector3f currentPerp = new Vector3f(currentF).sub(new Vector3f(upM).mul(currentF.dot(upM)));
        if (desiredPerp.lengthSquared() > 1.0e-6f && currentPerp.lengthSquared() > 1.0e-6f) {
            desiredPerp.normalize();
            currentPerp.normalize();
            Vector3f cross = new Vector3f(currentPerp).cross(desiredPerp);
            float angle = (float) Math.atan2(upM.dot(cross), currentPerp.dot(desiredPerp));
            if (Float.isFinite(angle)) {
                q = new Quaternionf().rotationAxis(angle, upM).mul(q).normalize();
            }
        }
        Vector3f e = new Matrix3f().set(q).getEulerAnglesZYX(new Vector3f());
        if (!Float.isFinite(e.x) || !Float.isFinite(e.y) || !Float.isFinite(e.z)) {
            return;
        }
        float liftY = (float) (player.getBbHeight() * 0.3 * 16.0 / Math.max(0.001, scaleY));
        var bones = bridge.listBones(player);
        if (bones == null) {
            return;
        }
        int count = 0;
        lyingRootIds.clear();
        for (String name : bones) {
            if (name == null) {
                continue;
            }
            String n = name.toLowerCase(java.util.Locale.ROOT);
            if (!n.contains("root")) {
                continue;
            }
            int id = bridge.resolveBone(player, name);
            if (id >= 0) {
                store.setRotationPositionOnly(id, e.x, e.y, e.z, 0f, liftY, 0f);
                lyingRootBones.add(name);
                lyingRootIds.add(id);
                if (drivenBones != null) {
                    drivenBones.add(name);
                }
                count++;
            }
        }
        if (count > 0) {
            lyingRootMatrix.set(new Matrix3f().rotateZ(e.z).rotateY(e.y).rotateX(e.x));
            lyingRootActive = true;
            debugLogKey("lying-root",
                    "lying root bones=%d upWorld=(%.2f,%.2f,%.2f) upM=(%.2f,%.2f,%.2f) rotDeg=(%.1f,%.1f,%.1f) liftY=%.2f",
                    count, upWorld.x, upWorld.y, upWorld.z, upM.x, upM.y, upM.z,
                    Math.toDegrees(e.x), Math.toDegrees(e.y), Math.toDegrees(e.z), liftY);
        }
    }    private void applyBone(LocalPlayer player, PoseOverrideStore store, Bridge bridge,
                           VrBindings.Binding binding,
                           double pitch, double yaw, double roll, float bodyYaw) {
        if (binding == null || binding.boneName == null || binding.boneName.isEmpty()) {
            return;
        }
        int pooled = bridge.resolveBone(player, binding.boneName);
        if (pooled < 0) {
            return;
        }

        // 世界姿态 → 模型空间（弧度）。实机校准后的符号约定：
        // - rotX：pitch 抬头为正，模型空间镜像 → 直接取正
        // - rotY：模型世界朝向 = 身体朝向；实机左右颠倒 → 临时改为 bodyYaw + yaw 镜像
        // - rotZ：roll 取反
        // 再叠加绑定配置的旋转偏移（度 → 弧度）
        float rx = (float) pitch + (float) Math.toRadians(binding.rotX);
        float ry = bodyYaw - (float) yaw;
        ry = (float) Math.IEEEremainder(ry, 2 * Math.PI);
        ry += (float) Math.toRadians(binding.rotY);
        float rz = -(float) roll + (float) Math.toRadians(binding.rotZ);
        // 最终归一化：绑定偏移可能被校准写成多圈值（如 -710°），叠加后越出 [-π,π]。
        // 渲染按 sin/cos 只取 mod 360 分量，多圈值不影响本帧渲染，但会污染校准采集
        // （record/apply 再累加）。归一化到真值域，切断多圈累积。
        rx = (float) Math.IEEEremainder(rx, 2 * Math.PI);
        ry = (float) Math.IEEEremainder(ry, 2 * Math.PI);
        rz = (float) Math.IEEEremainder(rz, 2 * Math.PI);
        store.setRotation(pooled, rx, ry, rz);
        String bindLower = binding.boneName.toLowerCase(java.util.Locale.ROOT);
        if (bindLower.equals("upbody")) {
            int upperId = bridge.resolveBone(player, "UpperBody");
            if (upperId >= 0 && upperId != pooled) {
                store.setRotation(upperId, rx, ry, rz);
            }
        }
        if (binding.boneName.toLowerCase(java.util.Locale.ROOT).contains("upbody")
                || binding.boneName.toLowerCase(java.util.Locale.ROOT).contains("allbody")
                || binding.boneName.toLowerCase(java.util.Locale.ROOT).contains("root")) {
            debugLogKey("waist-debug", "waist bone %s set=(%.1f,%.1f,%.1f) bodyYaw=%.1f",
                    binding.boneName, Math.toDegrees(rx), Math.toDegrees(ry), Math.toDegrees(rz), Math.toDegrees(bodyYaw));
        }

        // 绑定配置的位置偏移（模型单位）
        if (binding.posX != 0 || binding.posY != 0 || binding.posZ != 0) {
            store.setPosition(pooled, binding.posX, binding.posY, binding.posZ);
        }
        VrCalibration.record(binding.boneName, rx, ry, rz);
    }

    /**
     * 原版姿态复制：原版模型部位的局部旋转（弧度）→ YSM 骨骼旋转，返回是否写入。
     * <p>
     * 换算（字节码铁证推导 + 实机验证，见 {@link VanillaVrPoseSource} 与设计记录）：
     * <ol>
     * <li>原版部位渲染合成 {@code M_part = Rz(pz)·Ry(py)·Rx(px)}（x 最内层）；
     * 世界合成 {@code Ry(π−yaw)·D1}，D1 = diag(−1,−1,1)（1.20.1 管线内置镜像）</li>
     * <li>YSM 世界合成 {@code Ry(π−yaw)·diag(−1,1,1)·M_eff}（Java 惯例模型
     * 渲染正确的充要翻转），模型空间 {@code M_Y = diag(1,−1,1)·M_V} ⇒
     * <b>{@code M_eff = diag(1,−1,1)·M_part·diag(1,−1,1)}</b>，
     * 即 Euler 逐分量 {@code (rx, ry, rz) → (−rx, +ry, +rz)}——z 符号以实机为准
     * （T字型右臂 vanilla pz=−π/2 必须映射 rz=−π/2 → −X_MY=玩家右侧；
     * 代数共轭推导的 −rz 与实机相反）</li>
     * <li>store 是<b>绝对</b>模型空间旋转（VrPoseController/ModernVrPoseController
     * 提交队列时自行减去骨骼初始旋转）</li>
     * <li>根朝向补偿（deltaYaw = 原版模型身体朝向 − 渲染根朝向）：复制值在原版
     * 模型帧（pose.bodyYaw）摆出，渲染根用平滑身体朝向（无手臂/指针项）——
     * 两者差 Δ 时按 {@code M(S) = Rz·Ry·Rx} 合成后左乘 {@code Ry(Δ)} 再
     * ZYX 分解（JOML），世界姿态与原版模型一致（纯矩阵代数，无符号风险）</li>
     * </ol>
     * 再叠加绑定偏移；前臂/小腿骨骼的位置复制（xyz 平移）见
     * {@link #applyVanillaBonePosition}。
     */
    private boolean applyVanillaBone(LocalPlayer player, PoseOverrideStore store, Bridge bridge,
                                     VrBindings.Binding binding,
                                     VanillaVrPoseSource.Pose pose, VanillaVrPoseSource.Part part,
                                     float deltaYaw, float renderYaw) {
        if (binding == null || binding.boneName == null || binding.boneName.isEmpty()) {
            return false;
        }
        int pooled = bridge.resolveBone(player, binding.boneName);
        if (pooled < 0) {
            return false;
        }
        // M_part = Rz(pz)·Ry(py)·Rx(px) → M_eff = D_y·M_part·D_y（D_y = diag(1,−1,1)）
        float rx = -pose.rx(part) + (float) Math.toRadians(binding.rotX);
        float ry = pose.ry(part) + (float) Math.toRadians(binding.rotY);
        float rz = pose.rz(part) + (float) Math.toRadians(binding.rotZ);
        // 根朝向补偿：M(S) = Rz·Ry·Rx 左乘 Ry(Δ) 再 XYZ 分解（与 Geckolib ZYX 顺序一致）
        if (Math.abs(deltaYaw) > 1e-4f) {
            Matrix3f m = new Matrix3f().rotateZ(rz).rotateY(ry).rotateX(rx);
            Vector3f e = new Matrix3f().rotateY(deltaYaw).mul(m)
                    .getEulerAnglesXYZ(new Vector3f());
            rx = e.x; // getEulerAnglesXYZ 返回 (angleX, angleY, angleZ)
            ry = e.y;
            rz = e.z;
        }
        store.setRotation(pooled, rx, ry, rz);
        // 前臂/小腿位置复制（xyz 平移）；未复制时回退绑定配置的位置偏移
        if (!applyVanillaBonePosition(player, store, bridge, binding, pose, renderYaw)
                && (binding.posX != 0 || binding.posY != 0 || binding.posZ != 0)) {
            store.setPosition(pooled, binding.posX, binding.posY, binding.posZ);
        }
        // 低频日志：骨骼初始旋转（store 为绝对旋转，controller 提交时减 initial）
        if (VrBodyConfig.debugLog && loggedInitials.add(binding.boneName)) {
            float[] initial = bridge.getBoneInitialRotation(player, binding.boneName);
            if (initial != null) {
                YsmVrMod.LOGGER.info("[ysmvr][debug] {} 初始旋转=({}°,{}°,{}°)",
                        binding.boneName,
                        String.format("%.1f", Math.toDegrees(initial[0])),
                        String.format("%.1f", Math.toDegrees(initial[1])),
                        String.format("%.1f", Math.toDegrees(initial[2])));
            }
        }
        debugLog("复制 %s (%s)：vanilla=(%.2f,%.2f,%.2f)° → store=(%.2f,%.2f,%.2f)°",
                binding.boneName, part.name(),
                Math.toDegrees(pose.rx(part)), Math.toDegrees(pose.ry(part)), Math.toDegrees(pose.rz(part)),
                Math.toDegrees(rx), Math.toDegrees(ry), Math.toDegrees(rz));
        VrCalibration.record(binding.boneName, rx, ry, rz);
        return true;
    }

    /**
     * 位置复制（问题4：骨骼 xyz 平移）：把原版前臂/小腿部位（其位置由控制器/
     * 脚部追踪器经 IK 解出，含臂长 12 模型单位的限制）的平移写到对应 YSM
     * 骨骼的动画位移上，让模型的手/脚能伸到追踪点——纯旋转表达不了位置。
     * <p>
     * 只复制"下半段"骨骼（ForeArm/LowerLeg）：其原版对应部位（hand/foot，
     * 位置 = 控制器/追踪器换算进模型系，见 VRPlayerModel 的 positionConnectedLimb）
     * 才是 VR 驱动的。上臂/大腿的原版位置是模型静态枢轴 ± 小幅修正，且两边
     * 根系约定不同（原版根在颈部、YSM 根在脚底）会引入常数误差，保持模型
     * 自身枢轴更稳。
     * <p>
     * 换算走世界坐标往返：offset = (原版下部位 − 原版上部位) 的世界差向量
     * （模型根偏移 1.501 项与模型缩放在两者之差中抵消），加到 YSM 父骨骼枢轴
     * 的世界位置（getBoneModelPosition 的 R 系 → 世界换算，与 aimEuler 相同），
     * 再经根 → 父骨骼的祖先链实时逆变换（{@link Bridge#rootToParentLocal}）
     * 换到父系局部，按渲染器 {@code translate((pivotX−tx),(pivotY+ty),(pivotZ+tz))}
     * 的语义取 store = (−x, +y, +z)。官方 YSM 无父引用 → 跳过（返回 false，
     * 调用方回退绑定配置的位置偏移）。
     */
    private boolean applyVanillaBonePosition(LocalPlayer player, PoseOverrideStore store,
                                             Bridge bridge, VrBindings.Binding binding,
                                             VanillaVrPoseSource.Pose pose, float renderYaw) {
        String bone = binding.boneName;
        VanillaVrPoseSource.Part lower;
        VanillaVrPoseSource.Part upper;
        if (bone.contains("ForeArm")) {
            lower = bone.contains("Left") ? VanillaVrPoseSource.Part.LEFT_HAND : VanillaVrPoseSource.Part.RIGHT_HAND;
            upper = bone.contains("Left") ? VanillaVrPoseSource.Part.LEFT_ARM : VanillaVrPoseSource.Part.RIGHT_ARM;
        } else if (bone.contains("LowerLeg")) {
            lower = bone.contains("Left") ? VanillaVrPoseSource.Part.LEFT_FOOT : VanillaVrPoseSource.Part.RIGHT_FOOT;
            upper = bone.contains("Left") ? VanillaVrPoseSource.Part.LEFT_LEG : VanillaVrPoseSource.Part.RIGHT_LEG;
        } else {
            return false; // 上臂/大腿/头/身体：保持模型自身枢轴
        }
        if (!pose.has(lower) || !pose.has(upper)) {
            return false;
        }
        int pooled = bridge.resolveBone(player, bone);
        if (pooled < 0) {
            return false;
        }
        String parent = bridge.getParentBoneName(player, bone);
        if (parent == null) {
            return false;
        }
        float[] parentPos = bridge.getBoneModelPosition(player, parent);
        if (parentPos == null) {
            return false;
        }
        // 原版部位平移差（V 系模型单位）→ 世界：Ry(π−bodyYaw)·D1·Δv·(0.9375·heightScale·worldScale/16·强制缩放)。
        // 原版部位平移在原版模型帧内——用 pose.bodyYaw（摆原版模型的朝向）；
        // 强制缩放（模型大小×玩家大小）要计入，否则模型被强制放大/缩小时位移对不上。
        double s = 0.9375 * pose.heightScale * pose.worldScale / 16.0 * effectiveModelScale(player, bridge);
        double vx = -(pose.px(lower) - pose.px(upper)) * s;
        double vy = -(pose.py(lower) - pose.py(upper)) * s;
        double vz = (pose.pz(lower) - pose.pz(upper)) * s;
        double theta = Math.PI - pose.bodyYaw;
        double cos = Math.cos(theta);
        double sin = Math.sin(theta);
        double ox = vx * cos + vz * sin;
        double oy = vy;
        double oz = -vx * sin + vz * cos;
        // YSM 父骨骼枢轴世界位置（R 系 → 世界，与 aimEuler 相同）——渲染根朝向
        double mx = -parentPos[0] / 16.0;
        double my = parentPos[1] / 16.0;
        double mz = parentPos[2] / 16.0;
        Vec3 entityPos = player.getPosition(Minecraft.getInstance().getFrameTime());
        double rc = Math.cos(Math.PI - renderYaw);
        double rs = Math.sin(Math.PI - renderYaw);
        double tx = entityPos.x + mx * rc + mz * rs + ox;
        double ty = entityPos.y + my + oy;
        double tz = entityPos.z - mx * rs + mz * rc + oz;
        // 世界 → 模型根坐标（M 系模型单位）：Ry(renderYaw−π)·(t − entityPos)·16
        double ca = Math.cos(renderYaw - Math.PI);
        double sa = Math.sin(renderYaw - Math.PI);
        double wx = tx - entityPos.x;
        double wy = ty - entityPos.y;
        double wz = tz - entityPos.z;
        double rxn = ca * wx + sa * wz;
        double rzn = -sa * wx + ca * wz;
        float[] local = bridge.rootToParentLocal(player, bone,
                (float) (rxn * 16), (float) (wy * 16), (float) (rzn * 16));
        if (local == null) {
            return false;
        }
        // 渲染器 translate((pivotX−tx),(pivotY+ty),(pivotZ+tz)) → store = (−x, +y, +z)
        store.setPosition(pooled,
                -local[0] + binding.posX, local[1] + binding.posY, local[2] + binding.posZ);
        debugLog("复制 %s 位置：parent=%s parentPos=(%.1f,%.1f,%.1f) offset世界=(%.2f,%.2f,%.2f) → store pos=(%.2f,%.2f,%.2f)",
                bone, parent, parentPos[0], parentPos[1], parentPos[2], ox, oy, oz,
                -local[0] + binding.posX, local[1] + binding.posY, local[2] + binding.posZ);
        return true;
    }

    /**
     * 头部直驱：头部骨骼直接锁定 HMD 世界姿态，不复制原版模型（不用 vpose.bodyYaw）。
     * <p>
     * 原版头部局部旋转（VRPlayerModel.setupAnim）为
     * {@code tempM = headQuat · Ry(bodyYaw+π) · Rx(−xRot)}，再经
     * {@code ModelUtils.setRotation}（ZYX 分解 + x/y 翻转）写入 ModelPart。此处的
     * {@code headQuat} 即 {@code VRBodyPartData.getRotation()}——HMD 的完整世界朝向
     * 四元数。复制原版捕获值（applyVanillaBone）会带来 Vivecraft 的 bodyYaw 项
     * （ARMS_ONLY 下手柄位置参与解算，随手臂/指针晃动）；因此这里用同一四元数机制，
     * 但 bodyYaw 换成上方平滑的渲染根朝向（waist>HMD，无手柄项），头部稳定朝向 HMD、
     * 不随指针晃动，头身夹角自由，第三人称相机绕到正面即可看到正脸。
     */
    private boolean applyHeadDirect(LocalPlayer player, PoseOverrideStore store, Bridge bridge,
                                    VrBindings.Binding binding, VRBodyPartData headData,
                                    float bodyYaw, VRBodyPartData waistData,
                                    java.util.Set<String> drivenBones) {
        if (!VrBodyConfig.faceTrackingEnabled) {
            return false;
        }
        if (binding == null || binding.boneName == null || binding.boneName.isEmpty()) {
            return false;
        }
        int pooled = bridge.resolveBone(player, binding.boneName);
        if (pooled < 0) {
            return false;
        }
        // 头世界 MC-yaw = bodyYaw − ry（完整派生：模型正面−Z 经根 θ'=180°−bodyYaw+ry
        // 旋转后，其世界 MC-yaw = 180°−θ' = bodyYaw−ry）。要让头【正面面向摄像头】，
        // 摄像头视线方向 = HMD 世界前向（MC-yaw=headYaw），头的正面须指向摄像头反方向
        // = headYaw+180°。故 ry = bodyYaw − (headYaw+180°) = bodyYaw − headYaw − π。
        // 此前 ry=bodyYaw−headYaw 使头世界 yaw=headYaw（随 HMD 视线、正对视线方向），
        // 摄像头看到的是头后脑勺——这就是"头无法正面面向摄像头"的根源。
        // 注意：binding.rotY 若残留校准写的 +180°，会抵消这里的 −π，务必将头部
        // binding.rotY 归零（见本文件头部不再走 VrCalibration.record 的说明）。
        // Mixin 已取消 YSM 的 applyHeadTracking/resetHeadTracking，
        // 头部 pitch/yaw/roll 全部由这里显式驱动。
        // 0.1.80 的取负仍然方向不对，改为直接使用 HMD pitch。
        float rx = (float) headData.getPitch() + (float) Math.toRadians(binding.rotX);
        float desiredHead = (float) headData.getYaw();
        double twoPi = 2 * Math.PI;
        if (!Float.isNaN(lastDesiredHead)) {
            desiredHead += (float) (twoPi * Math.round((lastDesiredHead - desiredHead) / twoPi));
        }
        lastDesiredHead = desiredHead;
        float ry = bodyYaw - desiredHead;
        ry += (float) Math.toRadians(binding.rotY + VrBodyConfig.headYawOffset);
        // roll 也取负，加入左右摇头/歪头支持。
        float rz = -(float) headData.getRoll() + (float) Math.toRadians(binding.rotZ + VrBodyConfig.headRollOffset);
        if (waistData != null) {
            // 腰弯曲时头随躯干链倾斜，避免弯腰露出背部。
            rx += (float) waistData.getPitch();
            rz += -(float) waistData.getRoll();
        }
        // 仅做跨 ±180° 解缠，避免数值跳变；不再做任何限速/死区。
        if (Float.isNaN(lastHeadRy)) {
            ry = (float) Math.IEEEremainder(ry, twoPi);
        } else {
            ry += (float) (twoPi * Math.round((lastHeadRy - ry) / twoPi));
        }
        lastHeadRy = ry;
        rx = (float) Math.IEEEremainder(rx, twoPi);
        rz = (float) Math.IEEEremainder(rz, twoPi);
        // 头部必须同时锁旋转+位置：斯塔·柏等模型的 walk/run 动画会给 Head 写
        // position，只覆盖旋转会让头部跟着动画位置偏移。位置写 binding.pos（通常 0），
        // 缩放仍留给动画（AllHead 隐藏 scale=0 不会被压成 1）。
        // v0.2.44：上面算出的 rx/ry/rz 是"模型空间绝对朝向"，但 store 写的是本地
        // 旋转；walk/run 动画会旋转 UpperBody/UpBody 等父骨骼，需用父链累计旋转
        // 反解成本地旋转，否则移动时头会朝向玩家的左下侧。
        Quaternionf parentAbs = boneParentRotation(player, bridge, binding.boneName);
        debugLogKey(binding.boneName + ":head",
                "头补偿 %s: parent=%s desired=(%.1f,%.1f,%.1f)°（0.2.43 模式：直接写本地）",
                binding.boneName, parentAbs != null ? "yes" : "null",
                Math.toDegrees(rx), Math.toDegrees(ry), Math.toDegrees(rz));
        store.setRotationPositionOnly(pooled, rx, ry, rz,
                binding.posX, binding.posY, binding.posZ);

        // 90° 偏移诊断：worldRotation 恒为 0（已实测排除），渲染根用 vanilla 180−yaw
        // 无额外 90°；因此真正要确认的是"模型绝对朝向 vs HMD 绝对朝向"是否差 90°。
        // 同时输出头部局部 ry 与净朝向，区分"数据源偏置"与"根/局部旋转偏置"。
        if (VrBodyConfig.debugLog && headLogScan++ % 120 == 0) {
            float worldRot = readWorldRotationDeg();
            float camYawDeg = Float.NaN;
            String camOffset = "?";
            try {
                var cam = Minecraft.getInstance().gameRenderer.getMainCamera();
                camYawDeg = cam.getYRot();
                var camPos = cam.getPosition();
                var playerPos = player.position();
                camOffset = String.format("(%.1f,%.1f)", camPos.x - playerPos.x, camPos.z - playerPos.z);
            } catch (Throwable ignored) {
            }
            String thirdCamOffset = "?";
            float thirdCamYaw = Float.NaN;
            try {
                Vec3 pos = readThirdCamPos();
                if (pos != null) {
                    Vec3 pp = player.position();
                    thirdCamOffset = String.format("(%.1f,%.1f)", pos.x - pp.x, pos.z - pp.z);
                    thirdCamYaw = (float) Math.toDegrees(Math.atan2(-(pos.x - pp.x), pos.z - pp.z));
                }
            } catch (Throwable ignored) {
            }
            float headYawDeg = (float) Math.toDegrees(headData.getYaw());
            float headPitchDeg = (float) Math.toDegrees(headData.getPitch());
            float headRollDeg = (float) Math.toDegrees(headData.getRoll());
            float bodyYawDeg = (float) Math.toDegrees(bodyYaw);
            float waistYawDeg = waistData != null ? (float) Math.toDegrees(waistData.getYaw()) : Float.NaN;
            // 净朝向：模型根渲染 = 180° − yBodyRot（模型正面 −Z）。注意 yBodyRot 已是度数，
            // 之前误用 Math.toDegrees(度) 导致该列恒为垃圾值，此处修正。
            float rootRot = (float) Math.IEEEremainder(180.0 - player.yBodyRot, 360.0);
            // 头世界 MC-yaw（真正判据）：模型正面 −Z 经根 θ'=180°−bodyYaw+ry 旋转后，其
            // 世界 MC-yaw = 180° − θ' = bodyYaw − ry。此前把 rootRot(=θ 根) 与 ry 直接相加
            // 当作"头渲染yaw"，那其实是 pose stack 旋转角，不是头正面朝向，导致诊断误判。
            float headWorldYaw = (float) Math.IEEEremainder(bodyYawDeg - Math.toDegrees(ry), 360.0);
            // 目标：头正面指向摄像头反方向 = HMD 世界前向 + 180°
            float headTargetYaw = (float) Math.IEEEremainder(headYawDeg + 180.0, 360.0);
            // 头骨骼初始旋转（绑定姿态，弧度，模型空间）。若其 ry≈±90° 说明该模型的
            // Head 骨骼静止朝向本身就和身体相差 90°——即使局部 ry≈0 也恒定偏 90°，
            // 需要在 applyHeadDirect 里加一个常数 yaw 补偿；若 ry≈0 则问题在别处。
            float[] headInit = bridge.getBoneInitialRotation(player, binding.boneName);
            // 注意：此处 boneRows() 读取的是当前 tick 中段的矩阵数据，尚是上一次
            // resetHeadTracking/applyHeadTracking 写入的 armorBoneOffset（渲染管线末尾
            // reset() 才会用 snapshot.rotation+initialRotation 覆盖），因此 liveRotY 并非
            // 本帧最终渲染值，仅可作"上帧归属/分支"参考，切勿据此判断本帧 override 是否生效。
            float liveRotX = Float.NaN, liveRotY = Float.NaN, liveRotZ = Float.NaN;
            try {
                for (var row : bridge.boneRows(player)) {
                    if (binding.boneName.equals(row.name())) {
                        liveRotX = row.rotX();
                        liveRotY = row.rotY();
                        liveRotZ = row.rotZ();
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
            YsmVrMod.LOGGER.info("[ysmvr][debug] 【90°诊断】worldRot={}° camYaw={}° camOffset={} thirdCamYaw={}° thirdCamOffset={} 有腰={} 腰yaw={}° " +
                            "headYaw={}° headPitch={}° headRoll={}° bodyYaw={}° → ry={}° rz={}° | " +
                            "yBodyRot={}° 根渲染yaw≈{}° 头世界yaw≈{}° 目标yaw≈{}° store.ry={}° 头骨骼初始=(x={}°,y={}°,z={}°) " +
                            "实时渲染=(x={}°,y={}°,z={}°)",
                    Float.isNaN(worldRot) ? "?" : String.format("%.1f", worldRot),
                      Float.isNaN(camYawDeg) ? "?" : String.format("%.1f", camYawDeg), camOffset,
                        Float.isNaN(thirdCamYaw) ? "?" : String.format("%.1f", thirdCamYaw), thirdCamOffset, waistData != null,
                    Float.isNaN(waistYawDeg) ? "?" : String.format("%.1f", waistYawDeg),
                    String.format("%.1f", headYawDeg), String.format("%.1f", headPitchDeg),
                    String.format("%.1f", headRollDeg), String.format("%.1f", bodyYawDeg),
                    String.format("%.1f", Math.toDegrees(ry)), String.format("%.1f", Math.toDegrees(rz)),
                    String.format("%.1f", player.yBodyRot), String.format("%.1f", rootRot),
                    String.format("%.1f", headWorldYaw), String.format("%.1f", headTargetYaw),
                    String.format("%.1f", Math.toDegrees(store.get(pooled).ry())),
                    headInit == null ? "?" : String.format("%.1f", Math.toDegrees(headInit[0])),
                    headInit == null ? "?" : String.format("%.1f", Math.toDegrees(headInit[1])),
                    headInit == null ? "?" : String.format("%.1f", Math.toDegrees(headInit[2])),
                    Float.isNaN(liveRotX) ? "?" : String.format("%.1f", Math.toDegrees(liveRotX)),
                    Float.isNaN(liveRotY) ? "?" : String.format("%.1f", Math.toDegrees(liveRotY)),
                    Float.isNaN(liveRotZ) ? "?" : String.format("%.1f", Math.toDegrees(liveRotZ)));
            // 父链诊断：沿 getParentBoneName 逐级向上，输出每个祖先骨骼的【完整】
            // 初始旋转 (x,y,z)。旧版只累加 yaw(y) 分量，但 90° 偏置若是绕 X/Z 轴
            // 的滚转（骨骼坐标基 X/Z 互换，写局部 yaw 只会让头滚转而非转向），
            // Σyaw 会显示 ≈0° 而无法定位。现在每格输出全轴，并在尾部给出
            // Σx/Σy/Σz 三轴累计，便于一眼看出 90° 究竟是"偏航"(Σy≈±90) 还是
            // "滚转/基扭转"(Σx 或 Σz≈±90)。
            StringBuilder chain = new StringBuilder();
            double sumX = 0.0, sumY = 0.0, sumZ = 0.0;
            String cur = binding.boneName;
            int guard = 0;
            while (cur != null && guard++ < 16) {
                float[] pose = bridge.getBoneInitialRotation(player, cur);
                if (pose != null) {
                    sumX += Math.toDegrees(pose[0]);
                    sumY += Math.toDegrees(pose[1]);
                    sumZ += Math.toDegrees(pose[2]);
                }
                if (chain.length() > 0) {
                    chain.append(" ← ");
                }
                chain.append(cur).append('(')
                        .append(pose == null ? "?" : String.format("%.1f,%.1f,%.1f",
                                Math.toDegrees(pose[0]), Math.toDegrees(pose[1]),
                                Math.toDegrees(pose[2])))
                        .append("°)");
                cur = bridge.getParentBoneName(player, cur);
            }
            YsmVrMod.LOGGER.info("[ysmvr][debug] 【90°父链】{} | Σ初始(x,y,z)=({}°,{}°,{}°) | headYawOffset={}° headRollOffset={}°",
                    chain, String.format("%.1f", sumX), String.format("%.1f", sumY),
                    String.format("%.1f", sumZ), String.format("%.1f", VrBodyConfig.headYawOffset),
                    String.format("%.1f", VrBodyConfig.headRollOffset));
        }

        debugLog("头部直驱：pitch=%.1f° yaw=%.1f° roll=%.1f° | rawYHeadRot=%.1f° yHeadRot=%.1f° yBodyRot=%.1f° xRot=%.1f° → store=(%.1f,%.1f,%.1f)°",
                Math.toDegrees(headData.getPitch()), Math.toDegrees(headData.getYaw()),
                Math.toDegrees(headData.getRoll()),
                lastRawYHeadRot, player.yHeadRotO, player.yBodyRotO, player.xRotO,
                Math.toDegrees(rx), Math.toDegrees(ry), Math.toDegrees(rz));
        // 头部是世界锁定（用 −π 让正面朝摄像头），不是模型静止姿态的"摆回"目标：
        // 若让它走 VrCalibration.record，校准会把本为 −π 的 ry 取反进 binding.rotY（+180°），
        // 再把代码里的 −π 抵消回 0 → 头又背对摄像头。故头部不参与姿势校准。
        return true;
    }
    /** 90° 诊断采样计数（约每 120 帧输出一次，避免刷屏）。 */
    private static int headLogScan;

    /** 调试日志限流计数（每 60 条调用输出一条，避免刷屏）。 */
    private static int debugLogCount;
    private static int ikLogCount;
    private static final Map<String, Integer> ikForceLogCount = new java.util.HashMap<>();
    private static final Map<String, Integer> ikBoneLogCount = new java.util.HashMap<>();
    private static final Map<String, Integer> diagLogCount = new java.util.HashMap<>();

    /** 多用途诊断日志：每 key 前 5 次 + 每 120 次。 */
    private void debugLogKey(String key, String format, Object... args) {
        if (!VrBodyConfig.debugLog) {
            return;
        }
        int n = diagLogCount.merge(key, 1, Integer::sum);
        if (n <= 5 || n % 120 == 0) {
            YsmVrMod.LOGGER.info("[ysmvr][debug] " + String.format(format, args));
        }
    }

    /** 每骨骼 IK 日志（每根骨骼前 5 次 + 每 180 次一条，左右不再互相挤掉）。 */
    private void debugLogIkBone(String bone, String format, Object... args) {
        if (!VrBodyConfig.debugLog) {
            return;
        }
        int n = ikBoneLogCount.merge(bone, 1, Integer::sum);
        if (n <= 5 || n % 180 == 0) {
            YsmVrMod.LOGGER.info("[ysmvr][debug] " + String.format(format, args));
        }
    }

    /** IK/手臂专用日志（独立限流，避免被其它 debugLog 挤掉）。 */
    private void debugLogIk(String format, Object... args) {
        if (!VrBodyConfig.debugLog || ikLogCount++ % 120 != 0) {
            return;
        }
        YsmVrMod.LOGGER.info("[ysmvr][debug] " + String.format(format, args));
    }

    /** 每骨头最多 6 次的关键 IK 诊断（不受全局限流影响）。 */
    private void debugLogIkForce(String bone, String format, Object... args) {
        if (!VrBodyConfig.debugLog) {
            return;
        }
        int n = ikForceLogCount.merge(bone, 1, Integer::sum);
        if (n <= 6) {
            YsmVrMod.LOGGER.info("[ysmvr][debug] " + String.format(format, args));
        }
    }

    /** 已输出过初始旋转日志的骨骼名（每个骨骼只输出一次）。 */
    private static final Set<String> loggedInitials = new HashSet<>();

    /** 数值调试日志（VrBodyConfig.debugLog 开关，/ysmvr debug 切换）。 */
    private void debugLog(String format, Object... args) {
        if (!VrBodyConfig.debugLog || debugLogCount++ % 60 != 0) {
            return;
        }
        YsmVrMod.LOGGER.info("[ysmvr][debug] " + String.format(format, args));
    }

    /** 每骨骼限流的手方向日志（避免全局限流导致左右手日志被互相挤掉）。 */
    private void debugLogHand(String bone, String mode,
                              double mx, double my, double mz,
                              float aimRx, float aimRz, double roll,
                              float rx, float ry, float rz) {
        if (!VrBodyConfig.debugLog) {
            return;
        }
        int cnt = handLogCount.merge(bone, 1, Integer::sum);
        if (cnt % 30 != 1) {
            return;
        }
        YsmVrMod.LOGGER.info("[ysmvr][debug] 手方向 {}（{}）：modelDir=({},{},{}) " +
                        "aimEuler=({},{})° roll={}° → store=({},{},{})°",
                bone, mode,
                String.format("%.2f", mx), String.format("%.2f", my), String.format("%.2f", mz),
                String.format("%.1f", Math.toDegrees(aimRx)), String.format("%.1f", Math.toDegrees(aimRz)),
                String.format("%.1f", Math.toDegrees(roll)),
                String.format("%.1f", Math.toDegrees(rx)), String.format("%.1f", Math.toDegrees(ry)), String.format("%.1f", Math.toDegrees(rz)));
    }

    /** 手方向日志计数（按骨骼名限流）。 */
    private final java.util.Map<String, Integer> handLogCount = new java.util.HashMap<>();

    /** VR 部位 → 原版模型部位映射。 */
    private static VanillaVrPoseSource.Part vanillaPart(VRBodyPart part) {
        return switch (part) {
            case HEAD -> VanillaVrPoseSource.Part.HEAD;
            case WAIST -> VanillaVrPoseSource.Part.BODY;
            case MAIN_HAND -> VanillaVrPoseSource.Part.RIGHT_HAND;
            case OFF_HAND -> VanillaVrPoseSource.Part.LEFT_HAND;
            case RIGHT_ELBOW -> VanillaVrPoseSource.Part.RIGHT_ARM;
            case LEFT_ELBOW -> VanillaVrPoseSource.Part.LEFT_ARM;
            case RIGHT_KNEE -> VanillaVrPoseSource.Part.RIGHT_LEG;
            case LEFT_KNEE -> VanillaVrPoseSource.Part.LEFT_LEG;
            case RIGHT_FOOT -> VanillaVrPoseSource.Part.RIGHT_FOOT;
            case LEFT_FOOT -> VanillaVrPoseSource.Part.LEFT_FOOT;
        };
    }

    /**
     * 统计被 ≥2 个不同 VR 部位的绑定链共用的祖先骨骼。
     * 沿每条绑定骨骼的父链走到根，记录每个祖先首次出现的部位；
     * 再次出现时若部位不同则标记为共享。用于上臂补位的安全性判断。
     */
    private Set<String> computeSharedAncestors(Bridge bridge, LocalPlayer player,
                                               Map<VRBodyPart, List<VrBindings.Binding>> snapshot) {
        Map<String, VRBodyPart> owner = new HashMap<>();
        Set<String> shared = new HashSet<>();
        for (var entry : snapshot.entrySet()) {
            for (var b : entry.getValue()) {
                Set<String> visited = new HashSet<>();
                String p = bridge.getParentBoneName(player, b.boneName);
                while (p != null && visited.add(p)) {
                    VRBodyPart prev = owner.putIfAbsent(p, entry.getKey());
                    if (prev != null && prev != entry.getKey()) {
                        shared.add(p);
                    }
                    p = bridge.getParentBoneName(player, p);
                }
            }
        }
        return shared;
    }

    /**
     * 行走判定：与 YSM {@code AnimationRegister} 的 walk 状态同源同阈值
     * （{@code limbSwingAmount = walkAnimation.speed(partialTick) > 0.05}）。
     * YSM 只有在该值超过 0.05 时才会选中 walk/run 动画，因此我们释放身体
     * 骨骼的时机与动画状态切换保持一致，不会出现"动画没选上但骨骼已解冻"
     * 的抖动帧。
     */
    private static boolean isWalking(LocalPlayer player) {
        return player.walkAnimation.speed(Minecraft.getInstance().getFrameTime()) > 0.05f;
    }

    /**
     * 本地玩家是否正在挥击（攻击）。用三路冗余判定，避免 Vivecraft/网络包
     * 只设置其中一项时漏检：{@code swinging} / {@code swingTime} / 攻击动画进度。
     */
    /** 下肢骨骼名判断（移动动画关闭时只冻这些）。 */
    /** 当前追踪模式是否允许该 VR 部位。 */
    private static boolean trackingPartAllowed(VRBodyPart part, int mode) {
        if (part == VRBodyPart.HEAD || part == VRBodyPart.MAIN_HAND
                || part == VRBodyPart.OFF_HAND) {
            return true;
        }
        if (part == VRBodyPart.WAIST) {
            return mode >= 1;
        }
        if (mode <= 0) {
            return false;
        }
        if (mode == 1) {
            return part == VRBodyPart.WAIST || part == VRBodyPart.RIGHT_FOOT
                    || part == VRBodyPart.LEFT_FOOT;
        }
        return true;
    }
    private static boolean isLowerBodyBone(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.contains("head") || n.contains("arm") || n.contains("hand")
                || n.contains("locator")) {
            return false;
        }
        return n.contains("downbody") || n.contains("leg") || n.contains("foot")
                || n.contains("thigh") || n.contains("calf") || n.contains("knee")
                || n.contains("ankle") || n.contains("hip") || n.contains("waist");
    }
    /** 移动动画开始时清掉下半身 VR 覆盖，交还 YSM walk/run。 */
    private void clearLowerBodyOverrides(LocalPlayer player, PoseOverrideStore store, Bridge bridge) {
        var bones = bridge.listBones(player);
        if (bones == null) {
            return;
        }
        for (String name : bones) {
            if (isLowerBodyBone(name)) {
                int id = bridge.resolveBone(player, name);
                if (id >= 0) {
                    store.clear(id);
                }
            }
        }
    }
    private static boolean isAttacking(LocalPlayer player) {
        return player.swinging || player.swingTime > 0
                || player.getAttackAnim(Minecraft.getInstance().getFrameTime()) > 0.0f;
    }

    /**
     * 次级/物理动画骨骼（尾巴、耳朵）：即使开启动画冻结也不冻结，交还模型
     * 自带的物理摆动（如酒狐 pre_parallel0/2 的耳朵/尾巴）。
     */
    private static boolean isSecondaryAnimationBone(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("tail")) {
            return true;
        }
        // Ear / Ear2 / Left_ear / RightEar 等；endsWith("ear") 不会误伤 "heart"
        return lower.endsWith("ear") || lower.contains("_ear")
                || lower.startsWith("ear") || lower.contains("ear2") || lower.contains("ear3");
    }

    /**
     * 手臂 + 头部骨骼子树（含根自身）。
     * <p>
     * 行走释放身体动画时，这些骨骼必须保持冻结（VR 驱动或绑定姿态），否则
     * YSM 的 walk/run 动画会摆动上臂/前臂/手/头/头发/五官：
     * <ol>
     * <li>根 = 配置里绑定到 HEAD / 双手 / 双肘的骨骼（不管本帧是否有追踪数据，
     * 没数据时也不能被模型动画摆走）；</li>
     * <li>再加名字兜底（含 Head / Arm / Hand / Shoulder 的骨骼），覆盖自定义
     * 模型里没走绑定的手臂头部件；</li>
     * <li>最后沿父子链展开所有后代（头发、五官、肩挂件、手持物定位骨骼等）。</li>
     * </ol>
     * 躯干/腿/脚等不在子树内，行走时正常播放动画。
     */
    private Set<String> armHeadSubtree(Bridge bridge, LocalPlayer player,
                                       Map<VRBodyPart, List<VrBindings.Binding>> snapshot) {
        var rows = bridge.boneRows(player);
        if (rows.isEmpty()) {
            return Set.of();
        }
        Map<String, List<String>> children = new HashMap<>();
        for (var row : rows) {
            String parent = row.parent();
            if (parent != null && !parent.isEmpty()) {
                children.computeIfAbsent(parent, k -> new java.util.ArrayList<>()).add(row.name());
            }
        }
        Set<String> roots = new HashSet<>();
        for (var part : List.of(VRBodyPart.HEAD, VRBodyPart.MAIN_HAND, VRBodyPart.OFF_HAND,
                VRBodyPart.RIGHT_ELBOW, VRBodyPart.LEFT_ELBOW)) {
            var bindings = snapshot.get(part);
            if (bindings == null) {
                continue;
            }
            for (var binding : bindings) {
                if (binding != null && binding.boneName != null && !binding.boneName.isEmpty()) {
                    roots.add(binding.boneName);
                }
            }
        }
        for (var row : rows) {
            String name = row.name();
            if (name.contains("Head") || name.contains("Arm")
                    || name.contains("Hand") || name.contains("Shoulder")) {
                roots.add(name);
            }
        }
        Set<String> subtree = new HashSet<>();
        var stack = new ArrayDeque<>(roots);
        while (!stack.isEmpty()) {
            String name = stack.pop();
            if (!subtree.add(name)) {
                continue;
            }
            var kids = children.get(name);
            if (kids != null) {
                stack.addAll(kids);
            }
        }
        return subtree;
    }

    /**
     * 手柄方向定向：优先使用位置差（aimDirection）计算手臂实际指向，
     * 回退到 {@link VRBodyPartData#getDir()}。位置差反映的是“从骨骼到
     * VR 手”的方向，不受 Quest 2 手柄 getDir()（环顶方向≠手指方向）的
     * 坐标系歧义影响——T 字型展开时仍能正确指向侧面。
     * <p>
     * 关键修正 1：ModernYSM 提交后骨骼局部旋转 = store 写入值本身（队列存
     * 写入值−初始旋转，reset 再加回初始旋转）——是【局部】而非模型空间绝对
     * 旋转，父骨骼的写入会层级叠加到子骨骼上。
     * <p>
     * 关键修正 2：getEulerAnglesXYZ 假设 Rz·Ry·Rx，与 Geckolib 一致；
     * 之前对 rz 的全局取反导致 T 字型双手同向，现恢复为 aimRz = e.z。
     * <p>
     * 上臂补位：若绑定骨骼的父骨骼本帧不被任何部位驱动（3 点追踪常见）
     * 且不被多条绑定链共用，父子骨骼各写完整旋转的一半（half·half=full，
     * 层级叠加后子骨骼恰好到位），肘部自然前倾；共享父骨骼（如 SIMPLE
     * 模式左右臂共同的躯干骨骼）不补位，整臂从肩关节旋转。
     */
    private static Quaternionf aimDown(Vector3f d) {
        if (d.y > 0.999f) {
            return new Quaternionf().rotationX((float) Math.PI);
        }
        return new Quaternionf().rotationTo(0f, -1f, 0f, d.x, d.y, d.z).normalize();
    }

    /** 用腰头位置把 UpBody/UpperBody 对到真实躯干轴，弯腰时头/背不再断开。 */
    private void applySpineFollow(LocalPlayer player, PoseOverrideStore store, Bridge bridge,
                                  VRBodyPartData waistData, VRBodyPartData headData,
                                  float bodyYaw, java.util.Set<String> drivenBones) {
        if (waistData == null || headData == null || waistData.getPos() == null
                || headData.getPos() == null) {
            return;
        }
        double[] axis = aimDirection(waistData.getPos(), headData.getPos(), bodyYaw);
        if (axis == null) {
            return;
        }
        Vector3f upM = new Vector3f((float) axis[0], (float) axis[1], (float) axis[2]).normalize();
        Quaternionf q = new Quaternionf().rotationTo(new Vector3f(0f, 1f, 0f), upM);
        Vector3f e = new Matrix3f().set(q).getEulerAnglesZYX(new Vector3f());
        int count = 0;
        for (String name : new String[]{"UpBody", "UpperBody"}) {
            int id = bridge.resolveBone(player, name);
            if (id >= 0) {
                store.setRotation(id, e.x, e.y, e.z);
                if (drivenBones != null) {
                    drivenBones.add(name);
                }
                count++;
            }
        }
        if (count > 0) {
            debugLogKey("spine", "spine follow axis=(%.2f,%.2f,%.2f) bones=%d", upM.x, upM.y, upM.z, count);
        }
    }
    private Vec3 stablePos(String key, Vec3 next) {
        Vec3 prev = trackedElbowPos.get(key);
        if (prev == null) {
            trackedElbowPos.put(key, next);
            return next;
        }
        double d = prev.distanceTo(next);
        Vec3 out = d > 0.5 ? prev.add(next.subtract(prev).scale(0.25))
                : prev.add(next.subtract(prev).scale(0.6));
        trackedElbowPos.put(key, out);
        return out;
    }
    private Vector3f stableDir(String key, Vector3f next) {
        Vector3f prev = trackedArmDirs.get(key);
        if (prev == null) {
            Vector3f copy = new Vector3f(next);
            trackedArmDirs.put(key, copy);
            return copy;
        }
        if (prev.dot(next) < 0.35f) {
            Vector3f blended = new Vector3f(prev).lerp(next, 0.25f).normalize();
            trackedArmDirs.put(key, blended);
            return blended;
        }
        prev.set(next);
        return next;
    }
    /** 全身模式：上臂追踪器（肘部位） 上臂，手柄  前臂。 */
    private boolean applyTrackedArm(LocalPlayer player, PoseOverrideStore store, Bridge bridge,
                                    VrBindings.Binding binding, VRBodyPartData handData,
                                    VRBodyPartData elbowData, float bodyYaw,
                                    float scaleXZ, float scaleY,
                                    java.util.Set<String> drivenBones,
                                    VanillaVrPoseSource.Pose vpose, boolean tentacle) {
        if (binding == null || binding.boneName == null || handData == null || elbowData == null
                || handData.getPos() == null || elbowData.getPos() == null) {
            return false;
        }
        String foreBone = binding.boneName;
        String upperBone = foreBone.replace("ForeArm", "Arm");
        if (upperBone.equals(foreBone)) {
            upperBone = bridge.getParentBoneName(player, foreBone);
        }
        if (upperBone == null) {
            return false;
        }
        int upperId = bridge.resolveBone(player, upperBone);
        int foreId = bridge.resolveBone(player, foreBone);
        if (upperId < 0 || foreId < 0) {
            return false;
        }
        boolean left = foreBone.toLowerCase(java.util.Locale.ROOT).contains("left");
        Vec3 entity = player.getPosition(Minecraft.getInstance().getFrameTime());
        Vec3 shoulderWorld = vanillaPartWorld(vpose,
                left ? VanillaVrPoseSource.Part.LEFT_ARM : VanillaVrPoseSource.Part.RIGHT_ARM,
                entity);
        if (shoulderWorld == null) {
            float[] upperModel = bridge.getBoneModelPosition(player, upperBone);
            if (upperModel != null) {
                shoulderWorld = boneWorldPos(player, upperModel, bodyYaw, scaleXZ, scaleY);
            }
        }
        Vec3 elbowPos = elbowData.getPos();
        Vec3 handPos = handData.getPos();
        if (shoulderWorld == null) {
            return false;
        }
        double[] upperAim = aimDirection(shoulderWorld, handPos, bodyYaw);
Vec3 foreStart = stablePos((left ? "L" : "R") + "-elbow", elbowPos);
        double[] foreAim = aimDirection(foreStart, handPos, bodyYaw);        if (upperAim == null || foreAim == null) {
            return false;
        }
Vector3f rawU = new Vector3f((float) upperAim[0], (float) upperAim[1], (float) upperAim[2]).normalize();
        Vector3f rawF = new Vector3f((float) foreAim[0], (float) foreAim[1], (float) foreAim[2]).normalize();
        Vector3f uDir = stableDir((left ? "L" : "R") + "-up", rawU);
        Vector3f fDir = stableDir((left ? "L" : "R") + "-fore", rawF);
        Quaternionf qUpper = aimDown(uDir);
        Quaternionf qForeAbs = aimDown(fDir);
        if (!tentacle && handData.getRotation() != null) {
            Quaternionf yaw = new Quaternionf().rotationY((float) (bodyYaw - Math.PI));
            Quaternionf handModel = new Quaternionf(yaw).mul(handData.getRotation())
                    .mul(new Quaternionf(yaw).conjugate());
            qForeAbs.slerp(handModel, 0.35f).normalize();
        }        Quaternionf qForeLocal = new Quaternionf(qUpper).conjugate().mul(qForeAbs);
        Vector3f eUpper = new Matrix3f().set(qUpper).getEulerAnglesZYX(new Vector3f());
        Vector3f eFore = new Matrix3f().set(qForeLocal).getEulerAnglesZYX(new Vector3f());
        float[] initial = bridge.getBoneInitialRotation(player, foreBone);
        store.setRotation(upperId, eUpper.x, eUpper.y, eUpper.z);
        store.setRotation(foreId,
                (initial == null ? 0f : initial[0]) + eFore.x,
                (initial == null ? 0f : initial[1]) + eFore.y,
                (initial == null ? 0f : initial[2]) + eFore.z);
        if (drivenBones != null) {
            drivenBones.add(upperBone);
            drivenBones.add(foreBone);
        }
        debugLogKey("tarm", "tracked arm %s upper=%s shoulder=(%.2f,%.2f,%.2f) elbow=(%.2f,%.2f,%.2f) hand=(%.2f,%.2f,%.2f) upperDir=(%.2f,%.2f,%.2f)",
                foreBone, upperBone, shoulderWorld.x, shoulderWorld.y, shoulderWorld.z,
                elbowPos.x, elbowPos.y, elbowPos.z, handPos.x, handPos.y, handPos.z,
                uDir.x, uDir.y, uDir.z);
        return true;
    }
    /** 全身模式：大腿追踪器大腿朝向，小腿追踪器小腿朝向。 */
    private boolean applyTrackedLeg(LocalPlayer player, PoseOverrideStore store, Bridge bridge,
                                    VrBindings.Binding binding, VRBodyPartData footData,
                                    VRBodyPartData kneeData, float bodyYaw,
                                    float scaleXZ, float scaleY,
                                    java.util.Set<String> drivenBones,
                                    VanillaVrPoseSource.Pose vpose) {
        if (binding == null || binding.boneName == null || footData == null || kneeData == null
                || footData.getPos() == null || kneeData.getPos() == null) {
            return false;
        }
        String lowerBone = binding.boneName;
        String thighBone = bridge.getParentBoneName(player, lowerBone);
        if (thighBone == null) {
            thighBone = findThighForLower(bridge, player, lowerBone);
        }
        if (thighBone == null) {
            return false;
        }
        int thighId = bridge.resolveBone(player, thighBone);
        int lowerId = bridge.resolveBone(player, lowerBone);
        if (thighId < 0 || lowerId < 0) {
            return false;
        }
        boolean left = lowerBone.toLowerCase(java.util.Locale.ROOT).contains("left");
        Vec3 entity = player.getPosition(Minecraft.getInstance().getFrameTime());
        Vec3 hipWorld = vanillaPartWorld(vpose,
                left ? VanillaVrPoseSource.Part.LEFT_LEG : VanillaVrPoseSource.Part.RIGHT_LEG,
                entity);
        if (hipWorld == null) {
            float[] hipModel = bridge.getBoneModelPosition(player, thighBone);
            if (hipModel != null) {
                hipWorld = boneWorldPos(player, hipModel, bodyYaw, scaleXZ, scaleY);
            }
        }
        Vec3 kneePos = kneeData.getPos();
        Vec3 footPos = footData.getPos();
        if (hipWorld == null) {
            return false;
        }
        double[] upperAim = aimDirection(hipWorld, kneePos, bodyYaw);
        double[] lowerAim = aimDirection(kneePos, footPos, bodyYaw);
        if (upperAim == null || lowerAim == null) {
            return false;
        }
        Vector3f uDir = new Vector3f((float) upperAim[0], (float) upperAim[1], (float) upperAim[2]).normalize();
        Vector3f lDir = new Vector3f((float) lowerAim[0], (float) lowerAim[1], (float) lowerAim[2]).normalize();
        Quaternionf qUpper = aimDown(uDir);
        Quaternionf qLowerAbs = aimDown(lDir);
        Quaternionf qLowerLocal = new Quaternionf(qUpper).conjugate().mul(qLowerAbs);
        Vector3f eUpper = new Matrix3f().set(qUpper).getEulerAnglesZYX(new Vector3f());
        Vector3f eLower = new Matrix3f().set(qLowerLocal).getEulerAnglesZYX(new Vector3f());
        float[] initial = bridge.getBoneInitialRotation(player, lowerBone);
        store.setRotation(thighId, eUpper.x, eUpper.y, eUpper.z);
        store.setRotation(lowerId,
                (initial == null ? 0f : initial[0]) + eLower.x,
                (initial == null ? 0f : initial[1]) + eLower.y,
                (initial == null ? 0f : initial[2]) + eLower.z);
        if (drivenBones != null) {
            drivenBones.add(thighBone);
            drivenBones.add(lowerBone);
        }
        debugLogKey("tleg", "tracked leg %s thigh=%s knee=(%.2f,%.2f,%.2f) foot=(%.2f,%.2f,%.2f)",
                lowerBone, thighBone, kneePos.x, kneePos.y, kneePos.z, footPos.x, footPos.y, footPos.z);
        return true;
    }
    /** 无膝/大腿追踪时：脚目标  两骨腿 IK（大腿+小腿），膝类似手肘。 */
    private boolean applyFootIk(LocalPlayer player, PoseOverrideStore store, Bridge bridge,
                                VrBindings.Binding binding, VRBodyPartData footData,
                                float bodyYaw, float scaleXZ, float scaleY,
                                java.util.Set<String> drivenBones) {
        if (binding == null || binding.boneName == null || binding.boneName.isEmpty()
                || footData == null || footData.getPos() == null) {
            debugLogKey("footik", "脚IK跳过: binding/footData 为空 bone=%s",
                    binding == null ? "null" : binding.boneName);
            return false;
        }
        debugLogKey("footik", "脚IK调用 %s target=(%.2f,%.2f,%.2f)",
                binding.boneName, footData.getPos().x, footData.getPos().y, footData.getPos().z);
        String bone = binding.boneName;
        String lowerBone = bone;
        String lower = lowerBone.toLowerCase(java.util.Locale.ROOT);
        String thigh;
        if (lower.contains("lowerleg") || lower.contains("shin") || lower.contains("calf")) {
            thigh = bridge.getParentBoneName(player, bone);
        } else {
            thigh = bone;
            lowerBone = findLowerLegChild(bridge, player, bone);
            lower = lowerBone == null ? null : lowerBone.toLowerCase(java.util.Locale.ROOT);
        }
        if (thigh == null || lower == null) {
            debugLogKey("footik-fail", "footIK fail %s: thigh/lower null bone=%s",
                    binding.boneName, bone);
            return false;
        }
        int thighId = bridge.resolveBone(player, thigh);
        int lowerId = bridge.resolveBone(player, lowerBone);
        if (thighId < 0 || lowerId < 0) {
            debugLogKey("footik-fail", "footIK fail %s: resolve thigh=%s(%d) lower=%s(%d)",
                    binding.boneName, thigh, thighId, lower, lowerId);
            return false;
        }
        String footBone = findFootChild(bridge, player, lowerBone, thigh);
        if (footBone == null || bridge.resolveBone(player, footBone) < 0) {
            debugLogKey("footik-fail", "footIK fail %s: no foot child lower=%s thigh=%s",
                    binding.boneName, lowerBone, thigh);
            return false;
        }
        Quaternionf qCal = new Quaternionf().setFromNormalized(
                new Matrix3f()
                        .rotateZ((float) Math.toRadians(binding.rotZ))
                        .rotateY((float) Math.toRadians(binding.rotY))
                        .rotateX((float) Math.toRadians(binding.rotX)));
        Quaternionf qUpper = new Quaternionf();
        Quaternionf qForeLocal = new Quaternionf();
        float[] bend = new float[1];
        float[] stretch = new float[1];
        if (!solveForeArmIk(player, bridge, lowerBone, footData.getPos(), bodyYaw,
                scaleXZ, scaleY, qCal, null, null, qUpper, qForeLocal, bend, thigh, stretch, true, footBone)) {
            debugLogKey("footik-fail", "footIK solve fail %s lower=%s thigh=%s foot=%s target=(%.2f,%.2f,%.2f)",
                    binding.boneName, lowerBone, thigh, footBone,
                    footData.getPos().x, footData.getPos().y, footData.getPos().z);
            return false;
        }
        debugLogKey("footik-ok", "footIK ok %s thigh=%s lower=%s foot=%s bend=%.1f",
                binding.boneName, thigh, lowerBone, footBone, bend[0]);
        Vector3f eUpper = new Matrix3f().set(qUpper).getEulerAnglesZYX(new Vector3f());
        Vector3f eFore = new Matrix3f().set(qForeLocal).getEulerAnglesZYX(new Vector3f());
        float[] initial = bridge.getBoneInitialRotation(player, lowerBone);
        store.setRotation(thighId, eUpper.x, eUpper.y, eUpper.z);
        store.setRotation(lowerId,
                (initial == null ? 0f : initial[0]) + eFore.x,
                (initial == null ? 0f : initial[1]) + eFore.y,
                (initial == null ? 0f : initial[2]) + eFore.z);
        if (drivenBones != null) {
            drivenBones.add(thigh);
            drivenBones.add(lowerBone);
        }
        return true;
    }

    private static String findThighForLower(Bridge bridge, LocalPlayer player, String lowerBone) {
        String side = lowerBone != null && lowerBone.toLowerCase(java.util.Locale.ROOT).contains("left")
                ? "left" : "right";
        var bones = bridge.listBones(player);
        if (bones != null) {
            for (String name : bones) {
                if (name == null) {
                    continue;
                }
                String n = name.toLowerCase(java.util.Locale.ROOT);
                if (!n.contains(side) || n.contains("lowerleg") || n.contains("shin")
                        || n.contains("calf") || n.contains("foot") || n.contains("ankle")) {
                    continue;
                }
                if (n.contains("thigh") || (n.contains("leg") && !n.contains("lower"))) {
                    return name;
                }
            }
        }
        return null;
    }
    private static String findFootChild(Bridge bridge, LocalPlayer player, String lowerBone,
                                        String thighBone) {
        String side = (lowerBone != null && lowerBone.toLowerCase(java.util.Locale.ROOT).contains("left"))
                || (thighBone != null && thighBone.toLowerCase(java.util.Locale.ROOT).contains("left"))
                ? "left" : "right";
        var bones = bridge.listBones(player);
        if (bones != null) {
            for (String name : bones) {
                if (name == null) {
                    continue;
                }
                String n = name.toLowerCase(java.util.Locale.ROOT);
                if (n.contains(side) && (n.contains("foot") || n.contains("ankle"))) {
                    return name;
                }
            }
        }
        return null;
    }
    private static String findLowerLegChild(Bridge bridge, LocalPlayer player, String thighBone) {
        String side = thighBone != null && thighBone.toLowerCase(java.util.Locale.ROOT).contains("left")
                ? "left" : "right";
        var bones = bridge.listBones(player);
        if (bones != null) {
            for (String name : bones) {
                if (name == null) {
                    continue;
                }
                String n = name.toLowerCase(java.util.Locale.ROOT);
                if (n.contains(side)
                        && (n.contains("lowerleg") || n.contains("shin") || n.contains("calf"))) {
                    return name;
                }
            }
        }
        return null;
    }
    /** 用腰/头 tracker 和模型 rest 肩偏移，算 YSM 躯干链下的肩世界位置。 */
    private Vec3 modelShoulderWorld(LocalPlayer player, Bridge bridge, String upperBone,
                                    float bodyYaw, float scaleXZ, float scaleY) {
        if (pendingWaistData == null || pendingWaistData.getPos() == null) {
            return null;
        }
        float[] up = bridge.getBoneModelPosition(player, "UpBody");
        float[] shoulder = bridge.getBoneModelPosition(player, upperBone);
        if (up == null || shoulder == null) {
            return null;
        }
        Vec3 upRest = boneWorldPos(player, up, bodyYaw, scaleXZ, scaleY);
        Vec3 shRest = boneWorldPos(player, shoulder, bodyYaw, scaleXZ, scaleY);
        Vec3 delta = shRest.subtract(upRest);
        Vec3 bodyRight = new Vec3(Math.cos(bodyYaw), 0.0, Math.sin(bodyYaw));
        Vec3 bodyForward = new Vec3(-Math.sin(bodyYaw), 0.0, Math.cos(bodyYaw));
        double upAmt = delta.y;
        double latAmt = delta.dot(bodyRight);
        double fwdAmt = delta.dot(bodyForward);
        Vec3 axis = pendingHeadData != null && pendingHeadData.getPos() != null
                ? pendingHeadData.getPos().subtract(pendingWaistData.getPos())
                : new Vec3(0.0, 1.0, 0.0);
        if (axis.length() < 0.05) {
            axis = new Vec3(0.0, 1.0, 0.0);
        } else {
            axis = axis.normalize();
        }
        return pendingWaistData.getPos()
                .add(axis.scale(upAmt))
                .add(bodyRight.scale(latAmt))
                .add(bodyForward.scale(fwdAmt));
    }
    private void applyHandAim(LocalPlayer player, PoseOverrideStore store, Bridge bridge,
                              VrBindings.Binding binding, VRBodyPartData data,
                              float bodyYaw, Set<String> drivenBones, Set<String> sharedBones,
                              float scaleXZ, float scaleY,
                              VanillaVrPoseSource.Pose vpose, VanillaVrPoseSource.Part vpart,
                              float deltaYaw) {
        if (binding == null || binding.boneName == null || binding.boneName.isEmpty()) {
            return;
        }
        int pooled = bridge.resolveBone(player, binding.boneName);
        if (pooled < 0) {
            return;
        }
        // SIMPLE 模式下绑定的是上臂（RightArm/LeftArm）：只做方向瞄准，
        // 不再叠加掌心 twist，避免手臂在向前/向外过渡时被内旋。
        boolean upperArm = binding.boneName.endsWith("Arm")
                && !binding.boneName.endsWith("ForeArm");
        boolean isForeArm = binding.boneName.toLowerCase(java.util.Locale.ROOT).contains("forearm");
        boolean armLeft = binding.boneName.toLowerCase(java.util.Locale.ROOT).contains("left");
        // v0.2.36：SIMPLE 模式下若模型存在真正的 ForeArm 子骨骼，自动按两骨 IK 驱动
        // 整条手臂（上臂 + 前臂），而不是只给上臂一个方向。这样 12_little/08_sta
        // 等"手/物品没跟上"的模型也能精确跟随控制器。
        if (upperArm) {
            String foreArm = findForeArmChild(bridge, player, binding.boneName);
            if (foreArm != null && bridge.resolveBone(player, foreArm) >= 0) {
                var copy = binding.copy();
                copy.boneName = foreArm;
                applyHandAim(player, store, bridge, copy, data, bodyYaw, drivenBones, sharedBones,
                        scaleXZ, scaleY, vpose, vpart, deltaYaw);
                return;
            }
        }

        Vec3 entityPos = player.getPosition(Minecraft.getInstance().getFrameTime());
        Vec3 rawHandPos = data.getPos();
        Vec3 handDir = data.getDir();
        armPoleOverride = null;
        // 玩家 VR 身体的肩膀世界位置（Vivecraft 原版 VR 模型上臂枢轴换算）。
        // 这才是瞄准方向的正确原点：只跟玩家追踪数据/worldScale 有关，和 YSM
        // 模型大小、强制模型缩放完全无关。
        VanillaVrPoseSource.Part vrArmPart = binding.boneName.toLowerCase(java.util.Locale.ROOT)
                .contains("left") ? VanillaVrPoseSource.Part.LEFT_ARM
                : VanillaVrPoseSource.Part.RIGHT_ARM;
        Vec3 shoulderWorld = vanillaPartWorld(vpose, vrArmPart, entityPos);
        if (shoulderWorld == null) {
            // 原版姿态不可用（模型非拆分臂/数据缺失）：退回 YSM 模型肩膀
            float[] fallbackBone = bridge.getBoneModelPosition(player, binding.boneName);
            if (fallbackBone != null) {
                shoulderWorld = boneWorldPos(player, fallbackBone, bodyYaw, scaleXZ, scaleY);
            }
        }
        if (pendingWaistData != null) {
            String shoulderBone = binding.boneName.replace("ForeArm", "Arm");
            Vec3 modelShoulder = modelShoulderWorld(player, bridge, shoulderBone, bodyYaw, scaleXZ, scaleY);
            if (modelShoulder != null) {
                shoulderWorld = modelShoulder;
            }
        }        armPoleOverride = null;
        if (pendingElbowData != null && pendingElbowData.getPos() != null && rawHandPos != null
                && shoulderWorld != null) {
            // pole 用真实 shoulderWorld，方向才和世界中的肘 tracker 一致。
            Vec3 dir = rawHandPos.subtract(shoulderWorld);
            if (dir.lengthSqr() > 0.0025) {
                Vec3 dHat = dir.normalize();
                Vec3 e = pendingElbowData.getPos().subtract(shoulderWorld);
                Vec3 pole = e.subtract(dHat.scale(e.dot(dHat)));
                if (pole.lengthSqr() > 1.0e-4) {
                    armPoleOverride = pole.normalize();
{
                        int lm = armLeft ? Math.max(0, Math.min(5, VrBodyConfig.leftJointMode))
                                : Math.max(0, Math.min(5, VrBodyConfig.rightJointMode));
                        if (lm == 1) {
                            armPoleOverride = new Vec3(-armPoleOverride.x, armPoleOverride.y, armPoleOverride.z);
                        } else if (lm == 2) {
                            armPoleOverride = new Vec3(armPoleOverride.x, -armPoleOverride.y, armPoleOverride.z);
                        } else if (lm == 3) {
                            armPoleOverride = new Vec3(armPoleOverride.x, armPoleOverride.y, -armPoleOverride.z);
                        } else if (lm >= 4) {
                            armPoleOverride = armPoleOverride.scale(-1.0);
                        }
                    }
                    Vector3f stablePole = stableDir((armLeft ? "L" : "R") + "-pole",
                            new Vector3f((float) armPoleOverride.x, (float) armPoleOverride.y,
                                    (float) armPoleOverride.z));
                    armPoleOverride = new Vec3(stablePole.x, stablePole.y, stablePole.z);
                }
            }
        }
        // ── 模型空间方向：优先"玩家 VR 肩膀 → 手柄"，回退 getDir() ──
        if (VrCalibration.isCapturing() && data.getRotation() != null) {
            Vec3 modelPole = modelElbowPoleWorld(player, bridge, binding, bodyYaw, scaleXZ, scaleY);
            if (modelPole != null) {
                ArmDirectRotation.record(armLeft, data.getRotation(), modelPole);

                YsmVrMod.LOGGER.info("[ysmvr][debug] armDirectCAL side={} modelPole=({},{},{})",
                        armLeft ? "L" : "R", modelPole.x, modelPole.y, modelPole.z);            } else if (pendingElbowData != null && armPoleOverride != null) {
                ArmDirectRotation.record(armLeft, data.getRotation(), armPoleOverride);
            }
        }
        if (VrBodyConfig.armDirectRotation) {
            pendingElbowData = null;
            if (ArmDirectRotation.valid(armLeft) && data.getRotation() != null) {
                Vector3f directPole = ArmDirectRotation.pole(armLeft, data.getRotation());

                if (VrBodyConfig.armDirectPoleFlip) {
                    directPole.negate();
                }                armPoleOverride = new Vec3(directPole.x, directPole.y, directPole.z).normalize();

                if (VrBodyConfig.debugLog && (++armDirectLogCounter % 120 == 0)) {
                    YsmVrMod.LOGGER.info("[ysmvr][debug] armDirect side={} pole=({},{},{}) q=({},{},{},{})",
                            armLeft ? "L" : "R", directPole.x, directPole.y, directPole.z,
                            data.getRotation().x(), data.getRotation().y(), data.getRotation().z(), data.getRotation().w());
                }            } else {
                armPoleOverride = null;
            }
        }
        float mdx, mdy, mdz;
        boolean usingAim = false;
        if (rawHandPos != null && shoulderWorld != null) {
            double[] aim = aimDirection(shoulderWorld, rawHandPos, bodyYaw);
            if (aim != null) {
                mdx = (float) aim[0];
                mdy = (float) aim[1];
                mdz = (float) aim[2];
                usingAim = true;
            } else {
                // 手贴着肩膀方向不稳定，回退 getDir()
                if (handDir == null) return;
                double[] fb = handDirToModel(handDir, bodyYaw);
                mdx = (float) fb[0];
                mdy = (float) fb[1];
                mdz = (float) fb[2];
            }
        } else {
            if (handDir == null) return;
            double[] fb = handDirToModel(handDir, bodyYaw);
            mdx = (float) fb[0];
            mdy = (float) fb[1];
            mdz = (float) fb[2];
        }

        if (VrBodyConfig.debugLog && rawHandPos != null && ++handAimLogCounter % 180 == 0) {
            YsmVrMod.LOGGER.info(
                    "[ysmvr][debug] handAim {}: shoulder=({}, {}, {}) hand=({}, {}, {}) ratio={}",
                    binding.boneName,
                    shoulderWorld == null ? "?" : String.format(java.util.Locale.ROOT, "%.3f", shoulderWorld.x),
                    shoulderWorld == null ? "?" : String.format(java.util.Locale.ROOT, "%.3f", shoulderWorld.y),
                    shoulderWorld == null ? "?" : String.format(java.util.Locale.ROOT, "%.3f", shoulderWorld.z),
                    String.format(java.util.Locale.ROOT, "%.3f", rawHandPos.x),
                    String.format(java.util.Locale.ROOT, "%.3f", rawHandPos.y),
                    String.format(java.util.Locale.ROOT, "%.3f", rawHandPos.z),
                    String.format(java.util.Locale.ROOT, "%.3f", handWorldScaleRatio));
        }

        double len = Math.sqrt(mdx * mdx + mdy * mdy + mdz * mdz);
        if (len < 0.001) {
            return;
        }
        float ndx = (float) (mdx / len);
        float ndy = (float) (mdy / len);
        float ndz = (float) (mdz / len);

        // ── 完整定向（含 roll），取代旧"最短弧 + getRoll 塞 ry" ──
        // 旧方案缺陷：1) 最短弧不含 roll，T 字型掌心方向任意（手心朝上、双手内旋）；
        // 2) 举过头顶时 aim 与绑定方向 (0,-1,0) 反平行，rotationTo 轴向退化，
        //    两手各自乱翻（手关节折成 < 形）。
        // 现复用 Vivecraft 原版玩家模型做法（ModelUtils.pointAtModelWithLocal）：
        // 手柄局部 RIGHT(-1,0,0) 经手柄四元数 → 世界 → 模型空间，与 aim 叉乘得
        // 掌心参考方向 p，再把"最短弧隐含掌心 → p"的扭转角绕 aim 轴补上。
        // 扭转补偿恰好抵消最短弧在反平行处的任意性，过顶连续无极点。
        // 模型空间是世界的 x 镜像（det=-1），叉乘顺序 d×r 才与渲染器镜像协变
        // （与 Vivecraft dir.cross(right) 同序），写成 r×d 会让 roll 响应反向。
        // 手臂朝正上方时 -Y→+Y 是反平行，rotationTo 的旋转轴会退化/抖动；
        // 固定用绕 X 轴的 180° 翻转，让过顶方向连续。
        Quaternionf aimS;
        if (ndy > 0.999f) {
            aimS = new Quaternionf().rotationX((float) Math.PI);
        } else {
            aimS = new Quaternionf().rotationTo(0f, -1f, 0f, ndx, ndy, ndz);
        }
        Quaternionf full = aimS;
        double twist = 0.0;
        var cq = data.getRotation();
        if (!upperArm && cq != null) {
            Vector3f rWorld = cq.transform(new Vector3f(-1f, 0f, 0f));
            // 世界 → 模型：保持与 0.1.92 一致的 r 映射。
            // 0.1.93 曾尝试把这里改成与 aimDirection 相同的 +x，实机反而把
            // 手持物翻转了 180°（钻石剑朝上变朝下），说明该镜像不能只改一半；
            // 先恢复旧映射，把“向前→T 内旋”改到前臂本地 twist 上处理。
            double rt = bodyYaw - Math.PI;
            double rc = Math.cos(rt);
            double rs = Math.sin(rt);
            double rmx = -(rc * rWorld.x + rs * rWorld.z);
            double rmy = rWorld.y;
            double rmz = -rs * rWorld.x + rc * rWorld.z;
            // p = d × r；r 与 d 近平行时取不到参考 → 回退纯最短弧
            double px = ndy * rmz - ndz * rmy;
            double py = ndz * rmx - ndx * rmz;
            double pz = ndx * rmy - ndy * rmx;
            double plen = Math.sqrt(px * px + py * py + pz * pz);
            if (plen > 0.05) {
                px /= plen;
                py /= plen;
                pz /= plen;
                // 最短弧隐含掌心：假定绑定掌心朝 +Z，残余常量由绑定校准(rotZ)吸收
                Vector3f pS = aimS.transform(new Vector3f(0f, 0f, 1f));
                double cx = pS.y * pz - pS.z * py;
                double cy = pS.z * px - pS.x * pz;
                double cz = pS.x * py - pS.y * px;
                twist = Math.atan2(
                        cx * ndx + cy * ndy + cz * ndz,
                        pS.x * px + pS.y * py + pS.z * pz);
                // 0.1.86 之后 aimDirection 已去掉 x 镜像；0.1.96 实机日志确认：
                // 继续用 -twist 时前臂本地 X 轴在“向前→T”过渡中大幅摆动（内旋），
                // 改回 +twist 后本地 X 轴稳定对齐手柄 RIGHT 轴。
                full = new Quaternionf().rotateAxis((float) twist, ndx, ndy, ndz).mul(aimS);
            }
        }
        Vector3f e = new Matrix3f().set(full).getEulerAnglesZYX(new Vector3f());

        float rx = e.x + (float) Math.toRadians(binding.rotX);
        float ry = e.y + (float) Math.toRadians(binding.rotY);
        float rz = e.z + (float) Math.toRadians(binding.rotZ);

        debugLogHand(binding.boneName, usingAim ? "aim" : "dir",
                ndx, ndy, ndz, e.x, e.z, twist, rx, ry, rz);

        // ── 上臂虚拟补位 ──
        // store 写的是骨骼【局部】旋转（ModernYSM 提交 = 写入值本身，层级随父
        // 骨骼叠加），并非模型空间绝对值。父骨骼补位 half 后，子骨骼必须只写
        // half⁻¹·full = half（slerp(q,0.5) 即四元数平方根，half·half = full），
        // 否则肘关节双倍旋转、手臂折成 < 形（v0.1.10 T 字型右手朝天的根因）。
        // 父骨骼若被多条链共用（如 SIMPLE 模式左右臂共同的躯干骨骼）则禁止
        // 补位——半旋会串到另一条链上（v0.1.10 左手被右手半旋污染、手心面向
        // 玩家的根因），此时手臂整段从肩关节旋转即可。
        String parent = bridge.getParentBoneName(player, binding.boneName);
        if (isForeArm && (parent == null || bridge.resolveBone(player, parent) < 0)) {
            String fallback = findArmParentFallback(bridge, player, binding.boneName);
            if (fallback != null && bridge.resolveBone(player, fallback) >= 0) {
                parent = fallback;
                debugLogIkBone(binding.boneName, "前臂IK %s: parent=%s 不可用，按名字回退 parent=%s",
                        binding.boneName, binding.boneName, fallback);
            }
        }
        if (isForeArm) {
            if (parent == null) {
                debugLogIkForce(binding.boneName, "前臂IK跳过 %s: parent=null（骨骼无父引用）", binding.boneName);
            } else if (rawHandPos == null) {
                debugLogIkForce(binding.boneName, "前臂IK跳过 %s: rawHandPos=null", binding.boneName);
            } else if (bridge.resolveBone(player, parent) < 0) {
                debugLogIkForce(binding.boneName, "前臂IK跳过 %s: parent 骨骼 %s 无法解析", binding.boneName, parent);
            } else if (upperArm) {
                debugLogIkForce(binding.boneName, "前臂IK跳过 %s: upperArm 判定为 true", binding.boneName);
            }
        }
        // 上臂（SIMPLE）整条从肩关节直接指向手柄，不再做父骨骼 half 补位。
        boolean filled = false;
        Vector3f he = null;
        // 绑定骨骼位置偏移（binding.pos）所在的父坐标系绝对旋转；捕获锚点对齐
        // 用它把世界修正换算到 binding 本地模型单位（v0.2.30）。
        Quaternionf alignParentAbs = new Quaternionf();
        // 绑定到前臂（EXTENDED/旧配置）且没有肘部追踪时：
        // 用真正的两骨 IK：目标 = 控制器手位置，肘部选在“休息姿态肘方向”
        // 投影出的自然平面内；上臂/前臂分别写 IK 解出的绝对/本地旋转。
        // 这样伸直、弯曲、向前→T 过渡都由几何解决定，不再猜 bend 符号。
        if (!upperArm && parent != null && isForeArm && rawHandPos != null) {
            int parentId = bridge.resolveBone(player, parent);
            if (parentId >= 0) {
                // IK 目标：方向取"玩家 VR 肩膀 → 手柄"（与 YSM 模型缩放无关），
                // 距离 = 玩家手臂实际伸出的长度 × 模型/玩家身高比（handWorldScaleRatio）。
                // 注意不能用"手柄位置按脚底缩放后再量到模型肩膀的距离"——小模型/缩小
                // 玩家时该距离会被压到接近 0，肘部 IK 折到 ~175°，表现为手臂内旋。
                // IK 目标：直接用真实手柄世界位置（v0.2.33）。
                // 旋转解算从模型自己的肩膀出发，肩/肘关节始终保持连接；
                // 不再用"模型肩膀 + 缩放方向×距离"，也不给任何骨骼写位置偏移，
                // 避免肩或肘被平移导致手臂与身体/前臂与上臂分离。
                Vec3 ikTarget = rawHandPos;
                // 绑定偏移已包含在 rx/ry/rz 里，按渲染器 Rz·Ry·Rx 约定还原为四元数。
                Quaternionf qCal = new Quaternionf().setFromNormalized(
                        new Matrix3f().rotateZ(rz).rotateY(ry).rotateX(rx));
                // 优先使用 Vivecraft 原版 VR 模型的上臂/前臂部位旋转作为 roll 参考，
                // 再对齐到 IK 解出的手臂方向。这样肩/前臂的“正面”朝向与原版一致，
                // 同时 IK 仍负责肘部位置和手跟随。
                Quaternionf refUpper = null;
                Quaternionf refFore = null;
                if (cq != null) {
                    Quaternionf qCtrl = new Quaternionf()
                            .rotateY(bodyYaw - (float) Math.PI)
                            .mul(cq)
                            .mul(new Quaternionf().rotateX((float) Math.PI / 2f));
                    Quaternionf qBind = new Quaternionf().setFromNormalized(
                            new Matrix3f()
                                    .rotateZ((float) Math.toRadians(binding.rotZ))
                                    .rotateY((float) Math.toRadians(binding.rotY))
                                    .rotateX((float) Math.toRadians(binding.rotX)));
                    refUpper = qCtrl;
                    refFore = new Quaternionf(qCtrl).mul(qBind);
                }
                Quaternionf qUpper = new Quaternionf();
                Quaternionf qForeLocal = new Quaternionf();
                float[] ikBendDeg = new float[1];
                float[] ikStretch = new float[1];
                debugLogIkBone(binding.boneName,
                        "IK目标 %s: target=(%.2f,%.2f,%.2f) upper=%s l1?",
                        binding.boneName, rawHandPos.x, rawHandPos.y, rawHandPos.z, parent);
                if (solveForeArmIk(player, bridge, binding.boneName, ikTarget,
                        bodyYaw, scaleXZ, scaleY, qCal, refUpper, refFore,
                        qUpper, qForeLocal, ikBendDeg, parent, ikStretch)) {
                    Vector3f eUpper = new Matrix3f().set(qUpper).getEulerAnglesZYX(new Vector3f());
                    Vector3f eFore = new Matrix3f().set(qForeLocal).getEulerAnglesZYX(new Vector3f());
                    float[] initial = bridge.getBoneInitialRotation(player, binding.boneName);
                    float bx = (initial != null ? initial[0] : 0f) + eFore.x;
                    float by = (initial != null ? initial[1] : 0f) + eFore.y;
                    float bz = (initial != null ? initial[2] : 0f) + eFore.z;
                    store.setRotation(parentId, eUpper.x, eUpper.y, eUpper.z);
                    store.setRotation(pooled, bx, by, bz);
                    if (VrBodyConfig.armReachExtension && ikStretch[0] > 1.01f) {
                        float extModel = (ikStretch[0] - 1.0f) * 8.0f;
                        float[] pArr = bridge.getBoneModelPosition(player, parent);
                        float[] fArr = bridge.getBoneModelPosition(player, binding.boneName);
                        String endBone = bridge.getItemAnchorBone(player, binding.boneName);
                        if (endBone == null) {
                            endBone = findIkEndBone(bridge, player, binding.boneName);
                        }
                        if (pArr != null && fArr != null) {
                            Vec3 pW = boneWorldPos(player, pArr, bodyYaw, scaleXZ, scaleY);
                            Vec3 fW = boneWorldPos(player, fArr, bodyYaw, scaleXZ, scaleY);
                            double l1 = pW.distanceTo(fW);
                            double l2 = l1;
                            if (endBone != null) {
                                float[] eArr = bridge.getBoneModelPosition(player, endBone);
                                if (eArr != null) {
                                    l2 = fW.distanceTo(boneWorldPos(player, eArr, bodyYaw, scaleXZ, scaleY));
                                }
                            }
                            extModel = (float) ((ikStretch[0] - 1.0f) * (l1 + l2) * 16.0 / Math.max(0.001, scaleY));
                        }
                        store.setPositionOnly(pooled, mdx * extModel, mdy * extModel, mdz * extModel);
                        armExtendedBones.add(pooled);
                    } else if (armExtendedBones.remove(pooled)) {
                        store.setPositionOnly(pooled, 0f, 0f, 0f);
                    }
                    alignParentAbs.set(qUpper);
                debugLogIkBone(binding.boneName,
                        "前臂IK %s ref=%s: bend=%.1f° upper=(%.1f,%.1f,%.1f)° child=(%.1f,%.1f,%.1f)° " +
                                "foreStore=(%.1f,%.1f,%.1f)°",
                        binding.boneName,
                        refUpper != null ? "ctrl" : "self",
                        ikBendDeg[0],
                        Math.toDegrees(eUpper.x), Math.toDegrees(eUpper.y), Math.toDegrees(eUpper.z),
                        Math.toDegrees(eFore.x), Math.toDegrees(eFore.y), Math.toDegrees(eFore.z),
                        Math.toDegrees(bx), Math.toDegrees(by), Math.toDegrees(bz));
                    VrCalibration.record(binding.boneName, bx, by, bz);
                } else {
                    // 数据不足/骨骼比例异常：退回 0.1.92 的伸直姿态，保证不崩。
                    debugLogIkBone(binding.boneName,
                            "前臂IK %s: 不可用（比例/数据），整臂直接瞄准父骨骼 %s",
                            binding.boneName, parent);
                    store.setRotation(parentId, rx, ry, rz);
                    alignParentAbs.setFromNormalized(
                            new Matrix3f().rotateZ(rz).rotateY(ry).rotateX(rx));
                    float[] initial = bridge.getBoneInitialRotation(player, binding.boneName);
                    if (initial != null) {
                        store.setRotation(pooled, initial[0], initial[1], initial[2]);
                    } else {
                        store.clear(pooled);
                    }
        VrCalibration.record(binding.boneName, rx, ry, rz);
                }
                drivenBones.add(parent);
                filled = true;
            }
        }
        if (!filled && !upperArm && parent != null && !drivenBones.contains(parent) && !sharedBones.contains(parent)) {
            int parentId = bridge.resolveBone(player, parent);
            if (parentId >= 0) {
                // 渲染器按 Rz·Ry·Rx（Matrix4f.rotateZ→rotateY→rotateX 调用顺序）合成骨骼
                // 旋转，store 角度必须与该约定一致（getEulerAnglesZYX，而非 XYZ——XYZ 只在
                // JOML 四元数往返中自洽，写入渲染器会在大角度时方向错乱，T 字型手臂的根因）。
                // 四元数 qCal 由渲染器矩阵构建，保证 half·half = full 在渲染器约定下成立。
                Quaternionf qCal = new Quaternionf().setFromNormalized(
                        new Matrix3f().rotateZ(rz).rotateY(ry).rotateX(rx));
                Quaternionf half = new Quaternionf().slerp(qCal, 0.5f);
                he = new Matrix3f().set(half).getEulerAnglesZYX(new Vector3f());
                store.setRotation(parentId, he.x, he.y, he.z);
                store.setRotation(pooled, he.x, he.y, he.z);
                alignParentAbs.set(half);
                drivenBones.add(parent);
                VrCalibration.record(binding.boneName, he.x, he.y, he.z);
                filled = true;
            }
        }
        if (!filled) {
            store.setRotation(pooled, rx, ry, rz);
        VrCalibration.record(binding.boneName, rx, ry, rz);
        }

        // 绑定配置的静态位置偏移
        if (binding.posX != 0 || binding.posY != 0 || binding.posZ != 0) {
            store.setPosition(pooled, binding.posX, binding.posY, binding.posZ);
        }
        // v0.2.33：自动平移对齐暂时停用——肩部/前臂的位置偏移会让手臂与身体、
        // 前臂与上臂分离。当前改为 IK 直接指向真实手柄位置（纯旋转，关节保持连接）。
        // 保留 autoAlignHandItem()/positionOnly API 供以后"带约束/蒙皮绑定"方案使用。
        clearHandAlign(pooled, binding, store);
    }

    /** 目标骨骼父链累计旋转（四元数），用于绝对→本地旋转换算；不可用返回 null。 */
    private static Quaternionf boneParentRotation(LocalPlayer player, Bridge bridge, String boneName) {
        if (bridge == null || player == null || boneName == null) {
            return null;
        }
        float[] q = bridge.getBoneParentRotation(player, boneName);
        if (q == null || q.length < 4) {
            return null;
        }
        Quaternionf out = new Quaternionf(q[0], q[1], q[2], q[3]);
        return out.normalize();
    }

    /** 父链旋转不可用时：清零 Head 的非 VR 驱动祖先旋转，保证本地=期望绝对。 */
    private void stabilizeHeadParents(LocalPlayer player, PoseOverrideStore store, Bridge bridge,
                                      String boneName, java.util.Set<String> drivenBones) {
        String bone = boneName;
        for (int i = 0; i < 16 && bone != null; i++) {
            String parent = bridge.getParentBoneName(player, bone);
            if (parent == null || parent.isEmpty()) {
                break;
            }
            if (drivenBones == null || !drivenBones.contains(parent)) {
                int parentId = bridge.resolveBone(player, parent);
                if (parentId >= 0) {
                    store.setRotationPositionOnly(parentId, 0f, 0f, 0f, 0f, 0f, 0f);
                }
            }
            bone = parent;
        }
    }

    /** 模型骨骼枢轴 → 世界位置（与 aimDirection 相同换算）。 */
    private Vec3 boneWorldPos(LocalPlayer player, float[] bonePos, float bodyYaw,
                              float scaleXZ, float scaleY) {
        Vec3 entityPos = player.getPosition(Minecraft.getInstance().getFrameTime());
        double theta = Math.PI - bodyYaw;
        double cos = Math.cos(theta);
        double sin = Math.sin(theta);
        double mx = -bonePos[0] / 16.0 * scaleXZ;
        double my = bonePos[1] / 16.0 * scaleY;
        double mz = bonePos[2] / 16.0 * scaleXZ;
        return new Vec3(entityPos.x + mx * cos + mz * sin,
                entityPos.y + my,
                entityPos.z - mx * sin + mz * cos);
    }

    /** 世界点  玩家实体为原点的模型 M 系坐标（模型单位）。 */
    private static Vec3 worldToModelUnits(Vec3 world, Vec3 entityPos, float bodyYaw,
                                          float scaleXZ, float scaleY) {
        if (world == null || entityPos == null) {
            return null;
        }
        Vec3 d = world.subtract(entityPos);
        double theta = Math.PI - bodyYaw;
        double cos = Math.cos(theta);
        double sin = Math.sin(theta);
        double wx = d.x / Math.max(1.0e-4, scaleXZ) * 16.0;
        double wz = d.z / Math.max(1.0e-4, scaleXZ) * 16.0;
        double wy = d.y / Math.max(1.0e-4, scaleY) * 16.0;
        return new Vec3(wx * cos - wz * sin, wy, wx * sin + wz * cos);
    }

    /**
     * 两骨 IK 的末端骨骼：在 foreArmBone 的后代里找名字含 hand/locator 且离前臂
     * 枢轴最近的一根。
     * <p>
     * 必须这样找：YSM 模型（如内置 17_mini 小小酒狐）的 {@code RightHand} 是
     * 没有 cubes 的占位骨，枢轴离前臂枢轴 ~18 模型单位（1.1 格），而实际手部
     * 枢轴是它的子骨 {@code RightHandLocator}（离前臂 ~3 单位）。旧代码固定
     * 用 ForeArm→Hand，l2 被算成 0.8m，IK 目标距离（玩家手臂映射后）只有
     * 0.3m，肘部被折到 176°，看起来就是手臂内旋。
     */
    private static String findIkEndBone(Bridge bridge, LocalPlayer player, String foreArmBone) {
        String fallback = foreArmBone.replace("ForeArm", "Hand");
        var bones = bridge.listBones(player);
        float[] elbow = bridge.getBoneModelPosition(player, foreArmBone);
        if (bones == null || bones.isEmpty() || elbow == null) {
            return fallback;
        }
        String best = null;
        double bestDist = Double.MAX_VALUE;
        for (String name : bones) {
            if (name == null || name.equals(foreArmBone)) {
                continue;
            }
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (!lower.contains("hand") && !lower.contains("locator")) {
                continue;
            }
            if (!isDescendantOf(bridge, player, name, foreArmBone)) {
                continue;
            }
            float[] p = bridge.getBoneModelPosition(player, name);
            if (p == null) {
                continue;
            }
            double dx = p[0] - elbow[0];
            double dy = p[1] - elbow[1];
            double dz = p[2] - elbow[2];
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < 0.25) {
                continue; // 与前臂枢轴重合的手指/占位骨
            }
            if (d < bestDist) {
                bestDist = d;
                best = name;
            }
        }
        return best != null ? best : fallback;
    }

    private static boolean isDescendantOf(Bridge bridge, LocalPlayer player,
                                          String bone, String ancestor) {
        String cur = bone;
        for (int i = 0; i < 32 && cur != null; i++) {
            if (cur.equals(ancestor)) {
                return true;
            }
            cur = bridge.getParentBoneName(player, cur);
        }
        return false;
    }

    /**
     * 模型空间向量（模型单位）→ 原版 VR 世界方向（米）。
     * 与 {@link #vanillaPartWorld} 同一映射，但不含部位位置/脚底平移。
     */
    private static Vec3 vanillaVectorWorld(VanillaVrPoseSource.Pose pose,
                                           double mx, double my, double mz) {
        double scale = 0.9375 * pose.heightScale;
        double x = -mx * scale / 16.0;
        double y = -my * scale / 16.0;
        double z = mz * scale / 16.0;
        double theta = Math.PI - pose.bodyYaw;
        double cos = Math.cos(theta);
        double sin = Math.sin(theta);
        double rx = x * cos + z * sin;
        double rz = -x * sin + z * cos;
        return new Vec3(rx * pose.worldScale, y * pose.worldScale, rz * pose.worldScale);
    }

    /**
     * 原版 VR 模型"手持物品"锚点世界位置：
     * {@code VRPlayerModel_WithArms.translateToHand} =
     * 手部位 pivot 世界位置 + R(手部位局部旋转) ·
     * {@code (side==LEFT ? -0.0625 : 0.0625, -0.65, 0)}（单位 block）。
     * 这是 ItemInHandLayer 在原版模型上渲染物品的位置。
     * <p>
     * 若玩家的 Vivecraft 模型类型没有拆分手（{@code rightHand}/{@code leftHand}
     * 缺失），退回 {@code VRPlayerModel.translateToHand}：物品挂在 vanilla
     * {@code rightArm/leftArm} 上臂 pivot（slim 时额外 ±0.03125 block）。
     */
    private static Vec3 vanillaItemAnchorWorld(VanillaVrPoseSource.Pose pose, boolean left,
                                               Vec3 entityPos) {
        VanillaVrPoseSource.Part handPart = left
                ? VanillaVrPoseSource.Part.LEFT_HAND : VanillaVrPoseSource.Part.RIGHT_HAND;
        if (pose.has(handPart)) {
            Vec3 pivot = vanillaPartWorld(pose, handPart, entityPos);
            if (pivot == null) {
                return null;
            }
            float ox = (left ? -0.0625f : 0.0625f) * 16f; // block → 模型单位
            float oy = -0.65f * 16f;
            var rot = new Matrix3f()
                    .rotateZ(pose.rz(handPart))
                    .rotateY(pose.ry(handPart))
                    .rotateX(pose.rx(handPart));
            Vector3f off = rot.transform(new Vector3f(ox, oy, 0f));
            return pivot.add(vanillaVectorWorld(pose, off.x, off.y, off.z));
        }
        VanillaVrPoseSource.Part armPart = left
                ? VanillaVrPoseSource.Part.LEFT_ARM : VanillaVrPoseSource.Part.RIGHT_ARM;
        if (pose.has(armPart)) {
            // 没有拆分手时物品直接挂在手臂 pivot 上（slim 偏移很小，忽略）
            return vanillaPartWorld(pose, armPart, entityPos);
        }
        return null;
    }

    /** 由 {@link Bridge#getBoneModelPosition}（store 约定）取骨骼枢轴世界位置。 */
    private Vec3 ysmBoneWorld(Bridge bridge, LocalPlayer player, String bone,
                              float bodyYaw, float scaleXZ, float scaleY) {
        float[] pos = bridge.getBoneModelPosition(player, bone);
        return pos == null ? null : boneWorldPos(player, pos, bodyYaw, scaleXZ, scaleY);
    }

    /**
     * 自动手部/手持物品位置对齐（v0.2.26）：
     * <ol>
     * <li>目标 = 原版 VR 模型手持物品锚点世界位置（手部位 pivot + translateToHand 偏移）；</li>
     * <li>当前 = YSM 手持物品锚点（HandLocator）世界位置；</li>
     * <li>对绑定骨骼位置通道 X/Y/Z 各加 1 模型单位，数值测出 3×3 Jacobian，
     * 解 {@code J · Δ = target − current}，把 Δ 作为本帧位置覆盖写入
     * {@link PoseOverrideStore}（不改持久化的 binding.pos，用户微调仍叠加）。</li>
     * </ol>
     * 用数值 Jacobian 避免显式推导"模型根镜像/骨骼父级旋转/渲染缩放"的逆变换，
     * 左右手、模型大小、玩家大小都自动正确；手持物品位置与原版一致。
     */
    private static int handAlignDiagCounter;

    /** 记录自动对齐中间状态（/ysmvr status 可见），并按限流打 debug 日志。 */
    private static void handAlignDiag(String state, String message) {
        latestHandAlignState = state + (message == null || message.isEmpty() ? "" : ("（" + message + "）"));
        if (VrBodyConfig.debugLog && ++handAlignDiagCounter % 120 == 0) {
            YsmVrMod.LOGGER.info("[ysmvr][debug] 手持对齐: {} {}", state, message == null ? "" : message);
        }
    }

    private void autoAlignHandItem(LocalPlayer player, Bridge bridge, PoseOverrideStore store,
                                   int pooled, VrBindings.Binding binding,
                                   VanillaVrPoseSource.Pose pose, Vec3 rawHandPos, Vec3 entityPos,
                                   float bodyYaw, float scaleXZ, float scaleY, Quaternionf parentAbs,
                                   Vec3 playerShoulderWorld) {
        String boneName = binding == null ? "?" : binding.boneName;
        if (!VrBodyConfig.handItemAlign || binding == null || (pose == null && rawHandPos == null)) {
            clearHandAlign(pooled, binding, store);
            handAlignDiag(!VrBodyConfig.handItemAlign ? "关闭" : "跳过",
                    !VrBodyConfig.handItemAlign ? boneName : "vpose=null 且 rawHandPos=null bone=" + boneName);
            return;
        }
        // v0.2.31：先做肩部对齐——模型肩膀 → 玩家真实 VR 肩膀。
        // 用户手调的 ±2.5 横向偏差本质是模型肩宽/肩位与玩家原版 VR 模型不同；
        // 直接平移 parent arm 骨骼位置，让整条手臂（含手和物品）整体对齐，
        // 不依赖 locator 的静态层级，也不会脱手。
        if (attemptAlignShoulder(player, bridge, store, pooled, binding, playerShoulderWorld,
                bodyYaw, scaleXZ, scaleY, parentAbs)) {
            return;
        }
        boolean left = binding.boneName.toLowerCase(java.util.Locale.ROOT).contains("left");
        VanillaVrPoseSource.Part handPart = left
                ? VanillaVrPoseSource.Part.LEFT_HAND : VanillaVrPoseSource.Part.RIGHT_HAND;

        Vec3 itemTarget = pose != null && entityPos != null
                ? vanillaItemAnchorWorld(pose, left, entityPos) : null;
        if (itemTarget == null) {
            itemTarget = rawHandPos;
        }
        Vec3 handTarget = pose != null && entityPos != null && pose.has(handPart)
                ? vanillaPartWorld(pose, handPart, entityPos) : null;
        if (handTarget == null) {
            handTarget = rawHandPos;
        }

        // v0.2.30：优先用 YSM 物品渲染层捕获的真实锚点（上一帧）。
        // 它对动画/约束驱动的 HandLocator 也有效，不依赖 getBoneModelPosition
        // 的静态父子链；修正只写 binding.pos（绑定骨骼位置），物品仍跟手。
        if (parentAbs != null && itemTarget != null) {
            float[] captured = YsmItemAnchorCapture.get(left);
            if (captured != null) {
                Vec3 current = new Vec3(captured[0], captured[1], captured[2]);
                Vec3 deltaWorld = itemTarget.subtract(current);
                if (deltaWorld.lengthSqr() < 1.0e-8) {
                    handAlignDiag("无需修正", String.format(java.util.Locale.ROOT,
                            "captured target≈current %s", binding.boneName));
                    return;
                }
                Vec3 localDelta = worldDeltaToBindingLocal(deltaWorld, bodyYaw, scaleXZ, scaleY, parentAbs);
                double maxAbs = Math.max(Math.abs(localDelta.x),
                        Math.max(Math.abs(localDelta.y), Math.abs(localDelta.z)));
                if (Double.isFinite(maxAbs) && maxAbs <= 8.0) {
                    float[] applied = handAlignCorrection.computeIfAbsent(pooled, k -> new float[3]);
                    // 增量式平滑：captured 当前值已包含上一帧 applied，这里只补差。
                    for (int i = 0; i < 3; i++) {
                        float delta = i == 0 ? (float) localDelta.x
                                : i == 1 ? (float) localDelta.y : (float) localDelta.z;
                        float step = Math.max(-4.0f, Math.min(4.0f, delta));
                        applied[i] += step * 0.5f;
                    }
                    store.setPosition(pooled, binding.posX + applied[0],
                            binding.posY + applied[1], binding.posZ + applied[2]);
                    handAlignDiag("已对齐", String.format(java.util.Locale.ROOT,
                            "captured target=(%.3f,%.3f,%.3f) current=(%.3f,%.3f,%.3f) Δ=(%.1f,%.1f,%.1f)",
                            itemTarget.x, itemTarget.y, itemTarget.z, current.x, current.y, current.z,
                            localDelta.x, localDelta.y, localDelta.z));
                    return;
                }
            }
        }

        String locator = bridge.getItemAnchorBone(player, binding.boneName);
        if (locator == null || bridge.resolveBone(player, locator) < 0) {
            locator = findIkEndBone(bridge, player, binding.boneName);
        }
        String handProbe = locator == null ? null : findHandProbe(bridge, player, locator);

        // 探针优先级：
        // 1) locator 自己能响应 binding.pos 时，直接对物品锚点求 Δ（最准）；
        // 2) locator 被动画/约束驱动（日志 Jacobian 全 0，且硬改它会脱手）时，
        //    改用仍挂在手臂层级上的 Hand 骨骼做探针，目标是原版 hand pivot——
        //    仍然通过手臂 binding.pos 平移整条手臂，物品会跟手一起动，不会脱手。
        if (attemptAlignProbe(player, bridge, store, pooled, binding, locator, itemTarget,
                "locator", bodyYaw, scaleXZ, scaleY)) {
            return;
        }
        if (attemptAlignProbe(player, bridge, store, pooled, binding, handProbe, handTarget,
                "hand", bodyYaw, scaleXZ, scaleY)) {
            return;
        }
        clearHandAlign(pooled, binding, store);
        handAlignDiag("跳过", String.format(java.util.Locale.ROOT,
                "无响应探针 bone=%s locator=%s hand=%s", boneName, locator, handProbe));
    }

    /**
     * v0.2.31 肩部对齐：把模型手臂根（binding 的 parent arm 骨骼）平移到
     * 玩家真实 VR 肩膀世界位置。横向 ±2.5 的本质是模型肩宽/肩位与原版 VR 模型
     * 不同；平移 parent arm 的 position 通道会让整条手臂（手+物品）一起动，
     * 不碰 locator，因此不会脱手，也不依赖静态层级测量 locator。
     */
    private boolean attemptAlignShoulder(LocalPlayer player, Bridge bridge, PoseOverrideStore store,
                                         int pooled, VrBindings.Binding binding,
                                         Vec3 playerShoulderWorld,
                                         float bodyYaw, float scaleXZ, float scaleY,
                                         Quaternionf parentAbs) {
        if (playerShoulderWorld == null || binding == null || parentAbs == null) {
            return false;
        }
        // 只测量"模型手臂根"世界位置；**不移动它**（移动会让手臂和身体分离）。
        // 把"玩家肩膀 − 模型肩膀"的世界差换算到 binding 的父本地坐标，
        // 叠加到 binding.pos（前臂/整条下段），肩关节保持连着身体。
        String shoulderBone = binding.boneName;
        String lowerBinding = binding.boneName.toLowerCase(java.util.Locale.ROOT);
        if (!lowerBinding.endsWith("arm") || lowerBinding.contains("forearm")) {
            shoulderBone = bridge.getParentBoneName(player, binding.boneName);
        }
        if (shoulderBone == null) {
            return false;
        }
        String lowerShoulder = shoulderBone.toLowerCase(java.util.Locale.ROOT);
        if (!lowerShoulder.contains("left") && !lowerShoulder.contains("right")) {
            return false;
        }
        if (bridge.resolveBone(player, shoulderBone) < 0) {
            return false;
        }
        store.setPosition(pooled, binding.posX, binding.posY, binding.posZ);
        Vec3 current = ysmBoneWorld(bridge, player, shoulderBone, bodyYaw, scaleXZ, scaleY);
        if (current == null) {
            return false;
        }
        Vec3 desiredWorld = playerShoulderWorld.subtract(current);
        if (desiredWorld.lengthSqr() < 1.0e-8) {
            handAlignDiag("无需修正", "shoulder " + shoulderBone);
            return true;
        }
        Vec3 localDelta = worldDeltaToBindingLocal(desiredWorld, bodyYaw, scaleXZ, scaleY, parentAbs);
        double maxAbs = Math.max(Math.abs(localDelta.x),
                Math.max(Math.abs(localDelta.y), Math.abs(localDelta.z)));
        if (!Double.isFinite(maxAbs) || maxAbs > 8.0) {
            return false;
        }
        float[] applied = handAlignCorrection.computeIfAbsent(pooled, k -> new float[3]);
        for (int i = 0; i < 3; i++) {
            float delta = i == 0 ? (float) localDelta.x
                    : i == 1 ? (float) localDelta.y : (float) localDelta.z;
            applied[i] += (delta - applied[i]) * 0.5f;
        }
        store.setPosition(pooled, binding.posX + applied[0],
                binding.posY + applied[1], binding.posZ + applied[2]);
        handAlignDiag("已对齐", String.format(java.util.Locale.ROOT,
                "shoulder(arm-root) %s target=(%.3f,%.3f,%.3f) current=(%.3f,%.3f,%.3f) Δ=(%.1f,%.1f,%.1f)",
                shoulderBone, playerShoulderWorld.x, playerShoulderWorld.y, playerShoulderWorld.z,
                current.x, current.y, current.z, applied[0], applied[1], applied[2]));
        return true;
    }

    /** 在指定探针骨骼上测 Jacobian；修正通过 binding.pos 应用到整条手臂。 */
    private boolean attemptAlignProbe(LocalPlayer player, Bridge bridge, PoseOverrideStore store,
                                      int pooled, VrBindings.Binding binding, String probe, Vec3 target,
                                      String label, float bodyYaw, float scaleXZ, float scaleY) {
        if (probe == null || target == null || bridge.resolveBone(player, probe) < 0) {
            return false;
        }
        float bx = binding.posX;
        float by = binding.posY;
        float bz = binding.posZ;
        store.setPosition(pooled, bx, by, bz);
        Vec3 current = ysmBoneWorld(bridge, player, probe, bodyYaw, scaleXZ, scaleY);
        if (current == null) {
            return false;
        }
        Vec3 desired = target.subtract(current);
        if (desired.lengthSqr() < 1.0e-8) {
            handAlignCorrection.remove(pooled);
            store.setPosition(pooled, bx, by, bz);
            handAlignDiag("无需修正", label + " probe=" + probe);
            return true;
        }

        double[][] j = new double[3][3];
        float[] test = {bx, by, bz};
        for (int i = 0; i < 3; i++) {
            test[0] = bx;
            test[1] = by;
            test[2] = bz;
            test[i] += 1.0f;
            store.setPosition(pooled, test[0], test[1], test[2]);
            Vec3 moved = ysmBoneWorld(bridge, player, probe, bodyYaw, scaleXZ, scaleY);
            if (moved == null) {
                store.setPosition(pooled, bx, by, bz);
                return false;
            }
            j[0][i] = moved.x - current.x;
            j[1][i] = moved.y - current.y;
            j[2][i] = moved.z - current.z;
        }
        double det = det3(j);
        if (!Double.isFinite(det) || Math.abs(det) < 1.0e-6) {
            store.setPosition(pooled, bx, by, bz);
            return false;
        }
        double[] d = solve3x3(j, desired.x, desired.y, desired.z);
        if (d == null) {
            store.setPosition(pooled, bx, by, bz);
            return false;
        }
        double maxAbs = Math.max(Math.abs(d[0]), Math.max(Math.abs(d[1]), Math.abs(d[2])));
        if (!Double.isFinite(maxAbs) || maxAbs > 8.0) {
            store.setPosition(pooled, bx, by, bz);
            return false;
        }
        // 平滑收敛，避免逐帧 Jacobian 小噪声抖动
        float[] applied = handAlignCorrection.computeIfAbsent(pooled, k -> new float[3]);
        for (int i = 0; i < 3; i++) {
            applied[i] += ((float) d[i] - applied[i]) * 0.5f;
        }
        store.setPosition(pooled, bx + applied[0], by + applied[1], bz + applied[2]);
        handAlignDiag("已对齐", String.format(java.util.Locale.ROOT,
                "%s probe=%s target=(%.3f,%.3f,%.3f) current=(%.3f,%.3f,%.3f) Δ=(%.1f,%.1f,%.1f)",
                label, probe, target.x, target.y, target.z, current.x, current.y, current.z,
                applied[0], applied[1], applied[2]));
        return true;
    }

    /** 在 armBone 的后代里找真正的 ForeArm 骨骼（SIMPLE→IK 自动升级用）。 */
    private static String findForeArmChild(Bridge bridge, LocalPlayer player, String armBone) {
        var bones = bridge.listBones(player);
        if (bones == null || bones.isEmpty()) {
            return null;
        }
        boolean left = armBone.toLowerCase(java.util.Locale.ROOT).contains("left");
        String best = null;
        for (String name : bones) {
            if (name == null || name.equals(armBone)) {
                continue;
            }
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (!lower.contains("forearm")) {
                continue;
            }
            if (lower.contains("left") != left) {
                continue; // 必须同侧
            }
            boolean descendant = isDescendantOf(bridge, player, name, armBone);
            if (!descendant && !armBone.toLowerCase(java.util.Locale.ROOT).endsWith("arm")) {
                continue;
            }
            // 父链不可用（部分模型 parentName 缺失）时按同侧名字回退，仍优先短名。
            if (best == null || name.length() < best.length()) {
                best = name;
            }
        }
        return best;
    }

    /** ForeArm 的父骨骼名不可用时的名字回退：同侧、含 arm、非 forearm/hand，取短名。 */
    private static String findArmParentFallback(Bridge bridge, LocalPlayer player, String foreArmBone) {
        var bones = bridge.listBones(player);
        if (bones == null || bones.isEmpty()) {
            return null;
        }
        boolean left = foreArmBone.toLowerCase(java.util.Locale.ROOT).contains("left");
        String best = null;
        for (String name : bones) {
            if (name == null) {
                continue;
            }
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("forearm") || lower.contains("hand")) {
                continue;
            }
            if (!lower.contains("arm")) {
                continue;
            }
            if (lower.contains("left") != left) {
                continue;
            }
            if (best == null || name.length() < best.length()) {
                best = name;
            }
        }
        return best;
    }

    /** 从 locator 沿父链向上找最近的 Hand（非 Locator）骨骼，作为可响应手臂位置的探针。 */
    private static String findHandProbe(Bridge bridge, LocalPlayer player, String locator) {
        String cur = locator;
        for (int i = 0; i < 8 && cur != null; i++) {
            cur = bridge.getParentBoneName(player, cur);
            if (cur == null) {
                break;
            }
            String lower = cur.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("hand") && !lower.contains("locator")) {
                return cur;
            }
        }
        return null;
    }

    /** 追踪数据不可用/骨骼缺失/关闭对齐时，恢复 binding.pos 并清掉平滑修正。 */
    private static void clearHandAlign(int pooled, VrBindings.Binding binding, PoseOverrideStore store) {
        if (handAlignCorrection.remove(pooled) != null && binding != null && store != null) {
            store.setPosition(pooled, binding.posX, binding.posY, binding.posZ);
        }
    }

    /**
     * 世界位置修正 → binding 骨骼位置通道（父坐标系，模型单位）。
     * <p>
     * 先把世界向量按 {@link #boneWorldPos} 的根映射逆变换回模型根坐标系
     * （R 系：x 取反），再乘父骨骼绝对旋转的逆，得到 binding.pos 所在的
     * 父本地坐标系增量。
     */
    private static Vec3 worldDeltaToBindingLocal(Vec3 world, float bodyYaw,
                                                 float scaleXZ, float scaleY,
                                                 Quaternionf parentAbs) {
        double theta = Math.PI - bodyYaw;
        double cos = Math.cos(theta);
        double sin = Math.sin(theta);
        // 逆 Ry(π-bodyYaw)
        double mx = world.x * cos - world.z * sin;
        double mz = world.x * sin + world.z * cos;
        double my = world.y;
        float sx = (float) (-mx * 16.0 / Math.max(0.01, scaleXZ));
        float sy = (float) (my * 16.0 / Math.max(0.01, scaleY));
        float sz = (float) (mz * 16.0 / Math.max(0.01, scaleXZ));
        Vector3f local = new Quaternionf(parentAbs).conjugate()
                .transform(new Vector3f(sx, sy, sz));
        return new Vec3(local.x, local.y, local.z);
    }

    /** 3×3 线性方程求解（Cramer 法则）；奇异返回 null。 */
    private static double[] solve3x3(double[][] a, double bx, double by, double bz) {
        double det = det3(a);
        if (Math.abs(det) < 1.0e-9) {
            return null;
        }
        double dx = det3(new double[][]{
                {bx, a[0][1], a[0][2]},
                {by, a[1][1], a[1][2]},
                {bz, a[2][1], a[2][2]}});
        double dy = det3(new double[][]{
                {a[0][0], bx, a[0][2]},
                {a[1][0], by, a[1][2]},
                {a[2][0], bz, a[2][2]}});
        double dz = det3(new double[][]{
                {a[0][0], a[0][1], bx},
                {a[1][0], a[1][1], by},
                {a[2][0], a[2][1], bz}});
        return new double[]{dx / det, dy / det, dz / det};
    }

    private static double det3(double[][] m) {
        return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
                - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
    }

    /**
     * Vivecraft 原版 VR 模型部位位置（模型单位，ModelPart.x/y/z）→ 玩家脚底为原点的
     * 世界米（再加 entityPos 得到绝对世界坐标）。
     * <p>
     * 严格取 Vivecraft {@code ModelUtils.worldToModel} 的逆：
     * <pre>
     * worldToModel: p' = (p/worldScale − (0, 1.501·scale, 0))
     *                    · Ry(−π + bodyYaw) · (16/scale) · diag(−1,−1,1)
     * scale = 0.9375 · heightScale
     * </pre>
     * 用它换算出的肩膀/肘/手位置只属于玩家 VR 身体，和 YSM 模型骨骼、模型缩放
     * 无关——改玩家大小/模型大小不会改变手臂瞄准方向，校准偏移在缩放前后保持有效。
     */
    private static Vec3 vanillaPartWorld(VanillaVrPoseSource.Pose pose,
                                         VanillaVrPoseSource.Part part, Vec3 entityPos) {
        if (pose == null || !pose.has(part)) {
            return null;
        }
        double scale = 0.9375 * pose.heightScale;
        // 逆 diag(-1,-1,1)
        double x = -pose.px(part);
        double y = -pose.py(part);
        double z = pose.pz(part);
        // 逆 (16/scale)
        x *= scale / 16.0;
        y *= scale / 16.0;
        z *= scale / 16.0;
        // 逆 Ry(-π + bodyYaw) = Ry(π - bodyYaw)
        double theta = Math.PI - pose.bodyYaw;
        double cos = Math.cos(theta);
        double sin = Math.sin(theta);
        double rx = x * cos + z * sin;
        double rz = -x * sin + z * cos;
        // 逆 -(0, 1.501·scale, 0)，再逆 /worldScale
        double wy = y + 1.501 * scale;
        return new Vec3(entityPos.x + rx * pose.worldScale,
                entityPos.y + wy * pose.worldScale,
                entityPos.z + rz * pose.worldScale);
    }

    /**
     * 用上臂/前臂/手骨枢轴长度做两骨 IK，估算手贴脸/收回时的肘部弯曲角。
     * 返回 0（完全伸直）~ 约 π（完全折叠）。
     */
    private float estimateElbowBend(LocalPlayer player, Bridge bridge, String foreArmBone,
                                    Vec3 handPos, float bodyYaw, float scaleXZ, float scaleY) {
        String upperBone = bridge.getParentBoneName(player, foreArmBone);
        if (upperBone == null) {
            return 0f;
        }
        String handBone = foreArmBone.replace("ForeArm", "Hand");
        float[] s = bridge.getBoneModelPosition(player, upperBone);
        float[] e = bridge.getBoneModelPosition(player, foreArmBone);
        float[] w = bridge.getBoneModelPosition(player, handBone);
        if (s == null || e == null || w == null) {
            return 0f;
        }
        Vec3 shoulder = boneWorldPos(player, s, bodyYaw, scaleXZ, scaleY);
        Vec3 elbow = boneWorldPos(player, e, bodyYaw, scaleXZ, scaleY);
        Vec3 wrist = boneWorldPos(player, w, bodyYaw, scaleXZ, scaleY);
        double l1 = shoulder.distanceTo(elbow);
        double l2 = elbow.distanceTo(wrist);
        if (l1 < 1.0e-4 || l2 < 1.0e-4) {
            return 0f;
        }
        double reach = shoulder.distanceTo(handPos);
        double max = l1 + l2;
        double min = Math.abs(l1 - l2);
        reach = Math.max(min + 1.0e-3, Math.min(max - 1.0e-3, reach));
        double cosInterior = (l1 * l1 + l2 * l2 - reach * reach) / (2.0 * l1 * l2);
        cosInterior = Math.max(-1.0, Math.min(1.0, cosInterior));
        return (float) (Math.PI - Math.acos(cosInterior));
    }

    /** 世界方向 → store 模型空间方向（与 aimDirection 的世界→模型约定一致）。 */
    private static Vector3f worldToModelDir(Vec3 dir, float bodyYaw) {
        double a = bodyYaw - Math.PI;
        double c = Math.cos(a);
        double s = Math.sin(a);
        return new Vector3f(
                (float) (c * dir.x + s * dir.z),
                (float) dir.y,
                (float) (-s * dir.x + c * dir.z));
    }

    /** 求 from → to 的最短弧旋转；近反平行时固定轴，避免退化。 */
    private static Quaternionf rotationBetween(Vector3f from, Vector3f to) {
        float d = from.dot(to);
        if (d > 0.999999f) {
            return new Quaternionf();
        }
        if (d < -0.999999f) {
            Vector3f axis = new Vector3f(1f, 0f, 0f).cross(from);
            if (axis.lengthSquared() < 1.0e-6f) {
                axis.set(0f, 1f, 0f).cross(from);
            }
            axis.normalize();
            return new Quaternionf().rotateAxis((float) Math.PI, axis.x, axis.y, axis.z);
        }
        return new Quaternionf().rotationTo(from.x, from.y, from.z, to.x, to.y, to.z);
    }

    /** 原版 VR 模型部位旋转 → YSM store 绝对旋转（沿用 applyVanillaBone 的映射）。 */
    private static Quaternionf vanillaPartQuat(VanillaVrPoseSource.Pose pose,
                                               VanillaVrPoseSource.Part part,
                                               VrBindings.Binding binding,
                                               float deltaYaw) {
        float rx = -pose.rx(part) + (binding != null ? (float) Math.toRadians(binding.rotX) : 0f);
        float ry = pose.ry(part) + (binding != null ? (float) Math.toRadians(binding.rotY) : 0f);
        float rz = pose.rz(part) + (binding != null ? (float) Math.toRadians(binding.rotZ) : 0f);
        Matrix3f m = new Matrix3f().rotateZ(rz).rotateY(ry).rotateX(rx);
        if (Math.abs(deltaYaw) > 1.0e-4f) {
            m = new Matrix3f().rotateY(deltaYaw).mul(m);
        }
        return new Quaternionf().setFromNormalized(m);
    }

    /**
     * 真实两骨 IK：目标 = 控制器手位置，肘部放在“休息姿态肩→肘方向”
     * 投影出的自然平面内。成功后：
     * <ul>
     * <li>outUpper = 上臂绝对旋转（rest -Y 指向 IK 上臂方向）；</li>
     * <li>outForeLocal = 前臂相对上臂的本地旋转（rest -Y 指向 IK 前臂方向）；</li>
     * <li>outBendDeg[0] = 实际肘弯角（度，调试用）。</li>
     * </ul>
     * 手/手指的 roll 由 qCal（带绑定偏移的旧 full 方向）对齐到 IK 前臂方向，
     * 因此伸直时仍保持原来的掌心/手持物朝向。
     */
    /** 模型自身 rest 肘 pole（世界空间）：T 字校准时作为直接旋转的肘平面基准。 */
    private Vec3 modelElbowPoleWorld(LocalPlayer player, Bridge bridge, VrBindings.Binding binding,
                                     float bodyYaw, float scaleXZ, float scaleY) {
        if (binding == null || binding.boneName == null) {
            return null;
        }
        String fore = binding.boneName;
        String upper = bridge.getParentBoneName(player, fore);
        String end = bridge.getItemAnchorBone(player, fore);
        if (end == null || bridge.resolveBone(player, end) < 0) {
            end = findIkEndBone(bridge, player, fore);
        }
        if (upper == null || end == null) {
            return null;
        }
        float[] s = bridge.getBoneModelPosition(player, upper);
        float[] e = bridge.getBoneModelPosition(player, fore);
        float[] w = bridge.getBoneModelPosition(player, end);
        if (s == null || e == null || w == null) {
            return null;
        }
        Vec3 shoulder = boneWorldPos(player, s, bodyYaw, scaleXZ, scaleY);
        Vec3 elbow = boneWorldPos(player, e, bodyYaw, scaleXZ, scaleY);
        Vec3 wrist = boneWorldPos(player, w, bodyYaw, scaleXZ, scaleY);
        Vec3 target = wrist.subtract(shoulder);
        if (target.lengthSqr() < 1.0e-6) {
            return null;
        }
        Vec3 dHat = target.normalize();
        Vec3 raw = elbow.subtract(shoulder);
        Vec3 pole = raw.subtract(dHat.scale(raw.dot(dHat)));
        if (pole.lengthSqr() < 1.0e-6) {
            return null;
        }
        return pole.normalize();
    }
    private boolean solveForeArmIk(LocalPlayer player, Bridge bridge, String foreArmBone,
                                   Vec3 handPos, float bodyYaw, float scaleXZ, float scaleY,
                                   Quaternionf qCal, Quaternionf refUpper, Quaternionf refFore,
                                   Quaternionf outUpper, Quaternionf outForeLocal,
                                   float[] outBendDeg, String upperBoneOverride,
                                   float[] outStretch) {
        return solveForeArmIk(player, bridge, foreArmBone, handPos, bodyYaw, scaleXZ, scaleY,
                qCal, refUpper, refFore, outUpper, outForeLocal, outBendDeg, upperBoneOverride,
                outStretch, false, null);
    }

    private boolean solveForeArmIk(LocalPlayer player, Bridge bridge, String foreArmBone,
                                   Vec3 handPos, float bodyYaw, float scaleXZ, float scaleY,
                                   Quaternionf qCal, Quaternionf refUpper, Quaternionf refFore,
                                   Quaternionf outUpper, Quaternionf outForeLocal,
                                   float[] outBendDeg, String upperBoneOverride,
                                   float[] outStretch, boolean legPole) {
        return solveForeArmIk(player, bridge, foreArmBone, handPos, bodyYaw, scaleXZ, scaleY,
                qCal, refUpper, refFore, outUpper, outForeLocal, outBendDeg, upperBoneOverride,
                outStretch, legPole, null);
    }

    private boolean solveForeArmIk(LocalPlayer player, Bridge bridge, String foreArmBone,
                                   Vec3 handPos, float bodyYaw, float scaleXZ, float scaleY,
                                   Quaternionf qCal, Quaternionf refUpper, Quaternionf refFore,
                                   Quaternionf outUpper, Quaternionf outForeLocal,
                                   float[] outBendDeg, String upperBoneOverride,
                                   float[] outStretch, boolean legPole, String endBoneOverride) {
        if (handPos == null) {
            return false;
        }
        String upperBone = upperBoneOverride != null && !upperBoneOverride.isBlank()
                ? upperBoneOverride : bridge.getParentBoneName(player, foreArmBone);
        if (upperBone == null) {
            return false;
        }
        String handBone = endBoneOverride;
        if (handBone == null || bridge.resolveBone(player, handBone) < 0) {
            handBone = bridge.getItemAnchorBone(player, foreArmBone);
            if (handBone == null || bridge.resolveBone(player, handBone) < 0) {
                handBone = findIkEndBone(bridge, player, foreArmBone);
            }
        }
        if (handBone == null) {
            debugLogIkForce(foreArmBone, "前臂IK跳过 %s: handBone=null", foreArmBone);
            return false;
        }
        float[] s = bridge.getBoneModelPosition(player, upperBone);
        float[] e = bridge.getBoneModelPosition(player, foreArmBone);
        float[] w = handBone == null ? null : bridge.getBoneModelPosition(player, handBone);
        if (s == null || e == null || w == null) {
            debugLogIkForce(foreArmBone, "前臂IK跳过 %s: s/e/w=null upper=%s handBone=%s",
                    foreArmBone, upperBone, handBone);
            return false;
        }
        Vec3 shoulder = boneWorldPos(player, s, bodyYaw, scaleXZ, scaleY);
        Vec3 restElbow = boneWorldPos(player, e, bodyYaw, scaleXZ, scaleY);
        Vec3 restWrist = boneWorldPos(player, w, bodyYaw, scaleXZ, scaleY);
        debugLogKey(foreArmBone + ":wrist",
                "模型腕 %s: modelWrist=(%.3f,%.3f,%.3f) handPos=(%.3f,%.3f,%.3f) 偏差=%.3f",
                foreArmBone, restWrist.x, restWrist.y, restWrist.z,
                handPos.x, handPos.y, handPos.z, restWrist.distanceTo(handPos));
        double l1 = shoulder.distanceTo(restElbow);
        double l2 = restElbow.distanceTo(restWrist);
        if (l1 < 1.0e-4 || l2 < 1.0e-4) {
            debugLogIkForce(foreArmBone, "前臂IK跳过 %s: l1=%.4f l2=%.4f", foreArmBone, l1, l2);
            return false;
        }
        // 前臂/上臂长度比例异常（例如把没有网格的远距离 Hand 占位骨当成手端点）时
        // 两骨 IK 必然把肘部折到 ~176°，表现为手臂内旋；交给上层退回"整臂瞄准
        // 父骨骼"（applyHandAim 的 IK else 分支）。
        double minSeg = Math.min(l1, l2);
        double maxSeg = Math.max(l1, l2);
        if (maxSeg > 4.0 * minSeg) {
            debugLogIkForce(foreArmBone, "前臂IK跳过 %s: 比例异常 l1=%.3f l2=%.3f ratio=%.2f",
                    foreArmBone, l1, l2, maxSeg / minSeg);
            return false;
        }
        Vec3 targetVec = handPos.subtract(shoulder);
        double targetDist = targetVec.length();
        double targetFromPlayer = handPos.distanceTo(
                player.getPosition(Minecraft.getInstance().getFrameTime()));
        debugLogKey(foreArmBone + ":dist",
                "IK距离2 %s: reach=%.2f targetDist=%.2f shortfall=%.2f targetFromPlayer=%.2f",
                foreArmBone, l1 + l2, targetDist,
                Math.max(0.0, targetDist - (l1 + l2)), targetFromPlayer);
        if (targetDist < 1.0e-4) {
            debugLogIkForce(foreArmBone, "前臂IK跳过 %s: targetDist=0", foreArmBone);
            return false;
        }
        double min = Math.abs(l1 - l2) + 1.0e-3;
        if (legPole) {
            // 腿：限制最大屈膝约 120，避免脚抬太高时小腿反折成 U 形
            double maxFlex = Math.toRadians(90.0);
            double legMin = Math.sqrt(Math.max(0.0,
                    l1 * l1 + l2 * l2 - 2.0 * l1 * l2 * Math.cos(maxFlex)));
            min = Math.max(min, legMin);
        }
        double max = l1 + l2 - 1.0e-3;
        double d = Math.max(min, Math.min(max, targetDist));
                // v0.2.45：目标超出模型臂长时等比拉伸手臂骨骼（保持关节连接），
        // 避免"手臂伸直但手永远停在模型外侧/短一截"。
        float stretch = 1.0f;
        if (targetDist > l1 + l2) {
            stretch = (float) Math.min(2.0, targetDist / (l1 + l2));
        }
        if (outStretch != null && outStretch.length > 0) {
            outStretch[0] = stretch;
        }
        Vec3 dHat = targetVec.scale(1.0 / targetDist);
        Vec3 ikHandPos = handPos;
        if (legPole && d > targetDist + 1.0e-6) {
            ikHandPos = shoulder.add(dHat.scale(d));
        }
        // 肘部 pole：VRChat 风格——优先"上下弯曲"（v0.2.35）。
        // 旧版外向权重会让肘横着支出去；现在以世界"向下"为主、身体"向后"为辅，
        // 再加一点模型休息姿态，肘在竖直平面内自然下垂/后收。
        Vec3 restPoleRaw = restElbow.subtract(shoulder);
        Vec3 restPole = restPoleRaw.subtract(dHat.scale(restPoleRaw.dot(dHat)));
        Vec3 downRaw = new Vec3(0.0, -1.0, 0.0);
        Vec3 downFlat = downRaw.subtract(dHat.scale(downRaw.dot(dHat)));
        // bodyYaw 前方向 = (−sin, 0, cos)，向后取反
        Vec3 backRaw = new Vec3(Math.sin(bodyYaw), 0.0, -Math.cos(bodyYaw));
        Vec3 backFlat = backRaw.subtract(dHat.scale(backRaw.dot(dHat)));
        Vec3 poleCombined = new Vec3(0.0, 0.0, 0.0);
        if (legPole) {
            Vec3 forwardRaw = new Vec3(-Math.sin(bodyYaw), 0.0, Math.cos(bodyYaw));
            Vec3 forwardFlat = forwardRaw.subtract(dHat.scale(forwardRaw.dot(dHat)));
            if (forwardFlat.lengthSqr() > 1.0e-6) {
                poleCombined = forwardFlat.normalize().scale(1.0);
            }
            if (restPole.lengthSqr() > 1.0e-6) {
                poleCombined = poleCombined.add(restPole.normalize().scale(0.15));
            }
        } else {
            if (downFlat.lengthSqr() > 1.0e-6) {
                poleCombined = poleCombined.add(downFlat.normalize().scale(1.0));
            }
            if (backFlat.lengthSqr() > 1.0e-6) {
                poleCombined = poleCombined.add(backFlat.normalize().scale(0.35));
            }
            if (restPole.lengthSqr() > 1.0e-6) {
                poleCombined = poleCombined.add(restPole.normalize().scale(0.15));
            }
        }
        if (!legPole && armPoleOverride != null) {

            poleCombined = armPoleOverride;

            armPoleOverride = null;

        }

        Vec3 pole;

        if (poleCombined.lengthSqr() > 1.0e-6) {
            pole = poleCombined.normalize();
        } else {
            // 退化：向下/向后都无法构造时沿用旧 fallback
            pole = restPoleRaw.subtract(dHat.scale(restPoleRaw.dot(dHat)));
            if (pole.lengthSqr() < 1.0e-6) {
                Vec3 fallback = new Vec3(0.0, -1.0, 0.0);
                pole = fallback.subtract(dHat.scale(fallback.dot(dHat)));
                if (pole.lengthSqr() < 1.0e-6) {
                    Vec3 alt = dHat.cross(new Vec3(0.0, 0.0, 1.0));
                    if (alt.lengthSqr() < 1.0e-6) {
                        alt = dHat.cross(new Vec3(1.0, 0.0, 0.0));
                    }
                    pole = alt;
                }
            }
            pole = pole.normalize();
        }
        double a = (l1 * l1 - l2 * l2 + d * d) / (2.0 * d);
        double h = Math.sqrt(Math.max(0.0, l1 * l1 - a * a));
        Vec3 elbow = shoulder.add(dHat.scale(a)).add(pole.scale(h));
        Vec3 uWorld = elbow.subtract(shoulder).normalize();
        Vec3 fWorld = ikHandPos.subtract(elbow).normalize();
        Vector3f u = worldToModelDir(uWorld, bodyYaw).normalize();
        Vector3f f = worldToModelDir(fWorld, bodyYaw).normalize();
        Vector3f poleModel = worldToModelDir(pole, bodyYaw).normalize();
        // 0.2.43 追踪行为：不使用 pivot 差/父链共轭实验，保持原方向解算。
        Quaternionf qUpperAbs;
        Quaternionf qForeAbs;
        if (refUpper != null && refFore != null) {
            Vector3f refUpDir = refUpper.transform(new Vector3f(0f, -1f, 0f));
            qUpperAbs = rotationBetween(refUpDir, u);
            qUpperAbs.mul(refUpper);
            Vector3f refForeDir = refFore.transform(new Vector3f(0f, -1f, 0f));
            qForeAbs = rotationBetween(refForeDir, f);
            qForeAbs.mul(refFore);
        } else {
            Vector3f hinge = new Vector3f(u).cross(f);
            if (hinge.lengthSquared() < 1.0e-8f) {
                hinge = new Vector3f(u).cross(poleModel);
            }
            if (hinge.lengthSquared() < 1.0e-8f) {
                Vector3f axis = Math.abs(u.y) < 0.9f
                        ? new Vector3f(0f, 1f, 0f) : new Vector3f(1f, 0f, 0f);
                hinge = new Vector3f(u).cross(axis);
            }
            hinge.normalize();
            Vector3f yImg = new Vector3f(u).negate();
            Vector3f zImg = new Vector3f(hinge).cross(yImg);
            Matrix3f m = new Matrix3f();
            m.setColumn(0, hinge);
            m.setColumn(1, yImg);
            m.setColumn(2, zImg);
            qUpperAbs = new Quaternionf().setFromNormalized(m);
            Vector3f aimDir = qCal.transform(new Vector3f(0f, -1f, 0f));
            qForeAbs = rotationBetween(aimDir, f);
            qForeAbs.mul(qCal);
        }
        outUpper.set(qUpperAbs);
        outForeLocal.set(new Quaternionf(qUpperAbs).conjugate().mul(qForeAbs));
        if (outBendDeg != null && outBendDeg.length > 0) {
            double bend = Math.acos(Math.max(-1.0, Math.min(1.0, u.dot(f))));
            outBendDeg[0] = (float) Math.toDegrees(bend);
        }
        debugLogIkBone(foreArmBone,
                "IK pole %s: down=(%.2f,%.2f,%.2f) back=(%.2f,%.2f,%.2f) restPole=(%.2f,%.2f,%.2f) pole=(%.2f,%.2f,%.2f)",
                foreArmBone,
                downRaw.x, downRaw.y, downRaw.z,
                backRaw.x, backRaw.y, backRaw.z,
                restPole.x, restPole.y, restPole.z,
                pole.x, pole.y, pole.z);
        return true;
    }

    /**
     * 玩家 VR 肩膀世界位置 + 手柄世界位置 → YSM 模型空间单位向量。
     * <p>
     * 与 YSM 模型骨骼/缩放完全无关：肩膀来自 Vivecraft 原版 VR 模型的上臂枢轴，
     * 手柄是 Vivecraft 世界姿态位置；两者都随玩家 worldScale 同步缩放，因此改
     * 玩家大小/模型大小不会改变方向，校准偏移在缩放前后保持有效。
     * 返回 {dx, dy, dz}（模型空间 M_Y，已归一化）；两点过近时返回 null。
     */
    private double[] aimDirection(Vec3 shoulderWorld, Vec3 handPos, float bodyYaw) {
        double dx = handPos.x - shoulderWorld.x;
        double dy = handPos.y - shoulderWorld.y;
        double dz = handPos.z - shoulderWorld.z;
        // 世界 → 模型根系（rotY(bodyYaw−π)）；store 现在是模型空间绝对旋转
        double ca = Math.cos(bodyYaw - Math.PI);
        double sa = Math.sin(bodyYaw - Math.PI);
        double wx = ca * dx + sa * dz;
        double wz = -sa * dx + ca * dz;
        double mdx = wx;
        double mdy = dy;
        double mdz = wz;
        double len = Math.sqrt(mdx * mdx + mdy * mdy + mdz * mdz);
        if (len < 0.02) {
            return null;
        }
        // 渲染器世界合成 Ry(π−bodyYaw)·D1·M_eff·v ⇒ 模型方向 = D1·Ry(bodyYaw−π)·world
        // D1 = diag(−1, 1, 1)（YSM 管线 x 轴镜像，y/z 不变）。
        // 实机左右反向：直接镜像模型 X，而不是翻转最终 Euler yaw，
        // 这样 T 字型/过顶时不会破坏手臂平面。
        return new double[]{mdx / len, mdy / len, mdz / len};
    }

    /** 手柄朝向（世界）→ YSM 模型空间方向；与旧 fallback 的旋转/镜像约定一致。 */
    private static double[] handDirToModel(Vec3 handDir, float bodyYaw) {
        double theta = bodyYaw - Math.PI;
        double c = Math.cos(theta);
        double s = Math.sin(theta);
        double wx = c * handDir.x + s * handDir.z;
        double wz = -s * handDir.x + c * handDir.z;
        return new double[]{-wx, handDir.y, wz};
    }

    /**
     * 视角绑定：把第一人称相机平移到模型头部骨骼的动画实时位置。
     * <p>
     * 眼睛世界坐标 = origin + rotateY(θ)·hmdPos·scale。目标平移 Δ：
     * <pre>Δ = rotateY(180°−bodyYaw)·(头部模型坐标 + 偏移) − (0, 当前眼睛Y, 0)</pre>
     * 水平分量不含 HMD 房间坐标项 → 物理行走产生的位移不会被抵消；
     * 垂直分量完全锚定头部骨骼高度（蹲下等高度变化由头部动画/身高适配体现）。
     * 平滑收敛避免开关瞬间跳变；禁用或骨骼缺失时 Δ 指数衰减回 0。
     */
    private void applyView(VRBodyPartData headData, float bodyYaw, float[] headModelPos, double dt,
                           float scaleXZ, float scaleY) {
        double targetX = 0, targetY = 0, targetZ = 0;
        if (VrViewConfig.enabled && headData != null && headModelPos != null) {
            // 模型根坐标系（米）→ 世界：与渲染器相同的
            // Ry(180°−bodyYaw)·diag(heightScale,widthScale,heightScale)·(−x/16, y/16, z/16)
            double theta = Math.PI - bodyYaw;
            double cos = Math.cos(theta);
            double sin = Math.sin(theta);
            double mx = -headModelPos[0] / 16.0 * scaleXZ + VrViewConfig.offsetX;
            double my = headModelPos[1] / 16.0 * scaleY + VrViewConfig.offsetY;
            double mz = headModelPos[2] / 16.0 * scaleXZ + VrViewConfig.offsetZ;
            targetX = mx * cos + mz * sin;
            targetY = my - headData.getPos().y; // 垂直锚定：只补头部高度差
            targetZ = -mx * sin + mz * cos;
        }

        double k = 1.0 - Math.exp(-SMOOTH_RATE * dt);
        smoothedX += (targetX - smoothedX) * k;
        smoothedY += (targetY - smoothedY) * k;
        smoothedZ += (targetZ - smoothedZ) * k;

        if (Math.abs(smoothedX) < SMOOTH_EPS && Math.abs(smoothedY) < SMOOTH_EPS
                && Math.abs(smoothedZ) < SMOOTH_EPS) {
            smoothedX = smoothedY = smoothedZ = 0;
            return; // 已收敛且目标为 0，跳过注入
        }
        VrViewHook.shiftOrigin(VrViewHook.currentRenderData(), smoothedX, smoothedY, smoothedZ);
    }

    /**
     * 玩家身高/大小适配：调用 Vivecraft 自身的世界缩放功能（{@link VrWorldScale}，
     * preTick 每 tick 读取并应用）。
     * <p>
     * Vivecraft 语义（见 en_us 设置提示与 {@code VRPlayerRenderer}）：
     * {@code worldScale > 1} 玩家变大、{@code < 1} 变小，
     * 玩家世界身高 = 真实房间眼高 × worldScale。
     * <ul>
     * <li><b>跟随 YSM 模型高度</b>（{@link VrBodyConfig#scaleEnabled}）：
     * 目标 worldScale = 模型头部高度 / 真实眼高，即玩家世界身高 ≈ 模型头部高度。
     * 模型头部高度取自实时骨骼（只含"模型大小"倍率，见
     * {@link #applyForcedModelScale}）；玩家大小倍率在跟随模式下不再额外作用，
     * 玩家始终贴合模型；</li>
     * <li><b>手动玩家大小</b>（倍率 ≠ 1 且未跟随）：目标 worldScale =
     * Vivecraft 原值 × 倍率，即玩家世界身高 ≈ 原本身高 × 倍率；
     * <b>只改玩家，不改 YSM 模型</b>（模型大小由 {@link VrBodyConfig#modelScale}
     * 单独控制）；</li>
     * <li>两者都不生效（未跟随且倍率 = 1）：恢复玩家原有的 Vivecraft 世界缩放。</li>
     * </ul>
     * 眼睛高度取房间姿态的 HMD Y（≈ 真实眼睛高度，不受 worldScale 影响，
     * 无反馈回路）。接管时保存玩家原有设置值并平滑过渡，停用时恢复。
     */
    private void applyScale(float[] headModelPos, double dt, float scaleY) {
        LocalPlayer local = Minecraft.getInstance().player;
        Float sizeOverride = local == null ? null
                : ModelProfileStore.playerSizeOverride(local, ActiveBridge.get());
        float size = sizeOverride != null ? sizeOverride : VrBodyConfig.playerSize;
        if (!Float.isFinite(size) || size <= 0.01f) {
            size = 1.0f;
        }
        boolean follow = VrBodyConfig.scaleEnabled;
        boolean manual = Math.abs(size - 1.0f) > 1.0e-3f;

        // v0.2.21 一次性迁移：旧版反向语义可能已把 worldScale 写进 Vivecraft 配置，
        // 首帧先重置为 1.0，再按修正后的正比语义接管/恢复。
        if (VrBodyConfig.worldScaleMigrationReset) {
            VrBodyConfig.worldScaleMigrationReset = false;
            VrWorldScale.set(1.0f);
            if (worldScaleActive) {
                savedUserWorldScale = 1.0f;
                smoothedScale = 1.0f;
            }
            YsmVrMod.LOGGER.info(
                    "[ysmvr] 旧版 worldScale 残留已重置为 1.00x（玩家大小/身高语义已修正）");
        }

        // 未跟随且倍率=1：完全交还 Vivecraft 自身设置
        if (!follow && !manual) {
            worldScaleActive = false;
            VrWorldScale.set(1.0f);
            debugLog("世界缩放恢复: 1.000");
            latestEyeHeight = Float.NaN;
            latestModelHeadY = Float.NaN;
            latestTargetWorldScale = Float.NaN;
            latestSavedUserWorldScale = Float.NaN;
            return;
        }

        double target;
        double eyeHeight = 0;
        double modelHeadY = 0;
        if (follow) {
            // 跟随模型：需要真实眼高与模型头部高度
            var roomPose = VRClientAPI.instance().getLatestRoomPose();
            if (roomPose != null) {
                var roomHead = roomPose.getBodyPartData(VRBodyPart.HEAD);
                if (roomHead != null && roomHead.getPos() != null) {
                    eyeHeight = roomHead.getPos().y;
                }
            }
            if (headModelPos == null || eyeHeight < 0.3) {
                return; // 数据未就绪：保持当前缩放
            }
            modelHeadY = headModelPos[1] / 16.0 * scaleY;
            if (modelHeadY <= 0.01) {
                return;
            }
        }

        // 首次接管：保存玩家原有 Vivecraft 世界缩放，从其值开始平滑
        if (!worldScaleActive) {
            worldScaleActive = true;
            savedUserWorldScale = VrWorldScale.get();
            smoothedScale = savedUserWorldScale;
        }

        if (follow) {
            // 玩家世界身高 = 模型头部高度 ⇒ worldScale = 模型头高 / 真实眼高
            //（Vivecraft：玩家世界身高 = 真实眼高 × worldScale）。
            // 模型头高已含"模型大小 × 玩家大小"，玩家会跟着缩放后的模型一起走。
            target = Mth.clamp(modelHeadY / eyeHeight, 0.1, 10.0);
        } else {
            // 手动：在原 Vivecraft 世界缩放基础上按倍率缩放玩家（模型已同步乘了
            // 同一倍率，因此模型相对玩家的观感大小不变）。
            // Vivecraft 是正比语义，所以是 × 倍率，不是 ÷。
            target = Mth.clamp(size, 0.1, 10.0);
        }

        smoothedScale += (target - smoothedScale) * (1.0 - Math.exp(-SMOOTH_RATE * dt));
        VrWorldScale.set((float) smoothedScale);

        latestEyeHeight = follow ? (float) eyeHeight : Float.NaN;
        latestModelHeadY = follow ? (float) modelHeadY : Float.NaN;
        latestTargetWorldScale = (float) target;
        latestSavedUserWorldScale = savedUserWorldScale;
        debugLog("世界缩放: follow=%s eye=%.2fm modelHead=%.2fm size=%.2fx target=%.3f saved=%.3f → write=%.3f",
                follow, eyeHeight, modelHeadY, size, target, savedUserWorldScale, smoothedScale);
    }

    /**
     * 强制模型大小：给"头部骨骼的根祖先"写缩放覆盖，不依赖模型自己的
     * {@code v.player_size}（内置酒狐只有部分变体的动画引用了该变量，
     * 12_little / 17_mini 等无法用 YSM 自带大小设置）。
     * <p>
     * 每帧调用（包括轮盘/攻击动画挂起期间），保证覆盖不会被
     * {@code store.clearAll()} 或动画重置冲掉；模型自带的根缩放
     * （如 {@code MRoot.scale = v.player_size}）通常是该骨骼的后代，
     * 两者层级相乘。倍率回到 1.0 时清除覆盖，交还模型动画。
     */
    private void applyForcedModelScale(LocalPlayer player, PoseOverrideStore store, Bridge bridge) {
        // v0.2.22：模型大小只由"模型大小"倍率决定；"玩家大小"只改玩家，不再
        // 缩放 YSM 模型（用户明确要求：玩家缩小不影响 ysm 模型）。
        float scale = effectiveModelScale(player, bridge);
        boolean enabled = Math.abs(scale - 1.0f) > 1.0e-3f;
        String boneName = enabled ? bridge.modelScaleBone(player) : null;
        int id = boneName == null ? -1 : bridge.resolveBone(player, boneName);
        if (!enabled || id < 0) {
            // 关闭/无法解析：清掉上一帧的覆盖（冻结循环下一帧会补回旋转）
            if (forcedScaleBoneId >= 0) {
                store.clear(forcedScaleBoneId);
                forcedScaleBoneId = -1;
            }
            latestModelScaleBone = null;
            return;
        }
        if (forcedScaleBoneId >= 0 && forcedScaleBoneId != id) {
            store.clear(forcedScaleBoneId);
        }
        forcedScaleBoneId = id;
        latestModelScaleBone = boneName;
        // 只提交缩放通道：旋转/位置继续由模型动画提供（根骨骼可能带位置/旋转动画），
        // 与 store.setFrozenRotation 的"仅旋转"互补，不会互相覆盖。
        store.setScaleOnly(id, scale, scale, scale);
    }

    /**
     * v0.2.36：模型整体位置偏移（修正斯塔·柏等模型身体偏前/偏后/偏侧）。
     * 用 {@link PoseOverrideStore#setPositionOnly} 写到模型主根骨骼：
     * 只覆盖位置，旋转/缩放仍由模型动画提供；偏移为 0 时清除覆盖。
     */
    private void applyForcedModelOffset(LocalPlayer player, PoseOverrideStore store, Bridge bridge) {
        float[] offset = ModelProfileStore.effectiveOffset(player, bridge);
        float ox = offset[0];
        float oy = offset[1];
        float oz = offset[2];
        boolean enabled = Math.abs(ox) > 1.0e-4f || Math.abs(oy) > 1.0e-4f
                || Math.abs(oz) > 1.0e-4f;
        if (enabled) {
            String boneName = bridge.modelScaleBone(player);
            int id = boneName == null ? -1 : bridge.resolveBone(player, boneName);
            if (id >= 0) {
                if (modelOffsetBoneId >= 0 && modelOffsetBoneId != id) {
                    store.clearPositionOnly(modelOffsetBoneId);
                }
                modelOffsetBoneId = id;
                store.setPositionOnly(id, ox, oy, oz);
                return;
            }
        }
        if (modelOffsetBoneId >= 0) {
            store.clearPositionOnly(modelOffsetBoneId);
            modelOffsetBoneId = -1;
        }
    }

    /**
     * v0.2.41：主/副手物品锚点局部位置偏移（每模型）。
     * 只在模型配置里对应偏移非 0 时生效；写 HandLocator 的 position-only，
     * 旋转仍由手臂姿态/动画提供，物品不会脱离手部朝向。
     */
    private void applyForcedItemOffsets(LocalPlayer player, PoseOverrideStore store, Bridge bridge) {
        applyHandItemOffset(player, store, bridge, VRBodyPart.MAIN_HAND);
        applyHandItemOffset(player, store, bridge, VRBodyPart.OFF_HAND);
    }

    private void applyHandItemOffset(LocalPlayer player, PoseOverrideStore store, Bridge bridge,
                                     VRBodyPart part) {
        int previous = part == VRBodyPart.MAIN_HAND ? mainItemOffsetBoneId : offItemOffsetBoneId;
        float[] offset = ModelProfileStore.itemOffset(player, bridge, part);
        if (offset == null) {
            if (previous >= 0) {
                store.clearPositionOnly(previous);
                if (part == VRBodyPart.MAIN_HAND) {
                    mainItemOffsetBoneId = -1;
                } else {
                    offItemOffsetBoneId = -1;
                }
            }
            return;
        }
        String anchor = null;
        var bindings = VrBindings.get(part);
        if (bindings != null) {
            for (var binding : bindings) {
                if (binding == null || binding.boneName == null) {
                    continue;
                }
                anchor = bridge.getItemAnchorBone(player, binding.boneName);
                if (anchor == null) {
                    anchor = findIkEndBone(bridge, player, binding.boneName);
                }
                if (anchor != null && bridge.resolveBone(player, anchor) >= 0) {
                    break;
                }
                anchor = null;
            }
        }
        if (anchor == null) {
            return;
        }
        int id = bridge.resolveBone(player, anchor);
        if (id < 0) {
            return;
        }
        if (previous >= 0 && previous != id) {
            store.clearPositionOnly(previous);
        }
        if (part == VRBodyPart.MAIN_HAND) {
            mainItemOffsetBoneId = id;
        } else {
            offItemOffsetBoneId = id;
        }
        store.setPositionOnly(id, offset[0], offset[1], offset[2]);
    }

    /**
     * v0.2.42：移动中锁定中间骨骼位置，消除 YSM walk/run 对 Root 等骨骼的
     * position 动画造成的"模型/头部整体来回偏移"。
     * <p>
     * 只锁头部到"强制模型偏移根骨骼"之间的中间祖先（如 Root/AllRoot），
     * 保留最顶根上的模型偏移/移动偏移写入；停止移动立即清除，交还动画。
     * 旋转动画不受影响（身体仍按 YSM 走路姿态摆动）。
     */
    private void applyMovementPositionLock(LocalPlayer player, PoseOverrideStore store, Bridge bridge,
                                           java.util.Set<String> drivenBones) {
        boolean moving = player != null && (player.walkAnimation.speed() > 0.05f
                || player.getDeltaMovement().horizontalDistanceSqr() > 2.5e-3);
        boolean holding = player != null && (!player.getMainHandItem().isEmpty()
                || !player.getOffhandItem().isEmpty());
        boolean active = moving || holding;
        if (!active) {
            if (!movementLockedBones.isEmpty()) {
                for (int id : movementLockedBones) {
                    store.clear(id);
                }
                movementLockedBones.clear();
            }
            return;
        }
        boolean pauseBody = (moving && VrBodyConfig.pauseUpperBodyOnMove)
                || (holding && VrBodyConfig.pauseUpperBodyOnHold);
        String top = bridge.modelScaleBone(player);
        String bone = VrViewConfig.resolvedHeadBone();
        if (bone == null || bone.isEmpty()) {
            bone = "Head";
        }
        java.util.Set<Integer> keep = new java.util.HashSet<>();
        int guard = 0;
        while (bone != null && guard++ < 16) {
            String parent = bridge.getParentBoneName(player, bone);
            if (parent == null || (top != null && parent.equals(top))) {
                break;
            }
            int parentId = bridge.resolveBone(player, parent);
            if (parentId >= 0 && (drivenBones == null || !drivenBones.contains(parent))) {
                if (pauseBody) {
                    store.setRotationPositionOnly(parentId, 0f, 0f, 0f, 0f, 0f, 0f);
                } else {
                    store.setPositionOnly(parentId, 0f, 0f, 0f);
                }
                keep.add(parentId);
            } else if (parentId >= 0) {
                // VR/绑定驱动的骨骼本帧保留，不要清掉 store 覆盖
                keep.add(parentId);
            }
            bone = parent;
        }
        movementLockedBones.removeIf(id -> {
            if (!keep.contains(id)) {
                store.clear(id);
                return true;
            }
            return false;
        });
        movementLockedBones.addAll(keep);
        // v0.2.61：下半身 Y 锁改为渲染前直接清零（见 MixinCustomPlayerRenderer）。


        // v0.2.50：持 TACZ 枪械时暂停手部动画（锁到静止旋转，交给 IK/VR）。
        boolean taczHeld = player != null
                && (VrBodyConfig.isTaczItem(player.getMainHandItem()) || VrBodyConfig.isTaczItem(player.getOffhandItem()));
        String rawAnim = bridge.getRawAnimationKey(player);
        String rawLower = rawAnim == null ? "" : rawAnim.toLowerCase(java.util.Locale.ROOT);
        boolean actionPause = (rawLower.contains("reload") && VrBodyConfig.pauseTaczReload)
                || ((rawLower.contains("inspect") || rawLower.contains("check")
                        || rawLower.contains("examine")) && VrBodyConfig.pauseTaczInspect)
                || (rawLower.contains("aim") && VrBodyConfig.pauseTaczAim);
        boolean taczPause = taczHeld && (VrBodyConfig.pauseTaczHandAnimations || actionPause);
        if (VrBodyConfig.debugLog) {
            debugLogKey("tacz-pause", "TACZ暂停 master=%s action=%s held=%s active=%s key=%s",
                    VrBodyConfig.pauseTaczHandAnimations, actionPause, taczHeld, taczPause,
                    rawAnim == null ? "" : rawAnim);
        }
        if (taczPause) {
            java.util.Set<Integer> handKeep = new java.util.HashSet<>();
            var bones = bridge.listBones(player);
            if (bones != null) {
                for (String name : bones) {
                    if (name == null || (drivenBones != null && drivenBones.contains(name))) {
                        continue;
                    }
                    String lower = name.toLowerCase(java.util.Locale.ROOT);
                    boolean armPart = (lower.contains("arm") || lower.contains("hand"))
                            && !lower.contains("locator") && !lower.contains("head");
                    if (!armPart) {
                        continue;
                    }
                    int id = bridge.resolveBone(player, name);
                    if (id < 0) {
                        continue;
                    }
                    float[] init = bridge.getBoneInitialRotation(player, name);
                    if (init != null) {
                        store.setFrozenRotation(id, init[0], init[1], init[2]);
                    } else {
                        store.setFrozenRotation(id, 0f, 0f, 0f);
                    }
                    handKeep.add(id);
                }
            }
            taczHandLockedBones.removeIf(id -> {
                if (!handKeep.contains(id)) {
                    store.clear(id);
                    return true;
                }
                return false;
            });
            taczHandLockedBones.addAll(handKeep);
        } else if (!taczHandLockedBones.isEmpty()) {
            for (int id : taczHandLockedBones) {
                store.clear(id);
            }
            taczHandLockedBones.clear();
        }
    }

    /** 判断物品是否来自 TACZ（命名空间含 tacz）。 */
    private static boolean isTaczItem(net.minecraft.world.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            var key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
            return key != null && key.getNamespace().toLowerCase(java.util.Locale.ROOT).contains("tacz");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 当前 YSM 模型强制缩放 = 模型大小倍率（无效值按 1）。
     * <p>
     * v0.2.22 起不再乘玩家大小倍率：玩家大小只改玩家世界身高，模型保持自己的
     * 大小；与世界米换算的调用点（原版姿态平移复制等）用这个值才能和渲染出的
     * 模型大小一致。
     */
    private float effectiveModelScale(LocalPlayer player, Bridge bridge) {
        Float override = ModelProfileStore.modelScaleOverride(player, bridge);
        float modelScale = override != null ? override : VrBodyConfig.modelScale;
        if (!Float.isFinite(modelScale) || modelScale <= 0.01f) {
            modelScale = 1.0f;
        }
        return modelScale;
    }
}
