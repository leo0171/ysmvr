package com.elfmcys.ysmvr;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.lang.reflect.Method;

/**
 * 第一人称隐藏头部模型：只在 Vivecraft VR 眼 pass（LEFT/RIGHT）隐藏头部骨骼组。
 * 桌面镜像（CENTER）、第三人称/其他玩家视角保持可见（隐藏是客户端本地覆盖）。
 * <p>
 * 状态不再通过 RenderLevelStageEvent 全局切换，而是在 YSM 玩家模型真实渲染入口
 * 按当前 RenderPass 实时设置（见 MixinCustomPlayerRenderer）。这样每次模型实际
 * 绘制前都是正确状态，避免一帧内多个 pass 交替 hide/show 造成头部闪烁。
 * <p>
 * RenderPass 通过反射读取 Vivecraft {@code VRRenderingAPI.getCurrentRenderPass()}，
 * 避免编译期依赖 Vivecraft 内部类。
 */
@OnlyIn(Dist.CLIENT)
public final class VrHeadHide {

    private static Method instanceMethod;
    private static Method passMethod;
    private static boolean resolved;
    private static boolean broken;
    private static boolean warned;

    private VrHeadHide() {
    }

    /**
     * 当前 pass 是否应隐藏头部（开关开启）：
     * LEFT/RIGHT = VR 双眼；CENTER = Vivecraft 桌面第一人称镜像。
     * THIRD/CAMERA 等第三人称 pass 不隐藏。
     */
    public static boolean shouldHideHeadForCurrentPass() {
        if (!VrBodyConfig.hideHeadEnabled) {
            return false;
        }
        String pass = currentPassName();
        return "LEFT".equals(pass) || "RIGHT".equals(pass) || "CENTER".equals(pass);
    }

    /** 当前是否头显双眼 pass（LEFT/RIGHT）。 */
    public static boolean isHeadsetPass() {
        String pass = currentPassName();
        return "LEFT".equals(pass) || "RIGHT".equals(pass);
    }

    /** 当前渲染 pass 名（反射 VRRenderingAPI.getCurrentRenderPass()）；不可用返回 null。 */
    private static String currentPassName() {
        if (broken) {
            return null;
        }
        try {
            if (!resolved) {
                Class<?> api = Class.forName("org.vivecraft.api.client.VRRenderingAPI");
                instanceMethod = api.getMethod("instance");
                passMethod = api.getMethod("getCurrentRenderPass");
                resolved = true;
            }
            Object inst = instanceMethod.invoke(null);
            if (inst == null) {
                return null;
            }
            Object pass = passMethod.invoke(inst);
            if (pass == null) {
                return null;
            }
            // 枚举的 toString() 可能被重写，用 name() 取声明名
            if (pass instanceof Enum<?>) {
                return ((Enum<?>) pass).name();
            }
            return String.valueOf(pass);
        } catch (Throwable t) {
            broken = true;
            if (!warned) {
                warned = true;
                YsmVrMod.LOGGER.warn("[ysmvr] 隐藏头部：渲染 pass 读取失败，功能停用", t);
            }
            return null;
        }
    }
}