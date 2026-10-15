package com.elfmcys.ysmvr;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Field;

/**
 * 原版 VR 模型姿态源：调用 Vivecraft 自己的 {@code VRPlayerModel*.setupAnim}
 * 摆好原版 VR 玩家模型，再快照各部位（头/身体/上臂/前臂/大腿/小腿）的局部
 * 旋转，供 {@link VivecraftPoseTracker} 换算后写到 YSM 骨骼上。
 * <p>
 * 与 CPM 的"渲染替换"不同：YSM 是 GeckoLib 整模型渲染，无法像原版 ModelPart
 * 那样逐部位替换。但 Vivecraft 的原版模型姿态（手臂三轴、手肘、膝盖、滚动）
 * 是现成的完整 IK，把它"抄"到 YSM 骨骼上即可获得和原版 VR 玩家模型一致的
 * 追踪质量，绕过我们自己解算的轴数限制。
 * <p>
 * 实现要点：
 * <ul>
 * <li>模型实例取自 {@code EntityRenderDispatcher.getRenderer(player)}——Vivecraft
 * 用 mixin 保证 VR 玩家拿到 {@code VRPlayerRenderer}，其模型即
 * {@code VRPlayerModel_WithArms(Legs)}（类型由 playerModelType 决定）</li>
 * <li>每帧在追踪器里直接调用 {@code setupAnim}（渲染器稍后重摆一遍，无副作用），
 * 内部走 {@code animateVRModel} 读取 {@code ClientVRPlayers.RotInfo}（本地玩家
 * 的 HMD/手柄/FBT 数据），与渲染路径完全同源</li>
 * <li>上臂/前臂（left_hand/right_hand）与小腿（left_foot/right_foot）是子类
 * 字段，用反射读取；模型不是拆分臂腿类型时对应部位为 NaN，调用方回退到
 * 追踪点解算</li>
 * <li>{@code rotInfo} 为 null 表示本帧没摆上 VR 姿态（非 VR 玩家/数据未就绪），
 * 整体返回 null 让调用方走旧路径</li>
 * <li>腿部（大腿/小腿）只有 FBT 模式（rotInfo 携带脚部追踪器位置）才由 VR
 * 数据摆出，否则是行走动画——由 {@link Pose#partVrPosed(Part)} 区分</li>
 * </ul>
 * 任何异常都只降级不崩溃：失败多次后永久禁用本类（同 {@link VrHeadHide} 语义）。
 */
public final class VanillaVrPoseSource {

    /** 模型部位枚举（原版 VR 模型侧）。 */
    public enum Part {
        HEAD, BODY,
        RIGHT_ARM, LEFT_ARM,     // vanilla PlayerModel.rightArm/leftArm = 上臂（拆分模型）
        RIGHT_HAND, LEFT_HAND,   // VRPlayerModel_WithArms.rightHand/leftHand = 前臂+手
        RIGHT_LEG, LEFT_LEG,     // vanilla PlayerModel.rightLeg/leftLeg = 大腿
        RIGHT_FOOT, LEFT_FOOT,   // VRPlayerModel_WithArmsLegs.rightFoot/leftFoot = 小腿+脚
    }

    /** 一帧的原版模型姿态快照：每部位局部旋转（弧度）+ 平移（模型单位）+ 模型身体朝向（弧度）。 */
    public static final class Pose {
        public final float[] rotations = new float[Part.values().length * 3];
        /** 部位平移 x/y/z（模型单位；原版模型部位均为 root 直接子级，即模型根坐标）。 */
        public final float[] positions = new float[Part.values().length * 3];
        public final float bodyYaw;
        /** 本帧原版模型的腿部是否由 FBT 脚部追踪器摆出（否则是行走动画）。 */
        public final boolean legsVrPosed;
        /** rotInfo 的模型缩放（0.9375·heightScale·worldScale）：部位平移（模型单位）→ 世界米的换算因子。 */
        public final float heightScale;
        public final float worldScale;

        private Pose(float bodyYaw, boolean legsVrPosed, float heightScale, float worldScale) {
            this.bodyYaw = bodyYaw;
            this.legsVrPosed = legsVrPosed;
            this.heightScale = heightScale;
            this.worldScale = worldScale;
        }

        /** 部位局部旋转 x/y/z（弧度）。 */
        public float rx(Part part) {
            return rotations[part.ordinal() * 3];
        }

        public float ry(Part part) {
            return rotations[part.ordinal() * 3 + 1];
        }

        public float rz(Part part) {
            return rotations[part.ordinal() * 3 + 2];
        }

        /** 部位平移 x/y/z（模型单位）。 */
        public float px(Part part) {
            return positions[part.ordinal() * 3];
        }

        public float py(Part part) {
            return positions[part.ordinal() * 3 + 1];
        }

        public float pz(Part part) {
            return positions[part.ordinal() * 3 + 2];
        }

        public boolean has(Part part) {
            return !Float.isNaN(rotations[part.ordinal() * 3]);
        }

        /** 部位是否由 VR 追踪数据摆出（腿部在无 FBT 时是行走动画，不复制）。 */
        public boolean partVrPosed(Part part) {
            return switch (part) {
                case RIGHT_LEG, LEFT_LEG, RIGHT_FOOT, LEFT_FOOT -> legsVrPosed;
                default -> true;
            };
        }
    }

    private static boolean broken;
    private static int failCount;

    private VanillaVrPoseSource() {
    }

    /**
     * 摆好原版 VR 模型并快照姿态；返回 null 表示本帧不可用（非 VR 模型、
     * VR 数据未就绪、或本类已禁用），调用方应回退到追踪点解算。
     */
    @SuppressWarnings("unchecked")
    public static Pose capture(LocalPlayer player) {
        if (broken || player == null) {
            return null;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            EntityRenderer<?> renderer = mc.getEntityRenderDispatcher().getRenderer(player);
            if (!(renderer instanceof PlayerRenderer)) {
                return null;
            }
            // LivingEntityRenderer.model 是 protected，且生产环境（Forge 混淆 jar）字段名
            // 是 SRG 名 f_115290_——按名字反射必然失败。改为按类型沿类层级搜索第一个
            // EntityModel 类型的声明字段（VRPlayerRenderer/PlayerRenderer 自身不声明
            // 模型字段，搜到的就是 LivingEntityRenderer 的 model，dev/SRG 环境都有效）
            Field modelField = null;
            for (Class<?> c = renderer.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (EntityModel.class.isAssignableFrom(f.getType())) {
                        modelField = f;
                        break;
                    }
                }
                if (modelField != null) {
                    break;
                }
            }
            if (modelField == null) {
                return fail("找不到渲染器的 model 字段");
            }
            modelField.setAccessible(true);
            Object modelObj = modelField.get(renderer);
            if (!(modelObj instanceof PlayerModel)) {
                return null;
            }
            PlayerModel<LivingEntity> model = (PlayerModel<LivingEntity>) modelObj;
            if (!model.getClass().getName().startsWith("org.vivecraft.")) {
                return null; // 不是 Vivecraft 的 VR 模型（理论上 VR 玩家不会走到这）
            }

            float partialTick = mc.getFrameTime();
            // 与渲染器相同的调用参数；VR 姿态来自 RotInfo，摆动参数只影响走路动画
            model.setupAnim(player,
                    player.walkAnimation.position(partialTick),
                    player.walkAnimation.speed(partialTick),
                    player.tickCount + partialTick,
                    0f, 0f);

            // rotInfo 为 null = animateVRModel 没有摆上 VR 姿态（非 VR 玩家/数据未就绪）
            Field rotInfoField = findField(model.getClass(), "rotInfo");
            if (rotInfoField == null) {
                return fail("VR 模型缺少 rotInfo 字段（Vivecraft 版本不匹配？）");
            }
            rotInfoField.setAccessible(true);
            Object rotInfo = rotInfoField.get(model);
            if (rotInfo == null) {
                return null;
            }
            Field bodyYawField = findField(model.getClass(), "bodyYaw");
            if (bodyYawField == null) {
                return fail("VR 模型缺少 bodyYaw 字段（Vivecraft 版本不匹配？）");
            }
            // 必须先 setAccessible 再 get：JPMS 模块下 protected 字段跨模块访问受限
            bodyYawField.setAccessible(true);
            if (!(bodyYawField.get(model) instanceof Float bodyYaw)) {
                return fail("VR 模型 bodyYaw 字段类型异常（Vivecraft 版本不匹配？）");
            }

            // FBT 模式下 rotInfo 携带脚部追踪器位置 → 腿部由 VR 数据摆出
            boolean legsVrPosed = getFieldValue(rotInfo, "rightFootPos") != null
                    || getFieldValue(rotInfo, "leftFootPos") != null;

            // 模型缩放（位置复制换算世界坐标用；字段缺失按 1）
            float heightScale = getFieldValue(rotInfo, "heightScale") instanceof Float hs ? hs : 1f;
            float worldScale = getFieldValue(rotInfo, "worldScale") instanceof Float ws ? ws : 1f;

            Pose pose = new Pose(bodyYaw, legsVrPosed, heightScale, worldScale);
            put(pose, Part.HEAD, model.head);
            put(pose, Part.BODY, model.body);
            put(pose, Part.RIGHT_ARM, model.rightArm);
            put(pose, Part.LEFT_ARM, model.leftArm);
            put(pose, Part.RIGHT_LEG, model.rightLeg);
            put(pose, Part.LEFT_LEG, model.leftLeg);
            // 拆分部位：模型类型不支持时字段缺失 → NaN，调用方回退
            put(pose, Part.RIGHT_HAND, (ModelPart) getFieldValue(model, "rightHand"));
            put(pose, Part.LEFT_HAND, (ModelPart) getFieldValue(model, "leftHand"));
            put(pose, Part.RIGHT_FOOT, (ModelPart) getFieldValue(model, "rightFoot"));
            put(pose, Part.LEFT_FOOT, (ModelPart) getFieldValue(model, "leftFoot"));
            failCount = 0;
            return pose;
        } catch (Throwable t) {
            return fail(t);
        }
    }

    private static Pose fail(Throwable t) {
        failCount++;
        if (failCount >= 10) {
            broken = true;
            YsmVrMod.LOGGER.warn("[ysmvr] 原版模型姿态捕获连续失败，已禁用", t);
        } else {
            YsmVrMod.LOGGER.debug("[ysmvr] 原版模型姿态捕获失败", t);
        }
        return null;
    }

    private static Pose fail(String reason) {
        return fail(new IllegalStateException(reason));
    }

    private static void put(Pose pose, Part part, ModelPart modelPart) {
        int i = part.ordinal() * 3;
        if (modelPart == null) {
            pose.rotations[i] = Float.NaN;
            return;
        }
        pose.rotations[i] = modelPart.xRot;
        pose.rotations[i + 1] = modelPart.yRot;
        pose.rotations[i + 2] = modelPart.zRot;
        pose.positions[i] = modelPart.x;
        pose.positions[i + 1] = modelPart.y;
        pose.positions[i + 2] = modelPart.z;
    }

    /** 沿类层级向上找字段声明（rotInfo/bodyYaw 声明在父类 VRPlayerModel）。 */
    private static Field findField(Class<?> start, String name) {
        for (Class<?> c = start; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 继续向上找父类
            }
        }
        return null;
    }

    /** 取字段值（字段缺失或值为 null 都返回 null）。 */
    private static Object getFieldValue(Object obj, String name) throws IllegalAccessException {
        Field f = findField(obj.getClass(), name);
        if (f == null) {
            return null;
        }
        f.setAccessible(true);
        return f.get(obj);
    }
}
