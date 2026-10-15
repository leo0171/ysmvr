package com.elfmcys.ysmvr;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每玩家一份的骨骼覆盖状态。指令与 Vivecraft 追踪器写入，
 * {@link VrPoseController} 每帧读取并提交为骨骼动画队列。
 * <p>
 * 键为 {@code StringPool} 池化骨骼名（与 {@code AnimatedGeoModel.getBoneMap()} 的键一致）。
 * 旋转单位为弧度（模型空间），位置为模型单位（1 格 = 16），缩放为倍率。
 * <p>
 * 覆盖分两种：
 * <ul>
 * <li><b>完整覆盖</b>（{@link #setRotation}/{@link #setPosition}/{@link #setScale}）：
 * 旋转/位置/缩放三个通道都提交，骨骼完全由 VR/指令接管；</li>
 * <li><b>仅旋转冻结</b>（{@link #setFrozenRotation}）：只提交旋转（绑定姿态），
 * 位置/缩放通道留给 YSM 模型动画。必须这样——酒狐等模型用
 * {@code FOX.scale = v.roaming.a}（0 隐藏）来藏第二形态/坐骑，完整覆盖会把
 * 隐藏件强制 scale=1 导致待机时"坐骑"突然出现。</li>
 * </ul>
 */
public final class PoseOverrideStore {

    public record BonePose(float rx, float ry, float rz,
                           float px, float py, float pz,
                           float sx, float sy, float sz) {
        public static BonePose rotation(float rx, float ry, float rz) {
            return new BonePose(rx, ry, rz, 0, 0, 0, 1, 1, 1);
        }
    }

    private final Map<Integer, BonePose> poses = new ConcurrentHashMap<>();

    /** 仅旋转覆盖的骨骼（冻结用）；位置/缩放交还模型动画。 */
    private final Set<Integer> rotationOnly = ConcurrentHashMap.newKeySet();

    /**
     * 仅缩放覆盖的骨骼（强制模型大小用）：只提交缩放通道，旋转/位置继续由
     * 模型动画提供——根骨骼可能带位置/旋转动画，不能被覆盖成 0。
     */
    private final Set<Integer> scaleOnly = ConcurrentHashMap.newKeySet();

    /**
     * 仅位置覆盖的骨骼（v0.2.28 手持物品对齐用）：只提交位置通道，旋转/缩放
     * 继续由模型动画提供——锚点骨骼（HandLocator）的旋转必须保留动画/手臂姿态，
     * 否则物品会失去朝向；只有位置需要每帧对齐。
     */
    private final Set<Integer> positionOnly = ConcurrentHashMap.newKeySet();

    /**
     * 仅旋转+位置覆盖（v0.2.41 头部动画位置锁定用）：提交旋转和位置，缩放留给
     * 模型动画（不能覆盖 AllHead 的 hide scale=0，也不能把模型自带缩放压回 1）。
     */
    private final Set<Integer> rotationPositionOnly = ConcurrentHashMap.newKeySet();

    /**
     * 显式设置过位置/缩放的骨骼（如隐藏头部的 scale=0）：这些骨骼即使被冻结也
     * 保持完整覆盖，否则头部隐藏等渲染层覆盖会被"仅旋转"放掉。
     */
    private final Set<Integer> fullChannels = ConcurrentHashMap.newKeySet();

    public void setRotation(int pooledName, float rx, float ry, float rz) {
        poses.merge(pooledName, BonePose.rotation(rx, ry, rz),
                (old, value) -> new BonePose(rx, ry, rz, old.px(), old.py(), old.pz(),
                        old.sx(), old.sy(), old.sz()));
        rotationOnly.remove(pooledName);
        scaleOnly.remove(pooledName);
        positionOnly.remove(pooledName);
        rotationPositionOnly.remove(pooledName);
    }

    /**
     * 动画冻结：只写旋转（绑定姿态），位置/缩放继续由模型动画提供。
     * 若该骨骼已有显式位置/缩放覆盖（如隐藏头部），则退化为完整覆盖以保住它；
     * 若该骨骼是"仅缩放"（强制模型大小），保持仅缩放不变。
     */
    public void setFrozenRotation(int pooledName, float rx, float ry, float rz) {
        poses.merge(pooledName, BonePose.rotation(rx, ry, rz),
                (old, value) -> new BonePose(rx, ry, rz, old.px(), old.py(), old.pz(),
                        old.sx(), old.sy(), old.sz()));
        if (fullChannels.contains(pooledName)) {
            rotationOnly.remove(pooledName);
        } else if (!scaleOnly.contains(pooledName)) {
            rotationOnly.add(pooledName);
        }
    }

    public void setPosition(int pooledName, float px, float py, float pz) {
        poses.merge(pooledName, new BonePose(0, 0, 0, px, py, pz, 1, 1, 1),
                (old, value) -> new BonePose(old.rx(), old.ry(), old.rz(), px, py, pz,
                        old.sx(), old.sy(), old.sz()));
        fullChannels.add(pooledName);
        rotationOnly.remove(pooledName);
        scaleOnly.remove(pooledName);
        positionOnly.remove(pooledName);
        rotationPositionOnly.remove(pooledName);
    }

    /**
     * 仅旋转+位置覆盖：头部追踪用。位置写 0 抵消 walk/run 动画对 Head 的位置偏移，
     * 缩放留给动画（AllHead 隐藏 scale=0 仍生效）。
     */
    public void setRotationPositionOnly(int pooledName,
                                        float rx, float ry, float rz,
                                        float px, float py, float pz) {
        poses.merge(pooledName, new BonePose(rx, ry, rz, px, py, pz, 1, 1, 1),
                (old, value) -> new BonePose(rx, ry, rz, px, py, pz,
                        old.sx(), old.sy(), old.sz()));
        rotationPositionOnly.add(pooledName);
        fullChannels.add(pooledName);
        rotationOnly.remove(pooledName);
        scaleOnly.remove(pooledName);
        positionOnly.remove(pooledName);
    }

    /** 该骨骼是否仅旋转+位置（缩放留给动画）。 */
    public boolean isRotationPositionOnly(int pooledName) {
        return rotationPositionOnly.contains(pooledName);
    }

    public void setScale(int pooledName, float sx, float sy, float sz) {
        poses.merge(pooledName, new BonePose(0, 0, 0, 0, 0, 0, sx, sy, sz),
                (old, value) -> new BonePose(old.rx(), old.ry(), old.rz(),
                        old.px(), old.py(), old.pz(), sx, sy, sz));
        fullChannels.add(pooledName);
        rotationOnly.remove(pooledName);
        scaleOnly.remove(pooledName);
        positionOnly.remove(pooledName);
        rotationPositionOnly.remove(pooledName);
    }

    /**
     * 仅缩放覆盖（强制模型大小）：只提交缩放通道，旋转/位置留给模型动画。
     * 与 {@link #setScale} 的完整覆盖不同，不会把根骨骼的位置/旋转覆盖成 0。
     */
    public void setScaleOnly(int pooledName, float sx, float sy, float sz) {
        poses.merge(pooledName, new BonePose(0, 0, 0, 0, 0, 0, sx, sy, sz),
                (old, value) -> new BonePose(old.rx(), old.ry(), old.rz(),
                        old.px(), old.py(), old.pz(), sx, sy, sz));
        scaleOnly.add(pooledName);
        rotationOnly.remove(pooledName);
        rotationPositionOnly.remove(pooledName);
    }

    /**
     * 仅位置覆盖：只提交位置通道，旋转/缩放留给模型动画（手持物品锚点对齐用）。
     * 位置值语义与 {@link #setPosition} 相同（PoseOverrideStore R 系，模型单位）。
     */
    public void setPositionOnly(int pooledName, float px, float py, float pz) {
        poses.merge(pooledName, new BonePose(0, 0, 0, px, py, pz, 1, 1, 1),
                (old, value) -> new BonePose(old.rx(), old.ry(), old.rz(), px, py, pz,
                        old.sx(), old.sy(), old.sz()));
        positionOnly.add(pooledName);
        fullChannels.add(pooledName);
        rotationOnly.remove(pooledName);
        rotationPositionOnly.remove(pooledName);
    }

    /** 清除仅位置覆盖（锚点对齐关闭/追踪数据缺失时）。 */
    public void clearPositionOnly(int pooledName) {
        positionOnly.remove(pooledName);
        fullChannels.remove(pooledName);
        var p = poses.get(pooledName);
        if (p != null && !rotationOnly.contains(pooledName) && !scaleOnly.contains(pooledName)
                && !rotationPositionOnly.contains(pooledName)
                && p.rx() == 0f && p.ry() == 0f && p.rz() == 0f
                && p.sx() == 1f && p.sy() == 1f && p.sz() == 1f) {
            poses.remove(pooledName);
        }
    }

    /** 该骨骼是否只提交旋转（位置/缩放留给模型动画）。 */
    public boolean isRotationOnly(int pooledName) {
        return rotationOnly.contains(pooledName);
    }

    /** 该骨骼是否只提交位置（旋转/缩放留给模型动画）。 */
    public boolean isPositionOnly(int pooledName) {
        return positionOnly.contains(pooledName);
    }

    /** 该骨骼是否只提交缩放（旋转/位置留给模型动画）。 */
    public boolean isScaleOnly(int pooledName) {
        return scaleOnly.contains(pooledName);
    }

    public BonePose get(int pooledName) {
        return poses.get(pooledName);
    }

    public void clear(int pooledName) {
        poses.remove(pooledName);
        rotationOnly.remove(pooledName);
        scaleOnly.remove(pooledName);
        positionOnly.remove(pooledName);
        rotationPositionOnly.remove(pooledName);
        fullChannels.remove(pooledName);
    }

    public void clearAll() {
        poses.clear();
        rotationOnly.clear();
        scaleOnly.clear();
        positionOnly.clear();
        rotationPositionOnly.clear();
        fullChannels.clear();
    }

    /**
     * 躺下 Root 旋转补偿：把除 root 以外所有已覆盖骨骼的本地旋转
     * 左乘 inverse(root)，使这些骨骼的世界朝向保持不变（root 旋转主要
     * 影响未被 store 驱动的身体/动画骨骼）。
     * 仅修改旋转通道，保留位置/缩放和 rotation-only 等标记。
     */
    public void counterRotateAll(java.util.Set<Integer> exclude, org.joml.Matrix3fc invRoot) {
        if (invRoot == null || exclude == null) {
            return;
        }
        for (var entry : poses.entrySet()) {
            int id = entry.getKey();
            if (exclude.contains(id) || positionOnly.contains(id) || scaleOnly.contains(id)) {
                continue;
            }
            BonePose p = entry.getValue();
            org.joml.Matrix3f m = new org.joml.Matrix3f()
                    .rotateZ(p.rz()).rotateY(p.ry()).rotateX(p.rx());
            m = new org.joml.Matrix3f(invRoot).mul(m);
            org.joml.Vector3f e = m.getEulerAnglesZYX(new org.joml.Vector3f());
            poses.put(id, new BonePose(e.x, e.y, e.z,
                    p.px(), p.py(), p.pz(), p.sx(), p.sy(), p.sz()));
        }
    }
    /** 返回当前覆盖的只读视图（Map 本身会随写入变化，遍历安全）。 */
    public Map<Integer, BonePose> overrides() {
        return poses;
    }
}
