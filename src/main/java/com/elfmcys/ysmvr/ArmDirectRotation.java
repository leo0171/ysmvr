package com.elfmcys.ysmvr;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

/**
 * 手臂直接旋转：T 字校准阶段记录控制器世界旋转与肘 pole，
 * 运行时用控制器旋转增量旋转 pole，避免直接依赖 Vivecraft 的 elbow 位置。
 */
public final class ArmDirectRotation {

    private static final Quaternionf[] Q_CAL = {new Quaternionf(), new Quaternionf()};
    private static final Vector3f[] POLE_LOCAL = {new Vector3f(), new Vector3f()};
    private static final boolean[] VALID = new boolean[2];

    private ArmDirectRotation() {
    }

    private static int idx(boolean left) {
        return left ? 1 : 0;
    }

    /** 校准采集中调用：worldPole 为世界空间肘 pole。 */
    public static synchronized void record(boolean left, Quaternionfc worldRot, Vec3 worldPole) {
        if (worldRot == null || worldPole == null || worldPole.lengthSqr() < 1.0e-8) {
            return;
        }
        int i = idx(left);
        Q_CAL[i].set(worldRot);
        Vector3f p = new Vector3f((float) worldPole.x, (float) worldPole.y, (float) worldPole.z);
        new Quaternionf(Q_CAL[i]).conjugate().transform(p);
        if (p.lengthSquared() < 1.0e-8) {
            return;
        }
        POLE_LOCAL[i].set(p.normalize());
        VALID[i] = true;
    }

    public static synchronized boolean valid(boolean left) {
        return VALID[idx(left)];
    }

    /** 运行时：world delta 旋转后的世界 pole。 */
    public static synchronized Vector3f pole(boolean left, Quaternionfc worldRotNow) {
        int i = idx(left);
        Vector3f p = new Vector3f(POLE_LOCAL[i]);
        if (worldRotNow != null) {
            new Quaternionf(worldRotNow).transform(p);
        }
        return p.normalize();
    }

    public static synchronized void clear() {
        for (int i = 0; i < 2; i++) {
            Q_CAL[i].identity();
            POLE_LOCAL[i].set(0f, -1f, 0f);
            VALID[i] = false;
        }
    }
}