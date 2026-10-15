package com.elfmcys.ysmvr;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/** v0.2.61：单模型适配的选择列表独立界面（不再用半透明弹窗，避免底层文字浮现）。 */
@OnlyIn(Dist.CLIENT)
public final class ModelSelectionScreen extends Screen {

    private static final int ROWS = 10;
    private static final int ROW_H = 14;
    private static final int PANEL_W = 320;

    private final ModelProfileScreen parent;
    private final String title;
    private final List<String> items;
    private final IntConsumer onSelect;
    private int selected;
    private int scroll;
    private int x, y, listTop, listH, panelH;
    private boolean dragging;

    public ModelSelectionScreen(ModelProfileScreen parent, String title,
                                List<String> items, int selected, IntConsumer onSelect) {
        super(Component.literal(title));
        this.parent = parent;
        this.title = title;
        this.items = new ArrayList<>(items);
        this.selected = selected;
        this.onSelect = onSelect;
        this.scroll = Math.max(0, selected - ROWS / 2);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        panelH = ROW_H * ROWS + 24;
        x = (width - PANEL_W) / 2;
        y = (height - panelH) / 2;
        listTop = y + 20;
        listH = ROWS * ROW_H;
    }

    private void choose(int index) {
        if (index >= 0 && index < items.size()) {
            selected = index;
            if (onSelect != null) {
                onSelect.accept(index);
            }
        }
        Minecraft.getInstance().setScreen(parent);
    }

    private void scrollBy(int delta) {
        int max = Math.max(0, items.size() - ROWS);
        scroll = Math.max(0, Math.min(max, scroll + delta));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int trackX = x + PANEL_W - 12;
        if (mouseX >= trackX && mouseX <= trackX + 8 && mouseY >= listTop && mouseY <= listTop + listH) {
            int max = Math.max(0, items.size() - ROWS);
            float ratio = (float) ((mouseY - listTop) / listH);
            scroll = Math.max(0, Math.min(max, (int) (ratio * max)));
            dragging = true;
            return true;
        }
        if (mouseX >= x && mouseX <= x + PANEL_W - 14 && mouseY >= listTop && mouseY <= listTop + listH) {
            int row = (int) ((mouseY - listTop) / ROW_H);
            choose(scroll + row);
            return true;
        }
        if (mouseX < x || mouseX > x + PANEL_W || mouseY < y || mouseY > y + panelH) {
            Minecraft.getInstance().setScreen(parent);
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging) {
            int max = Math.max(0, items.size() - ROWS);
            float ratio = (float) ((mouseY - listTop) / listH);
            scroll = Math.max(0, Math.min(max, (int) (ratio * max)));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scrollBy(delta > 0 ? -1 : 1);
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.fill(0, 0, width, height, 0xFF0A0C12);
        graphics.fill(x, y, x + PANEL_W, y + panelH, 0xFF161A22);
        graphics.fill(x, y, x + PANEL_W, y + 1, 0xFF3FB6FF);
        graphics.fill(x, y + panelH - 1, x + PANEL_W, y + panelH, 0xFF3FB6FF);
        graphics.drawString(font, title, x + 6, y + 5, 0xFFFFFFFF, false);
        graphics.drawString(font, (scroll + 1) + "-" + Math.min(items.size(), scroll + ROWS)
                + "/" + items.size(), x + PANEL_W - 58, y + 5, 0xFFAAAAAA, false);

        for (int i = 0; i < ROWS; i++) {
            int index = scroll + i;
            if (index >= items.size()) {
                break;
            }
            int rowY = listTop + i * ROW_H;
            boolean sel = index == selected;
            boolean hover = mouseX >= x && mouseX < x + PANEL_W - 14
                    && mouseY >= rowY && mouseY < rowY + ROW_H;
            if (sel || hover) {
                graphics.fill(x + 3, rowY, x + PANEL_W - 14, rowY + ROW_H - 1,
                        sel ? 0xFF2E5F86 : 0x60304056);
            }
            String label = items.get(index);
            if (label == null || label.isEmpty()) {
                label = "（自动）";
            }
            graphics.drawString(font, label, x + 8, rowY + 3,
                    sel ? 0xFFFFFFFF : 0xFF9AA4B2, false);
        }

        int trackX = x + PANEL_W - 10;
        graphics.fill(trackX, listTop, trackX + 4, listTop + listH, 0xFF30343C);
        int max = Math.max(1, items.size());
        int handleH = Math.max(12, listH * ROWS / max);
        int handleY = listTop + (items.size() <= ROWS ? 0
                : (int) ((listH - handleH) * ((float) scroll / (items.size() - ROWS))));
        graphics.fill(trackX - 1, handleY, trackX + 5, handleY + handleH, 0xFF3FB6FF);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}