package com.elfmcys.ysmvr;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.vivecraft.api.data.VRBodyPart;

/**
 * VR 追踪模拟器：桌面调试时替代真实 Vivecraft 硬件数据。
 * 通过 /ysmvr sim 指令控制，覆盖 {@link VivecraftPoseTracker} 的输入。
 */
public final class VrSimulator {

    private static boolean enabled;
    private static final SimulatedVRPose pose = new SimulatedVRPose();

    private VrSimulator() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * 取模拟姿态，并把坐标基准更新为玩家脚底（tracker 每帧调用）。
     * 模拟坐标为相对玩家脚底的世界轴偏移，不随转身旋转。
     */
    public static SimulatedVRPose getPose(LocalPlayer player) {
        pose.setBase(player.getPosition(Minecraft.getInstance().getFrameTime()));
        return pose;
    }

    public static void setEnabled(boolean enabled) {
        VrSimulator.enabled = enabled;
        YsmVrMod.LOGGER.info("[ysmvr] VR 模拟模式：{}", enabled ? "开启" : "关闭");
    }

    /** 设置部位位置和旋转（度）。 */
    public static void setPart(VRBodyPart part, double x, double y, double z,
                               double pitchDeg, double yawDeg, double rollDeg) {
        SimulatedBodyPartData data = pose.getOrCreate(part);
        data.setPos(x, y, z);
        data.setRotation(pitchDeg, yawDeg, rollDeg);
    }

    /** 快捷设置：头部。 */
    public static void setHead(double x, double y, double z,
                               double pitchDeg, double yawDeg, double rollDeg) {
        setPart(VRBodyPart.HEAD, x, y, z, pitchDeg, yawDeg, rollDeg);
    }

    /** 快捷设置：主手/副手。 */
    public static void setHand(boolean main, double x, double y, double z,
                               double pitchDeg, double yawDeg, double rollDeg) {
        setPart(main ? VRBodyPart.MAIN_HAND : VRBodyPart.OFF_HAND,
                x, y, z, pitchDeg, yawDeg, rollDeg);
    }

    /** 重置所有模拟数据。 */
    public static void reset() {
        pose.clear();
        enabled = false;
    }

    /** 向指令发送者回显当前模拟状态。 */
    public static void printStatus(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
                "[ysmvr] 模拟模式：" + (enabled ? "开启" : "关闭")), false);
        boolean any = false;
        for (VRBodyPart part : VRBodyPart.values()) {
            // 不能用 getOrCreate：会给未设置的部位造出全零数据，
            // tracker 读到 (0,0,0) 的腰/脚会污染身体解算
            SimulatedBodyPartData data = pose.peek(part);
            if (data == null) {
                continue;
            }
            any = true;
            Vec3 p = data.getRelativePos(); // 回显用户输入的相对偏移，而非世界坐标
            source.sendSuccess(() -> Component.literal(String.format(
                    "  %s: pos=(%.2f,%.2f,%.2f) pitch=%.1f° yaw=%.1f° roll=%.1f°",
                    part.name(), p.x, p.y, p.z,
                    Math.toDegrees(data.getPitch()),
                    Math.toDegrees(data.getYaw()),
                    Math.toDegrees(data.getRoll()))), false);
        }
        if (!any) {
            source.sendSuccess(() -> Component.literal(
                    "  （尚未设置任何部位，可用 /ysmvr sim preset 加载预设）"), false);
        }
    }
}
