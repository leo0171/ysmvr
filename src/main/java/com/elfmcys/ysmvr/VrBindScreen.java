package com.elfmcys.ysmvr;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.vivecraft.api.data.VRBodyPart;

import java.util.List;
import java.util.function.Predicate;

/**
 * VR 绑定配置 GUI：
 * <ul>
 * <li>左列：VR 追踪部位（点击选中），底部按钮："恢复默认绑定"、"重置模型修改"
 * （/ysmvr clear）、"视角修改"、"姿势校准"、"身高/玩家大小"、"隐藏头部"</li>
 * <li>右列：YSM 模型骨骼（点击勾选绑定到选中的 VR 部位，支持一个部位绑定多根骨骼）</li>
 * <li>已勾选骨骼右侧的 ⚙ 打开偏移编辑面板（模态弹窗，位置/旋转各 XYZ）</li>
 * <li>"视角修改"打开视角编辑面板：开关 + 位置偏移（米）+ 头部骨骼名，
 * 把 VR 第一人称视角绑定到模型头部骨骼的动画实时位置（解决移动动画
 * 把头推前、第一视角看到头发的问题）</li>
 * <li>"身高/玩家大小"打开大小编辑面板：跟随 YSM 模型高度开关 +
 * <b>模型大小（强制）</b>滑块（给模型主根骨骼写缩放，所有模型可用）+
 * <b>玩家大小</b>倍率滑块（通过 Vivecraft worldScale <b>只改玩家世界身高，
 * 不影响 YSM 模型</b>；跟随开启时玩家自动贴合模型），
 * 并显示真实眼高、模型头部高度、目标/当前 worldScale</li>
 * <li>"姿势校准"打开校准 GUI：可切换"自然站立"（默认，按原版模型站立姿势对齐，
 * 对应 2.13 前不强制 T 字的方式）或"T 字"目标，倒计时后采集当前旋转并入绑定偏移；
 * "隐藏头部"第一人称隐藏头部（第三人称/他人视角仍可见）</li>
 * </ul>
 * 修改实时生效并持久化到 config/ysmvr-bindings.json / config/ysmvr-view.json
 * / config/ysmvr-body.json。
 */
public final class VrBindScreen extends Screen {

    /** 部位显示名：按常见 SlimeVR 追踪器位置命名（脚部位=小腿、膝部位=大腿、肘部位=上臂）。 */
    private static final String[][] PART_LABELS = {
            {"头部", "HEAD"},
            {"主手", "MAIN_HAND"},
            {"副手", "OFF_HAND"},
            {"腰部", "WAIST"},
            {"右小腿", "RIGHT_FOOT"},
            {"左小腿", "LEFT_FOOT"},
            {"右大腿", "RIGHT_KNEE"},
            {"左大腿", "LEFT_KNEE"},
            {"右上臂", "RIGHT_ELBOW"},
            {"左上臂", "LEFT_ELBOW"},
    };

    private static final int LEFT_X = 8;
    private static final int LEFT_W = 170;
    private static final int BONE_X = 192;
    private static final int ROW_H = 12;
    private static final int TOP_Y = 24;
    private static final int FOOTER_H = 14;

    /** 偏移编辑面板（模态）。 */
    private static final int PANEL_W = 420;
    private static final int PANEL_H = 190;

    /** 视角编辑面板（模态）。 */
    private static final int VIEW_PANEL_H = 210;

    /** 身高/玩家大小编辑面板（模态）。 */
    private static final int SIZE_PANEL_H = 240;

    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_ROW = 0xFFBBBBBB;
    private static final int COLOR_ROW_HOVER = 0xFFFFFFFF;
    private static final int COLOR_SEL = 0xFFFFD700;
    private static final int COLOR_BOUND = 0xFF55FF55;
    private static final int COLOR_DIM = 0xFF777777;
    private static final int COLOR_BG = 0x88000000;

    private VRBodyPart selectedPart;
    private int scrollBones;

    /** 偏移编辑面板状态（模态，编辑某部位下的某根骨骼）。 */
    private VRBodyPart editingPart;
    private String editingBone;
    private final EditBox[] editBoxes = new EditBox[6];
    private Button applyButton;
    private Button cancelButton;
    private Button resetButton;
    private Button clearOverridesButton;
    private Button viewButton;
    private Button calibrateButton;
    private Button scaleButton;
    private Button hideHeadButton;

    /** 视角编辑面板状态（模态）。 */
    private boolean viewEditorOpen;
    private final EditBox[] viewBoxes = new EditBox[4]; // 位置X/Y/Z（米）+ 头部骨骼名
    private Button viewToggleButton;
    private Button viewApplyButton;
    private Button viewCancelButton;

    /** 身高/玩家大小编辑面板状态（模态）。 */
    private boolean sizeEditorOpen;
    private Button sizeToggleButton;
    private Button sizeResetButton;
    private Button sizeCloseButton;
    private Button sizeSaveModelButton;
    /** 从总 GUI 打开时的返回目标。 */
    private final Screen returnScreen = Minecraft.getInstance().screen;
    private SizeSlider sizeSlider;
    private ModelSizeSlider modelSizeSlider;
    private Button modelSizeResetButton;
    /** 当前模型能否解析出强制缩放骨骼（ModernYSM 未加载模型时为 false）。 */
    private boolean modelScaleSupported;

    public VrBindScreen() {
        this(false);
    }

    /** @param openSizeEditor 打开时直接展开"身高/玩家大小"面板（/ysmvr scale gui）。 */
    public VrBindScreen(boolean openSizeEditor) {
        super(Component.literal("VR 绑定配置"));
        this.sizeEditorOpen = openSizeEditor;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static final Predicate<String> NUMBER_FILTER =
            s -> s == null || s.matches("[-+]?\\d*\\.?\\d*");

    @Override
    protected void init() {
        clearWidgets();
        int px = (width - PANEL_W) / 2;
        int py = (height - PANEL_H) / 2;
        String[] labels = {"位置X", "位置Y", "位置Z", "旋转X°", "旋转Y°", "旋转Z°"};
        for (int i = 0; i < 6; i++) {
            int col = i % 3;
            int row = i / 3;
            var box = new EditBox(font, px + 60 + col * 120, py + 30 + row * 34, 100, 14,
                    Component.literal(labels[i]));
            box.setFilter(NUMBER_FILTER);
            box.setVisible(false);
            box.setMaxLength(8);
            editBoxes[i] = addRenderableWidget(box);
        }
        applyButton = Button.builder(Component.literal("应用"),
                        b -> applyEdit())
                .bounds(px + 60, py + 104, 70, 16).build();
        applyButton.visible = false;
        addRenderableWidget(applyButton);
        cancelButton = Button.builder(Component.literal("取消"),
                        b -> closeEditor())
                .bounds(px + 200, py + 104, 70, 16).build();
        cancelButton.visible = false;
        addRenderableWidget(cancelButton);

        // 左列底部按钮：恢复默认绑定 / 重置模型修改 / 视角修改
        int btnY = TOP_Y + PART_LABELS.length * ROW_H + 6;
        resetButton = Button.builder(Component.literal("恢复默认绑定"), b -> {
                    VrBindings.resetDefaults();
                    VrBindings.save();
                })
                .bounds(LEFT_X + 4, btnY, LEFT_W - 8, 16)
                .build();
        addRenderableWidget(resetButton);
        clearOverridesButton = Button.builder(Component.literal("重置模型修改"), b -> {
                    var player = Minecraft.getInstance().player;
                    var bridge = ActiveBridge.get();
                    if (player != null && bridge != null) {
                        var store = bridge.store(player);
                        if (store != null) {
                            store.clearAll();
                        }
                    }
                })
                .bounds(LEFT_X + 4, btnY + 20, LEFT_W - 8, 16)
                .build();
        addRenderableWidget(clearOverridesButton);
        viewButton = Button.builder(Component.literal(""), b -> openViewEditor())
                .bounds(LEFT_X + 4, btnY + 40, LEFT_W - 8, 16)
                .build();
        addRenderableWidget(viewButton);
        refreshViewButtonLabel();
        calibrateButton = Button.builder(Component.literal("姿势校准"), b ->
                        Minecraft.getInstance().setScreen(new CalibrationScreen()))
                .bounds(LEFT_X + 4, btnY + 60, LEFT_W - 8, 16)
                .build();
        addRenderableWidget(calibrateButton);
        scaleButton = Button.builder(Component.literal(""), b -> openSizeEditor())
                .bounds(LEFT_X + 4, btnY + 80, LEFT_W - 8, 16)
                .build();
        addRenderableWidget(scaleButton);
        refreshScaleButtonLabel();
        hideHeadButton = Button.builder(Component.literal(""), b -> {
                    VrBodyConfig.setHideHeadEnabled(!VrBodyConfig.hideHeadEnabled);
                    VrBodyConfig.save();
                    refreshHideHeadButtonLabel();
                })
                .bounds(LEFT_X + 4, btnY + 100, LEFT_W - 8, 16)
                .build();
        addRenderableWidget(hideHeadButton);
        refreshHideHeadButtonLabel();

        // 视角编辑面板控件（默认隐藏）
        int vpx = px;
        int vpy = (height - VIEW_PANEL_H) / 2;
        viewToggleButton = Button.builder(Component.literal(""), b -> {
                    VrViewConfig.setEnabled(!VrViewConfig.enabled);
                    VrViewConfig.save();
                    refreshViewToggleLabel();
                })
                .bounds(vpx + 6, vpy + 16, 150, 16).build();
        viewToggleButton.visible = false;
        addRenderableWidget(viewToggleButton);
        String[] viewLabels = {"位置X", "位置Y", "位置Z"};
        for (int i = 0; i < 3; i++) {
            var box = new EditBox(font, vpx + 60 + i * 120, vpy + 56, 100, 14,
                    Component.literal(viewLabels[i]));
            box.setFilter(NUMBER_FILTER);
            box.setVisible(false);
            box.setMaxLength(8);
            viewBoxes[i] = addRenderableWidget(box);
        }
        var boneBox = new EditBox(font, vpx + 60, vpy + 96, 240, 14,
                Component.literal("头部骨骼"));
        boneBox.setVisible(false);
        boneBox.setMaxLength(32);
        viewBoxes[3] = addRenderableWidget(boneBox);
        viewApplyButton = Button.builder(Component.literal("应用"),
                        b -> applyViewEdit())
                .bounds(vpx + 60, vpy + 136, 70, 16).build();
        viewApplyButton.visible = false;
        addRenderableWidget(viewApplyButton);
        viewCancelButton = Button.builder(Component.literal("取消"),
                        b -> closeViewEditor())
                .bounds(vpx + 200, vpy + 136, 70, 16).build();
        viewCancelButton.visible = false;
        addRenderableWidget(viewCancelButton);
        refreshViewToggleLabel();

        // 身高/玩家大小面板控件（默认隐藏）
        int spx = px;
        int spy = (height - SIZE_PANEL_H) / 2;
        sizeToggleButton = Button.builder(Component.literal(""), b -> {
                    VrBodyConfig.setScaleEnabled(!VrBodyConfig.scaleEnabled);
                    VrBodyConfig.save();
                    refreshSizeToggleLabel();
                    refreshScaleButtonLabel();
                })
                .bounds(spx + 8, spy + 20, PANEL_W - 16, 16).build();
        sizeToggleButton.visible = false;
        addRenderableWidget(sizeToggleButton);
        modelSizeSlider = new ModelSizeSlider(spx + 8, spy + 52, PANEL_W - 16, 20, 1.0f);
        modelSizeSlider.visible = false;
        addRenderableWidget(modelSizeSlider);
        sizeSlider = new SizeSlider(spx + 8, spy + 88, PANEL_W - 16, 20,
                VrBodyConfig.playerSize);
        sizeSlider.visible = false;
        addRenderableWidget(sizeSlider);
        modelSizeResetButton = Button.builder(Component.literal("模型重置"), b -> {
                    VrBodyConfig.setModelScale(1.0f);
                    VrBodyConfig.save();
                    var local = Minecraft.getInstance().player;
                    var bridge = ActiveBridge.get();
                    if (local != null && bridge != null) {
                        ModelProfileStore.setModelScaleOverride(local, bridge, null);
                    }
                    modelSizeSlider.setFromSize(1.0f);
                })
                .bounds(spx + 6, spy + 116, 96, 16).build();
        modelSizeResetButton.visible = false;
        addRenderableWidget(modelSizeResetButton);
        sizeResetButton = Button.builder(Component.literal("玩家重置"), b -> {
                    VrBodyConfig.setPlayerSize(1.0f);
                    VrBodyConfig.save();
                    var local = Minecraft.getInstance().player;
                    var bridge = ActiveBridge.get();
                    if (local != null && bridge != null) {
                        ModelProfileStore.setPlayerSizeOverride(local, bridge, null);
                    }
                    sizeSlider.setFromSize(1.0f);
                    refreshScaleButtonLabel();
                })
                .bounds(spx + 108, spy + 116, 96, 16).build();
        sizeResetButton.visible = false;
        addRenderableWidget(sizeResetButton);
        sizeCloseButton = Button.builder(Component.literal("返回"), b -> {
                    if (returnScreen != null) {
                        Minecraft.getInstance().setScreen(returnScreen);
                    } else {
                        closeSizeEditor();
                    }
                })
                .bounds(spx + 312, spy + 116, 96, 16).build();
        sizeCloseButton.visible = false;
        addRenderableWidget(sizeCloseButton);
        sizeSaveModelButton = Button.builder(Component.literal("保存模型配置"), b -> {
                    var player = Minecraft.getInstance().player;
                    var bridge = ActiveBridge.get();
                    ModelProfileStore.setModelScaleOverride(player, bridge, VrBodyConfig.modelScale);
                    ModelProfileStore.setPlayerSizeOverride(player, bridge, VrBodyConfig.playerSize);
                    b.setMessage(Component.literal("已保存当前模型配置"));
                })
                .bounds(spx + 210, spy + 116, 96, 16).build();
        sizeSaveModelButton.visible = false;
        addRenderableWidget(sizeSaveModelButton);
        refreshSizeToggleLabel();

        // 屏幕尺寸变化导致 init 重跑时恢复打开的面板（已输入未应用的值会丢失）
        if (editingPart != null && editingBone != null) {
            var b = VrBindings.findBone(editingPart, editingBone);
            fillEditorValues(b == null ? new float[6]
                    : new float[]{b.posX, b.posY, b.posZ, b.rotX, b.rotY, b.rotZ});
            applyButton.visible = true;
            cancelButton.visible = true;
            hideMainButtons();
        } else if (viewEditorOpen) {
            showViewEditor();
        } else if (sizeEditorOpen) {
            showSizeEditor();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);

        var bridge = ActiveBridge.get();
        var player = Minecraft.getInstance().player;

        // 偏移/视角/大小编辑面板是模态：遮罩盖住整个主界面，只渲染面板与控件，
        // 主界面内容不绘制，保证面板不会被任何元素遮挡
        if (sizeEditorOpen) {
            renderSizeEditor(graphics, mouseX, mouseY, partialTick);
            return;
        }
        if (viewEditorOpen) {
            renderViewEditor(graphics, mouseX, mouseY, partialTick);
            return;
        }
        if (editingPart != null) {
            renderEditor(graphics, mouseX, mouseY, partialTick);
            return;
        }

        graphics.drawCenteredString(font, "VR 绑定配置：先选左侧 VR 部位，再点右侧骨骼勾选（可多选）",
                width / 2, 8, COLOR_TITLE);

        if (bridge == null || player == null) {
            graphics.drawCenteredString(font, "YSM 不可用", width / 2, height / 2 - 10, 0xFFFF5555);
            super.render(graphics, mouseX, mouseY, partialTick);
            return;
        }

        var bones = bridge.listBones(player);

        // 当前帧 Vivecraft 姿势：部位行按"是否有追踪数据"着色，方便排查 FBT 校准问题
        var pose = currentPose();
        var avail = java.util.EnumSet.noneOf(VRBodyPart.class);
        if (pose != null) {
            for (var part : VRBodyPart.values()) {
                if (pose.getBodyPartData(part) != null) {
                    avail.add(part);
                }
            }
            // 未被 Vivecraft 分配角色的原始追踪器（自识别的大腿/小腿）也算有数据
            float bodyYaw = (float) Math.toRadians(net.minecraft.util.Mth.rotLerp(
                    Minecraft.getInstance().getFrameTime(), player.yBodyRotO, player.yBodyRot));
            avail.addAll(VrRawTrackers.classify(pose, bodyYaw).keySet());
        }

        // ── 左列：VR 部位 ──
        int y = TOP_Y;
        for (var label : PART_LABELS) {
            VRBodyPart part = VRBodyPart.valueOf(label[1]);
            boolean selected = part == selectedPart;
            boolean hasData = avail.contains(part);
            boolean hover = mouseX >= LEFT_X && mouseX <= LEFT_X + LEFT_W
                    && mouseY >= y && mouseY < y + ROW_H;
            if (hover) {
                graphics.fill(LEFT_X, y, LEFT_X + LEFT_W, y + ROW_H, 0x22FFFFFF);
            }
            if (selected) {
                graphics.fill(LEFT_X, y - 1, LEFT_X + 3, y + ROW_H, COLOR_SEL);
            }
            // 无数据部位变暗：该部位 Vivecraft 没有追踪数据（常见于 FBT 未扩展校准）
            String text = truncate(label[0] + " " + label[1] + boundText(VrBindings.get(part)),
                    LEFT_W - 12);
            int color = selected ? COLOR_SEL
                    : (hover ? COLOR_ROW_HOVER : (hasData ? COLOR_BOUND : COLOR_DIM));
            graphics.drawString(font, text, LEFT_X + 6, y + 1, color);
            y += ROW_H;
        }

        // ── 右列：骨骼列表 ──
        int visibleHeight = height - TOP_Y - FOOTER_H;
        scrollBones = Math.min(scrollBones, Math.max(0, bones.size() * ROW_H - visibleHeight));
        int by = TOP_Y - scrollBones;
        for (var boneName : bones) {
            if (by >= TOP_Y - ROW_H && by <= height - FOOTER_H) {
                drawBoneRow(graphics, mouseX, mouseY, boneName, by);
            }
            by += ROW_H;
        }

        // ── 底部提示 ──
        graphics.fill(0, height - FOOTER_H, width, height, COLOR_BG);
        graphics.drawString(font,
                "点击骨骼勾选/取消（可多选）| ⚙ 偏移设置 | 滚轮滚动 | Esc 关闭",
                8, height - FOOTER_H + 3, 0xFFEEEEEE);
        String fbtText = "FBT: " + (pose == null ? "无VR数据"
                : String.valueOf(pose.getFBTMode())) + "｜绿=有追踪数据";
        graphics.drawString(font, fbtText, width - font.width(fbtText) - 8,
                height - FOOTER_H + 3, 0xFFEEEEEE);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderEditor(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 模态遮罩
        graphics.fill(0, 0, width, height, 0x66000000);
        int px = (width - PANEL_W) / 2;
        int py = (height - PANEL_H) / 2;
        graphics.fill(px, py, px + PANEL_W, py + PANEL_H, 0xF5202020);
        graphics.fill(px, py, px + PANEL_W, py + 14, 0xCC303030);
        graphics.drawString(font, "偏移设置：" + partLabel(editingPart) + " → " + editingBone,
                px + 6, py + 3, COLOR_TITLE);
        graphics.drawString(font, "位置（模型单位，1 格=16）", px + 6, py + 16, COLOR_DIM);
        graphics.drawString(font, "旋转（度）", px + 6, py + 50, COLOR_DIM);
        graphics.drawString(font, "（偏移对追踪数据生效，可与 /ysmvr bone 手动覆盖叠加）",
                px + 6, py + 82, COLOR_DIM);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderViewEditor(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 模态遮罩
        graphics.fill(0, 0, width, height, 0x66000000);
        int px = (width - PANEL_W) / 2;
        int py = (height - VIEW_PANEL_H) / 2;
        graphics.fill(px, py, px + PANEL_W, py + VIEW_PANEL_H, 0xF5202020);
        graphics.fill(px, py, px + PANEL_W, py + 14, 0xCC303030);
        graphics.drawString(font, "视角修改：VR 第一人称视角绑定到模型头部",
                px + 6, py + 3, COLOR_TITLE);
        graphics.drawString(font, "位置偏移（米，模型坐标系，随身体朝向旋转）",
                px + 6, py + 40, COLOR_DIM);
        graphics.drawString(font, "头部骨骼（留空 = 自动：HEAD 部位绑定的第一根骨骼）",
                px + 6, py + 80, COLOR_DIM);
        graphics.drawString(font, "自动解析: " + VrViewConfig.resolvedHeadBone(),
                px + 6, py + 118, COLOR_DIM);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawBoneRow(GuiGraphics graphics, int mouseX, int mouseY,
                             String boneName, int y) {
        int maxX = width - 8;
        boolean hover = mouseX >= BONE_X && mouseX <= maxX && mouseY >= y && mouseY < y + ROW_H;
        if (hover) {
            graphics.fill(BONE_X, y, maxX, y + ROW_H, 0x22FFFFFF);
        }
        boolean bound = selectedPart != null
                && VrBindings.findBone(selectedPart, boneName) != null;
        var parts = VrBindings.findPartsByBone(boneName);

        String mark = bound ? "[✓]" : "[  ]";
        graphics.drawString(font, mark, BONE_X + 4, y + 1, bound ? COLOR_BOUND : COLOR_DIM);
        graphics.drawString(font, boneName, BONE_X + 26, y + 1,
                bound ? COLOR_BOUND : (hover ? COLOR_ROW_HOVER : COLOR_ROW));
        int nextX = BONE_X + 26 + font.width(boneName) + 6;
        if (!parts.isEmpty()) {
            var sb = new StringBuilder("← ");
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) {
                    sb.append("、");
                }
                sb.append(partLabel(parts.get(i)));
            }
            graphics.drawString(font, sb.toString(), nextX, y + 1, COLOR_DIM);
        }
        if (bound) {
            boolean gearHover = mouseX >= maxX - 14 && mouseX <= maxX && mouseY >= y && mouseY < y + ROW_H;
            graphics.drawString(font, "⚙", maxX - 12, y + 1,
                    gearHover ? COLOR_ROW_HOVER : COLOR_DIM);
        }
    }

    private static String partLabel(VRBodyPart part) {
        for (var label : PART_LABELS) {
            if (label[1].equals(part.name())) {
                return label[0];
            }
        }
        return part.name();
    }

    /** 当前帧的 Vivecraft 渲染姿势（不可用时为 null）。 */
    private static org.vivecraft.api.data.VRPose currentPose() {
        try {
            var api = org.vivecraft.api.client.VRClientAPI.instance();
            if (api == null || !api.isVRActive()) {
                return null;
            }
            return api.getWorldRenderPose();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 部位绑定骨骼的显示后缀："(未绑定)" / "→ Head" / "→ ×3 Head、AllHead、..."。 */
    private static String boundText(List<VrBindings.Binding> list) {
        if (list == null || list.isEmpty()) {
            return "  (未绑定)";
        }
        if (list.size() == 1) {
            return "  → " + list.get(0).boneName;
        }
        var sb = new StringBuilder("  → ×").append(list.size()).append(" ");
        for (var b : list) {
            sb.append(b.boneName).append("、");
        }
        sb.setLength(sb.length() - 1); // 去掉末尾顿号
        return sb.toString();
    }

    /** 按像素宽度截断文本，超出部分用 … 代替。 */
    private String truncate(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        int lo = 0;
        int hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (font.width(text.substring(0, mid) + "…") <= maxWidth) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return text.substring(0, lo) + "…";
    }

    // ── 身高/玩家大小面板 ──

    private void openSizeEditor() {
        sizeEditorOpen = true;
        hideMainButtons();
        showSizeEditor();
    }

    /** 显示大小面板控件并填充当前配置（实时生效，无"应用"步骤）。 */
    private void showSizeEditor() {
        hideMainButtons();
        sizeToggleButton.visible = true;
        refreshSizeToggleLabel();
        var bridge = ActiveBridge.get();
        var player = Minecraft.getInstance().player;
        modelScaleSupported = bridge != null && player != null && bridge.modelScaleBone(player) != null;
        Float modelOverride = ModelProfileStore.modelScaleOverride(player, bridge);
        modelSizeSlider.setFromSize(modelOverride != null ? modelOverride : VrBodyConfig.modelScale);
        modelSizeSlider.active = modelScaleSupported;
        modelSizeSlider.visible = true;
        modelSizeResetButton.active = true;
        modelSizeResetButton.visible = true;
        Float playerOverride = ModelProfileStore.playerSizeOverride(player, bridge);
        sizeSlider.setFromSize(playerOverride != null ? playerOverride : VrBodyConfig.playerSize);
        sizeSlider.visible = true;
        sizeResetButton.visible = true;
        sizeCloseButton.visible = true;
        sizeSaveModelButton.visible = true;
    }

    private void closeSizeEditor() {
        sizeEditorOpen = false;
        sizeToggleButton.visible = false;
        modelSizeSlider.visible = false;
        modelSizeResetButton.visible = false;
        sizeSlider.visible = false;
        sizeResetButton.visible = false;
        sizeCloseButton.visible = false;
        sizeSaveModelButton.visible = false;
        showMainButtons();
    }

    private void refreshSizeToggleLabel() {
        if (sizeToggleButton != null) {
            sizeToggleButton.setMessage(Component.literal(
                    "跟随 YSM 模型高度: " + (VrBodyConfig.scaleEnabled ? "开（点击关闭）" : "关（点击开启）")));
        }
    }

    /** 面板上的实时数值行（真实眼高/模型高度/模型与玩家大小/目标与当前 worldScale）。 */
    private void renderSizeEditor(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 模态遮罩
        graphics.fill(0, 0, width, height, 0x66000000);
        int px = (width - PANEL_W) / 2;
        int py = (height - SIZE_PANEL_H) / 2;
        graphics.fill(px, py, px + PANEL_W, py + SIZE_PANEL_H, 0xF5202020);
        graphics.fill(px, py, px + PANEL_W, py + 14, 0xCC303030);
        graphics.drawString(font, "模型大小 / 玩家大小设置", px + 6, py + 3, COLOR_TITLE);

        // 每帧刷新强制缩放可用性（模型未加载/切模型时可能变化）
        var bridge = ActiveBridge.get();
        var player = Minecraft.getInstance().player;
        modelScaleSupported = bridge != null && player != null && bridge.modelScaleBone(player) != null;
        modelSizeSlider.active = modelScaleSupported;
        modelSizeResetButton.active = true;

        graphics.drawString(font, "模型大小：强制给模型主根骨骼写缩放（所有模型可用，含小酒狐）",
                px + 8, py + 40, COLOR_TITLE);
        graphics.drawString(font, "玩家大小：只改玩家世界身高（不影响 YSM 模型；跟随开启时自动贴合模型）",
                px + 8, py + 76, COLOR_TITLE);

        int y = py + 138;
        graphics.drawString(font, "模式: " + (VrBodyConfig.scaleEnabled ? "跟随 YSM 模型高度" : "手动/默认")
                        + "    模型大小: " + String.format("%.2fx", VrBodyConfig.modelScale)
                        + (modelScaleSupported ? "" : "（不可用）")
                        + "    玩家大小: " + String.format("%.2fx", VrBodyConfig.playerSize)
                        + "（只改玩家）",
                px + 8, y, 0xFFEEEEEE);
        y += 13;
        graphics.drawString(font, "真实眼高: " + metric(VivecraftPoseTracker.latestEyeHeight)
                        + "    模型头部高度: " + metric(VivecraftPoseTracker.latestModelHeadY)
                        + "    手柄比例: " + number(VivecraftPoseTracker.latestHandScaleRatio),
                px + 8, y, COLOR_ROW);
        y += 13;
        graphics.drawString(font, "目标 worldScale: " + number(VivecraftPoseTracker.latestTargetWorldScale)
                        + "    当前: " + number(VrWorldScale.get())
                        + "    原值: " + number(VivecraftPoseTracker.latestSavedUserWorldScale),
                px + 8, y, COLOR_ROW);
        y += 14;
        graphics.drawString(font, "强制缩放骨骼: " + (VivecraftPoseTracker.latestModelScaleBone == null
                        ? "未启用" : VivecraftPoseTracker.latestModelScaleBone)
                        + "；改玩家/模型大小都会立即生效，跟随开启时玩家自动贴合模型。",
                px + 8, y, COLOR_DIM);
        y += 12;
        graphics.drawString(font, "跟随开启：玩家世界身高 ≈ 模型头部高度（含模型大小 × 玩家大小）；",
                px + 8, y, COLOR_DIM);
        y += 12;
        graphics.drawString(font, "跟随关闭且倍率=1：恢复 Vivecraft 原世界缩放；倍率≠1：只有玩家身高 ≈ 原身高 × 倍率，模型大小不变。",
                px + 8, y, COLOR_DIM);
        y += 12;
        graphics.drawString(font, "修改立即生效；Esc 或点击面板外关闭。",
                px + 8, y, COLOR_DIM);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** 带单位/无数据标记的显示。 */
    private static String metric(float value) {
        return Float.isFinite(value) ? String.format("%.2f m", value) : "--";
    }

    /** 无数据标记的数值显示。 */
    private static String number(float value) {
        return Float.isFinite(value) ? String.format("%.3f", value) : "--";
    }

    /**
     * 玩家大小倍率滑块（0.10–5.00，0.05 步进，实时保存到 config）。
     * 只改 Vivecraft worldScale（玩家世界身高），<b>不影响 YSM 模型</b>；
     * 跟随开启时玩家自动贴合模型高度，本倍率不再额外作用。
     */
    private static final class SizeSlider extends AbstractSliderButton {
        SizeSlider(int x, int y, int w, int h, float size) {
            super(x, y, w, h, Component.empty(), normalize(size));
            updateMessage();
        }

        private static double normalize(float size) {
            float clamped = Math.max(VrBodyConfig.PLAYER_SIZE_MIN,
                    Math.min(VrBodyConfig.PLAYER_SIZE_MAX, size));
            return (clamped - VrBodyConfig.PLAYER_SIZE_MIN)
                    / (VrBodyConfig.PLAYER_SIZE_MAX - VrBodyConfig.PLAYER_SIZE_MIN);
        }

        private static float sizeOf(double value) {
            float raw = (float) (VrBodyConfig.PLAYER_SIZE_MIN
                    + (VrBodyConfig.PLAYER_SIZE_MAX - VrBodyConfig.PLAYER_SIZE_MIN) * value);
            return Math.round(raw * 20f) / 20f; // 0.05 步进
        }

        void setFromSize(float size) {
            this.value = normalize(size);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(String.format("玩家大小（只改玩家、不影响模型）: %.2fx", sizeOf(this.value))));
        }

        @Override
        protected void applyValue() {
            float snapped = sizeOf(this.value);
            if (Math.abs(snapped - VrBodyConfig.playerSize) > 1.0e-4f) {
                VrBodyConfig.setPlayerSize(snapped);
                VrBodyConfig.save();
            }
            var local = Minecraft.getInstance().player;
            var bridge = ActiveBridge.get();
            if (local != null && bridge != null) {
                ModelProfileStore.setPlayerSizeOverride(local, bridge, snapped);
            }
        }
    }

    /**
     * 模型强制大小倍率滑块（0.10–5.00，0.05 步进，实时保存到本模组配置）。
     * 不依赖模型自己的 {@code v.player_size}：由追踪器给模型主根骨骼
     * （头部骨骼的根祖先）写缩放覆盖，所有模型（含小酒狐）都能生效；
     * 跟随开启时玩家世界身高会自动重新对齐。
     */
    private static final class ModelSizeSlider extends AbstractSliderButton {
        ModelSizeSlider(int x, int y, int w, int h, float size) {
            super(x, y, w, h, Component.empty(), normalize(size));
            updateMessage();
        }

        private static double normalize(float size) {
            float clamped = Math.max(VrBodyConfig.MODEL_SCALE_MIN,
                    Math.min(VrBodyConfig.MODEL_SCALE_MAX, size));
            return (clamped - VrBodyConfig.MODEL_SCALE_MIN)
                    / (VrBodyConfig.MODEL_SCALE_MAX - VrBodyConfig.MODEL_SCALE_MIN);
        }

        private static float sizeOf(double value) {
            float raw = (float) (VrBodyConfig.MODEL_SCALE_MIN
                    + (VrBodyConfig.MODEL_SCALE_MAX - VrBodyConfig.MODEL_SCALE_MIN) * value);
            return Math.round(raw * 20f) / 20f; // 0.05 步进
        }

        void setFromSize(float size) {
            this.value = normalize(size);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(String.format("模型大小（强制）: %.2fx", sizeOf(this.value))));
        }

        @Override
        protected void applyValue() {
            float snapped = sizeOf(this.value);
            if (Math.abs(snapped - VrBodyConfig.modelScale) > 1.0e-4f) {
                VrBodyConfig.setModelScale(snapped);
                VrBodyConfig.save();
            }
            var local = Minecraft.getInstance().player;
            var bridge = ActiveBridge.get();
            if (local != null && bridge != null) {
                ModelProfileStore.setModelScaleOverride(local, bridge, snapped);
            }
        }
    }

    // ── 偏移编辑面板 ──

    private void openEditor(VRBodyPart part, String boneName) {
        editingPart = part;
        editingBone = boneName;
        var b = VrBindings.findBone(part, boneName);
        fillEditorValues(b == null
                ? new float[6]
                : new float[]{b.posX, b.posY, b.posZ, b.rotX, b.rotY, b.rotZ});
        applyButton.visible = true;
        cancelButton.visible = true;
        hideMainButtons();
    }

    private void fillEditorValues(float[] values) {
        for (int i = 0; i < 6; i++) {
            editBoxes[i].setValue(trimFloat(values[i]));
            editBoxes[i].setVisible(true);
        }
    }

    private void closeEditor() {
        editingPart = null;
        editingBone = null;
        for (var box : editBoxes) {
            box.setVisible(false);
        }
        applyButton.visible = false;
        cancelButton.visible = false;
        showMainButtons();
    }

    // ── 视角编辑面板 ──

    private void openViewEditor() {
        viewEditorOpen = true;
        hideMainButtons();
        showViewEditor();
    }

    /** 显示视角面板控件并填充当前配置。 */
    private void showViewEditor() {
        hideMainButtons();
        viewToggleButton.visible = true;
        refreshViewToggleLabel();
        viewBoxes[0].setValue(trimFloat(VrViewConfig.offsetX));
        viewBoxes[1].setValue(trimFloat(VrViewConfig.offsetY));
        viewBoxes[2].setValue(trimFloat(VrViewConfig.offsetZ));
        viewBoxes[3].setValue(VrViewConfig.headBone);
        for (var box : viewBoxes) {
            box.setVisible(true);
        }
        viewApplyButton.visible = true;
        viewCancelButton.visible = true;
    }

    private void closeViewEditor() {
        viewEditorOpen = false;
        viewToggleButton.visible = false;
        for (var box : viewBoxes) {
            box.setVisible(false);
        }
        viewApplyButton.visible = false;
        viewCancelButton.visible = false;
        showMainButtons();
    }

    private void applyViewEdit() {
        VrViewConfig.setOffset(parseFloat(viewBoxes[0]), parseFloat(viewBoxes[1]),
                parseFloat(viewBoxes[2]));
        VrViewConfig.setHeadBone(viewBoxes[3].getValue().trim());
        VrViewConfig.save();
        closeViewEditor();
    }

    private void refreshViewToggleLabel() {
        if (viewToggleButton != null) {
            viewToggleButton.setMessage(Component.literal(
                    VrViewConfig.enabled ? "视角绑定: 开（点击关闭）" : "视角绑定: 关（点击开启）"));
        }
    }

    private void refreshViewButtonLabel() {
        if (viewButton != null) {
            viewButton.setMessage(Component.literal(
                    "视角修改" + (VrViewConfig.enabled ? "（开）" : "（关）")));
        }
    }

    private void refreshScaleButtonLabel() {
        if (scaleButton != null) {
            String mode = VrBodyConfig.scaleEnabled
                    ? "（跟随模型）"
                    : (Math.abs(VrBodyConfig.playerSize - 1.0f) > 1.0e-3f
                            ? String.format("（%.2fx）", VrBodyConfig.playerSize)
                            : "（默认）");
            scaleButton.setMessage(Component.literal("身高/玩家大小" + mode));
        }
    }

    private void refreshHideHeadButtonLabel() {
        if (hideHeadButton != null) {
            hideHeadButton.setMessage(Component.literal(
                    "隐藏头部" + (VrBodyConfig.hideHeadEnabled ? "（开）" : "（关）")));
        }
    }

    /** 任一模态面板打开时隐藏主界面按钮，避免遮罩上残留可点控件。 */
    private void hideMainButtons() {
        resetButton.visible = false;
        clearOverridesButton.visible = false;
        viewButton.visible = false;
        calibrateButton.visible = false;
        scaleButton.visible = false;
        hideHeadButton.visible = false;
    }

    private void showMainButtons() {
        resetButton.visible = true;
        clearOverridesButton.visible = true;
        viewButton.visible = true;
        calibrateButton.visible = true;
        scaleButton.visible = true;
        hideHeadButton.visible = true;
        refreshViewButtonLabel();
        refreshScaleButtonLabel();
    }

    private boolean modalOpen() {
        return editingPart != null || viewEditorOpen || sizeEditorOpen;
    }

    private void applyEdit() {
        if (editingPart == null || editingBone == null) {
            return;
        }
        var b = VrBindings.findBone(editingPart, editingBone);
        if (b == null) {
            closeEditor();
            return;
        }
        b.posX = parseFloat(editBoxes[0]);
        b.posY = parseFloat(editBoxes[1]);
        b.posZ = parseFloat(editBoxes[2]);
        b.rotX = parseFloat(editBoxes[3]);
        b.rotY = parseFloat(editBoxes[4]);
        b.rotZ = parseFloat(editBoxes[5]);
        VrBindings.save();
        closeEditor();
    }

    private static float parseFloat(EditBox box) {
        try {
            return Float.parseFloat(box.getValue().trim());
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    private static String trimFloat(float v) {
        if (v == (long) v) {
            return String.valueOf((long) v);
        }
        String s = String.valueOf(v);
        return s.length() > 7 ? s.substring(0, 7) : s;
    }

    // ── 交互 ──

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 组件（输入框/按钮）优先
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        // 编辑面板打开时是模态：点击面板外关闭
        if (sizeEditorOpen) {
            int px = (width - PANEL_W) / 2;
            int py = (height - SIZE_PANEL_H) / 2;
            if (mouseX < px || mouseX > px + PANEL_W || mouseY < py || mouseY > py + SIZE_PANEL_H) {
                closeSizeEditor();
            }
            return true;
        }
        if (viewEditorOpen) {
            int px = (width - PANEL_W) / 2;
            int py = (height - VIEW_PANEL_H) / 2;
            if (mouseX < px || mouseX > px + PANEL_W || mouseY < py || mouseY > py + VIEW_PANEL_H) {
                closeViewEditor();
            }
            return true;
        }
        if (editingPart != null) {
            int px = (width - PANEL_W) / 2;
            int py = (height - PANEL_H) / 2;
            if (mouseX < px || mouseX > px + PANEL_W || mouseY < py || mouseY > py + PANEL_H) {
                closeEditor();
            }
            return true;
        }
        if (button != 0) {
            return false;
        }

        // 左列：选择 VR 部位
        if (mouseX >= LEFT_X && mouseX <= LEFT_X + LEFT_W && mouseY >= TOP_Y) {
            int index = (int) ((mouseY - TOP_Y) / ROW_H);
            if (index >= 0 && index < PART_LABELS.length) {
                selectedPart = VRBodyPart.valueOf(PART_LABELS[index][1]);
                return true;
            }
        }

        // 右列：勾选骨骼 / 打开偏移编辑器
        if (mouseX >= BONE_X) {
            var bridge = ActiveBridge.get();
            var player = Minecraft.getInstance().player;
            if (bridge == null || player == null || selectedPart == null) {
                return true;
            }
            int index = (int) ((mouseY - TOP_Y + scrollBones) / ROW_H);
            var bones = bridge.listBones(player);
            if (index < 0 || index >= bones.size()) {
                return true;
            }
            String boneName = bones.get(index);
            int maxX = width - 8;

            boolean bound = VrBindings.findBone(selectedPart, boneName) != null;

            // ⚙ 点击 → 偏移编辑器
            if (mouseX >= maxX - 14 && bound) {
                openEditor(selectedPart, boneName);
                return true;
            }

            // 勾选/取消（多选：只影响当前选中的部位）
            if (bound) {
                VrBindings.removeBone(selectedPart, boneName);
            } else {
                VrBindings.addBone(selectedPart, boneName);
            }
            VrBindings.save();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!modalOpen() && mouseX >= BONE_X) {
            scrollBones = Math.max(0, scrollBones - (int) (delta * 2 * ROW_H));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256 && modalOpen()) { // Esc 先关当前打开的面板
            if (sizeEditorOpen) {
                closeSizeEditor();
            } else if (viewEditorOpen) {
                closeViewEditor();
            } else {
                closeEditor();
            }
            return true;
        }
        if (modalOpen()) {
            var boxes = sizeEditorOpen ? new EditBox[0] : (viewEditorOpen ? viewBoxes : editBoxes);
            for (var box : boxes) {
                if (box.isFocused()) {
                    return box.keyPressed(keyCode, scanCode, modifiers);
                }
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
