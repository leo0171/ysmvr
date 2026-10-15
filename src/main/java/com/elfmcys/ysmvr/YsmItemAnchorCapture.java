package com.elfmcys.ysmvr;

/**
 * YSM 物品渲染层捕获的真实手持物品锚点（世界坐标）缓存。
 * <p>
 * v0.2.30 新增：`CustomPlayerItemInHandLayer` 渲染物品前会
 * `prepMatrixForLocator(poseStack, rightHandBones()/leftHandBones())`，
 * 此时 poseStack 的平移就是物品真正要被渲染到的世界位置。Mixin 把它按左右手
 * 写入本类；tracker 下一帧用这个"真实锚点"而不是静态骨骼层级
 * （`getBoneModelPosition`）来算对齐修正，避免 locator 由动画/约束驱动导致的
 * Jacobian 全 0 和脱手问题。
 */
public final class YsmItemAnchorCapture {

    /** 锚点有效时间（纳秒）；超过视为过期，避免用旧帧数据。 */
    private static final long MAX_AGE_NANOS = 250_000_000L;

    private static volatile float[] left;
    private static volatile long leftNanos;
    private static volatile float[] right;
    private static volatile long rightNanos;

    private YsmItemAnchorCapture() {
    }

    /** 渲染层写入：left=副手（左手），right=主手（右手）；坐标是绝对世界坐标。 */
    public static void set(boolean left, float x, float y, float z) {
        float[] p = {x, y, z};
        if (left) {
            YsmItemAnchorCapture.left = p;
            leftNanos = System.nanoTime();
        } else {
            YsmItemAnchorCapture.right = p;
            rightNanos = System.nanoTime();
        }
    }

    /** tracker 读取；过期/无数据返回 null。返回值是副本，调用方不要改。 */
    public static float[] get(boolean left) {
        float[] p = left ? YsmItemAnchorCapture.left : YsmItemAnchorCapture.right;
        long t = left ? leftNanos : rightNanos;
        if (p == null || System.nanoTime() - t > MAX_AGE_NANOS) {
            return null;
        }
        return new float[]{p[0], p[1], p[2]};
    }

    public static void clear() {
        left = null;
        right = null;
        leftNanos = 0;
        rightNanos = 0;
    }
}
