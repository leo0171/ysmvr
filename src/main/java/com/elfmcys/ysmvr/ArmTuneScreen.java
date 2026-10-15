package com.elfmcys.ysmvr;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.vivecraft.api.data.VRBodyPart;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 手臂偏移手动微调 GUI（v0.2.25：绝对偏移、实时保存、重新打开读回当前值）。
 * <p>
 * 左/右臂各 6 个滑块：旋转 X/Y/Z（±180°，0.5°）+ 位置 X/Y/Z（±16 模型单位，
 * 0.25 步进）。同一侧有多根骨骼时以 ForeArm &gt; Arm &gt; Hand 优先级的主骨骼
 * 显示绝对偏移；拖动时把变化量同步加到该侧全部手臂骨骼，实时写入 VrBindings。
 * "重置本次微调"恢复打开界面时快照；"清零左/右臂偏移"把旋转+位置都置 0。
 */
@OnlyIn(Dist.CLIENT)
public final class ArmTuneScreen extends Screen {

    private static final float MIN_ROT = -180f;
    private static final float MAX_ROT = 180f;
    private static final float STEP_ROT = 0.5f;
    private static final float MIN_POS = -16f;
    private static final float MAX_POS = 16f;
    private static final float STEP_POS = 0.25f;

    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_DIM = 0xFFAAAAAA;
    private static final int COLOR_SIDE = 0xFF55FF55;

    private final List<VrBindings.Binding> leftBindings = new ArrayList<>();
    private final List<VrBindings.Binding> rightBindings = new ArrayList<>();
    private final Map<VrBindings.Binding, float[]> openRotation = new HashMap<>();
    private final Map<VrBindings.Binding, float[]> openPosition = new HashMap<>();
    private VrBindings.Binding leftPrimary;
    private VrBindings.Binding rightPrimary;
    /** 显示用绝对值：0-2 旋转 X/Y/Z，3-5 位置 X/Y/Z。 */
    private final float[] leftDisplay = new float[6];
    private final float[] rightDisplay = new float[6];
    private final DeltaSlider[] leftSliders = new DeltaSlider[6];
    private final DeltaSlider[] rightSliders = new DeltaSlider[6];

    public ArmTuneScreen() {
        super(Component.literal("手臂偏移微调"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        leftBindings.clear();
        rightBindings.clear();
        openRotation.clear();
        openPosition.clear();
        collectBindings(true, leftBindings);
        collectBindings(false, rightBindings);
        for (var b : leftBindings) {
            snapshot(b);
        }
        for (var b : rightBindings) {
            if (!openRotation.containsKey(b)) {
                snapshot(b);
            }
        }
        leftPrimary = choosePrimary(leftBindings);
        rightPrimary = choosePrimary(rightBindings);
        loadDisplayFromPrimary(true);
        loadDisplayFromPrimary(false);

        int leftX = width / 2 - 160;
        int rightX = width / 2 + 10;
        int sliderW = 150;
        int rowH = 18;
        int top = 52;
        for (int i = 0; i < 6; i++) {
            leftSliders[i] = new DeltaSlider(leftX, top + i * rowH, sliderW, 16, true, i);
            rightSliders[i] = new DeltaSlider(rightX, top + i * rowH, sliderW, 16, false, i);
            addRenderableWidget(leftSliders[i]);
            addRenderableWidget(rightSliders[i]);
        }

        int btnY = height - 54;
        addRenderableWidget(Button.builder(Component.literal("重置本次微调"), b -> resetToOpen())
                .bounds(width / 2 - 150, btnY, 140, 18).build());
        addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose())
                .bounds(width / 2 + 10, btnY, 140, 18).build());
        btnY += 22;
        addRenderableWidget(Button.builder(Component.literal("清零左臂偏移"), b -> clearOffsets(true))
                .bounds(width / 2 - 150, btnY, 140, 18).build());
        addRenderableWidget(Button.builder(Component.literal("清零右臂偏移"), b -> clearOffsets(false))
                .bounds(width / 2 + 10, btnY, 140, 18).build());
    }

    private void snapshot(VrBindings.Binding b) {
        openRotation.put(b, new float[]{b.rotX, b.rotY, b.rotZ});
        openPosition.put(b, new float[]{b.posX, b.posY, b.posZ});
    }

    private static void collectBindings(boolean left, List<VrBindings.Binding> out) {
        String side = left ? "left" : "right";
        for (var part : VRBodyPart.values()) {
            var list = VrBindings.get(part);
            if (list == null) {
                continue;
            }
            for (var b : list) {
                String name = b.boneName == null ? "" : b.boneName.toLowerCase(Locale.ROOT);
                if (!name.contains(side)) {
                    continue;
                }
                if ((name.contains("arm") || name.contains("hand")) && !out.contains(b)) {
                    out.add(b);
                }
            }
        }
    }

    /** 显示优先级：ForeArm > Arm（非 ForeArm）> Hand > 其它。 */
    private static VrBindings.Binding choosePrimary(List<VrBindings.Binding> list) {
        if (list.isEmpty()) {
            return null;
        }
        for (var b : list) {
            String n = b.boneName == null ? "" : b.boneName.toLowerCase(Locale.ROOT);
            if (n.contains("forearm")) {
                return b;
            }
        }
        for (var b : list) {
            String n = b.boneName == null ? "" : b.boneName.toLowerCase(Locale.ROOT);
            if (n.endsWith("arm") && !n.contains("forearm")) {
                return b;
            }
        }
        for (var b : list) {
            String n = b.boneName == null ? "" : b.boneName.toLowerCase(Locale.ROOT);
            if (n.contains("hand")) {
                return b;
            }
        }
        return list.get(0);
    }

    private void loadDisplayFromPrimary(boolean left) {
        float[] d = displayFor(left);
        VrBindings.Binding p = left ? leftPrimary : rightPrimary;
        if (p == null) {
            Arrays.fill(d, 0f);
            return;
        }
        d[0] = p.rotX;
        d[1] = p.rotY;
        d[2] = p.rotZ;
        d[3] = p.posX;
        d[4] = p.posY;
        d[5] = p.posZ;
    }

    private List<VrBindings.Binding> bindingsFor(boolean left) {
        return left ? leftBindings : rightBindings;
    }

    private float[] displayFor(boolean left) {
        return left ? leftDisplay : rightDisplay;
    }

    private void addOffset(VrBindings.Binding b, int index, float step) {
        switch (index) {
            case 0 -> b.rotX = wrap180(b.rotX + step);
            case 1 -> b.rotY = wrap180(b.rotY + step);
            case 2 -> b.rotZ = wrap180(b.rotZ + step);
            case 3 -> b.posX += step;
            case 4 -> b.posY += step;
            default -> b.posZ += step;
        }
    }

    private void resetToOpen() {
        for (var b : leftBindings) {
            restore(b);
        }
        for (var b : rightBindings) {
            restore(b);
        }
        loadDisplayFromPrimary(true);
        loadDisplayFromPrimary(false);
        syncSliders(leftSliders);
        syncSliders(rightSliders);
        VrBindings.save();
    }

    private void restore(VrBindings.Binding b) {
        float[] r = openRotation.get(b);
        float[] p = openPosition.get(b);
        if (r != null) {
            b.rotX = r[0];
            b.rotY = r[1];
            b.rotZ = r[2];
        }
        if (p != null) {
            b.posX = p[0];
            b.posY = p[1];
            b.posZ = p[2];
        }
    }

    private void clearOffsets(boolean left) {
        for (var b : bindingsFor(left)) {
            b.rotX = 0f;
            b.rotY = 0f;
            b.rotZ = 0f;
            b.posX = 0f;
            b.posY = 0f;
            b.posZ = 0f;
            openRotation.put(b, new float[]{0f, 0f, 0f});
            openPosition.put(b, new float[]{0f, 0f, 0f});
        }
        Arrays.fill(displayFor(left), 0f);
        syncSliders(left ? leftSliders : rightSliders);
        VrBindings.save();
    }

    private static void syncSliders(DeltaSlider[] sliders) {
        for (var s : sliders) {
            if (s != null) {
                s.syncFromDisplay();
            }
        }
    }

    private static float wrap180(float deg) {
        float d = deg % 360f;
        if (d > 180f) {
            d -= 360f;
        }
        if (d < -180f) {
            d += 360f;
        }
        return d;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, "手臂偏移手动微调（显示当前绝对值，实时保存）", width / 2, 6, COLOR_TITLE);
        graphics.drawCenteredString(font, "旋转 ±180° / 位置 ±16 模型单位（1 格=16）；重新打开会读回当前值；校准会覆盖旋转偏移",
                width / 2, 20, COLOR_DIM);
        String leftName = leftPrimary == null ? "（无）" : leftPrimary.boneName;
        String rightName = rightPrimary == null ? "（无）" : rightPrimary.boneName;
        graphics.drawString(font, "左臂（OFF_HAND）主骨骼: " + leftName, width / 2 - 160, 40, COLOR_SIDE);
        graphics.drawString(font, "右臂（MAIN_HAND）主骨骼: " + rightName, width / 2 + 10, 40, COLOR_SIDE);
        graphics.drawString(font, "覆盖: " + boneNames(leftBindings) + " | " + boneNames(rightBindings),
                width / 2 - 150, height - 76, COLOR_DIM);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private static String boneNames(List<VrBindings.Binding> bindings) {
        if (bindings.isEmpty()) {
            return "（无匹配骨骼）";
        }
        var sb = new StringBuilder();
        for (var b : bindings) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(b.boneName);
        }
        return sb.toString();
    }

    /** index 0-2 = 旋转 X/Y/Z，3-5 = 位置 X/Y/Z；滑块显示绝对值。 */
    private final class DeltaSlider extends AbstractSliderButton {
        private final boolean left;
        private final int index;

        DeltaSlider(int x, int y, int w, int h, boolean left, int index) {
            super(x, y, w, h, Component.empty(), normalize(displayFor(left)[index], index));
            this.left = left;
            this.index = index;
            updateMessage();
        }

        void syncFromDisplay() {
            this.value = normalize(displayFor(left)[index], index);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int axis = index % 3;
            boolean position = index >= 3;
            String axisName = switch (axis) {
                case 0 -> "X";
                case 1 -> "Y";
                default -> "Z";
            };
            String label = (left ? "左" : "右") + "臂 " + (position ? "位置" : "旋转") + axisName + ": ";
            double shown = degrees(this.value, index);
            setMessage(Component.literal(label + (position
                    ? String.format(Locale.ROOT, "%+.2f", shown)
                    : String.format(Locale.ROOT, "%+.1f°", shown))));
        }

        @Override
        protected void applyValue() {
            float[] display = displayFor(left);
            float next = degrees(this.value, index);
            float step = next - display[index];
            if (Math.abs(step) > 1.0e-4f) {
                for (var b : bindingsFor(left)) {
                    addOffset(b, index, step);
                }
                display[index] = next;
                VrBindings.save();
            }
        }
    }

    private static double normalize(float value, int index) {
        float min = index < 3 ? MIN_ROT : MIN_POS;
        float max = index < 3 ? MAX_ROT : MAX_POS;
        return (Math.max(min, Math.min(max, value)) - min) / (max - min);
    }

    private static float degrees(double value, int index) {
        float min = index < 3 ? MIN_ROT : MIN_POS;
        float max = index < 3 ? MAX_ROT : MAX_POS;
        float step = index < 3 ? STEP_ROT : STEP_POS;
        float raw = (float) (min + (max - min) * value);
        return Math.round(raw / step) * step;
    }
}

