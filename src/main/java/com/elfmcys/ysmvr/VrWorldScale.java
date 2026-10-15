package com.elfmcys.ysmvr;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 通过反射读写 Vivecraft 的"世界缩放"设置（{@code VRSettings.worldScale}）：
 * {@code VRPlayer.preTick} 每 tick 从该设置读取并应用到 VR 玩家（最终钳制
 * 0.025–100），与 Vivecraft 自己的世界缩放滑块共用同一个旋钮。写入后
 * 下一 tick 生效——Vivecraft 是正比语义：worldScale &gt; 1 玩家变大、
 * &lt; 1 玩家变小，玩家的世界身高 = 真实身高 × worldScale（见 en_us 设置
 * 提示与 {@code VRPlayerRenderer} 的 scale 计算）。
 * <p>
 * 反射路径：{@code ClientDataHolderVR.getInstance().vrSettings.worldScale}
 * （字段均为 public，vivecraft 类不混淆；这里只按名字反射，不依赖
 * vivecraft 主 jar 编译）。服务器通过 overrides 覆盖该设置时写入不生效
 * （与 Vivecraft 自身语义一致，玩家身高适配在这些服务器上自动无效）。
 */
final class VrWorldScale {

    private static final Method GET_INSTANCE;
    private static final Field SETTINGS_FIELD;
    private static final Field SCALE_FIELD;

    /** 解析成功后的 VRSettings 单例（整个会话不变），避免每帧反射取链。 */
    private static Object cachedSettings;

    static {
        Method getInstance = null;
        Field settings = null;
        Field scale = null;
        try {
            Class<?> holder = Class.forName("org.vivecraft.client_vr.ClientDataHolderVR");
            getInstance = holder.getMethod("getInstance");
            settings = holder.getField("vrSettings");
            scale = Class.forName("org.vivecraft.client_vr.settings.VRSettings")
                    .getField("worldScale");
        } catch (ReflectiveOperationException e) {
            YsmVrMod.LOGGER.warn(
                    "[ysmvr] 找不到 Vivecraft 世界缩放设置（版本不匹配？），玩家身高适配不可用", e);
        }
        GET_INSTANCE = getInstance;
        SETTINGS_FIELD = settings;
        SCALE_FIELD = scale;
    }

    private VrWorldScale() {
    }

    /** 是否可用（Vivecraft 版本匹配）。 */
    static boolean available() {
        return GET_INSTANCE != null && SETTINGS_FIELD != null && SCALE_FIELD != null;
    }

    /** 读取当前设置值（玩家原有值/其它模组写入）；不可用时返回 1。 */
    static float get() {
        if (!available() || !resolveSettings()) {
            return 1f;
        }
        try {
            return (Float) SCALE_FIELD.get(cachedSettings);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 1f;
        }
    }

    /** 写入设置值（Vivecraft 下一 tick 生效）。 */
    static void set(float value) {
        if (!available() || !resolveSettings()) {
            return;
        }
        try {
            SCALE_FIELD.set(cachedSettings, value);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 不可用/字段被覆盖时静默忽略
        }
    }

    private static boolean resolveSettings() {
        if (cachedSettings != null) {
            return true;
        }
        try {
            cachedSettings = SETTINGS_FIELD.get(GET_INSTANCE.invoke(null));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
        return cachedSettings != null;
    }
}
