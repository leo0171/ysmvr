package com.elfmcys.ysmvr;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;

/**
 * 全身 T 字校准：记录 T 字时模型 Root 与玩家实体、HMD 的偏移关系。
 * 站立时 Root 跟随实体；身体接近水平（躺/趴）时 Root 跟随 HMD 位移。
 */
public final class FullBodyCalibration {

    private static volatile boolean valid;
    private static Vec3 entityOffsetCal = Vec3.ZERO;
    private static Vec3 headOffsetCal = Vec3.ZERO;
    private static Vec3 rootModelCal = Vec3.ZERO;
    private static final Quaternionf REF_ROT_CAL = new Quaternionf();

    private FullBodyCalibration() {
    }

    public static boolean valid() {
        return valid;
    }

    public static void record(Vec3 rootWorld, Vec3 entityPos, Vec3 headWorld, Vec3 rootModelM,
                              Quaternionfc refRot) {
        if (rootWorld == null || entityPos == null || headWorld == null || rootModelM == null) {
            return;
        }
        entityOffsetCal = rootWorld.subtract(entityPos);
        headOffsetCal = rootWorld.subtract(headWorld);
        rootModelCal = rootModelM;
        if (refRot != null) {
            REF_ROT_CAL.set(refRot);
        } else {
            REF_ROT_CAL.identity();
        }
        valid = true;
    }

    /** 目标 Root 世界位置：horizontal=true 时跟随 HMD，否则跟随实体。 */
    public static Vec3 rootWorldNow(Vec3 entityPosNow, Vec3 headWorldNow, boolean horizontal) {
        if (!valid) {
            return null;
        }
        if (horizontal && headWorldNow != null) {
            return headWorldNow.add(headOffsetCal);
        }
        return entityPosNow == null ? null : entityPosNow.add(entityOffsetCal);
    }

    public static Vec3 entityOffset() {
        return entityOffsetCal;
    }

    public static Vec3 rootModelCal() {
        return rootModelCal;
    }

    public static void clear() {
        valid = false;
    }
}