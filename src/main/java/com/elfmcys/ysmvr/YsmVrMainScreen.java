package com.elfmcys.ysmvr;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * v0.2.55 总设置 GUI：VRChat 风格深色面板 + 分类/滚轮 + 自绘按钮。
 * 打开：YSM 轮盘动作 GUI 左下角按钮 / key.ysmvr.open_main / /ysmvr main。
 */
@OnlyIn(Dist.CLIENT)
public final class YsmVrMainScreen extends Screen {

    private static final int PANEL_W = 320;
    private static final int W = 145;
    private static final int H = 16;
    private static final int GAP = 2;
    private static final int ACCENT = 0xFF3FB6FF;
    private static final int ACCENT_DIM = 0xFF24516E;
    private static final int BG = 0xE0101118;
    private static final int PANEL = 0xE01A1D26;
    private static final int BORDER = 0xFF3FB6FF;
    private static final int TEXT = 0xFFF2F5F8;
    private static final int TEXT_DIM = 0xFF9AA4B2;
    private static final int OFF = 0xFF3A3F4B;

    private static final String[] CATEGORIES = {"基础", "暂停", "手臂", "界面", "调试"};
    private static final String[] TRACK_MODES = {"简单（无全身）", "入门（腰追+脚追）", "腿部全追（无手追）", "Vivecraft全追", "全身触手（彩蛋）", "全身（T字校准实验）"};

    private int category;

    private record ToggleDef(String label, Supplier<Boolean> getter, Consumer<Boolean> setter) {
    }

    private record ScreenDef(String label, Supplier<Screen> screen) {
    }

    private static final List<ToggleDef> BASE_TOGGLES = List.of(
            new ToggleDef("隐藏头部", () -> VrBodyConfig.hideHeadEnabled, VrBodyConfig::setHideHeadEnabled),
            new ToggleDef("手臂超长延伸", () -> VrBodyConfig.armReachExtension, VrBodyConfig::setArmReachExtension),
            new ToggleDef("潜行移动隐藏模型", () -> VrBodyConfig.hideModelSneakMoving, VrBodyConfig::setHideModelSneakMoving),
            new ToggleDef("潜行时隐藏模型", () -> VrBodyConfig.hideModelWhenSneaking, VrBodyConfig::setHideModelWhenSneaking),
            new ToggleDef("创造飞行隐藏模型", () -> VrBodyConfig.hideModelCreativeFlying, VrBodyConfig::setHideModelCreativeFlying),
            new ToggleDef("鞘翅飞行隐藏模型", () -> VrBodyConfig.hideModelElytraFlying, VrBodyConfig::setHideModelElytraFlying),
            new ToggleDef("全身躺下适配", () -> VrBodyConfig.fullBodyLyingMode, VrBodyConfig::setFullBodyLyingMode),
            new ToggleDef("快捷栏覆盖模型", () -> VrBodyConfig.hotbarOverModel, VrBodyConfig::setHotbarOverModel),
            new ToggleDef("原版姿态复制", () -> VrBodyConfig.vanillaCopyEnabled, VrBodyConfig::setVanillaCopyEnabled),
            new ToggleDef("动画冻结", () -> VrBodyConfig.freezeModelAnimations, VrBodyConfig::setFreezeModelAnimations),
            new ToggleDef("移动动画", () -> VrBodyConfig.moveAnimationEnabled, VrBodyConfig::setMoveAnimationEnabled),
            new ToggleDef("攻击动画", () -> VrBodyConfig.attackAnimationEnabled, VrBodyConfig::setAttackAnimationEnabled),
            new ToggleDef("隐藏VR手持物品", () -> VrBodyConfig.hideVrHeldItem, VrBodyConfig::setHideVrHeldItem),
            new ToggleDef("隐藏YSM手持物品", () -> VrBodyConfig.hideYsmHeldItem, VrBodyConfig::setHideYsmHeldItem)
    );

    private static final List<ToggleDef> PAUSE_TOGGLES = List.of(
            new ToggleDef("移动暂停上半身", () -> VrBodyConfig.pauseUpperBodyOnMove, VrBodyConfig::setPauseUpperBodyOnMove),
            new ToggleDef("持械暂停上半身", () -> VrBodyConfig.pauseUpperBodyOnHold, VrBodyConfig::setPauseUpperBodyOnHold),
            new ToggleDef("持械暂停下半身Y", () -> VrBodyConfig.pauseLowerBodyOnHold, VrBodyConfig::setPauseLowerBodyOnHold),
            new ToggleDef("TACZ手部动画暂停", () -> VrBodyConfig.pauseTaczHandAnimations, VrBodyConfig::setPauseTaczHandAnimations),
            new ToggleDef("TACZ装弹暂停", () -> VrBodyConfig.pauseTaczReload, VrBodyConfig::setPauseTaczReload),
            new ToggleDef("TACZ观察暂停", () -> VrBodyConfig.pauseTaczInspect, VrBodyConfig::setPauseTaczInspect),
            new ToggleDef("TACZ瞄准暂停", () -> VrBodyConfig.pauseTaczAim, VrBodyConfig::setPauseTaczAim),
            new ToggleDef("隐藏YSM第一人称手", () -> VrBodyConfig.hideYsmFirstPersonArms, VrBodyConfig::setHideYsmFirstPersonArms),
            new ToggleDef("持械取消手臂动画", () -> VrBodyConfig.cancelArmAnimation, VrBodyConfig::setCancelArmAnimation)
    );

    private static final List<ToggleDef> ARM_TOGGLES = List.of(
            new ToggleDef("手部位置追踪", () -> VrBodyConfig.handPositionTracking, VrBodyConfig::setHandPositionTracking),
            new ToggleDef("手持物品自动对齐", () -> VrBodyConfig.handItemAlign, VrBodyConfig::setHandItemAlign),
            new ToggleDef("校准目标=自然站立", () -> VrBodyConfig.calibrationStandingPose, VrBodyConfig::setCalibrationStandingPose),
            new ToggleDef("手臂直接旋转（T字校准后）", () -> VrBodyConfig.armDirectRotation, VrBodyConfig::setArmDirectRotation),
            new ToggleDef("直接旋转反转肘方向", () -> VrBodyConfig.armDirectPoleFlip, VrBodyConfig::setArmDirectPoleFlip)
    );

    private static final List<ToggleDef> DEBUG_TOGGLES = List.of(
            new ToggleDef("身高跟随", () -> VrBodyConfig.scaleEnabled, VrBodyConfig::setScaleEnabled),
            new ToggleDef("面部追踪", () -> VrBodyConfig.faceTrackingEnabled, VrBodyConfig::setFaceTrackingEnabled),
            new ToggleDef("调试日志", () -> VrBodyConfig.debugLog, VrBodyConfig::setDebugLog),
            new ToggleDef("手部位置追踪", () -> VrBodyConfig.handPositionTracking, VrBodyConfig::setHandPositionTracking)
    );

    private static final List<ScreenDef> SCREEN_BUTTONS = List.of(
            new ScreenDef("打开骨骼绑定", () -> new VrBindScreen(false)),
            new ScreenDef("打开大小设置", () -> new VrBindScreen(true)),
            new ScreenDef("打开手臂微调", ArmTuneScreen::new),
            new ScreenDef("打开单模型适配", ModelProfileScreen::new),
            new ScreenDef("当前模型信息", CurrentModelInfoScreen::new),
            new ScreenDef("打开姿势校准 GUI", CalibrationScreen::new)
    );

    private static final List<ScreenDef> DEBUG_SCREENS = List.of(
            new ScreenDef("打开骨骼查看器", BoneViewerScreen::new),
            new ScreenDef("打开面部检查", FaceInspectScreen::new)
    );

    public YsmVrMainScreen() {
        super(Component.literal("YSM VR 设置"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int panelLeft() {
        return width / 2 - PANEL_W / 2;
    }

    private int panelTop() {
        return height / 2 - 105;
    }

    @Override
    protected void init() {
        int panelX = panelLeft();
        int top = panelTop();
        // 顶部 5 个分类页签
        for (int i = 0; i < CATEGORIES.length; i++) {
            final int idx = i;
            StyleButton tab = new StyleButton(panelX + 8 + i * 61, top + 24, 58, 15,
                    Component.literal(CATEGORIES[i]), b -> switchCategory(idx));
            tab.setAccent(i == category ? ACCENT : OFF);
            addRenderableWidget(tab);
        }

        List<ToggleDef> toggles = switch (category) {
            case 1 -> PAUSE_TOGGLES;
            case 2 -> ARM_TOGGLES;
            case 3 -> List.of();
            case 4 -> DEBUG_TOGGLES;
            default -> BASE_TOGGLES;
        };
        int left = panelX + 10;
        int right = panelX + PANEL_W - W - 10;
        for (int i = 0; i < toggles.size(); i++) {
            int x = (i % 2 == 0) ? left : right;
            int y = top + 46 + (i / 2) * (H + GAP);
            ToggleDef def = toggles.get(i);
            addRenderableWidget(new ToggleButton(x, y, W, H, def.label(), def.getter(), def.setter()));
        }

        if (category == 0) {
            final int rows = (BASE_TOGGLES.size() + 1) / 2;
            int modeY = top + 46 + rows * (H + GAP) + 2;
            StyleButton mode = new StyleButton(left, modeY,
                    PANEL_W - 20, H, Component.literal(trackModeLabel()), b -> {
                int next = (VrBodyConfig.trackingMode + 1) % 6;
                VrBodyConfig.setTrackingMode(next);
                VrBindings.setManualMode(next >= 2 ? VrBindings.Mode.EXTENDED : VrBindings.Mode.SIMPLE);
                b.setMessage(Component.literal(trackModeLabel()));
            });
            addRenderableWidget(mode);
        }

        if (category == 2) {
            final int rows = (ARM_TOGGLES.size() + 1) / 2;
            int armY = top + 46 + rows * (H + GAP) + 2;
            StyleButton leftJoint = new StyleButton(left, armY,
                    PANEL_W - 20, H, Component.literal(leftJointModeLabel()), b -> {
                VrBodyConfig.setLeftJointMode((VrBodyConfig.leftJointMode + 1) % 6);
                VrBodyConfig.save();
                b.setMessage(Component.literal(leftJointModeLabel()));
            });
            addRenderableWidget(leftJoint);
            StyleButton rightJoint = new StyleButton(left, armY + H + 2,
                    PANEL_W - 20, H, Component.literal(rightJointModeLabel()), b -> {
                VrBodyConfig.setRightJointMode((VrBodyConfig.rightJointMode + 1) % 6);
                VrBodyConfig.save();
                b.setMessage(Component.literal(rightJointModeLabel()));
            });
            addRenderableWidget(rightJoint);
            StyleButton armCal = new StyleButton(left, armY + 2 * (H + 2),
                    PANEL_W - 20, H, Component.literal("手臂校准（T 字，打开校准 GUI）"), b -> {
                VrBodyConfig.setCalibrationStandingPose(false);
                VrBodyConfig.setArmDirectRotation(true);
                VrBodyConfig.save();
                Minecraft.getInstance().setScreen(new CalibrationScreen());
            });
            addRenderableWidget(armCal);

            StyleButton fullCal = new StyleButton(left, armY + 3 * (H + 2),
                    PANEL_W - 20, H, Component.literal("全身校准（T 字，实验）"), b -> {
                VrBodyConfig.setTrackingMode(5);
                VrBindings.setManualMode(VrBindings.Mode.EXTENDED);
                VrBodyConfig.setCalibrationStandingPose(false);
                VrBodyConfig.save();
                FullBodyCalibration.clear();
                Minecraft.getInstance().setScreen(new CalibrationScreen());
            });
            addRenderableWidget(fullCal);        }
        List<ScreenDef> screens = category == 3 ? SCREEN_BUTTONS : category == 4 ? DEBUG_SCREENS : List.of();
        int baseY = top + 46 + ((toggles.size() + 1) / 2) * (H + GAP) + 4;
        for (int i = 0; i < screens.size(); i++) {
            ScreenDef def = screens.get(i);
            int x = (i % 2 == 0) ? left : right;
            int y = baseY + (i / 2) * (H + GAP);
            addRenderableWidget(new StyleButton(x, y, W, H, Component.literal(def.label()),
                    b -> open(def.screen().get())));
        }


        addRenderableWidget(new StyleButton(width / 2 - 45, top + 196, 90, 16,
                Component.literal("关闭"), b -> onClose()));
    }

    @Override
    public void onClose() {
        var local = Minecraft.getInstance().player;
        var bridge = ActiveBridge.get();
        if (local != null && bridge != null) {
            ModelProfileStore.saveCurrentBodyConfig(local, bridge);
        }
        super.onClose();
    }
    private static String rightJointModeLabel() {
        String[] names = {"无", "X", "Y", "Z", "全部", "屏蔽tracker"};
        int m = Math.max(0, Math.min(5, VrBodyConfig.rightJointMode));
        return "右臂关节修正: " + names[m] + "（点击切换）";
    }
    private static String leftJointModeLabel() {
        String[] names = {"无", "X", "Y", "Z", "全部", "屏蔽tracker"};
        int m = Math.max(0, Math.min(5, VrBodyConfig.leftJointMode));
        return "左臂关节修正: " + names[m] + "（点击切换）";
    }
    private static String trackModeLabel() {
        int mode = Math.max(0, Math.min(5, VrBodyConfig.trackingMode));
        return "追踪模式: " + TRACK_MODES[mode] + "（点击切换）";
    }

    private void switchCategory(int index) {
        category = Math.floorMod(index, CATEGORIES.length);
        clearWidgets();
        init();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        switchCategory(category + (delta > 0 ? -1 : 1));
        return true;
    }

    private void open(Screen screen) {
        if (minecraft != null) {
            minecraft.setScreen(screen);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.fill(0, 0, width, height, BG);
        int panelX = panelLeft();
        int panelY = panelTop();
        graphics.fill(panelX, panelY, panelX + PANEL_W, panelY + 230, PANEL);
        graphics.fill(panelX, panelY, panelX + PANEL_W, panelY + 2, BORDER);
        graphics.fill(panelX, panelY + 228, panelX + PANEL_W, panelY + 230, BORDER);

        graphics.drawString(font, "YSM VR 设置", panelX + 10, panelY + 2, TEXT, false);
        graphics.drawString(font, "分类: " + CATEGORIES[category] + "  |  滚轮/顶部页签切换",
                panelX + 10, panelY + 12, TEXT_DIM, false);
        graphics.drawString(font, "调试页可放置暂时用不到的开关；子界面仍在界面页。",
                panelX + 10, panelY + 214, TEXT_DIM, false);
        graphics.drawString(font, "打开：YSM 轮盘动作 GUI / key.ysmvr.open_main / /ysmvr main",
                panelX + 10, panelY + 222, TEXT_DIM, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }    /** VRChat 风格深色圆角感按钮（用矩形+高亮边框模拟）。 */
    private static class StyleButton extends Button {
        private int accent = ACCENT;

        StyleButton(int x, int y, int w, int h, Component message, OnPress onPress) {
            super(x, y, w, h, message, onPress, DEFAULT_NARRATION);
        }

        void setAccent(int color) {
            this.accent = color;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            boolean hover = isHovered() || isFocused();
            int bg = hover ? 0xFF2A3040 : 0xFF20242E;
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), bg);
            graphics.fill(getX(), getY(), getX() + 3, getY() + getHeight(), accent);
            if (hover) {
                graphics.fill(getX(), getY(), getX() + getWidth(), getY() + 1, 0x80FFFFFF);
                graphics.fill(getX(), getY() + getHeight() - 1, getX() + getWidth(),
                        getY() + getHeight(), 0x80FFFFFF);
            }
            int textColor = hover ? 0xFFFFFFFF : 0xFFE8EDF2;
            graphics.drawString(net.minecraft.client.Minecraft.getInstance().font,
                    getMessage().getString(), getX() + 8, getY() + (getHeight() - 8) / 2,
                    textColor, false);
        }
    }

    /** 开关按钮：开=蓝色/绿色，关=灰色。 */
    private static final class ToggleButton extends StyleButton {
        private final String name;
        private final Supplier<Boolean> getter;
        private final Consumer<Boolean> setter;

        ToggleButton(int x, int y, int w, int h, String name,
                     Supplier<Boolean> getter, Consumer<Boolean> setter) {
            super(x, y, w, h, Component.empty(), b -> {
            });
            this.name = name;
            this.getter = getter;
            this.setter = setter;
            updateLabel();
        }

        @Override
        public void onPress() {
            boolean next = !Boolean.TRUE.equals(getter.get());
            setter.accept(next);
            updateLabel();
        }

        private void updateLabel() {
            boolean on = Boolean.TRUE.equals(getter.get());
            setMessage(Component.literal(name + ": " + (on ? "开" : "关")));
            setAccent(on ? 0xFF36D399 : OFF);
        }
    }
}