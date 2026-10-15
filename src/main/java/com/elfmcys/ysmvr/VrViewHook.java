package com.elfmcys.ysmvr;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 反射平移 Vivecraft 每帧渲染用 VRData 的 origin（视角绑定注入点）。
 * <p>
 * 背景：VR 眼睛世界坐标 = origin + rotateY(rotation)·(hmd.pos·worldScale)。
 * VRPlayer.preRender 每帧都新建 vrdata_world_render 实例（在 PER_FRAME
 * tracker 的 activeProcess 之前），本帧相机随后读取该实例——因此每帧修改
 * 当前实例的 origin 只影响本帧，不存在跨帧累积/反馈问题。
 * <p>
 * 整条链均为 public 字段，无需绕过访问权限：
 * ClientDataHolderVR.getInstance().vrPlayer.vrdata_world_render.origin。
 * 任何一步失败（版本不兼容等）则永久停用本注入，只告警一次。
 */
final class VrViewHook {

    private static Method getInstance;
    private static Field vrPlayerField;
    private static Field renderDataField;
    private static Field originField;
    private static Field vrProviderField;
    private static boolean resolved;
    private static boolean broken;
    private static boolean warned;

    private VrViewHook() {
    }

    private static synchronized void resolve() {
        if (resolved || broken) {
            return;
        }
        try {
            Class<?> holder = Class.forName("org.vivecraft.client_vr.ClientDataHolderVR");
            getInstance = holder.getMethod("getInstance");
            vrPlayerField = holder.getField("vrPlayer");
            Class<?> vrPlayerClass = vrPlayerField.getType(); // org.vivecraft.client_vr.VRPlayer
            renderDataField = vrPlayerClass.getField("vrdata_world_render");
            originField = renderDataField.getType().getField("origin"); // VRData.origin (Vec3)
            vrProviderField = holder.getField("vr"); // VR 提供器（OpenVR 时为 MCOpenVR）
            resolved = true;
        } catch (Throwable t) {
            broken = true;
            warn("注入初始化失败", t);
        }
    }

    /** 当前 VR 提供器实例（OpenVR 运行时为 MCOpenVR）；不可用返回 null。 */
    static Object currentProvider() {
        resolve();
        if (broken) {
            return null;
        }
        try {
            Object holder = getInstance.invoke(null);
            return holder == null ? null : vrProviderField.get(holder);
        } catch (Throwable t) {
            broken = true;
            warn("读取 VR 提供器失败", t);
            return null;
        }
    }

    /** 当前帧的渲染用 VRData 实例；不可用返回 null。 */
    static Object currentRenderData() {
        resolve();
        if (broken) {
            return null;
        }
        try {
            Object holder = getInstance.invoke(null);
            if (holder == null) {
                return null;
            }
            Object vrPlayer = vrPlayerField.get(holder);
            if (vrPlayer == null) {
                return null;
            }
            return renderDataField.get(vrPlayer);
        } catch (Throwable t) {
            broken = true;
            warn("读取渲染数据失败", t);
            return null;
        }
    }

    /** 把渲染 VRData 的 origin 平移 delta；不可用返回 false。 */
    static boolean shiftOrigin(Object renderData, double dx, double dy, double dz) {
        if (renderData == null) {
            return false;
        }
        resolve();
        if (broken) {
            return false;
        }
        try {
            Object origin = originField.get(renderData);
            if (origin == null) {
                return false;
            }
            // origin 是 net.minecraft.world.phys.Vec3（不可变），反射 add 后写回
            originField.set(renderData,
                    origin.getClass().getMethod("add", double.class, double.class, double.class)
                            .invoke(origin, dx, dy, dz));
            return true;
        } catch (Throwable t) {
            broken = true;
            warn("写入 origin 失败", t);
            return false;
        }
    }

    private static void warn(String what, Throwable t) {
        if (!warned) {
            warned = true;
            YsmVrMod.LOGGER.warn("[ysmvr] 视角绑定注入不可用（{}，Vivecraft 版本不兼容？）", what, t);
        }
    }
}
