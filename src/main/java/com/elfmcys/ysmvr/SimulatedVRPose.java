package com.elfmcys.ysmvr;

import net.minecraft.world.phys.Vec3;
import org.vivecraft.api.data.FBTMode;
import org.vivecraft.api.data.VRBodyPart;
import org.vivecraft.api.data.VRBodyPartData;
import org.vivecraft.api.data.VRPose;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.Map;

/**
 * 模拟的 VR 姿态，通过 {@link VrSimulator} 注入到 {@link VivecraftPoseTracker}。
 * 完全绕过 Vivecraft 硬件层，用于桌面调试姿态映射。
 */
public final class SimulatedVRPose implements VRPose {

    private final Map<VRBodyPart, SimulatedBodyPartData> parts = new EnumMap<>(VRBodyPart.class);
    /** 世界坐标基准（玩家脚底），部位 getPos = base + 相对偏移。 */
    private Vec3 base = Vec3.ZERO;
    private boolean seated;
    private boolean leftHanded;
    private FBTMode fbtMode = FBTMode.WITH_JOINTS;

    public SimulatedBodyPartData getOrCreate(VRBodyPart part) {
        return parts.computeIfAbsent(part, p -> new SimulatedBodyPartData(() -> base));
    }

    /** 只读查询：不创建缺失条目（状态回显用，避免全零数据污染 tracker）。 */
    @Nullable
    public SimulatedBodyPartData peek(VRBodyPart part) {
        return parts.get(part);
    }

    /** 每帧由 {@link VrSimulator} 更新为玩家脚底世界坐标。 */
    public void setBase(Vec3 base) {
        this.base = base == null ? Vec3.ZERO : base;
    }

    @Nullable
    @Override
    public VRBodyPartData getBodyPartData(VRBodyPart vrBodyPart) {
        return parts.get(vrBodyPart);
    }

    @Override
    public boolean isSeated() {
        return seated;
    }

    @Override
    public boolean isLeftHanded() {
        return leftHanded;
    }

    @Override
    public FBTMode getFBTMode() {
        return fbtMode;
    }

    public void setSeated(boolean seated) {
        this.seated = seated;
    }

    public void setLeftHanded(boolean leftHanded) {
        this.leftHanded = leftHanded;
    }

    public void setFbtMode(FBTMode fbtMode) {
        this.fbtMode = fbtMode;
    }

    public void clear() {
        parts.clear();
    }
}
