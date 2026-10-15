package com.elfmcys.ysmvr;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 身体适配配置：
 * <ul>
 * <li>身高适配：调用 Vivecraft 的世界缩放功能，使玩家在世界中的身高 ≈
 * YSM 模型身高（不同模型身高差异大；不动模型本身）</li>
 * <li>隐藏头部：第一人称（VR 眼 pass LEFT/RIGHT）隐藏头部骨骼组，电脑屏幕
 * （CENTER 桌面镜面 pass）/第三人称/镜面/他人视角仍可见（隐藏是客户端本地覆盖）</li>
 * <li>原版姿态复制：把 Vivecraft 原版 VR 玩家模型的肢体 IK（三轴手臂、手肘、
 * 膝盖、滚动）直接换算到 YSM 骨骼，替换旧的手动解算</li>
 * <li>动画冻结：把非驱动骨骼的旋转覆盖为初始值（绑定姿态），模型自身的
 * 动画不再与 VR 姿态打架（"骨架限制手臂双脚移动"的根源）。开启移动动画后，
 * 行走时只冻结手臂/头部子树，其余骨骼交还 YSM 的 walk/run 动画</li>
 * </ul>
 * 持久化到 config/ysmvr-body.json。
 */
public final class VrBodyConfig {

    /** 玩家身高适配开关（调用 Vivecraft 世界缩放对齐模型身高）。 */
    public static boolean scaleEnabled;

    /**
     * 玩家大小倍率（默认 1.0，允许 0.10–5.00，GUI 滑块调整）：
     * <ul>
     * <li>身高适配开启：玩家世界身高 ≈ 模型头部高度（跟随模式忽略本倍率的
     * 世界缩放作用，玩家始终自动贴合模型）；</li>
     * <li>身高适配关闭：倍率 ≠ 1 时按 "Vivecraft 原世界缩放 × 倍率" 生效
     * （玩家世界身高 ≈ 原本身高 × 倍率），倍率 = 1 时恢复 Vivecraft 原值。</li>
     * </ul>
     * 通过 Vivecraft 的 worldScale 只改玩家世界身高；<b>不影响 YSM 模型</b>
     * （v0.2.22 起；模型大小由 {@link #modelScale} 单独控制，跟随开启时玩家会
     * 自动贴合模型高度）。GUI 的"玩家大小"滑块与 /ysmvr size 使用本值。
     * 要单独改模型观感大小用 {@link #modelScale}（/ysmvr modelsize 或 GUI 的
     * "模型大小"滑块）。
     */
    public static float playerSize = 1.0f;

    /** 玩家大小倍率的允许范围（GUI 滑块与配置共用）。 */
    public static final float PLAYER_SIZE_MIN = 0.10f;
    public static final float PLAYER_SIZE_MAX = 5.00f;

    /**
     * 模型强制大小倍率（默认 1.0）：不依赖模型自己的 {@code v.player_size}
     * 变量，直接给模型主根骨骼（头部骨骼的根祖先）写缩放覆盖——所有模型都
     * 生效（内置酒狐里只有部分变体的动画引用了 v.player_size，如 12_little /
     * 17_mini 等就无法用 YSM 自带的大小设置，只能靠这里强制）。
     * 与模型自带缩放（如 MRoot.scale = v.player_size）是层级相乘关系，
     * 前提是强制缩放的骨骼位于其祖先链上（本实现取头部骨骼的根祖先，满足）。
     */
    public static float modelScale = 1.0f;

    /** 模型强制大小倍率的允许范围（GUI 滑块与配置共用）。 */
    public static final float MODEL_SCALE_MIN = 0.10f;
    public static final float MODEL_SCALE_MAX = 5.00f;

    /**
     * 模型整体位置偏移（模型单位，1 格 = 16；PoseOverrideStore 位置通道，
     * 施加到模型主根骨骼，rotation/scale 仍由动画提供）。
     * 用于修正某些模型整体偏前/偏后/偏左/偏右（例如斯塔·柏身体偏后）。
     * 命令：/ysmvr modelpos &lt;x&gt; &lt;y&gt; &lt;z&gt;。
     */
    public static float modelOffsetX;
    public static float modelOffsetY;
    public static float modelOffsetZ;
    public static final float MODEL_OFFSET_MIN = -32f;
    public static final float MODEL_OFFSET_MAX = 32f;

    /** 第一人称隐藏头部开关（默认开启：VR 头显内隐藏，电脑屏幕/他人视角可见）。 */
    public static boolean hideHeadEnabled = true;

    /**
     * 潜行移动时在头显内隐藏当前玩家 YSM 模型（默认开启）。
     * 只影响本地玩家的 VR 双眼 pass；松开潜行/停下立即恢复。
     */
    public static boolean hideModelSneakMoving = true;

    /** 潜行时隐藏模型（不要求移动）。 */
    public static boolean hideModelWhenSneaking = false;

    /** 创造飞行时隐藏模型（头显内）。 */
    public static boolean hideModelCreativeFlying = false;

    /** 鞘翅飞行时隐藏模型（头显内）。 */
    public static boolean hideModelElytraFlying = false;

    /** 手持物品时取消 YSM 手臂/手腕动画，完全交给 VR/IK。 */
    public static boolean cancelArmAnimation = false;


    /** 手超过模型臂展时，是否用前臂位置延伸去够到手（会脱节/拉伸）。 */
    public static boolean armReachExtension = false;

    /** estimateJointDir 左臂镜像模式：0 无 1 X 2 Y 3 Z 4 全部。 */
    public static int leftJointMode = 1;
    public static int rightJointMode = 0;
    /** 手臂直接旋转：T 字校准后，用控制器旋转增量驱动肘 pole（实验）。 */
    public static boolean armDirectRotation = false;
    /** 直接旋转的肘 pole 是否反转（用于修正反屈）。 */
    public static boolean armDirectPoleFlip = true;    /** 追踪模式：0=简单(无全身)，1=入门(腰+脚)，2=全身。 */
    public static int trackingMode = 1;

    /** 全身躺下适配：关闭潜行/游泳动画，让全身追踪驱动模型躺下。 */
    public static boolean fullBodyLyingMode = false;

    /**
     * 移动时暂停上半身动画（默认开启）：行走/奔跑时把 Head 到最顶根之间的
     * 祖先骨骼旋转/位置清零（Root/AllBody/UpBody/UpperBody 等），避免 YSM
     * walk/run 动画带动头/身体偏移；Head 子骨骼（表情、耳朵等）不受影响。
     */
    public static boolean pauseUpperBodyOnMove = true;

    /**
     * 手持物品/武器时暂停上半身动画（默认开启）：主手或副手拿着物品时，
     * 同样锁住 Head 祖先的旋转/位置，避免 hold_mainhand:sword / TACZ 枪械
     * 等持械动画带动头/身体偏移（含持械移动）。
     */
    public static boolean pauseUpperBodyOnHold = true;

    /**
     * 手持武器/物品时暂停下半身 Y 轴旋转（默认开启）：持械动画（含 TACZ）会写
     * DownBody/腿/脚等骨骼的 yaw，导致下半身转向偏移；持械时只把 Y 轴锁 0，
     * X/Z 旋转与位置仍由动画提供。
     */
    public static boolean pauseLowerBodyOnHold = true;

    /** 手持 TACZ 枪械时暂停手部动画（默认开启）：手骨锁在静止旋转，交给 VR/IK。 */
    public static boolean pauseTaczHandAnimations = true;

    /** TACZ 装弹动作是否暂停手部动画。 */
    public static boolean pauseTaczReload = true;

    /** TACZ 观察/检视动作是否暂停手部动画。 */
    public static boolean pauseTaczInspect = true;

    /** TACZ 瞄准动作是否暂停手部动画。 */
    public static boolean pauseTaczAim = true;

    /** 是否启用面部（HMD）追踪；关闭后头部不再锁定 HMD。 */
    public static boolean faceTrackingEnabled = true;

    /** 隐藏 Vivecraft 手持物品渲染（避免与 YSM 物品重叠）。 */
    public static boolean hideVrHeldItem = false;

    /** 隐藏 YSM 模型手持物品渲染（只想看 VR 物品时使用）。 */
    public static boolean hideYsmHeldItem = false;

    /**
     * VR 中隐藏 YSM 独立第一人称手臂模型（默认开启）。
     * 12_little 等模型有单独的 arm.json 手臂模型，与主模型同名但枢轴不同；
     * 不隐藏时它会盖在主模型手的位置外侧，导致"YSM 手不跟控制器"。
     */
    public static boolean hideYsmFirstPersonArms = true;

    /**
     * 物品栏/HUD 覆盖 YSM 模型开关（默认开启）：渲染 hotbar 时临时关闭深度测试，
     * 避免 Vivecraft 的世界内快捷栏被 YSM 玩家模型遮挡。
     */
    public static boolean hotbarOverModel = true;

    /** 原版模型姿态复制开关（默认开启）。 */
    public static boolean vanillaCopyEnabled = true;

    /** 动画冻结开关（默认开启）：非驱动骨骼锁定在绑定姿态，模型动画"无动作"。 */
    public static boolean freezeModelAnimations = true;

    /**
     * 移动动画开关（默认开启，仅在 {@link #freezeModelAnimations} 开启时生效）：
     * 行走/奔跑时只冻结手臂与头部（含其子骨骼，仍由 VR 驱动），其余骨骼释放
     * 给 YSM 自身的 walk/run 动画——实现"移动时身体正常动、手臂和头部不动"。
     * 待机时仍冻结全部非驱动骨骼，只保留头部（VR）动作。
     */
    public static boolean moveAnimationEnabled = true;

    /**
     * 攻击动画开关（默认开启）：本地玩家挥击时，除头部继续跟随 HMD 外，
     * 其余骨骼全部交还 YSM 的挥击/攻击动画，直到挥击结束；关闭时挥击动画
     * 完全被 VR 姿态覆盖（即"无攻击动画"）。
     */
    public static boolean attackAnimationEnabled = true;

    /**
     * 校准 GUI 的目标姿势（默认"自然站立"，v0.2.18 新增）：
     * <ul>
     * <li>{@code true}：YSM 目标模型显示模型绑定/自然站立姿态（手臂自然下垂），
     * 玩家按原版模型的站立姿势站直、手臂自然下垂即可对齐校准——对应"2.13 前"
     * 那种不强制 T 字的校准方式；</li>
     * <li>{@code false}：YSM 目标模型强制摆 T 字（上臂水平），玩家需举臂摆 T 字
     * （v0.2.15–v0.2.17 行为）。</li>
     * </ul>
     * 校准 GUI 的"目标姿势"按钮可切换，持久化到 ysmvr-body.json。
     */
    public static boolean calibrationStandingPose = true;

    /**
     * 手部位置追踪开关（默认关闭，同 v0.1.10）：把控制器世界位置写到手骨，
     * 让手停留在控制器位置（VRChat 风格）。v0.1.12 默认开启时实测把手中物品
     * 拉到肩膀上（火把错误），故恢复默认关闭；仍可用 /ysmvr handpos on 开启。
     */
    public static boolean handPositionTracking;

    /**
     * 手持物品自动对齐（默认开启）：每帧把 YSM 模型的手持物品锚点（HandLocator）
     * 对齐到原版 VR 模型手持物品锚点（hand 部位 + translateToHand 偏移），
     * 通过数值 Jacobian 求绑定骨骼位置通道增量。关闭后用 binding.pos 手动微调。
     */
    public static boolean handItemAlign = true;

    /** 数值调试日志（默认关闭）：原版角度/矩阵分解/store 值与位置复制数值（限流）。 */
    public static boolean debugLog;

    /**
     * v0.2.21 一次性迁移标记：旧版把反向语义的 worldScale 写进了 Vivecraft 配置，
     * 更新后第一次运行由 {@code VivecraftPoseTracker.applyScale} 把残留值重置为
     * 1.00x 再按新语义接管。运行期标记，不持久化。
     */
    public static boolean worldScaleMigrationReset;

    /**
     * 头部恒定 yaw 补偿（度，默认 0）：当诊断确认模型 Head 骨骼静止朝向
     * （或头网格几何）本身与身体正向相差固定角度（实测 90°）时使用——
     * YSM 官方动画管线会用 netHeadYaw/headPitch 抵消该差，而 VR 直驱已清零
     * 内置追踪，故需在此用一个常数把模型还原为"头脸朝前"。
     */
    public static float headYawOffset;

    /**
     * 头部恒定 roll 补偿（度，默认 0）：当诊断确认模型头网格（或 Head 骨骼基）
     * 本身绕前后轴（roll）扭转了 90°（表现为只露侧脸、且 +90/−90 只能镜像翻转
     * 露哪侧脸、永远无法让脸朝正前方）时使用。与 headYawOffset 不同，它写的是
     * 局部 rz（绕 Z 轴的滚转），用于校正网格级滚转，而非 yaw 偏航。
     */
    public static float headRollOffset;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FMLPaths.CONFIGDIR.get().resolve("ysmvr-body.json");

    private VrBodyConfig() {
    }

    /** 导出所有可变配置（用于每模型快照）。 */
    public static synchronized java.util.Map<String, Object> snapshot() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (var field : VrBodyConfig.class.getFields()) {
            if (java.lang.reflect.Modifier.isFinal(field.getModifiers())) {
                continue;
            }
            try {
                Object value = field.get(null);
                if (value instanceof Boolean || value instanceof Number || value instanceof String) {
                    map.put(field.getName(), value);
                }
            } catch (Throwable ignored) {
            }
        }
        return map;
    }

    /** 应用每模型配置快照（缺失字段保留当前值）。 */
    public static synchronized void applySnapshot(java.util.Map<String, Object> map) {
        if (map == null) {
            return;
        }
        for (var entry : map.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            try {
                var field = VrBodyConfig.class.getField(entry.getKey());
                if (java.lang.reflect.Modifier.isFinal(field.getModifiers())) {
                    continue;
                }
                Class<?> type = field.getType();
                Object value = entry.getValue();
                if (type == boolean.class) {
                    field.setBoolean(null, value instanceof Boolean b ? b
                            : Boolean.parseBoolean(String.valueOf(value)));
                } else if (type == int.class) {
                    field.setInt(null, ((Number) value).intValue());
                } else if (type == float.class) {
                    field.setFloat(null, ((Number) value).floatValue());
                } else if (type == double.class) {
                    field.setDouble(null, ((Number) value).doubleValue());
                } else if (type == String.class) {
                    field.set(null, String.valueOf(value));
                }
            } catch (Throwable ignored) {
            }
        }
        save();
    }
    private static final class Fields {
        boolean scaleEnabled;
        boolean hideHeadEnabled;
        Boolean armReachExtension;
        Integer leftJointMode;
        Integer rightJointMode;
        Boolean armDirectRotation;
        Boolean armDirectPoleFlip;
        Boolean hotbarOverModel; // 装箱类型：旧配置文件缺此字段时为 null → 保持默认开启
        Boolean hideModelSneakMoving; // 旧配置缺省 → 默认开启
        Boolean hideModelWhenSneaking;
        Boolean hideModelCreativeFlying;
        Boolean hideModelElytraFlying;
        Boolean cancelArmAnimation;
        Integer trackingMode;
        Boolean fullBodyLyingMode;
        Boolean pauseUpperBodyOnMove; // 旧配置缺省 → 默认开启
        Boolean pauseUpperBodyOnHold; // 旧配置缺省 → 默认开启
        Boolean pauseLowerBodyOnHold; // 旧配置缺省 → 默认开启（Y 轴旋转暂停）
        Boolean pauseTaczHandAnimations; // 旧配置缺省 → 默认开启
        Boolean pauseTaczReload;
        Boolean pauseTaczInspect;
        Boolean pauseTaczAim;
        Boolean faceTrackingEnabled;
        Boolean hideVrHeldItem;
        Boolean hideYsmHeldItem;
        Boolean hideYsmFirstPersonArms; // 旧配置缺省 → 默认开启
        Boolean vanillaCopyEnabled; // 装箱类型：旧配置文件缺此字段时为 null → 保持默认开启
        Boolean freezeModelAnimations; // 装箱类型：旧配置文件缺此字段时为 null → 保持默认开启
        Boolean moveAnimationEnabled; // 装箱类型：旧配置文件缺此字段时为 null → 保持默认开启
        Boolean attackAnimationEnabled; // 装箱类型：旧配置文件缺此字段时为 null → 保持默认开启
        Boolean calibrationStandingPose; // 装箱类型：旧配置文件缺此字段时为 null → 保持默认开启（自然站立）
        Boolean handPositionTracking; // 装箱类型：旧配置文件缺此字段时为 null → 保持默认关闭（同 v0.1.10）
        Boolean handItemAlign; // 装箱类型：旧配置缺省 → 默认开启
        Boolean debugLog; // 装箱类型：旧配置文件缺此字段时为 null → 保持默认关闭
        Float headYawOffset; // 装箱类型：旧配置文件缺此字段时为 null → 保持默认 0°
        Float headRollOffset; // 装箱类型：旧配置文件缺此字段时为 null → 保持默认 0°
        Float playerSize; // 装箱类型：旧配置文件缺此字段时为 null → 保持默认 1.0
        Float modelScale; // 装箱类型：旧配置文件缺此字段时为 null → 保持默认 1.0
        Float modelOffsetX; // 旧配置缺省 → 0
        Float modelOffsetY;
        Float modelOffsetZ;
        Boolean worldScaleDirect; // v0.2.21 语义标记：旧配置为 null → 玩家大小按反向公式设过，重置为 1.0
    }

    public static synchronized void setScaleEnabled(boolean value) {
        scaleEnabled = value;
    }

    public static synchronized void setPlayerSize(float value) {
        float clamped = Math.max(PLAYER_SIZE_MIN, Math.min(PLAYER_SIZE_MAX, value));
        if (Math.abs(clamped - playerSize) > 1.0e-4f) {
            YsmVrMod.LOGGER.info("[ysmvr] 玩家大小 {}x → {}x（只改玩家，不影响 YSM 模型）",
                    String.format(java.util.Locale.ROOT, "%.2f", playerSize),
                    String.format(java.util.Locale.ROOT, "%.2f", clamped));
        }
        playerSize = clamped;
    }

    public static synchronized void setModelScale(float value) {
        float clamped = Math.max(MODEL_SCALE_MIN, Math.min(MODEL_SCALE_MAX, value));
        if (Math.abs(clamped - modelScale) > 1.0e-4f) {
            YsmVrMod.LOGGER.info("[ysmvr] 模型大小 {}x → {}x（只改 YSM 模型）",
                    String.format(java.util.Locale.ROOT, "%.2f", modelScale),
                    String.format(java.util.Locale.ROOT, "%.2f", clamped));
        }
        modelScale = clamped;
    }

    public static synchronized void setModelOffset(float x, float y, float z) {
        modelOffsetX = clampOffset(x);
        modelOffsetY = clampOffset(y);
        modelOffsetZ = clampOffset(z);
    }

    private static float clampOffset(float v) {
        if (!Float.isFinite(v)) {
            return 0f;
        }
        return Math.max(MODEL_OFFSET_MIN, Math.min(MODEL_OFFSET_MAX, v));
    }

    public static synchronized void setHideHeadEnabled(boolean value) {
        hideHeadEnabled = value;
    }

    public static synchronized void setArmReachExtension(boolean value) {
        armReachExtension = value;
    }

    public static synchronized void setLeftJointMode(int value) {
        leftJointMode = Math.max(0, Math.min(5, value));
    }

    public static synchronized void setRightJointMode(int value) {
        rightJointMode = Math.max(0, Math.min(5, value));
    }

    public static synchronized void setArmDirectRotation(boolean value) {
        armDirectRotation = value;
    }

    public static synchronized void setArmDirectPoleFlip(boolean value) {
        armDirectPoleFlip = value;
    }    public static synchronized void setHideModelSneakMoving(boolean value) {
        hideModelSneakMoving = value;
        save();
    }

    public static synchronized void setHideModelWhenSneaking(boolean value) {
        hideModelWhenSneaking = value;
        save();
    }

    public static synchronized void setHideModelCreativeFlying(boolean value) {
        hideModelCreativeFlying = value;
        save();
    }

    public static synchronized void setHideModelElytraFlying(boolean value) {
        hideModelElytraFlying = value;
        save();
    }

    public static synchronized void setCancelArmAnimation(boolean value) {
        cancelArmAnimation = value;
        save();
    }

    public static synchronized void setTrackingMode(int value) {
        trackingMode = Math.max(0, Math.min(5, value));
        save();
    }

    public static synchronized void setFullBodyLyingMode(boolean value) {
        fullBodyLyingMode = value;
        save();
    }

    public static synchronized void setPauseUpperBodyOnMove(boolean value) {
        pauseUpperBodyOnMove = value;
        save();
    }

    public static synchronized void setPauseUpperBodyOnHold(boolean value) {
        pauseUpperBodyOnHold = value;
        save();
    }

    public static synchronized void setPauseLowerBodyOnHold(boolean value) {
        pauseLowerBodyOnHold = value;
        save();
    }

    public static synchronized void setPauseTaczHandAnimations(boolean value) {
        pauseTaczHandAnimations = value;
        save();
    }

    /** 物品是否来自 TACZ（命名空间含 tacz）。 */
    public static boolean isTaczItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            var key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
            return key != null && key.getNamespace().toLowerCase(java.util.Locale.ROOT).contains("tacz");
        } catch (Throwable t) {
            return false;
        }
    }

    /** VR 中是否隐藏 YSM 独立第一人称手臂模型（持 TACZ 且关闭暂停时显示 TACZ 动画）。 */
    public static boolean shouldHideYsmFirstPersonArms(LocalPlayer player) {
        if (!hideYsmFirstPersonArms) {
            return false;
        }
        if (player != null && !pauseTaczHandAnimations
                && (isTaczItem(player.getMainHandItem()) || isTaczItem(player.getOffhandItem()))) {
            return false;
        }
        return true;
    }

    public static synchronized void setPauseTaczReload(boolean value) { pauseTaczReload = value; save(); }
    public static synchronized void setPauseTaczInspect(boolean value) { pauseTaczInspect = value; save(); }
    public static synchronized void setPauseTaczAim(boolean value) { pauseTaczAim = value; save(); }
    public static synchronized void setFaceTrackingEnabled(boolean value) { faceTrackingEnabled = value; save(); }
    public static synchronized void setHideVrHeldItem(boolean value) { hideVrHeldItem = value; save(); }
    public static synchronized void setHideYsmHeldItem(boolean value) { hideYsmHeldItem = value; save(); }

    public static synchronized void setHideYsmFirstPersonArms(boolean value) {
        hideYsmFirstPersonArms = value;
        save();
    }

    public static synchronized void setHotbarOverModel(boolean value) {
        hotbarOverModel = value;
    }

    public static synchronized void setVanillaCopyEnabled(boolean value) {
        vanillaCopyEnabled = value;
    }

    public static synchronized void setFreezeModelAnimations(boolean value) {
        freezeModelAnimations = value;
    }

    public static synchronized void setMoveAnimationEnabled(boolean value) {
        moveAnimationEnabled = value;
    }

    public static synchronized void setAttackAnimationEnabled(boolean value) {
        attackAnimationEnabled = value;
    }

    public static synchronized void setCalibrationStandingPose(boolean value) {
        calibrationStandingPose = value;
    }

    public static synchronized void setDebugLog(boolean value) {
        debugLog = value;
    }

    public static synchronized void setHandPositionTracking(boolean value) {
        handPositionTracking = value;
    }

    public static synchronized void setHandItemAlign(boolean value) {
        handItemAlign = value;
    }

    public static synchronized void setHeadYawOffset(float value) {
        headYawOffset = value;
    }

    public static synchronized void setHeadRollOffset(float value) {
        headRollOffset = value;
    }

    public static synchronized void save() {
        var fields = new Fields();
        fields.scaleEnabled = scaleEnabled;
        fields.hideHeadEnabled = hideHeadEnabled;
        fields.armReachExtension = armReachExtension;
        fields.leftJointMode = leftJointMode;
        fields.rightJointMode = rightJointMode;
        fields.armDirectRotation = armDirectRotation;
        fields.armDirectPoleFlip = armDirectPoleFlip;
        fields.hideModelSneakMoving = hideModelSneakMoving;
        fields.hideModelWhenSneaking = hideModelWhenSneaking;
        fields.hideModelCreativeFlying = hideModelCreativeFlying;
        fields.hideModelElytraFlying = hideModelElytraFlying;
        fields.cancelArmAnimation = cancelArmAnimation;
        fields.trackingMode = trackingMode;
        fields.fullBodyLyingMode = fullBodyLyingMode;
        fields.pauseUpperBodyOnMove = pauseUpperBodyOnMove;
        fields.pauseUpperBodyOnHold = pauseUpperBodyOnHold;
        fields.pauseLowerBodyOnHold = pauseLowerBodyOnHold;
        fields.pauseTaczHandAnimations = pauseTaczHandAnimations;
        fields.pauseTaczReload = pauseTaczReload;
        fields.pauseTaczInspect = pauseTaczInspect;
        fields.pauseTaczAim = pauseTaczAim;
        fields.faceTrackingEnabled = faceTrackingEnabled;
        fields.hideVrHeldItem = hideVrHeldItem;
        fields.hideYsmHeldItem = hideYsmHeldItem;
        fields.hideYsmFirstPersonArms = hideYsmFirstPersonArms;
        fields.hotbarOverModel = hotbarOverModel;
        fields.vanillaCopyEnabled = vanillaCopyEnabled;
        fields.freezeModelAnimations = freezeModelAnimations;
        fields.moveAnimationEnabled = moveAnimationEnabled;
        fields.attackAnimationEnabled = attackAnimationEnabled;
        fields.calibrationStandingPose = calibrationStandingPose;
        fields.handPositionTracking = handPositionTracking;
        fields.handItemAlign = handItemAlign;
        fields.debugLog = debugLog;
        fields.headYawOffset = headYawOffset;
        fields.headRollOffset = headRollOffset;
        fields.playerSize = playerSize;
        fields.modelScale = modelScale;
        fields.modelOffsetX = modelOffsetX;
        fields.modelOffsetY = modelOffsetY;
        fields.modelOffsetZ = modelOffsetZ;
        fields.worldScaleDirect = true;
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(fields), StandardCharsets.UTF_8);
        } catch (IOException e) {
            YsmVrMod.LOGGER.warn("[ysmvr] 身体适配配置保存失败", e);
        }
    }

    public static synchronized void load() {
        if (!Files.exists(CONFIG_PATH)) {
            return;
        }
        boolean migratedToDirectWorldScale = false;
        try {
            var fields = GSON.fromJson(
                    Files.readString(CONFIG_PATH, StandardCharsets.UTF_8), Fields.class);
            if (fields == null) {
                return;
            }
            scaleEnabled = fields.scaleEnabled;
            hideHeadEnabled = fields.hideHeadEnabled;
            armReachExtension = fields.armReachExtension != null && fields.armReachExtension;
            leftJointMode = fields.leftJointMode == null ? 1 : Math.max(0, Math.min(5, fields.leftJointMode));
            rightJointMode = fields.rightJointMode == null ? 0 : Math.max(0, Math.min(5, fields.rightJointMode));
            armDirectRotation = fields.armDirectRotation != null && fields.armDirectRotation;
            armDirectPoleFlip = fields.armDirectPoleFlip == null || fields.armDirectPoleFlip;
            // 旧配置文件缺此字段（null）时保持默认开启
            hideModelSneakMoving = fields.hideModelSneakMoving == null
                    || fields.hideModelSneakMoving;
            hideModelWhenSneaking = fields.hideModelWhenSneaking != null && fields.hideModelWhenSneaking;
            hideModelCreativeFlying = fields.hideModelCreativeFlying != null && fields.hideModelCreativeFlying;
            hideModelElytraFlying = fields.hideModelElytraFlying != null && fields.hideModelElytraFlying;
            cancelArmAnimation = fields.cancelArmAnimation != null && fields.cancelArmAnimation;
            trackingMode = fields.trackingMode == null ? 1 : Math.max(0, Math.min(5, fields.trackingMode));
            fullBodyLyingMode = fields.fullBodyLyingMode != null && fields.fullBodyLyingMode;
            pauseUpperBodyOnMove = fields.pauseUpperBodyOnMove == null
                    || fields.pauseUpperBodyOnMove;
            pauseUpperBodyOnHold = fields.pauseUpperBodyOnHold == null
                    || fields.pauseUpperBodyOnHold;
            pauseLowerBodyOnHold = fields.pauseLowerBodyOnHold == null
                    || fields.pauseLowerBodyOnHold;
            pauseTaczHandAnimations = fields.pauseTaczHandAnimations == null
                    || fields.pauseTaczHandAnimations;
            pauseTaczReload = fields.pauseTaczReload == null || fields.pauseTaczReload;
            pauseTaczInspect = fields.pauseTaczInspect == null || fields.pauseTaczInspect;
            pauseTaczAim = fields.pauseTaczAim == null || fields.pauseTaczAim;
            faceTrackingEnabled = fields.faceTrackingEnabled == null || fields.faceTrackingEnabled;
            hideVrHeldItem = fields.hideVrHeldItem != null && fields.hideVrHeldItem;
            hideYsmHeldItem = fields.hideYsmHeldItem != null && fields.hideYsmHeldItem;
            hideYsmFirstPersonArms = fields.hideYsmFirstPersonArms == null
                    || fields.hideYsmFirstPersonArms;
            hotbarOverModel = fields.hotbarOverModel != null
                    ? fields.hotbarOverModel : true;
            // 旧配置文件缺此字段（null）时保持默认开启
            vanillaCopyEnabled = fields.vanillaCopyEnabled != null
                    ? fields.vanillaCopyEnabled : true;
            freezeModelAnimations = fields.freezeModelAnimations != null
                    ? fields.freezeModelAnimations : true;
            // 旧配置文件缺此字段（null）时保持默认开启
            moveAnimationEnabled = fields.moveAnimationEnabled != null
                    ? fields.moveAnimationEnabled : true;
            // 旧配置文件缺此字段（null）时保持默认开启
            attackAnimationEnabled = fields.attackAnimationEnabled != null
                    ? fields.attackAnimationEnabled : true;
            // 旧配置文件缺此字段（null）时保持默认开启（自然站立目标）
            calibrationStandingPose = fields.calibrationStandingPose == null
                    || fields.calibrationStandingPose;
            // 旧配置文件缺此字段（null）时保持默认关闭（同 v0.1.10）
            handPositionTracking = fields.handPositionTracking != null
                    ? fields.handPositionTracking : false;
            handItemAlign = fields.handItemAlign == null || fields.handItemAlign;
            // 旧配置文件缺此字段（null）时保持默认关闭
            debugLog = fields.debugLog != null && fields.debugLog;
            // 旧配置文件缺此字段（null）时保持默认 0°
            headYawOffset = fields.headYawOffset != null ? fields.headYawOffset : 0f;
            headRollOffset = fields.headRollOffset != null ? fields.headRollOffset : 0f;
            // 玩家大小：v0.2.21 起 Vivecraft worldScale 修正为正比语义
            //（<1 变小、>1 变大）。旧配置里的 playerSize 是按反向公式调的，
            // 直接沿用会让更新后玩家大小反向；检测到旧配置（缺 worldScaleDirect）
            // 时重置为 1.0，由用户用修正后的滑块/跟随重新设置。
            if (fields.worldScaleDirect == null) {
                playerSize = 1.0f;
                worldScaleMigrationReset = true;
                migratedToDirectWorldScale = true;
                YsmVrMod.LOGGER.info(
                        "[ysmvr] 检测到旧版玩家大小配置：worldScale 已修正为正比语义"
                                + "（<1 变小 / >1 变大），玩家大小重置为 1.00x；"
                                + "Vivecraft worldScale 残留将在首帧重置为 1.00x");
            } else {
                playerSize = fields.playerSize != null
                        ? Math.max(PLAYER_SIZE_MIN, Math.min(PLAYER_SIZE_MAX, fields.playerSize))
                        : 1.0f;
            }
            // 旧配置文件缺此字段（null）时保持默认 1.0 倍
            modelScale = fields.modelScale != null
                    ? Math.max(MODEL_SCALE_MIN, Math.min(MODEL_SCALE_MAX, fields.modelScale))
                    : 1.0f;
            modelOffsetX = fields.modelOffsetX != null ? clampOffset(fields.modelOffsetX) : 0f;
            modelOffsetY = fields.modelOffsetY != null ? clampOffset(fields.modelOffsetY) : 0f;
            modelOffsetZ = fields.modelOffsetZ != null ? clampOffset(fields.modelOffsetZ) : 0f;
        } catch (Exception e) {
            YsmVrMod.LOGGER.warn("[ysmvr] 身体适配配置加载失败，使用默认值", e);
        }
        // 迁移后立刻落盘，写入 worldScaleDirect 标记，避免每次启动都重置
        if (migratedToDirectWorldScale) {
            save();
        }
    }
}
