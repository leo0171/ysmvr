package com.elfmcys.ysmvr;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端调试指令：手动驱动骨骼、观察模型与 VR 状态。通过 {@link ActiveBridge}
 * 访问 YSM，同时支持官方 3.0 与 ModernYSM 2.6.x。
 * <pre>
 * /ysmvr bone &lt;name&gt; rot &lt;rx&gt; &lt;ry&gt; &lt;rz&gt;     设置骨骼旋转（度，模型空间）
 * /ysmvr bone &lt;name&gt; pos &lt;x&gt; &lt;y&gt; &lt;z&gt;      设置骨骼位置（模型单位，1 格 = 16）
 * /ysmvr bone &lt;name&gt; scale &lt;x&gt; &lt;y&gt; &lt;z&gt;   设置骨骼缩放
 * /ysmvr bones                              列出当前模型骨骼
 * /ysmvr clear [&lt;name&gt;]                     清除覆盖
 * /ysmvr head &lt;pitch&gt; &lt;yaw&gt;                快捷设置 Head 骨骼旋转（度）
 * /ysmvr vr &lt;on|off&gt;                        Vivecraft 追踪开关
 * /ysmvr view [on|off]                      视角绑定开关（把 VR 第一人称视角粘到模型头部）
 * /ysmvr view pos &lt;x&gt; &lt;y&gt; &lt;z&gt;             视角位置偏移（米，模型坐标系）
 * /ysmvr view head &lt;bone&gt;                  视角绑定的头部骨骼（留空靠 GUI）
 * /ysmvr calibrate [auto|gui]               姿势校准（auto=按当前姿势立即多帧自动校准；gui=纸娃娃引导）
 * /ysmvr armtune                            手臂偏移手动微调 GUI（左/右臂 X/Y/Z Δ，实时保存）
 * /ysmvr scale [on|off]                     玩家身高适配（跟随 YSM 模型高度；gui 打开大小设置界面）
 * /ysmvr size [倍率]                        玩家大小（0.10–5.00，只改玩家世界身高，不影响 YSM 模型）
 * /ysmvr modelsize [倍率]                   模型强制大小倍率（主根骨骼缩放，0.10–5.00，所有模型含小酒狐）
 * /ysmvr modelpos [x y z|reset]             模型整体位置偏移（模型单位；修某些模型整体偏后）
 * /ysmvr profile [gui|reset|main &lt;骨&gt;|off &lt;骨&gt;] 单模型适配（按模型 id 存位置偏移与手臂骨骼）
 * /ysmvr hidehead [on|off]                  第一人称隐藏头部（第三人称/他人仍可见）
 * /ysmvr hidesneak [on|off]                 潜行移动时头显内隐藏本地 YSM 模型（默认开启）
 * /ysmvr pausemove [on|off]                 移动时暂停上半身动画，Head 表情/耳朵保留（默认开启）
 * /ysmvr pausehold [on|off]                 手持武器/物品时暂停上半身（含 TACZ，默认开启）
 * /ysmvr pauselower [on|off]                手持武器/物品时暂停下半身 Y 轴旋转（默认开启）
 * /ysmvr pausetacz [on|off]                 手持 TACZ 枪械时暂停手部动画（默认开启）
 * /ysmvr hidehands [on|off]                 VR 中隐藏 YSM 独立第一人称手臂模型（默认开启）
 * /ysmvr dumpbones                          导出当前模型骨骼名/父级/枢轴到 config（排查手不跟随）
 * /ysmvr vrcopy [on|off]                    原版姿态复制（原版 VR 模型肢体 IK → YSM 骨骼）
 * /ysmvr freeze [on|off]                    动画冻结（非驱动骨骼锁定在绑定姿态）
 * /ysmvr moveanim [on|off]                  移动动画（行走时身体播放 YSM walk，手臂/头部仍 VR 驱动）
 * /ysmvr attackanim [on|off]                攻击动画（挥击时其余骨骼播放 YSM 挥击，头部仍跟 HMD）
 * /ysmvr animdiag                           动画/骨骼诊断（酒狐坐骑/边框/控制器当前动画）
 * /ysmvr handpos [on|off]                   手部位置追踪（骨骼分离：手停留控制器位置）
 * /ysmvr handalign [on|off]                 手持物品自动对齐（YSM 手部锚点 → 原版 VR 物品锚点，默认开）
 * /ysmvr mode [auto|simple|extended]        追踪默认绑定模式（手动锁定或自动检测）
 * /ysmvr debug [on|off]                     数值调试日志（原版角度/store 值/位置复制）
 * /ysmvr headyaw <deg>                     头部恒定 yaw 补偿（度，矫正模型头 90° 几何偏差）
 * /ysmvr status                             状态信息
 * /ysmvr gui                              打开骨骼查看器（拖拽旋转模型）
 * /ysmvr main                             打开总设置 GUI（所有开关 + 子 GUI 入口，暂停界面也有按钮）
 * /ysmvr face                             正面检视（固定正面视角 + 头部角度诊断）
 * /ysmvr sim
 * </pre>
 */
public final class YsmVrCommands {
    private static final String ROOT = "ysmvr";

    private static final SuggestionProvider<CommandSourceStack> BONE_NAMES =
            (source, builder) -> {
                var player = Minecraft.getInstance().player;
                var bridge = ActiveBridge.get();
                if (player == null || bridge == null) {
                    return Suggestions.empty();
                }
                return SharedSuggestionProvider.suggest(bridge.listBones(player), builder);
            };

    private YsmVrCommands() {
    }

    /** YSM 不可用时的占位指令，给出明确反馈。 */
    public static void registerPlaceholder(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(ROOT)
                .executes(ctx -> {
                    ctx.getSource().sendFailure(
                            Component.literal("未检测到可用的 YSM（官方 3.0 或 ModernYSM），ysmvr 已禁用"));
                    return 0;
                }));
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // 不加 requires：指令始终可见，处理器内部判空并给出明确反馈
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(ROOT);

        root.then(Commands.literal("bone")
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(BONE_NAMES)
                        .then(Commands.literal("rot")
                                .then(Commands.argument("rx", FloatArgumentType.floatArg())
                                        .then(Commands.argument("ry", FloatArgumentType.floatArg())
                                                .then(Commands.argument("rz", FloatArgumentType.floatArg())
                                                        .executes(ctx -> {
                                                            var name = StringArgumentType.getString(ctx, "name");
                                                            float rx = FloatArgumentType.getFloat(ctx, "rx");
                                                            float ry = FloatArgumentType.getFloat(ctx, "ry");
                                                            float rz = FloatArgumentType.getFloat(ctx, "rz");
                                                            return setBoneRot(ctx.getSource(), name, rx, ry, rz);
                                                        })))))
                        .then(Commands.literal("pos")
                                .then(Commands.argument("x", FloatArgumentType.floatArg())
                                        .then(Commands.argument("y", FloatArgumentType.floatArg())
                                                .then(Commands.argument("z", FloatArgumentType.floatArg())
                                                        .executes(ctx -> {
                                                            var name = StringArgumentType.getString(ctx, "name");
                                                            float x = FloatArgumentType.getFloat(ctx, "x");
                                                            float y = FloatArgumentType.getFloat(ctx, "y");
                                                            float z = FloatArgumentType.getFloat(ctx, "z");
                                                            return setBonePos(ctx.getSource(), name, x, y, z);
                                                        })))))
                        .then(Commands.literal("scale")
                                .then(Commands.argument("x", FloatArgumentType.floatArg())
                                        .then(Commands.argument("y", FloatArgumentType.floatArg())
                                                .then(Commands.argument("z", FloatArgumentType.floatArg())
                                                        .executes(ctx -> {
                                                            var name = StringArgumentType.getString(ctx, "name");
                                                            float x = FloatArgumentType.getFloat(ctx, "x");
                                                            float y = FloatArgumentType.getFloat(ctx, "y");
                                                            float z = FloatArgumentType.getFloat(ctx, "z");
                                                            return setBoneScale(ctx.getSource(), name, x, y, z);
                                                        })))))));

        root.then(Commands.literal("bones").executes(ctx -> listBones(ctx.getSource())));

        root.then(Commands.literal("clear")
                .executes(ctx -> {
                    var player = localPlayer(ctx.getSource());
                    var bridge = ActiveBridge.get();
                    if (player == null || bridge == null) {
                        return 0;
                    }
                    var store = bridge.store(player);
                    if (store != null) {
                        store.clearAll();
                    }
                    ctx.getSource().sendSuccess(() -> Component.literal("已清除全部骨骼覆盖"), false);
                    return 1;
                })
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(BONE_NAMES)
                        .executes(ctx -> {
                            var player = localPlayer(ctx.getSource());
                            var bridge = ActiveBridge.get();
                            if (player == null || bridge == null) {
                                return 0;
                            }
                            var name = StringArgumentType.getString(ctx, "name");
                            int pooled = bridge.resolveBone(player, name);
                            if (pooled < 0) {
                                ctx.getSource().sendFailure(Component.literal("未找到骨骼: " + name));
                                return 0;
                            }
                            var store = bridge.store(player);
                            if (store != null) {
                                store.clear(pooled);
                            }
                            ctx.getSource().sendSuccess(() -> Component.literal("已清除骨骼覆盖: " + name), false);
                            return 1;
                        })));

        root.then(Commands.literal("head")
                .then(Commands.argument("pitch", FloatArgumentType.floatArg(-180, 180))
                        .then(Commands.argument("yaw", FloatArgumentType.floatArg(-180, 180))
                                .executes(ctx -> {
                                    float pitch = FloatArgumentType.getFloat(ctx, "pitch");
                                    float yaw = FloatArgumentType.getFloat(ctx, "yaw");
                                    return setBoneRot(ctx.getSource(), "Head", pitch, yaw, 0);
                                }))));

        root.then(Commands.literal("vr")
                .then(Commands.literal("on").executes(ctx -> {
                    ClientSetup.setTrackingEnabled(true);
                    ctx.getSource().sendSuccess(() -> Component.literal("Vivecraft 追踪已开启"), false);
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    ClientSetup.setTrackingEnabled(false);
                    ctx.getSource().sendSuccess(() -> Component.literal("Vivecraft 追踪已关闭（保留手动覆盖）"), false);
                    return 1;
                })));

        root.then(Commands.literal("view")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "视角绑定: " + (VrViewConfig.enabled ? "开启" : "关闭")
                                    + "，偏移 (" + VrViewConfig.offsetX + ", " + VrViewConfig.offsetY
                                    + ", " + VrViewConfig.offsetZ + ") 米"
                                    + "，头部骨骼: " + (VrViewConfig.headBone.isEmpty()
                                            ? "自动(" + VrViewConfig.resolvedHeadBone() + ")"
                                            : VrViewConfig.headBone)), false);
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    VrViewConfig.setEnabled(true);
                    VrViewConfig.save();
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("视角绑定已开启（需 Vivecraft 追踪开启）"), false);
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    VrViewConfig.setEnabled(false);
                    VrViewConfig.save();
                    ctx.getSource().sendSuccess(() -> Component.literal("视角绑定已关闭"), false);
                    return 1;
                }))
                .then(Commands.literal("pos")
                        .then(Commands.argument("x", FloatArgumentType.floatArg())
                                .then(Commands.argument("y", FloatArgumentType.floatArg())
                                        .then(Commands.argument("z", FloatArgumentType.floatArg())
                                                .executes(ctx -> {
                                                    float x = FloatArgumentType.getFloat(ctx, "x");
                                                    float y = FloatArgumentType.getFloat(ctx, "y");
                                                    float z = FloatArgumentType.getFloat(ctx, "z");
                                                    VrViewConfig.setOffset(x, y, z);
                                                    VrViewConfig.save();
                                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                                            "视角偏移 → (" + x + ", " + y + ", " + z + ") 米"), false);
                                                    return 1;
                                                })))))
                .then(Commands.literal("head")
                        .then(Commands.argument("bone", StringArgumentType.word())
                                .suggests(BONE_NAMES)
                                .executes(ctx -> {
                                    String bone = StringArgumentType.getString(ctx, "bone");
                                    VrViewConfig.setHeadBone(bone);
                                    VrViewConfig.save();
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("视角头部骨骼 → " + bone), false);
                                    return 1;
                                }))));

        root.then(Commands.literal("calibrate")
                .executes(ctx -> {
                    if (!ModList.get().isLoaded("vivecraft")) {
                        ctx.getSource().sendFailure(Component.literal("姿势校准需要安装 Vivecraft"));
                        return 0;
                    }
                    VrCalibration.request();
                    ctx.getSource().sendSuccess(() -> Component.literal(VrBodyConfig.calibrationStandingPose
                            ? "姿势校准开始：请保持自然站立（手臂自然下垂），约 1.5 秒后捕获"
                            : "姿势校准开始：请保持 T 字姿势，约 1.5 秒后捕获"), false);
                    return 1;
                })
                .then(Commands.literal("auto").executes(ctx -> {
                    if (!ModList.get().isLoaded("vivecraft")) {
                        ctx.getSource().sendFailure(Component.literal("姿势校准需要安装 Vivecraft"));
                        return 0;
                    }
                    VrCalibration.requestAuto();
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "自动校准开始：按当前姿势连续多帧采集并平均，稍候写入绑定偏移"), false);
                    return 1;
                }))
                .then(Commands.literal("gui").executes(ctx -> {
                    if (!ModList.get().isLoaded("vivecraft")) {
                        ctx.getSource().sendFailure(Component.literal("姿势校准需要安装 Vivecraft"));
                        return 0;
                    }
                    var mc = Minecraft.getInstance();
                    mc.execute(() -> mc.setScreen(new CalibrationScreen()));
                    String poseName = VrBodyConfig.calibrationStandingPose ? "自然站立" : "T 字";
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "已打开姿势校准 GUI：YSM 目标为\"" + poseName
                                    + "\"；可点\"自动校准\"立即取当前姿势，或\"开始校准\"走 3 秒倒计时；"
                                    + "还有\"手臂微调\"按钮"), false);
                    return 1;
                })));

        root.then(Commands.literal("armtune")
                .executes(ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.execute(() -> mc.setScreen(new ArmTuneScreen()));
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "已打开手臂偏移微调 GUI（左/右臂 X/Y/Z 旋转与位置 Δ 滑块，实时保存）"), false);
                    return 1;
                }));

        root.then(Commands.literal("scale")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "身高适配: " + (VrBodyConfig.scaleEnabled ? "开启" : "关闭")
                                    + "，玩家大小: " + String.format("%.2fx", VrBodyConfig.playerSize)
                                    + "（只改玩家世界身高，不影响 YSM 模型；"
                                    + "模型大小用 /ysmvr modelsize；GUI: /ysmvr scale gui）"), false);
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    VrBodyConfig.setScaleEnabled(true);
                    VrBodyConfig.save();
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("身高适配已开启（跟随模型高度，需 Vivecraft 追踪开启）"), false);
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    VrBodyConfig.setScaleEnabled(false);
                    VrBodyConfig.save();
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "身高适配已关闭（玩家大小倍率仍可单独生效）"), false);
                    return 1;
                }))
                .then(Commands.literal("gui").executes(ctx -> {
                    var player = localPlayer(ctx.getSource());
                    if (player == null) {
                        return 0;
                    }
                    if (!ModList.get().isLoaded("vivecraft")) {
                        ctx.getSource().sendFailure(Component.literal("玩家大小设置需要安装 Vivecraft"));
                        return 0;
                    }
                    var mc = Minecraft.getInstance();
                    mc.execute(() -> mc.setScreen(new VrBindScreen(true)));
                    return 1;
                })));

        root.then(Commands.literal("size")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "玩家大小: " + String.format("%.2fx", VrBodyConfig.playerSize)
                                    + "（只改玩家世界身高，不影响 YSM 模型；身高适配"
                                    + (VrBodyConfig.scaleEnabled ? "开启" : "关闭")
                                    + "；范围 " + VrBodyConfig.PLAYER_SIZE_MIN + "–" + VrBodyConfig.PLAYER_SIZE_MAX
                                    + "；模型大小用 /ysmvr modelsize；"
                                    + "GUI: /ysmvr scale gui）"), false);
                    return 1;
                })
                .then(Commands.argument("size", FloatArgumentType.floatArg(
                                VrBodyConfig.PLAYER_SIZE_MIN, VrBodyConfig.PLAYER_SIZE_MAX))
                        .executes(ctx -> {
                            float size = FloatArgumentType.getFloat(ctx, "size");
                            VrBodyConfig.setPlayerSize(size);
                            VrBodyConfig.save();
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "玩家大小已设为 " + String.format("%.2fx", VrBodyConfig.playerSize)
                                            + "（模型同步缩放，观感大小不变）"), false);
                            return 1;
                        })));

        root.then(Commands.literal("modelsize")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "模型强制大小: " + String.format("%.2fx", VrBodyConfig.modelScale)
                                    + "，范围 " + VrBodyConfig.MODEL_SCALE_MIN + "–" + VrBodyConfig.MODEL_SCALE_MAX
                                    + "（给模型主根骨骼写缩放，所有模型生效；"
                                    + "跟随开启时玩家世界身高会自动对齐）"), false);
                    return 1;
                })
                .then(Commands.argument("size", FloatArgumentType.floatArg(
                                VrBodyConfig.MODEL_SCALE_MIN, VrBodyConfig.MODEL_SCALE_MAX))
                        .executes(ctx -> {
                            float size = FloatArgumentType.getFloat(ctx, "size");
                            VrBodyConfig.setModelScale(size);
                            VrBodyConfig.save();
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "模型强制大小已设为 " + String.format("%.2fx", VrBodyConfig.modelScale)
                                            + (Math.abs(VrBodyConfig.modelScale - 1.0f) < 1.0e-3f
                                                    ? "（已恢复模型自带大小）"
                                                    : "（跟随开启时玩家世界身高会自动对齐）")), false);
                            return 1;
                        })));

        root.then(Commands.literal("modelpos")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                            java.util.Locale.ROOT,
                            "模型位置偏移: x=%.2f y=%.2f z=%.2f 模型单位（1 格=16；"
                                    + "正/负方向按模型根坐标，用于修斯塔·柏等整体偏后）",
                            VrBodyConfig.modelOffsetX, VrBodyConfig.modelOffsetY,
                            VrBodyConfig.modelOffsetZ)), false);
                    return 1;
                })
                .then(Commands.literal("reset").executes(ctx -> {
                    VrBodyConfig.setModelOffset(0f, 0f, 0f);
                    VrBodyConfig.save();
                    ctx.getSource().sendSuccess(() -> Component.literal("模型位置偏移已重置"), false);
                    return 1;
                }))
                .then(Commands.argument("x", FloatArgumentType.floatArg(
                                VrBodyConfig.MODEL_OFFSET_MIN, VrBodyConfig.MODEL_OFFSET_MAX))
                        .then(Commands.argument("y", FloatArgumentType.floatArg(
                                        VrBodyConfig.MODEL_OFFSET_MIN, VrBodyConfig.MODEL_OFFSET_MAX))
                                .then(Commands.argument("z", FloatArgumentType.floatArg(
                                                VrBodyConfig.MODEL_OFFSET_MIN, VrBodyConfig.MODEL_OFFSET_MAX))
                                        .executes(ctx -> {
                                            float x = FloatArgumentType.getFloat(ctx, "x");
                                            float y = FloatArgumentType.getFloat(ctx, "y");
                                            float z = FloatArgumentType.getFloat(ctx, "z");
                                            VrBodyConfig.setModelOffset(x, y, z);
                                            VrBodyConfig.save();
                                            ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                                                    java.util.Locale.ROOT,
                                                    "模型位置偏移已设为 x=%.2f y=%.2f z=%.2f（模型单位）",
                                                    VrBodyConfig.modelOffsetX, VrBodyConfig.modelOffsetY,
                                                    VrBodyConfig.modelOffsetZ)), false);
                                            return 1;
                                        })))));

        root.then(Commands.literal("profile")
                .executes(ctx -> {
                    var player = localPlayer(ctx.getSource());
                    if (player == null) {
                        return 0;
                    }
                    var bridge = ActiveBridge.get();
                    String key = ModelProfileStore.currentKey(player, bridge);
                    var p = ModelProfileStore.getByKey(key);
                    ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                            java.util.Locale.ROOT,
                            "单模型适配 [%s]: 偏移=(%.1f,%.1f,%.1f)，主手=%s，副手=%s"
                                    + "（/ysmvr profile gui 打开 GUI）",
                            key, p.offsetX, p.offsetY, p.offsetZ,
                            p.mainArmBone.isEmpty() ? "自动" : p.mainArmBone,
                            p.offArmBone.isEmpty() ? "自动" : p.offArmBone)), false);
                    return 1;
                })
                .then(Commands.literal("gui").executes(ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.execute(() -> mc.setScreen(new ModelProfileScreen()));
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "已打开单模型适配 GUI（位置偏移 + 主/副手骨骼选择）"), false);
                    return 1;
                }))
                .then(Commands.literal("reset").executes(ctx -> {
                    var player = localPlayer(ctx.getSource());
                    if (player == null) {
                        return 0;
                    }
                    ModelProfileStore.reset(player, ActiveBridge.get());
                    ctx.getSource().sendSuccess(() -> Component.literal("本模型适配已重置"), false);
                    return 1;
                }))
                .then(Commands.literal("clearscale").executes(ctx -> {
                    var player = localPlayer(ctx.getSource());
                    if (player == null) {
                        return 0;
                    }
                    var bridge = ActiveBridge.get();
                    ModelProfileStore.setModelScaleOverride(player, bridge, null);
                    ModelProfileStore.setPlayerSizeOverride(player, bridge, null);
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "本模型的模型大小/玩家大小覆盖已清除（改用全局设置）"), false);
                    return 1;
                }))
                .then(Commands.literal("anim")
                        .then(Commands.literal("clear").executes(ctx -> {
                            var player = localPlayer(ctx.getSource());
                            if (player == null) {
                                return 0;
                            }
                            ModelProfileStore.clearAnimationOffset(player, ActiveBridge.get());
                            ctx.getSource().sendSuccess(() -> Component.literal("当前动画位置偏移已清除"), false);
                            return 1;
                        }))
                        .then(Commands.argument("x", FloatArgumentType.floatArg(
                                        VrBodyConfig.MODEL_OFFSET_MIN, VrBodyConfig.MODEL_OFFSET_MAX))
                                .then(Commands.argument("y", FloatArgumentType.floatArg(
                                                VrBodyConfig.MODEL_OFFSET_MIN, VrBodyConfig.MODEL_OFFSET_MAX))
                                        .then(Commands.argument("z", FloatArgumentType.floatArg(
                                                        VrBodyConfig.MODEL_OFFSET_MIN, VrBodyConfig.MODEL_OFFSET_MAX))
                                                .executes(ctx -> {
                                                    var player = localPlayer(ctx.getSource());
                                                    if (player == null) {
                                                        return 0;
                                                    }
                                                    float ax = FloatArgumentType.getFloat(ctx, "x");
                                                    float ay = FloatArgumentType.getFloat(ctx, "y");
                                                    float az = FloatArgumentType.getFloat(ctx, "z");
                                                    ModelProfileStore.setAnimationOffset(player, ActiveBridge.get(),
                                                            ax, ay, az);
                                                    ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                                                            java.util.Locale.ROOT,
                                                            "当前动画位置偏移已设为 (%.1f,%.1f,%.1f)（模型单位）",
                                                            ax, ay, az)), false);
                                                    return 1;
                                                })))))
                .then(Commands.literal("main")
                        .then(Commands.argument("bone", com.mojang.brigadier.arguments.StringArgumentType.string())
                                .executes(ctx -> {
                                    var player = localPlayer(ctx.getSource());
                                    if (player == null) {
                                        return 0;
                                    }
                                    var bridge = ActiveBridge.get();
                                    var p = ModelProfileStore.get(player, bridge);
                                    ModelProfileStore.setArmBones(player, bridge,
                                            com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "bone"),
                                            p.offArmBone);
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "本模型主手骨骼已设为 " + com.mojang.brigadier.arguments.StringArgumentType
                                                    .getString(ctx, "bone")), false);
                                    return 1;
                                })))
                .then(Commands.literal("off")
                        .then(Commands.argument("bone", com.mojang.brigadier.arguments.StringArgumentType.string())
                                .executes(ctx -> {
                                    var player = localPlayer(ctx.getSource());
                                    if (player == null) {
                                        return 0;
                                    }
                                    var bridge = ActiveBridge.get();
                                    var p = ModelProfileStore.get(player, bridge);
                                    ModelProfileStore.setArmBones(player, bridge,
                                            p.mainArmBone,
                                            com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "bone"));
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "本模型副手骨骼已设为 " + com.mojang.brigadier.arguments.StringArgumentType
                                                    .getString(ctx, "bone")), false);
                                    return 1;
                                }))));

        root.then(Commands.literal("hidehead")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "隐藏头部: " + (VrBodyConfig.hideHeadEnabled ? "开启" : "关闭")
                                    + "（仅第一人称；第三人称/他人视角仍可见）"), false);
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    VrBodyConfig.setHideHeadEnabled(true);
                    VrBodyConfig.save();
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("第一人称隐藏头部已开启"), false);
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    VrBodyConfig.setHideHeadEnabled(false);
                    VrBodyConfig.save();
                    ctx.getSource().sendSuccess(() -> Component.literal("第一人称隐藏头部已关闭"), false);
                    return 1;
                })));

        root.then(Commands.literal("hidesneak")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "潜行移动隐藏模型: " + (VrBodyConfig.hideModelSneakMoving ? "开启" : "关闭")
                                    + "（仅头显左右眼 pass；桌面镜像/他人视角保持可见）"), false);
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    VrBodyConfig.setHideModelSneakMoving(true);
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("潜行移动隐藏模型已开启"), false);
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    VrBodyConfig.setHideModelSneakMoving(false);
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("潜行移动隐藏模型已关闭"), false);
                    return 1;
                })));

        root.then(Commands.literal("pausemove")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "移动时暂停上半身: " + (VrBodyConfig.pauseUpperBodyOnMove ? "开启" : "关闭")
                                    + "（Root~UpperBody 旋转/位置锁 0，Head 表情/耳朵保留）"), false);
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    VrBodyConfig.setPauseUpperBodyOnMove(true);
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("移动时暂停上半身已开启"), false);
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    VrBodyConfig.setPauseUpperBodyOnMove(false);
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("移动时暂停上半身已关闭"), false);
                    return 1;
                })));

        root.then(Commands.literal("pausehold")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "手持武器/物品时暂停上半身: "
                                    + (VrBodyConfig.pauseUpperBodyOnHold ? "开启" : "关闭")
                                    + "（含 TACZ 枪械等 hold_mainhand 动画）"), false);
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    VrBodyConfig.setPauseUpperBodyOnHold(true);
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("手持时暂停上半身已开启"), false);
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    VrBodyConfig.setPauseUpperBodyOnHold(false);
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("手持时暂停上半身已关闭"), false);
                    return 1;
                })));

        root.then(Commands.literal("pauselower")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "手持武器/物品时暂停下半身: "
                                    + (VrBodyConfig.pauseLowerBodyOnHold ? "开启" : "关闭")
                                    + "（腿/脚/DownBody 位置+旋转锁 0）"), false);
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    VrBodyConfig.setPauseLowerBodyOnHold(true);
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("手持时暂停下半身已开启"), false);
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    VrBodyConfig.setPauseLowerBodyOnHold(false);
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("手持时暂停下半身已关闭"), false);
                    return 1;
                })));

        root.then(Commands.literal("pausetacz")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "TACZ 手部动画暂停: "
                                    + (VrBodyConfig.pauseTaczHandAnimations ? "开启" : "关闭")
                                    + "（仅持 TACZ 枪械时，手骨锁静止旋转）"), false);
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    VrBodyConfig.setPauseTaczHandAnimations(true);
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("TACZ 手部动画暂停已开启"), false);
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    VrBodyConfig.setPauseTaczHandAnimations(false);
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("TACZ 手部动画暂停已关闭"), false);
                    return 1;
                })));

        root.then(Commands.literal("hidehands")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "VR 隐藏 YSM 第一人称手臂: "
                                    + (VrBodyConfig.hideYsmFirstPersonArms ? "开启" : "关闭")
                                    + "（关闭后 12_little 等 arm.json 手会盖在主模型手外侧）"), false);
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    VrBodyConfig.setHideYsmFirstPersonArms(true);
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("VR 隐藏 YSM 第一人称手臂已开启"), false);
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    VrBodyConfig.setHideYsmFirstPersonArms(false);
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("VR 隐藏 YSM 第一人称手臂已关闭"), false);
                    return 1;
                })));

        root.then(Commands.literal("dumpbones").executes(ctx -> {
            var player = localPlayer(ctx.getSource());
            if (player == null) {
                return 0;
            }
            var bridge = ActiveBridge.get();
            String key = ModelProfileStore.currentKey(player, bridge);
            StringBuilder sb = new StringBuilder();
            sb.append("# ysmvr bone dump\n");
            sb.append("# model=").append(key).append('\n');
            sb.append("# name\tparent\tpivotX\tpivotY\tpivotZ\n");
            var bones = bridge.listBones(player);
            for (String name : bones) {
                if (name == null) {
                    continue;
                }
                String parent = bridge.getParentBoneName(player, name);
                float[] pivot = bridge.getBonePivot(player, name);
                sb.append(name).append('\t')
                        .append(parent == null ? "" : parent).append('\t')
                        .append(pivot == null ? "" : String.format(java.util.Locale.ROOT,
                                "%.3f\t%.3f\t%.3f", pivot[0], pivot[1], pivot[2]))
                        .append('\n');
            }
            String safe = key.replaceAll("[^A-Za-z0-9._-]", "_");
            var path = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get()
                    .resolve("ysmvr-bones-" + safe + ".txt");
            try {
                java.nio.file.Files.writeString(path, sb.toString(),
                        java.nio.charset.StandardCharsets.UTF_8);
                ctx.getSource().sendSuccess(() -> Component.literal("骨骼已导出: " + path), false);
            } catch (Exception e) {
                YsmVrMod.LOGGER.warn("[ysmvr] 骨骼导出失败", e);
                ctx.getSource().sendFailure(Component.literal("骨骼导出失败: " + e.getMessage()));
            }
            return 1;
        }));

        root.then(Commands.literal("hotbar")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "快捷栏覆盖 YSM 模型: " + (VrBodyConfig.hotbarOverModel ? "开启" : "关闭")
                                    + "（开启时渲染 hotbar 临时禁用深度测试，避免被模型遮挡）"), false);
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    VrBodyConfig.setHotbarOverModel(true);
                    VrBodyConfig.save();
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("快捷栏覆盖 YSM 模型已开启"), false);
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    VrBodyConfig.setHotbarOverModel(false);
                    VrBodyConfig.save();
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("快捷栏覆盖 YSM 模型已关闭"), false);
                    return 1;
                })));

        root.then(Commands.literal("vrcopy")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "原版姿态复制: " + (VrBodyConfig.vanillaCopyEnabled ? "开启" : "关闭")
                                    + "（把原版 VR 模型的肢体 IK 换算到 YSM 骨骼，替换旧追踪解算）"), false);
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    VrBodyConfig.setVanillaCopyEnabled(true);
                    VrBodyConfig.save();
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("原版姿态复制已开启"), false);
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    VrBodyConfig.setVanillaCopyEnabled(false);
                    VrBodyConfig.save();
                    ctx.getSource().sendSuccess(() -> Component.literal("原版姿态复制已关闭"), false);
                    return 1;
                })));

        root.then(Commands.literal("freeze")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "动画冻结: " + (VrBodyConfig.freezeModelAnimations ? "开启" : "关闭")
                                    + "（待机时非驱动骨骼锁定在绑定姿态；行走时见 moveanim）"), false);
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    VrBodyConfig.setFreezeModelAnimations(true);
                    VrBodyConfig.save();
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("动画冻结已开启"), false);
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    VrBodyConfig.setFreezeModelAnimations(false);
                    VrBodyConfig.save();
                    ctx.getSource().sendSuccess(() -> Component.literal("动画冻结已关闭（模型动画全部播放）"), false);
                    return 1;
                })));

        root.then(Commands.literal("moveanim")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "移动动画（行走时身体动画）: "
                                    + (VrBodyConfig.moveAnimationEnabled ? "开启" : "关闭")
                                    + "（开启：行走时躯干/腿脚播放 YSM 移动动画，手臂和头部仍由 VR 驱动；"
                                    + "关闭：全部非驱动骨骼冻结）"), false);
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    VrBodyConfig.setMoveAnimationEnabled(true);
                    VrBodyConfig.save();
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("移动动画已开启"), false);
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    VrBodyConfig.setMoveAnimationEnabled(false);
                    VrBodyConfig.save();
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("移动动画已关闭（行走时身体仍冻结）"), false);
                    return 1;
                })));

        root.then(Commands.literal("attackanim")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "攻击动画: " + (VrBodyConfig.attackAnimationEnabled ? "开启" : "关闭")
                                    + "（开启：挥击时除头部仍跟 HMD 外，其余骨骼播放 YSM 挥击动画；"
                                    + "关闭：挥击动画被 VR 姿态覆盖，即无攻击动画）"), false);
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    VrBodyConfig.setAttackAnimationEnabled(true);
                    VrBodyConfig.save();
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("攻击动画已开启"), false);
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    VrBodyConfig.setAttackAnimationEnabled(false);
                    VrBodyConfig.save();
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("攻击动画已关闭（无攻击动画）"), false);
                    return 1;
                })));

        root.then(Commands.literal("animdiag")
                .executes(ctx -> {
                    var player = localPlayer(ctx.getSource());
                    if (player == null) {
                        return 0;
                    }
                    var bridge = ActiveBridge.get();
                    if (bridge == null) {
                        ctx.getSource().sendFailure(Component.literal("YSM 桥接不可用"));
                        return 0;
                    }
                    java.util.List<String> lines = new java.util.ArrayList<>();
                    lines.add("=== ysmvr 动画诊断 ===");
                    lines.addAll(bridge.animationDiagnostics(player));
                    lines.add("轮盘动画播放中: " + bridge.isPlayingYsmAnimation(player));
                    ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
                    return 1;
                }));

        root.then(Commands.literal("handpos")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "手部位置追踪（骨骼分离）: " + (VrBodyConfig.handPositionTracking ? "开启" : "关闭")
                                    + "（手停留控制器位置，身体/头部旋转时不被带动）"), false);
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    VrBodyConfig.setHandPositionTracking(true);
                    VrBodyConfig.save();
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("手部位置追踪已开启"), false);
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    VrBodyConfig.setHandPositionTracking(false);
                    VrBodyConfig.save();
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("手部位置追踪已关闭"), false);
                    return 1;
                })));

        root.then(Commands.literal("handalign")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "手持物品自动对齐: " + (VrBodyConfig.handItemAlign ? "开启" : "关闭")
                                    + "（YSM HandLocator → 原版 VR 模型手持物品锚点）"), false);
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    VrBodyConfig.setHandItemAlign(true);
                    VrBodyConfig.save();
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("手持物品自动对齐已开启"), false);
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    VrBodyConfig.setHandItemAlign(false);
                    VrBodyConfig.save();
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("手持物品自动对齐已关闭（改用 binding.pos 手动微调）"), false);
                    return 1;
                })));

        root.then(Commands.literal("mode")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "追踪模式: " + (VrBindings.modeAuto() ? "AUTO（自动检测）"
                                    : VrBindings.mode() + "（手动锁定）")
                                    + "——SIMPLE：手柄→整臂、脚→大腿；EXTENDED：手柄→前臂、"
                                    + "脚→小腿、肘→上臂、膝→大腿"), false);
                    return 1;
                })
                .then(Commands.literal("auto").executes(ctx -> {
                    VrBindings.setAuto();
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("追踪模式 → AUTO（自动检测，下一帧生效）"), false);
                    return 1;
                }))
                .then(Commands.literal("simple").executes(ctx -> {
                    VrBindings.setManualMode(VrBindings.Mode.SIMPLE);
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("追踪模式 → SIMPLE（手动锁定）"), false);
                    return 1;
                }))
                .then(Commands.literal("extended").executes(ctx -> {
                    VrBindings.setManualMode(VrBindings.Mode.EXTENDED);
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("追踪模式 → EXTENDED（手动锁定）"), false);
                    return 1;
                })));

        root.then(Commands.literal("debug")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "数值调试日志: " + (VrBodyConfig.debugLog ? "开启" : "关闭")
                                    + "（原版角度/矩阵/store 值与位置复制数值，限流输出）"), false);
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    VrBodyConfig.setDebugLog(true);
                    VrBodyConfig.save();
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("数值调试日志已开启（查看日志 latest.log 的 [ysmvr][debug]）"), false);
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    VrBodyConfig.setDebugLog(false);
                    VrBodyConfig.save();
                    ctx.getSource().sendSuccess(() -> Component.literal("数值调试日志已关闭"), false);
                    return 1;
                })));

        root.then(Commands.literal("status").executes(ctx -> status(ctx.getSource())));

        root.then(Commands.literal("headyaw")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "头部 yaw 补偿: " + VrBodyConfig.headYawOffset + "°"
                                    + "（90° 头偏移可按需设为 90/-90）"), false);
                    return 1;
                })
                .then(Commands.argument("deg", FloatArgumentType.floatArg())
                        .executes(ctx -> {
                            float deg = FloatArgumentType.getFloat(ctx, "deg");
                            VrBodyConfig.setHeadYawOffset(deg);
                            VrBodyConfig.save();
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal(
                                            "头部 yaw 补偿 → " + deg + "°（作用于 Head 骨骼局部 yaw）"),
                                    false);
                            return 1;
                        })));

        root.then(Commands.literal("headroll")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "头部 roll 补偿: " + VrBodyConfig.headRollOffset + "°"
                                    + "（仅作用于 Head 骨骼局部 rz，通常保持 0）"), false);
                    return 1;
                })
                .then(Commands.argument("deg", FloatArgumentType.floatArg())
                        .executes(ctx -> {
                            float deg = FloatArgumentType.getFloat(ctx, "deg");
                            VrBodyConfig.setHeadRollOffset(deg);
                            VrBodyConfig.save();
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal(
                                            "头部 roll 补偿 → " + deg + "°（作用于 Head 骨骼局部 roll/rz）"),
                                    false);
                            return 1;
                        })));

        root.then(Commands.literal("main").executes(ctx -> {
            var mc = Minecraft.getInstance();
            mc.execute(() -> mc.setScreen(new YsmVrMainScreen()));
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "已打开 YSM VR 总设置 GUI"), false);
            return 1;
        }));

        root.then(Commands.literal("gui").executes(ctx -> {
            var player = localPlayer(ctx.getSource());
            if (player == null || ActiveBridge.get() == null) {
                return 0;
            }
            var mc = Minecraft.getInstance();
            mc.execute(() -> mc.setScreen(new BoneViewerScreen()));
            return 1;
        }));

        root.then(Commands.literal("face").executes(ctx -> {
            var player = localPlayer(ctx.getSource());
            if (player == null || ActiveBridge.get() == null) {
                return 0;
            }
            var mc = Minecraft.getInstance();
            mc.execute(() -> mc.setScreen(new FaceInspectScreen()));
            return 1;
        }));

        root.then(Commands.literal("sim")
                .then(Commands.literal("on").executes(ctx -> {
                    VrSimulator.setEnabled(true);
                    ctx.getSource().sendSuccess(() -> Component.literal("[ysmvr] VR 模拟模式已开启"), false);
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    VrSimulator.setEnabled(false);
                    ctx.getSource().sendSuccess(() -> Component.literal("[ysmvr] VR 模拟模式已关闭"), false);
                    return 1;
                }))
                .then(Commands.literal("reset").executes(ctx -> {
                    VrSimulator.reset();
                    ctx.getSource().sendSuccess(() -> Component.literal("[ysmvr] VR 模拟数据已重置"), false);
                    return 1;
                }))
                .then(Commands.literal("status").executes(ctx -> {
                    VrSimulator.printStatus(ctx.getSource());
                    return 1;
                }))
                .then(Commands.literal("head")
                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                .then(Commands.argument("pitch", DoubleArgumentType.doubleArg())
                                                        .then(Commands.argument("yaw", DoubleArgumentType.doubleArg())
                                                                .then(Commands.argument("roll", DoubleArgumentType.doubleArg())
                                                                        .executes(ctx -> {
                                                                            double x = DoubleArgumentType.getDouble(ctx, "x");
                                                                            double y = DoubleArgumentType.getDouble(ctx, "y");
                                                                            double z = DoubleArgumentType.getDouble(ctx, "z");
                                                                            double pitch = DoubleArgumentType.getDouble(ctx, "pitch");
                                                                            double yaw = DoubleArgumentType.getDouble(ctx, "yaw");
                                                                            double roll = DoubleArgumentType.getDouble(ctx, "roll");
                                                                            VrSimulator.setHead(x, y, z, pitch, yaw, roll);
                                                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                                                    String.format("[ysmvr] 模拟头部：pos=(%.2f,%.2f,%.2f) rot=(%.1f,%.1f,%.1f)", x, y, z, pitch, yaw, roll)), false);
                                                                            return 1;
                                                                        }))))))))
                .then(Commands.literal("hand")
                        .then(Commands.argument("side", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    builder.suggest("main");
                                    builder.suggest("off");
                                    return builder.buildFuture();
                                })
                                .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                                .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                        .then(Commands.argument("pitch", DoubleArgumentType.doubleArg())
                                                                .then(Commands.argument("yaw", DoubleArgumentType.doubleArg())
                                                                        .then(Commands.argument("roll", DoubleArgumentType.doubleArg())
                                                                                .executes(ctx -> {
                                                                                    String side = StringArgumentType.getString(ctx, "side");
                                                                                    boolean main = side.equalsIgnoreCase("main");
                                                                                    double x = DoubleArgumentType.getDouble(ctx, "x");
                                                                                    double y = DoubleArgumentType.getDouble(ctx, "y");
                                                                                    double z = DoubleArgumentType.getDouble(ctx, "z");
                                                                                    double pitch = DoubleArgumentType.getDouble(ctx, "pitch");
                                                                                    double yaw = DoubleArgumentType.getDouble(ctx, "yaw");
                                                                                    double roll = DoubleArgumentType.getDouble(ctx, "roll");
                                                                                    VrSimulator.setHand(main, x, y, z, pitch, yaw, roll);
                                                                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                                                                            String.format("[ysmvr] 模拟 %s 手：pos=(%.2f,%.2f,%.2f) rot=(%.1f,%.1f,%.1f)",
                                                                                                    main ? "主" : "副", x, y, z, pitch, yaw, roll)), false);
                                                                                    return 1;
                                                                                })))))))))
                .then(Commands.literal("preset")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    builder.suggest("tpose");
                                    builder.suggest("forward");
                                    builder.suggest("up");
                                    builder.suggest("clap");
                                    builder.suggest("idle");
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "name").toLowerCase();
                                    switch (name) {
                                        case "idle":
                                            VrSimulator.setHead(0, 1.6, 0, 0, 0, 0);
                                            VrSimulator.setHand(true, 0.3, 1.2, 0.2, 0, 0, 0);
                                            VrSimulator.setHand(false, -0.3, 1.2, 0.2, 0, 0, 0);
                                            break;
                                        case "tpose":
                                            VrSimulator.setHead(0, 1.6, 0, 0, 0, 0);
                                            VrSimulator.setHand(true, 0.8, 1.4, 0, 0, 90, 0);
                                            VrSimulator.setHand(false, -0.8, 1.4, 0, 0, -90, 0);
                                            break;
                                        case "forward":
                                            VrSimulator.setHead(0, 1.6, 0, 0, 0, 0);
                                            VrSimulator.setHand(true, 0.3, 1.4, 0.6, 0, 0, 0);
                                            VrSimulator.setHand(false, -0.3, 1.4, 0.6, 0, 0, 0);
                                            break;
                                        case "up":
                                            VrSimulator.setHead(0, 1.6, 0, 0, 0, 0);
                                            VrSimulator.setHand(true, 0.2, 2.2, 0, 0, 0, 0);
                                            VrSimulator.setHand(false, -0.2, 2.2, 0, 0, 0, 0);
                                            break;
                                        case "clap":
                                            // 双手胸前相合：右手 yaw=0、左手 yaw=180° 使掌心参考相对
                                            VrSimulator.setHead(0, 1.6, 0, 0, 0, 0);
                                            VrSimulator.setHand(true, 0.06, 1.35, 0.35, 0, 0, 0);
                                            VrSimulator.setHand(false, -0.06, 1.35, 0.35, 0, 180, 0);
                                            break;
                                        default:
                                            ctx.getSource().sendFailure(Component.literal("未知预设: " + name));
                                            return 0;
                                    }
                                    VrSimulator.setEnabled(true);
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "[ysmvr] 已加载模拟预设: " + name), false);
                                    return 1;
                                }))));

        root.then(Commands.literal("bind").executes(ctx -> {
            var player = localPlayer(ctx.getSource());
            if (player == null || ActiveBridge.get() == null) {
                return 0;
            }
            if (!ModList.get().isLoaded("vivecraft")) {
                ctx.getSource().sendFailure(Component.literal("绑定配置需要安装 Vivecraft"));
                return 0;
            }
            var mc = Minecraft.getInstance();
            mc.execute(() -> mc.setScreen(new VrBindScreen()));
            return 1;
        }));

        dispatcher.register(root);
    }

    private static int setBoneRot(CommandSourceStack source, String name, float rx, float ry, float rz) {
        var player = localPlayer(source);
        if (player == null) {
            return 0;
        }
        var bridge = ActiveBridge.get();
        if (bridge == null) {
            return 0;
        }
        int pooled = bridge.resolveBone(player, name);
        if (pooled < 0) {
            source.sendFailure(Component.literal("未找到骨骼: " + name));
            return 0;
        }
        var store = bridge.store(player);
        if (store != null) {
            store.setRotation(pooled, (float) Math.toRadians(rx), (float) Math.toRadians(ry), (float) Math.toRadians(rz));
        }
        source.sendSuccess(() -> Component.literal(
                "骨骼 " + name + " 旋转 → (" + rx + "°, " + ry + "°, " + rz + "°)"), false);
        return 1;
    }

    private static int setBonePos(CommandSourceStack source, String name, float x, float y, float z) {
        var player = localPlayer(source);
        if (player == null) {
            return 0;
        }
        var bridge = ActiveBridge.get();
        if (bridge == null) {
            return 0;
        }
        int pooled = bridge.resolveBone(player, name);
        if (pooled < 0) {
            source.sendFailure(Component.literal("未找到骨骼: " + name));
            return 0;
        }
        var store = bridge.store(player);
        if (store != null) {
            store.setPosition(pooled, x, y, z);
        }
        source.sendSuccess(() -> Component.literal(
                "骨骼 " + name + " 位置 → (" + x + ", " + y + ", " + z + ") 模型单位"), false);
        return 1;
    }

    private static int setBoneScale(CommandSourceStack source, String name, float x, float y, float z) {
        var player = localPlayer(source);
        if (player == null) {
            return 0;
        }
        var bridge = ActiveBridge.get();
        if (bridge == null) {
            return 0;
        }
        int pooled = bridge.resolveBone(player, name);
        if (pooled < 0) {
            source.sendFailure(Component.literal("未找到骨骼: " + name));
            return 0;
        }
        var store = bridge.store(player);
        if (store != null) {
            store.setScale(pooled, x, y, z);
        }
        source.sendSuccess(() -> Component.literal(
                "骨骼 " + name + " 缩放 → (" + x + ", " + y + ", " + z + ")"), false);
        return 1;
    }

    private static int listBones(CommandSourceStack source) {
        var player = localPlayer(source);
        if (player == null) {
            return 0;
        }
        var bridge = ActiveBridge.get();
        if (bridge == null) {
            return 0;
        }
        var names = bridge.listBones(player);
        if (names.isEmpty()) {
            source.sendFailure(Component.literal("当前没有已加载的 YSM 模型"));
            return 0;
        }
        var store = bridge.store(player);
        List<String> lines = new ArrayList<>();
        lines.add("骨骼数: " + names.size());
        for (var name : names) {
            int pooled = bridge.resolveBone(player, name);
            var pose = store == null ? null : store.get(pooled);
            lines.add(pose == null ? "  " + name : "  " + name + "  [覆盖中]");
        }
        source.sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
        return 1;
    }

    private static int status(CommandSourceStack source) {
        var player = localPlayer(source);
        if (player == null) {
            return 0;
        }
        var bridge = ActiveBridge.get();
        List<String> lines = new ArrayList<>();
        if (bridge == null) {
            lines.add("YSM 桥接: 不可用");
        } else {
            lines.add("YSM 桥接: " + (bridge instanceof ModernYsmBridge ? "ModernYSM" : "官方 3.0"));
            lines.add("模型已加载: " + bridge.isModelLoaded(player));
            lines.add("控制器已挂载: " + bridge.isAttached(player));
            var store = bridge.store(player);
            lines.add("覆盖数: " + (store == null ? 0 : store.overrides().size()));
        }
        lines.add("追踪开启: " + ClientSetup.isTrackingEnabled());
        lines.add("视角绑定: " + (VrViewConfig.enabled ? "开启" : "关闭")
                + "（头部骨骼: " + VrViewConfig.resolvedHeadBone() + "）");
        String scaleBone = bridge == null ? null : bridge.modelScaleBone(player);
        float actualModelScale = VrBodyConfig.modelScale;
        lines.add("身高适配: " + (VrBodyConfig.scaleEnabled ? "开启" : "关闭")
                + "，玩家大小: " + String.format("%.2fx", VrBodyConfig.playerSize)
                + "（只改玩家，不影响模型）"
                + "，模型大小: " + String.format("%.2fx", VrBodyConfig.modelScale)
                + "，模型位置偏移: " + String.format(java.util.Locale.ROOT,
                        "(%.1f,%.1f,%.1f)", VrBodyConfig.modelOffsetX,
                        VrBodyConfig.modelOffsetY, VrBodyConfig.modelOffsetZ)
                + "，实际模型缩放: " + String.format("%.2fx", actualModelScale)
                + "，手柄比例: " + String.format("%.2fx", VivecraftPoseTracker.latestHandScaleRatio)
                + (Math.abs(actualModelScale - 1.0f) > 1.0e-3f
                        ? (scaleBone == null ? "（平台不支持）" : "（缩放骨骼: " + scaleBone + "）") : "")
                + "，隐藏头部: " + (VrBodyConfig.hideHeadEnabled ? "开启" : "关闭")
                + "，潜行移动隐藏: " + (VrBodyConfig.hideModelSneakMoving ? "开启" : "关闭")
                + "，移动暂停上半身: " + (VrBodyConfig.pauseUpperBodyOnMove ? "开启" : "关闭")
                + "，手持暂停上半身: " + (VrBodyConfig.pauseUpperBodyOnHold ? "开启" : "关闭")
                + "，手持暂停下半身: " + (VrBodyConfig.pauseLowerBodyOnHold ? "开启" : "关闭")
                + "，TACZ手部暂停: " + (VrBodyConfig.pauseTaczHandAnimations ? "开启" : "关闭")
                + "，隐藏YSM第一人称手: " + (VrBodyConfig.hideYsmFirstPersonArms ? "开启" : "关闭")
                + "，快捷栏覆盖: " + (VrBodyConfig.hotbarOverModel ? "开启" : "关闭"));
        lines.add("原版姿态复制: " + (VrBodyConfig.vanillaCopyEnabled ? "开启" : "关闭")
                + "，动画冻结: " + (VrBodyConfig.freezeModelAnimations ? "开启" : "关闭")
                + "，移动动画: " + (VrBodyConfig.moveAnimationEnabled ? "开启" : "关闭")
                + "，攻击动画: " + (VrBodyConfig.attackAnimationEnabled ? "开启" : "关闭"));
        lines.add("手部位置追踪: " + (VrBodyConfig.handPositionTracking ? "开启" : "关闭")
                + "，手持物品自动对齐: " + (VrBodyConfig.handItemAlign ? "开启" : "关闭")
                + "（状态: " + VivecraftPoseTracker.latestHandAlignState + "）");
        lines.add("单模型适配: " + ModelProfileStore.currentKey(player, bridge)
                + "（/ysmvr profile gui）");
        lines.add("头部 yaw 补偿: " + VrBodyConfig.headYawOffset + "°");
        if (ModList.get().isLoaded("vivecraft")) {
            try {
                var api = org.vivecraft.api.client.VRClientAPI.instance();
                lines.add("Vivecraft: VR激活=" + (api != null && api.isVRActive())
                        + ", FBT模式=" + (api != null ? api.getFBTMode() : "?"));
                // 逐部位数据可用性：=无 的部位 Vivecraft 没有提供追踪数据
                // （膝/肘部位要求扩展 FBT 校准完成，见 /ysmvr bind GUI 底部 FBT 模式）
                if (api != null && api.isVRActive()) {
                    var pose = api.getWorldRenderPose();
                    if (pose != null) {
                        var sb = new StringBuilder("FBT数据: ");
                        for (var part : org.vivecraft.api.data.VRBodyPart.values()) {
                            if (part == org.vivecraft.api.data.VRBodyPart.HEAD) {
                                continue;
                            }
                            sb.append(partLabel(part)).append("=")
                                    .append(pose.getBodyPartData(part) != null ? "有 " : "无 ");
                        }
                        lines.add(sb.toString().trim());
                        // 自识别：未分配角色的原始追踪器按位置分类补足的部位
                        float bodyYaw = (float) Math.toRadians(net.minecraft.util.Mth.rotLerp(
                                Minecraft.getInstance().getFrameTime(),
                                player.yBodyRotO, player.yBodyRot));
                        var self = VrRawTrackers.classify(pose, bodyYaw);
                        if (!self.isEmpty()) {
                            var selfSb = new StringBuilder("自识别: ");
                            for (var part : self.keySet()) {
                                selfSb.append(partLabel(part)).append(" ");
                            }
                            lines.add(selfSb.toString().trim());
                        }
                    }
                }
            } catch (Throwable t) {
                lines.add("Vivecraft: 状态获取失败 (" + t.getClass().getSimpleName() + ")");
            }
        } else {
            lines.add("Vivecraft: 未安装");
        }
        source.sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
        return 1;
    }

    /** VR 部位的显示名（与绑定 GUI 的部位命名一致）。 */
    private static String partLabel(org.vivecraft.api.data.VRBodyPart part) {
        return switch (part) {
            case HEAD -> "头";
            case MAIN_HAND -> "主手";
            case OFF_HAND -> "副手";
            case WAIST -> "腰";
            case RIGHT_FOOT -> "右小腿";
            case LEFT_FOOT -> "左小腿";
            case RIGHT_KNEE -> "右大腿";
            case LEFT_KNEE -> "左大腿";
            case RIGHT_ELBOW -> "右上臂";
            case LEFT_ELBOW -> "左上臂";
        };
    }

    /**
     * 从指令源取本地玩家。
     * <p>
     * 注意：单机下指令在集成服务器端执行，source.getEntity() 是 ServerPlayer
     * 而非 LocalPlayer（客户端与服务端是不同对象，走本地网络栈）——因此与 YSM 官方
     * CommandUtil 一样用 UUID 比对，且始终返回客户端的 Minecraft.player。
     */
    private static LocalPlayer localPlayer(CommandSourceStack source) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            source.sendFailure(Component.literal("当前没有本地玩家"));
            return null;
        }
        var sender = source.getEntity();
        if (sender != null && !sender.getUUID().equals(player.getUUID())) {
            source.sendFailure(Component.literal("仅本地玩家可执行此指令"));
            return null;
        }
        return player;
    }
}
