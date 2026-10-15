package com.elfmcys.ysmvr;

import com.elfmcys.ysm.api.annotation.Side;
import com.elfmcys.ysm.api.annotation.YsmExtension;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 官方 YSM 3.0（modId=ysm）桥接：为本地玩家挂载 {@link VrPoseController} 并维护覆盖状态。
 * <p>
 * 通过 {@code @YsmExtension} 走 YSM 的兼容性检查（生成 {@code YsmBridgeCompatibilityChecker}），
 * YSM 缺失或不兼容时由 {@link ModBusSetup} 在启动期判定并跳过本类。
 */
@OnlyIn(Dist.CLIENT)
@YsmExtension(side = Side.CLIENT)
public final class YsmBridge implements Bridge {
    public static final String CONTROLLER_NAME = "ysmvr_pose";

    public static final YsmBridge INSTANCE = new YsmBridge();

    private static final Map<UUID, PoseOverrideStore> STORES = new ConcurrentHashMap<>();
    private static final Map<UUID, VrPoseController> CONTROLLERS = new ConcurrentHashMap<>();

    private YsmBridge() {
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
        var controller = CONTROLLERS.computeIfAbsent(player.getUUID(), uuid -> new VrPoseController(store));

        player.getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(animatable -> {
            // 注意：getAnimationController 内部 Map 有缓存，新挂载的 controller 不会出现在其中，
            // 因此直接遍历列表判重
            boolean present = false;
            for (var existing : animatable.getAnimationData().getAnimationControllers()) {
                if (CONTROLLER_NAME.equals(existing.getName())) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                animatable.addAnimationController(controller);
                YsmVrMod.LOGGER.info("[ysmvr] pose controller attached to {}", player.getGameProfile().getName());
            }
        });
    }

    @Override
    public void detach(UUID playerId) {
        CONTROLLERS.remove(playerId);
        STORES.remove(playerId);
    }

    /** 退出世界且玩家对象已失效时清空全部状态。 */
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
        var cap = player.getCapability(PlayerAnimatableCapabilityProvider.CAP).orElse(null);
        if (cap == null) {
            return -1;
        }
        var model = cap.getLoadedGeoModel();
        if (model == null) {
            return -1;
        }
        var pooled = com.elfmcys.ysm.geckolib3.core.molang.util.StringPool.getName(boneName);
        if (pooled == com.elfmcys.ysm.geckolib3.core.molang.util.StringPool.NONE) {
            return -1;
        }
        return model.getBoneMap().containsKey(pooled) ? pooled : -1;
    }

    @Override
    public List<String> listBones(LocalPlayer player) {
        var cap = player.getCapability(PlayerAnimatableCapabilityProvider.CAP).orElse(null);
        if (cap == null || cap.getLoadedGeoModel() == null) {
            return List.of();
        }
        var names = new ArrayList<String>();
        for (var bone : cap.getLoadedGeoModel().getSortedBones()) {
            names.add(bone.getName());
        }
        return names;
    }

    @Override
    public boolean isAttached(LocalPlayer player) {
        var cap = player.getCapability(PlayerAnimatableCapabilityProvider.CAP).orElse(null);
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
        var cap = player.getCapability(PlayerAnimatableCapabilityProvider.CAP).orElse(null);
        return cap != null && cap.getLoadedGeoModel() != null;
    }

    @Override
    public List<BoneRow> boneRows(LocalPlayer player) {
        var cap = player.getCapability(PlayerAnimatableCapabilityProvider.CAP).orElse(null);
        if (cap == null || cap.getLoadedGeoModel() == null) {
            return List.of();
        }
        var model = cap.getLoadedGeoModel();
        var store = store(player);
        var rows = new ArrayList<BoneRow>();
        for (var bone : model.getSortedBones()) {
            rows.add(new BoneRow(bone.getPooledName(),
                    bone.getName(),
                    "",
                    bone.getRotationX(),
                    bone.getRotationY(),
                    bone.getRotationZ(),
                    bone.getPositionX(),
                    bone.getPositionY(),
                    bone.getPositionZ(),
                    bone.getScaleX(),
                    bone.getScaleY(),
                    bone.getScaleZ(),
                    bone.getPivot().x,
                    bone.getPivot().y,
                    bone.getPivot().z,
                    store != null && store.get(bone.getPooledName()) != null));
        }
        return rows;
    }

    @Override
    public List<GroupRow> groups(LocalPlayer player) {
        return List.of();
    }

    @Override
    public float[] getBoneModelPosition(LocalPlayer player, String boneName) {
        var cap = player.getCapability(PlayerAnimatableCapabilityProvider.CAP).orElse(null);
        if (cap == null || cap.getLoadedGeoModel() == null) {
            return null;
        }
        var model = cap.getLoadedGeoModel();
        int pooled = com.elfmcys.ysm.geckolib3.core.molang.util.StringPool.getName(boneName);
        if (pooled == com.elfmcys.ysm.geckolib3.core.molang.util.StringPool.NONE) {
            return null;
        }
        var bone = model.getBoneMap().get(pooled);
        if (bone == null) {
            return null;
        }
        // 官方 3.0 的 AnimatedGeoBone 没有父骨骼引用，只能返回该骨骼自身
        // pivot + 实时位移的近似值（父空间，未叠加祖先链）。常数误差用视角
        // 偏移配置吸收；动态部分（动画位移增量）不受影响。
        return new float[]{
                bone.getPivot().x + bone.getPositionX(),
                bone.getPivot().y + bone.getPositionY(),
                bone.getPivot().z + bone.getPositionZ()};
    }

    @Override
    public boolean renderModelPreview(LocalPlayer player, float x, float y, float scale,
                                      float pitch, float yaw, float partialTick) {
        return false; // 官方 3.0 预览暂未实现
    }

    @Override
    public List<String> rootBoneNames(LocalPlayer player) {
        // 官方 3.0 骨骼无父引用，无法定位根骨骼 → 身高适配不支持
        return List.of();
    }

    @Override
    public String getParentBoneName(LocalPlayer player, String boneName) {
        return null; // 官方 3.0 骨骼无父引用
    }

    @Override
    public boolean setBoneHidden(LocalPlayer player, String boneName, boolean hidden) {
        var cap = player.getCapability(PlayerAnimatableCapabilityProvider.CAP).orElse(null);
        if (cap == null || cap.getLoadedGeoModel() == null) {
            return false;
        }
        int pooled = com.elfmcys.ysm.geckolib3.core.molang.util.StringPool.getName(boneName);
        if (pooled == com.elfmcys.ysm.geckolib3.core.molang.util.StringPool.NONE) {
            return false;
        }
        var bone = cap.getLoadedGeoModel().getBoneMap().get(pooled);
        if (bone == null) {
            return false;
        }
        // 双保险：骨骼隐藏标记 + 缩放 0/1 覆盖（子骨骼随父骨骼缩放继承）
        // hidden=false 时必须同时清掉“隐藏子骨骼”标记，否则恢复后会一直不可见。
        bone.setHidden(hidden, hidden);
        var store = store(player);
        if (store != null) {
            store.setScale(pooled, hidden ? 0f : 1f, hidden ? 0f : 1f, hidden ? 0f : 1f);
        }
        return true;
    }

    @Override
    public float[] rootToParentLocal(LocalPlayer player, String boneName,
                                     float x, float y, float z) {
        // 官方 3.0 骨骼无父引用，无法沿祖先链求逆 → 位置复制降级跳过
        return null;
    }

    @Override
    public float[] getBoneInitialRotation(LocalPlayer player, String boneName) {
        var cap = player.getCapability(PlayerAnimatableCapabilityProvider.CAP).orElse(null);
        if (cap == null || cap.getLoadedGeoModel() == null) {
            return null;
        }
        int pooled = com.elfmcys.ysm.geckolib3.core.molang.util.StringPool.getName(boneName);
        if (pooled == com.elfmcys.ysm.geckolib3.core.molang.util.StringPool.NONE) {
            return null;
        }
        var bone = cap.getLoadedGeoModel().getBoneMap().get(pooled);
        if (bone == null) {
            return null;
        }
        var initial = bone.getInitialRotation();
        return new float[]{initial.x, initial.y, initial.z};
    }
}
