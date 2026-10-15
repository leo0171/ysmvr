package com.elfmcys.ysmvr;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.vivecraft.api.data.VRBodyPart;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 姿势校准：玩家按目标姿势保持不动后调用 {@link #request()}，
 * 捕获当前所有被驱动骨骼的实时旋转（弧度，模型空间），取反并入
 * {@link VrBindings} 的旋转偏移——消除追踪器安装角度、模型比例等造成的姿势偏差。
 * <p>
 * v0.2.23 起：
 * <ul>
 * <li>多帧采集：默认连续 8 个渲染帧，每帧取该骨骼最后写入值，用四元数
 * 累加平均，消除单帧抖动；</li>
 * <li>校准结果按"覆盖"而不是"累加"写入绑定偏移（旧版 {@code +=} 反复校准会
 * 累积成 -900° 之类的值，导致模型内旋/翻转），并归一化到 ±180°；</li>
 * <li>{@link #requestAuto()}：立即对当前姿势做一次自动校准（GUI"自动校准"按钮），
 * 适合初次默认校准——玩家自然站立、手臂下垂时点一下即可。</li>
 * </ul>
 * 语义：在什么姿势校准，之后回到该姿势时模型就回到绑定姿态。
 * 流程（tick 与渲染帧协作）：请求 → 短倒计时 → 连续若干渲染帧采集 →
 * 下一 tick 平均/取反写入绑定偏移并保存。
 * 指令：/ysmvr calibrate [auto|gui]，校准 GUI 有"自动校准"按钮。
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = YsmVrMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class VrCalibration {

    /** 请求后等待玩家摆姿势的 tick 数（约 1.5 秒，/ysmvr calibrate 旧流程）。 */
    private static final int SETTLE_TICKS = 30;
    /** 自动校准的等待 tick 数（立即开始，当前姿势）。 */
    private static final int AUTO_SETTLE_TICKS = 1;
    /** 默认采集帧数（四元数平均，抑制单帧抖动）。 */
    private static final int CAPTURE_FRAMES = 8;
    /** 自动校准采集帧数（稍长一点，保证稳定）。 */
    private static final int AUTO_CAPTURE_FRAMES = 12;
    /** 采集帧的最长等待 tick 数（约 10 秒）。 */
    private static final int TIMEOUT_TICKS = 200;

    /** 状态机：-1 空闲；>0 摆姿势倒计时；-2 等待/采集中；-3 采集完成待应用。 */
    private static int pending = -1;
    private static int waits;
    private static boolean capturing;
    private static boolean lastSuccess;
    private static int framesRemaining;
    /** 本帧最后一次写入值（骨骼名 → {rx, ry, rz} 弧度）。 */
    private static final Map<String, float[]> FRAME = new HashMap<>();
    /** 多帧四元数累加。 */
    private static final Map<String, QuatAcc> ACCUM = new LinkedHashMap<>();

    /** 单骨骼的多帧四元数累加（带半球对齐，避免 q 与 -q 相互抵消）。 */
    private static final class QuatAcc {
        float x, y, z, w = 1f;
        int count;

        void add(Quaternionf q) {
            if (count > 0 && (x * q.x + y * q.y + z * q.z + w * q.w) < 0f) {
                x -= q.x;
                y -= q.y;
                z -= q.z;
                w -= q.w;
            } else {
                x += q.x;
                y += q.y;
                z += q.z;
                w += q.w;
            }
            count++;
        }

        Quaternionf average() {
            var q = new Quaternionf(x, y, z, w);
            if (q.lengthSquared() < 1.0e-12f) {
                return new Quaternionf();
            }
            return q.normalize();
        }
    }

    private VrCalibration() {
    }

    /** 请求一次姿势校准（先按目标姿势摆好并保持；默认约 1.5 秒摆姿势时间）。 */
    public static synchronized void request() {
        autoRequested = false;
        request(SETTLE_TICKS, CAPTURE_FRAMES);
    }

    /**
     * 请求一次姿势校准，指定摆姿势的 tick 数。
     *
     * @param settleTicks 等待玩家摆姿势的 tick 数（≥1；GUI 已自行倒计时时传 1）
     */
    public static synchronized void request(int settleTicks) {
        autoRequested = false;
        request(settleTicks, CAPTURE_FRAMES);
    }

    /**
     * 立即开始采集（GUI 已在界面上完成 3 秒倒计时后调用）：下一 tick 进入
     * 多帧采集，之后与 {@link #request()} 流程完全一致。
     */
    public static synchronized void requestNow() {
        autoRequested = false;
        request(1, CAPTURE_FRAMES);
    }

    /**
     * 自动校准（GUI"自动校准"按钮 / /ysmvr calibrate auto）：不等待摆姿势，
     * 下一 tick 立即对当前姿势连续采集多帧并平均，适合初次默认校准。
     */
    public static synchronized void requestAuto() {
        autoRequested = true;
        request(AUTO_SETTLE_TICKS, AUTO_CAPTURE_FRAMES);
    }

    private static synchronized void request(int settleTicks, int frames) {
        pending = Math.max(1, settleTicks);
        waits = 0;
        capturing = false;
        framesRemaining = Math.max(1, frames);
        lastSuccess = false;
        FRAME.clear();
        ACCUM.clear();
    }

    /** 是否处于校准流程中。 */
    public static synchronized boolean isPending() {
        return pending != -1;
    }

    /** 上一次校准流程是否成功应用（GUI 提示用）。 */
    public static synchronized boolean lastSuccess() {
        return lastSuccess;
    }

    /** 当前是否处于"自动校准"状态（GUI 文案用）。 */
    public static synchronized boolean isAuto() {
        return autoRequested;
    }

    private static boolean autoRequested;

    /** tracker 侧：本帧是否采集。 */
    static synchronized boolean isCapturing() {
        return capturing;
    }

    /** tracker 侧：记录一条实际写入骨骼的旋转（弧度）；一帧内同骨骼取最后值。 */
    static synchronized void record(String boneName, float rx, float ry, float rz) {
        if (!capturing || boneName == null || boneName.isEmpty()) {
            return;
        }
        // 防止 NaN/Infinity 进入校准集合并最终被 Gson 保存成非法 JSON
        if (!Float.isFinite(rx) || !Float.isFinite(ry) || !Float.isFinite(rz)) {
            return;
        }
        FRAME.put(boneName, new float[]{rx, ry, rz});
    }

    /** tracker 侧：渲染帧结束；累计本帧数据，采满预设帧数后转入待应用状态。 */
    static synchronized void endFrameCapture() {
        if (!capturing || FRAME.isEmpty()) {
            return;
        }
        for (var e : FRAME.entrySet()) {
            var v = e.getValue();
            // store 是渲染器 Rz·Ry·Rx 约定，四元数按同一约定构建
            var m = new Matrix3f().rotateZ(v[2]).rotateY(v[1]).rotateX(v[0]);
            var q = new Quaternionf().setFromNormalized(m);
            ACCUM.computeIfAbsent(e.getKey(), k -> new QuatAcc()).add(q);
        }
        FRAME.clear();
        if (--framesRemaining <= 0) {
            capturing = false;
            pending = -3;
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        synchronized (VrCalibration.class) {
            switch (pending) {
                case -2 -> {
                    // 等待 tracker 的采集帧（VR 未激活时 tracker 不运行 → 超时）
                    if (++waits > TIMEOUT_TICKS) {
                        finish("校准超时：没有捕获到追踪数据（VR 追踪是否开启？）");
                    }
                }
                case -3 -> apply();
                default -> {
                    if (pending > 0 && --pending == 0) {
                        FRAME.clear();
                        ACCUM.clear();
                        waits = 0;
                        capturing = true;
                        pending = -2;
                    }
                }
            }
        }
    }

    /**
     * 取反写入绑定偏移。v0.2.23 起对每根捕获骨骼按"覆盖 + 归一化"写入：
     * {@code binding.rot = wrap360(-captured)}，不再 {@code +=}——否则重复校准会
     * 把偏移累加到数百上千度，欧拉空间叠加后表现为手臂内旋/翻转。
     */
    private static void apply() {
        pending = -1;
        if (ACCUM.isEmpty()) {
            finish("校准失败：本帧没有骨骼被驱动");
            return;
        }
        int n = 0;
        for (var entry : ACCUM.entrySet()) {
            Quaternionf avg = entry.getValue().average();
            Vector3f e = new Matrix3f().rotation(avg).getEulerAnglesZYX(new Vector3f());
            float dx = wrap180((float) -Math.toDegrees(e.x));
            float dy = wrap180((float) -Math.toDegrees(e.y));
            float dz = wrap180((float) -Math.toDegrees(e.z));
            for (var part : VRBodyPart.values()) {
                var list = VrBindings.get(part);
                if (list == null) {
                    continue;
                }
                for (var b : list) {
                    if (!entry.getKey().equals(b.boneName)) {
                        continue;
                    }
                    b.rotX = dx;
                    b.rotY = dy;
                    b.rotZ = dz;
                    n++;
                }
            }
        }
        VrBindings.save();
        lastSuccess = n > 0;
        var mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(
                    "[ysmvr] " + (autoRequested ? "自动" : "姿势") + "校准完成：已更新 "
                            + n + " 条绑定偏移（覆盖写入）"), false);
        }
        ACCUM.clear();
        FRAME.clear();
        autoRequested = false;
    }

    private static float wrap180(float deg) {
        float d = deg % 360f;
        if (d > 180f) {
            d -= 360f;
        }
        if (d < -180f) {
            d += 360f;
        }
        return d;
    }

    private static void finish(String message) {
        pending = -1;
        capturing = false;
        lastSuccess = false;
        autoRequested = false;
        ACCUM.clear();
        FRAME.clear();
        var mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[ysmvr] " + message), false);
        }
    }
}
