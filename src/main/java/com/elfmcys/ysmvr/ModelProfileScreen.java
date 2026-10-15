package com.elfmcys.ysmvr;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 单模型适配 GUI（v0.2.58）：
 * <ul>
 * <li>模型偏移 / 动画偏移 / 移动偏移 / 主副手物品偏移 / 攻击动画 / 持械动画；</li>
 * <li>每种模式都有位置 XYZ + 旋转 XYZ（旋转仅整体/动画模式生效）；</li>
 * <li>只有一个保存按钮，保存后变灰，改动任一数值后才重新可点；</li>
 * <li>编辑模式、主手骨骼、副手骨骼改为点击弹出分页列表，支持滚轮/滚动条。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class ModelProfileScreen extends Screen {

    private static final float OFFSET_MIN = VrBodyConfig.MODEL_OFFSET_MIN;
    private static final float OFFSET_MAX = VrBodyConfig.MODEL_OFFSET_MAX;
    private static final float OFFSET_STEP = 0.5f;
    private static final float ROT_MIN = -180f;
    private static final float ROT_MAX = 180f;
    private static final float ROT_STEP = 1.0f;

    private static final String[] MODES = {
            "模型整体偏移", "当前动画偏移", "移动中偏移",
            "主手物品偏移", "副手物品偏移", "攻击动画调整", "手持武器/物品动画调整"
    };

    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_DIM = 0xFFAAAAAA;
    private static final int COLOR_GOOD = 0xFF55FF55;
    private static final int POPUP_BG = 0xFF161A22;
    private static final int POPUP_SEL = 0xFF2E5F86;
    private static final int POPUP_BORDER = 0xFF3FB6FF;

    private final LocalPlayer player;
    private final Bridge bridge;
    private final String modelKey;
    private final List<String> mainCandidates = new ArrayList<>();
    private final List<String> offCandidates = new ArrayList<>();
    private int mainIndex;
    private int offIndex;
    private int editMode;

    private float px, py, pz;
    private float rx, ry, rz;
    private boolean dirty;

    private ValueSlider sx, sy, sz, srx, sry, srz;
    private Button saveButton;
    private Button modeButton;
    private Button mainButton;
    private Button offButton;


    public ModelProfileScreen() {
        super(Component.literal("单模型适配"));
        this.player = Minecraft.getInstance().player;
        this.bridge = ActiveBridge.get();
        this.modelKey = ModelProfileStore.currentKey(player, bridge);
        var p = ModelProfileStore.getByKey(modelKey);
        this.mainCandidates.add("");
        this.offCandidates.add("");
        var bones = bridge == null || player == null ? List.<String>of() : bridge.listBones(player);
        for (String n : bones) {
            if (n == null) {
                continue;
            }
            String l = n.toLowerCase(Locale.ROOT);
            if (!l.contains("arm") && !l.contains("hand")) {
                continue;
            }
            if (l.contains("left")) {
                if (!offCandidates.contains(n)) {
                    offCandidates.add(n);
                }
            } else if (!mainCandidates.contains(n)) {
                mainCandidates.add(n);
            }
        }
        this.mainIndex = Math.max(0, mainCandidates.indexOf(p.mainArmBone == null ? "" : p.mainArmBone));
        this.offIndex = Math.max(0, offCandidates.indexOf(p.offArmBone == null ? "" : p.offArmBone));
        loadWorkingValues();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        int colW = 190;
        int left = width / 2 - 200;
        int right = width / 2 + 10;
        int top = height / 2 - 105;
        sx = new ValueSlider(left, top, colW, 18, 0, false, sliderValue(0, false));
        sy = new ValueSlider(left, top + 22, colW, 18, 1, false, sliderValue(1, false));
        sz = new ValueSlider(left, top + 44, colW, 18, 2, false, sliderValue(2, false));
        srx = new ValueSlider(right, top, colW, 18, 0, true, sliderValue(0, true));
        sry = new ValueSlider(right, top + 22, colW, 18, 1, true, sliderValue(1, true));
        srz = new ValueSlider(right, top + 44, colW, 18, 2, true, sliderValue(2, true));
        addRenderableWidget(sx);
        addRenderableWidget(sy);
        addRenderableWidget(sz);
        addRenderableWidget(srx);
        addRenderableWidget(sry);
        addRenderableWidget(srz);

        modeButton = Button.builder(modeLabel(), b -> openList("选择编辑模式", List.of(MODES), editMode, idx -> {
            editMode = idx;
            loadWorkingValues();
            markDirty();
        })).bounds(left, top + 76, 400, 20).build();
        mainButton = Button.builder(mainLabel(), b -> openList("选择主手骨骼", mainCandidates, mainIndex, idx -> {
            mainIndex = idx;
            markDirty();
        })).bounds(left, top + 100, 195, 20).build();
        offButton = Button.builder(offLabel(), b -> openList("选择副手骨骼", offCandidates, offIndex, idx -> {
            offIndex = idx;
            markDirty();
        })).bounds(left + 205, top + 100, 195, 20).build();
        addRenderableWidget(modeButton);
        addRenderableWidget(mainButton);
        addRenderableWidget(offButton);

        addRenderableWidget(Button.builder(Component.literal("清除当前模式"), b -> {
            clearCurrentMode();
            loadWorkingValues();
            dirty = false;
            updateSaveButton();
        }).bounds(left, top + 128, 195, 20).build());

        saveButton = Button.builder(Component.literal("保存"), b -> {
            applyAll();
            dirty = false;
            updateSaveButton();
            b.setMessage(Component.literal("已保存"));
        }).bounds(left + 205, top + 128, 195, 20).build();
        addRenderableWidget(saveButton);

        addRenderableWidget(Button.builder(Component.literal("重置本模型"), b -> {
            ModelProfileStore.reset(player, bridge);
            loadWorkingValues();
            dirty = false;
            updateSaveButton();
        }).bounds(left, top + 152, 195, 20).build());
        addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose())
                .bounds(left + 205, top + 152, 195, 20).build());

        updateSaveButton();
    }

    private float sliderValue(int axis, boolean rotation) {
        if (rotation) {
            return switch (axis) {
                case 0 -> rx;
                case 1 -> ry;
                default -> rz;
            };
        }
        return switch (axis) {
            case 0 -> px;
            case 1 -> py;
            default -> pz;
        };
    }

    private boolean supportsRotation() {
        return editMode == 0 || editMode == 1 || editMode == 2 || editMode == 5 || editMode == 6;
    }

    private String currentSpecialKey() {
        return switch (editMode) {
            case 5 -> ModelProfileStore.SPECIAL_ATTACK;
            case 6 -> ModelProfileStore.SPECIAL_HOLD;
            default -> ModelProfileStore.currentAnimationKey(player, bridge);
        };
    }

    private void loadWorkingValues() {
        var p = ModelProfileStore.getByKey(modelKey);
        switch (editMode) {
            case 1, 5, 6 -> {
                String key = currentSpecialKey();
                float[] o = key == null ? null : p.animationOffsets.get(key);
                float[] r = key == null ? null : p.animationRotOffsets.get(key);
                px = o == null ? 0f : o[0];
                py = o == null ? 0f : o[1];
                pz = o == null ? 0f : o[2];
                rx = r == null ? 0f : r[0];
                ry = r == null ? 0f : r[1];
                rz = r == null ? 0f : r[2];
            }
            case 2 -> {
                px = p.moveOffsetX;
                py = p.moveOffsetY;
                pz = p.moveOffsetZ;
                rx = p.moveRotX;
                ry = p.moveRotY;
                rz = p.moveRotZ;
            }
            case 3 -> {
                px = p.mainItemOffsetX;
                py = p.mainItemOffsetY;
                pz = p.mainItemOffsetZ;
                rx = 0f;
                ry = 0f;
                rz = 0f;
            }
            case 4 -> {
                px = p.offItemOffsetX;
                py = p.offItemOffsetY;
                pz = p.offItemOffsetZ;
                rx = 0f;
                ry = 0f;
                rz = 0f;
            }
            default -> {
                px = p.offsetX;
                py = p.offsetY;
                pz = p.offsetZ;
                rx = p.rotX;
                ry = p.rotY;
                rz = p.rotZ;
            }
        }
        syncSliders();
        if (modeButton != null) {
            modeButton.setMessage(modeLabel());
            mainButton.setMessage(mainLabel());
            offButton.setMessage(offLabel());
        }
    }

    private void syncSliders() {
        if (sx == null) {
            return;
        }
        sx.sync();
        sy.sync();
        sz.sync();
        srx.sync();
        sry.sync();
        srz.sync();
    }

    private void markDirty() {
        dirty = true;
        updateSaveButton();
        if (modeButton != null) {
            modeButton.setMessage(modeLabel());
            mainButton.setMessage(mainLabel());
            offButton.setMessage(offLabel());
        }
    }

    private void updateSaveButton() {
        if (saveButton == null) {
            return;
        }
        saveButton.active = dirty;
        saveButton.setMessage(Component.literal(dirty ? "保存 *" : "保存"));
    }

    private void applyAll() {
        var p = ModelProfileStore.getByKey(modelKey);
        switch (editMode) {
            case 1, 5, 6 -> {
                String key = currentSpecialKey();
                if (key != null) {
                    ModelProfileStore.setAnimationOffsetByKey(player, bridge, key, px, py, pz);
                    ModelProfileStore.setAnimationRotationByKey(player, bridge, key, rx, ry, rz);
                }
            }
            case 2 -> {
                ModelProfileStore.setMoveOffset(player, bridge, px, py, pz);
                ModelProfileStore.setMoveRotation(player, bridge, rx, ry, rz);
            }
            case 3 -> ModelProfileStore.setItemOffset(player, bridge,
                    org.vivecraft.api.data.VRBodyPart.MAIN_HAND, px, py, pz);
            case 4 -> ModelProfileStore.setItemOffset(player, bridge,
                    org.vivecraft.api.data.VRBodyPart.OFF_HAND, px, py, pz);
            default -> {
                ModelProfileStore.setOffset(player, bridge, px, py, pz);
                ModelProfileStore.setOverallRotation(player, bridge, rx, ry, rz);
            }
        }
        ModelProfileStore.setArmBones(player, bridge,
                mainCandidates.get(mainIndex), offCandidates.get(offIndex));
        // 单个保存按钮同时保存当前玩家/模型大小为本模型覆盖。
        ModelProfileStore.setModelScaleOverride(player, bridge, VrBodyConfig.modelScale);
        ModelProfileStore.setPlayerSizeOverride(player, bridge, VrBodyConfig.playerSize);
    }

    private void clearCurrentMode() {
        switch (editMode) {
            case 1, 5, 6 -> {
                String key = currentSpecialKey();
                if (key != null) {
                    ModelProfileStore.setAnimationOffsetByKey(player, bridge, key, 0f, 0f, 0f);
                    ModelProfileStore.setAnimationRotationByKey(player, bridge, key, 0f, 0f, 0f);
                }
            }
            case 2 -> {
                ModelProfileStore.setMoveOffset(player, bridge, 0f, 0f, 0f);
                ModelProfileStore.setMoveRotation(player, bridge, 0f, 0f, 0f);
            }
            case 3 -> ModelProfileStore.setItemOffset(player, bridge,
                    org.vivecraft.api.data.VRBodyPart.MAIN_HAND, 0f, 0f, 0f);
            case 4 -> ModelProfileStore.setItemOffset(player, bridge,
                    org.vivecraft.api.data.VRBodyPart.OFF_HAND, 0f, 0f, 0f);
            default -> {
                ModelProfileStore.setOffset(player, bridge, 0f, 0f, 0f);
                ModelProfileStore.setOverallRotation(player, bridge, 0f, 0f, 0f);
            }
        }
    }

    private Component modeLabel() {
        return Component.literal("编辑模式: " + MODES[editMode] + "  ");
    }

    private Component mainLabel() {
        return Component.literal("主手骨骼: " + boneName(mainCandidates, mainIndex) + "  ");
    }

    private Component offLabel() {
        return Component.literal("副手骨骼: " + boneName(offCandidates, offIndex) + "  ");
    }

    private static String boneName(List<String> list, int index) {
        if (list.isEmpty() || index < 0 || index >= list.size()) {
            return "（自动）";
        }
        String s = list.get(index);
        return s.isEmpty() ? "（自动）" : s;
    }

    /** 打开分页选择界面。 */
    private void openList(String title, List<String> items, int selected, java.util.function.IntConsumer onSelect) {
        Minecraft.getInstance().setScreen(new ModelSelectionScreen(this, title, items, selected, idx -> {
            onSelect.accept(idx);
            Minecraft.getInstance().setScreen(this);
        }));
    }

    void selectEditMode(int idx) {
        editMode = idx;
        loadWorkingValues();
        markDirty();
    }

    void selectMainBone(int idx) {
        mainIndex = idx;
        markDirty();
    }

    void selectOffBone(int idx) {
        offIndex = idx;
        markDirty();
    }
@Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, "单模型适配（按模型 id 保存）", width / 2, height / 2 - 145, COLOR_TITLE);
        graphics.drawCenteredString(font, "模型: " + modelKey, width / 2, height / 2 - 132, COLOR_GOOD);
        graphics.drawCenteredString(font,
                "位置 XYZ + 旋转 XYZ；只有一个保存按钮，改动后才会亮起。",
                width / 2, height / 2 - 119, COLOR_DIM);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (!supportsRotation()) {
            graphics.drawCenteredString(font, "当前模式不应用旋转（只保存位置）",
                    width / 2, height / 2 + 70, COLOR_DIM);
        }
    }

    /** 位置/旋转滑条。 */
    private final class ValueSlider extends AbstractSliderButton {
        private final int axis;
        private final boolean rotation;

        ValueSlider(int x, int y, int w, int h, int axis, boolean rotation, float initial) {
            super(x, y, w, h, Component.empty(), normalize(initial, rotation));
            this.axis = axis;
            this.rotation = rotation;
            updateMessage();
        }

        float current() {
            if (rotation) {
                return switch (axis) {
                    case 0 -> rx;
                    case 1 -> ry;
                    default -> rz;
                };
            }
            return switch (axis) {
                case 0 -> px;
                case 1 -> py;
                default -> pz;
            };
        }

        void sync() {
            this.value = normalize(current(), rotation);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            String prefix = (rotation ? "旋转 " : "偏移 ") + switch (axis) {
                case 0 -> "X: ";
                case 1 -> "Y: ";
                default -> "Z: ";
            };
            double raw = denormalize(this.value, rotation);
            setMessage(Component.literal(prefix + String.format(Locale.ROOT,
                    rotation ? "%+.0f" : "%+.1f", raw)));
        }

        @Override
        protected void applyValue() {
            float v = (float) denormalize(this.value, rotation);
            if (rotation) {
                switch (axis) {
                    case 0 -> rx = v;
                    case 1 -> ry = v;
                    default -> rz = v;
                }
            } else {
                switch (axis) {
                    case 0 -> px = v;
                    case 1 -> py = v;
                    default -> pz = v;
                }
            }
            markDirty();
        }
    }

    private static double normalize(float value, boolean rotation) {
        float min = rotation ? ROT_MIN : OFFSET_MIN;
        float max = rotation ? ROT_MAX : OFFSET_MAX;
        float clamped = Math.max(min, Math.min(max, value));
        return (clamped - min) / (max - min);
    }

    private static double denormalize(double value, boolean rotation) {
        float min = rotation ? ROT_MIN : OFFSET_MIN;
        float max = rotation ? ROT_MAX : OFFSET_MAX;
        float step = rotation ? ROT_STEP : OFFSET_STEP;
        float raw = (float) (min + (max - min) * value);
        return Math.round(raw / step) * step;
    }
}
