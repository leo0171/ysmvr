package com.elfmcys.ysmvr;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.vivecraft.api.data.VRBodyPart;
import org.vivecraft.api.data.VRPose;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 原始 SteamVR 追踪器回退：Vivecraft 的 FBT 校准只会给追踪器分配"腰/脚"等角色
 * （例如 6 点 SlimeVR 套件：髋→腰、小腿→脚，大腿/胸口追踪器永远不被分配），
 * 这些未分配角色的追踪器不会出现在 API 姿势里，但 {@code MCOpenVR.getTrackers()}
 * 会把所有未分配的通用追踪器（role = -1）附带返回。
 * <p>
 * 本类通过反射读取这些原始追踪器的世界矩阵，按"相对腰部的高度"自识别角色：
 * 腰部以下的 2 个 = 大腿（→膝部位）；4 个 = 小腿×2（→脚部位）+ 大腿×2（→膝部位）。
 * 左右按身体朝向的横向分量区分，横向不可区分（并腿、蹲坐）时沿用上一帧分类。
 * 只补充 Vivecraft 姿势里<b>缺失数据</b>的部位，绝不覆盖官方已提供的数据。
 * <p>
 * 旋转换算与 {@code VRBodyPartDataImpl} 完全一致：dir = 设备前向（OpenVR −Z）
 * 经 rotY(−bodyYaw) 到世界系 → pitch/yaw；roll 取世界矩阵四元数的 roll。
 * 追踪器安装朝向的固定偏差用绑定 GUI 的旋转偏移补偿。
 */
public final class VrRawTrackers {

    /** 单个原始追踪器：SteamVR 设备索引 + 世界系矩阵（含站立原点偏移，与 API 同空间）。 */
    private static final class RawTracker {
        final int deviceIndex;
        final Matrix4f matrix;

        RawTracker(int deviceIndex, Matrix4fc matrix) {
            this.deviceIndex = deviceIndex;
            this.matrix = new Matrix4f(matrix);
        }
    }

    // ── 反射缓存：provider.getTrackers() → List<Triple<DeviceSource, Integer, Matrix4fc>> ──

    private static Class<?> providerClass;
    private static Method getTrackersMethod;
    private static Method tripleLeft;
    private static Method tripleMiddle;
    private static Method tripleRight;
    private static Field sourceField;
    private static Field indexField;
    private static boolean broken;
    private static boolean warned;

    /** 最近一次分类（设备索引 → 部位），横向不可区分时保持稳定。 */
    private static final Map<Integer, VRBodyPart> LAST_ROLE = new HashMap<>();

    /** 捕获结果缓存（约一帧），GUI 与 tracker 同帧读取时避免重复反射。 */
    private static long lastCaptureNanos;
    private static List<RawTracker> captureCache;

    private VrRawTrackers() {
    }

    /**
     * 分类当前帧的原始追踪器并换算成旋转，返回 部位 → {pitch, yaw, roll}（弧度）。
     * 仅补充 Vivecraft 姿势缺失数据、且已配置绑定的部位；无可用追踪器时返回空表。
     * <p>
     * 前置条件：{@code pose} 的腰部数据必须存在（分类的基准），即用户至少完成了
     * Vivecraft 的基础 FBT 校准（腰+脚）。
     */
    public static Map<VRBodyPart, float[]> classify(VRPose pose, float bodyYaw) {
        if (pose == null) {
            return Collections.emptyMap();
        }
        var waist = pose.getBodyPartData(VRBodyPart.WAIST);
        if (waist == null) {
            return Collections.emptyMap();
        }

        List<RawTracker> raws = capture();
        if (raws.isEmpty()) {
            return Collections.emptyMap();
        }

        var waistPos = waist.getPos();
        // 身体朝向的右向量（世界系）：yaw=0 朝 +z 时右手为 −x
        double cos = Math.cos(bodyYaw);
        double rightX = -cos;
        double rightZ = Math.sin(bodyYaw);

        // 腰部以下的原始追踪器（水平距离近身体轴），按高度从低到高
        List<RawTracker> below = new ArrayList<>(4);
        for (var t : raws) {
            double dx = t.matrix.m30() - waistPos.x;
            double dy = t.matrix.m31() - waistPos.y;
            double dz = t.matrix.m32() - waistPos.z;
            if (dy < -0.03 && Math.sqrt(dx * dx + dz * dz) < 0.45) {
                below.add(t);
            }
        }
        below.sort((a, b) -> Float.compare(a.matrix.m31(), b.matrix.m31()));

        var result = new EnumMap<VRBodyPart, float[]>(VRBodyPart.class);
        if (below.size() == 2) {
            // 平均高度决定是"大腿"还是"小腿"（后者见于校准把大腿分给脚部位、小腿反而未分配的情形）
            double avgDy = (below.get(0).matrix.m31() + below.get(1).matrix.m31()) / 2.0 - waistPos.y;
            if (avgDy < -0.45) {
                assign(below, 0, VRBodyPart.LEFT_FOOT, VRBodyPart.RIGHT_FOOT,
                        waistPos, rightX, rightZ, pose, bodyYaw, result);
            } else {
                assign(below, 0, VRBodyPart.LEFT_KNEE, VRBodyPart.RIGHT_KNEE,
                        waistPos, rightX, rightZ, pose, bodyYaw, result);
            }
        } else if (below.size() == 4) {
            // 下方两个 = 小腿（→脚部位），上方两个 = 大腿（→膝部位）
            assign(below, 0, VRBodyPart.LEFT_FOOT, VRBodyPart.RIGHT_FOOT,
                    waistPos, rightX, rightZ, pose, bodyYaw, result);
            assign(below, 2, VRBodyPart.LEFT_KNEE, VRBodyPart.RIGHT_KNEE,
                    waistPos, rightX, rightZ, pose, bodyYaw, result);
        }
        return result;
    }

    /** 两个追踪器分左右并换算旋转，写入 result（数据缺失且有绑定的部位才写）。 */
    private static void assign(List<RawTracker> below, int from,
                               VRBodyPart leftPart, VRBodyPart rightPart,
                               Vec3 waistPos, double rightX, double rightZ,
                               VRPose pose, float bodyYaw,
                               Map<VRBodyPart, float[]> result) {
        RawTracker a = below.get(from);
        RawTracker b = below.get(from + 1);
        double latA = (a.matrix.m30() - waistPos.x) * rightX + (a.matrix.m32() - waistPos.z) * rightZ;
        double latB = (b.matrix.m30() - waistPos.x) * rightX + (b.matrix.m32() - waistPos.z) * rightZ;

        RawTracker rightT;
        RawTracker leftT;
        if (Math.abs(latA) >= 0.02 && Math.abs(latB) >= 0.02 && Math.signum(latA) != Math.signum(latB)) {
            rightT = latA > 0 ? a : b;
            leftT = latA > 0 ? b : a;
        } else {
            // 并腿/蹲坐等横向不可区分：沿用上一帧分类
            var lastA = LAST_ROLE.get(a.deviceIndex);
            var lastB = LAST_ROLE.get(b.deviceIndex);
            if ((lastA == rightPart && lastB == leftPart) || (lastA == leftPart && lastB == rightPart)) {
                rightT = lastA == rightPart ? a : b;
                leftT = lastA == rightPart ? b : a;
            } else {
                return;
            }
        }
        LAST_ROLE.put(rightT.deviceIndex, rightPart);
        LAST_ROLE.put(leftT.deviceIndex, leftPart);

        if (pose.getBodyPartData(rightPart) == null && VrBindings.get(rightPart) != null) {
            result.put(rightPart, euler(rightT, bodyYaw));
        }
        if (pose.getBodyPartData(leftPart) == null && VrBindings.get(leftPart) != null) {
            result.put(leftPart, euler(leftT, bodyYaw));
        }
    }

    /** 原始矩阵 → {pitch, yaw, roll}（与 VRBodyPartDataImpl.getPitch/getYaw/getRoll 同约定）。 */
    private static float[] euler(RawTracker t, float bodyYaw) {
        // 世界矩阵 = rotY(−bodyYaw)·room（与 VRDevicePose.getMatrix 一致，rotation_radians = −bodyYaw）
        var world = new Matrix4f().rotationY(-bodyYaw).mul(t.matrix);
        var dir = world.transformDirection(new Vector3f(0f, 0f, -1f));
        float pitch = (float) Math.asin(dir.y / dir.length());
        float yaw = (float) Math.atan2(-dir.x, dir.z);
        var q = new Quaternionf();
        q.setFromUnnormalized(world);
        float roll = (float) -Math.atan2(2.0 * (q.x * q.y + q.w * q.z),
                q.w * q.w - q.x * q.x + q.y * q.y - q.z * q.z);
        return new float[]{pitch, yaw, roll};
    }

    // ── 反射捕获 ──

    private static List<RawTracker> capture() {
        long now = System.nanoTime();
        if (captureCache != null && now - lastCaptureNanos < 16_000_000L) {
            return captureCache;
        }
        List<RawTracker> raws = broken ? Collections.emptyList() : doCapture();
        lastCaptureNanos = now;
        captureCache = raws;
        return raws;
    }

    private static List<RawTracker> doCapture() {
        try {
            Object provider = VrViewHook.currentProvider();
            if (provider == null) {
                return Collections.emptyList();
            }
            Class<?> cls = provider.getClass();
            if (cls != providerClass) {
                // 提供器类固定，只需解析一次；Triple 为 commons-lang3，DeviceSource 字段公开
                providerClass = cls;
                getTrackersMethod = cls.getMethod("getTrackers");
                Class<?> triple = Class.forName("org.apache.commons.lang3.tuple.Triple");
                tripleLeft = triple.getMethod("getLeft");
                tripleMiddle = triple.getMethod("getMiddle");
                tripleRight = triple.getMethod("getRight");
                Class<?> source = Class.forName("org.vivecraft.client_vr.provider.DeviceSource");
                sourceField = source.getField("source");
                indexField = source.getField("deviceIndex");
            }
            Object result = getTrackersMethod.invoke(provider);
            if (!(result instanceof List<?> list)) {
                return Collections.emptyList();
            }
            var raws = new ArrayList<RawTracker>(4);
            for (var item : list) {
                Integer role = (Integer) tripleMiddle.invoke(item);
                if (role != null && role >= 0) {
                    continue; // 已分配角色，官方姿势已包含
                }
                Object deviceSource = tripleLeft.invoke(item);
                Object source = sourceField.get(deviceSource);
                if (!"OPENVR".equals(String.valueOf(source))) {
                    continue; // 跳过 OSC 等其它来源
                }
                Object matrix = tripleRight.invoke(item);
                if (!(matrix instanceof Matrix4fc m)) {
                    continue;
                }
                raws.add(new RawTracker((Integer) indexField.get(deviceSource), m));
            }
            return raws;
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                YsmVrMod.LOGGER.warn("[ysmvr] 原始追踪器读取失败，自识别回退停用", t);
            }
            broken = true;
            return Collections.emptyList();
        }
    }
}
