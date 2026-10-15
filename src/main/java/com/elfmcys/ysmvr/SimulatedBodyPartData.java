package com.elfmcys.ysmvr;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.vivecraft.api.data.VRBodyPartData;

import java.util.function.Supplier;

/**
 * 模拟的 VR 身体部位数据，用于不戴头显调试 YSMVR 姿态映射。
 * 通过 {@link VrSimulator} 和 /ysmvr sim 指令控制。
 * <p>
 * 坐标约定：setPos 输入相对玩家脚底的偏移（米，世界轴，不随转身旋转）；
 * getPos 返回 base + 偏移，与真实 Vivecraft 的世界坐标语义一致。
 */
public final class SimulatedBodyPartData implements VRBodyPartData {

    /** 玩家脚底世界坐标（由所属 pose 注入，每帧更新）。 */
    private final Supplier<Vec3> base;
    private Vec3 pos = Vec3.ZERO;
    private Vec3 dir = new Vec3(0, 0, -1);
    private double pitch;
    private double yaw;
    private double roll;
    private final Quaternionf rotation = new Quaternionf();

    public SimulatedBodyPartData(Supplier<Vec3> base) {
        this.base = base;
    }

    public void setPos(double x, double y, double z) {
        this.pos = new Vec3(x, y, z);
    }

    /** 相对玩家脚底的原始输入偏移（状态回显用，不含 base）。 */
    public Vec3 getRelativePos() {
        return pos;
    }

    public void setRotation(double pitchDeg, double yawDeg, double rollDeg) {
        this.pitch = Math.toRadians(pitchDeg);
        this.yaw = Math.toRadians(yawDeg);
        this.roll = Math.toRadians(rollDeg);
        // 角度用 Minecraft 惯例：yaw 0 = 南(+Z)，正值向西；pitch 负值向上。
        // JOML 绕 +Y 的正旋转与 MC yaw 方向相反，四元数 yaw 取负以保持二者一致。
        this.rotation.rotationYXZ((float) -this.yaw, (float) this.pitch, (float) this.roll);
        // dir = MC 视线向量（roll 不影响前向）
        double cp = Math.cos(this.pitch);
        this.dir = new Vec3(-Math.sin(this.yaw) * cp, -Math.sin(this.pitch), Math.cos(this.yaw) * cp);
    }

    @Override
    public Vec3 getPos() {
        Vec3 b = base.get();
        return b == null ? pos : pos.add(b);
    }

    @Override
    public Vec3 getDir() {
        return dir;
    }

    @Override
    public double getPitch() {
        return pitch;
    }

    @Override
    public double getYaw() {
        return yaw;
    }

    @Override
    public double getRoll() {
        return roll;
    }

    @Override
    public Quaternionfc getRotation() {
        return rotation;
    }
}
