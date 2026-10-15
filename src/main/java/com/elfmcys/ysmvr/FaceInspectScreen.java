package com.elfmcys.ysmvr;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class FaceInspectScreen extends Screen {

    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_INFO = 0xFFEEEEEE;
    private static final int COLOR_DIM = 0xFFBBBBBB;
    private static final int COLOR_BG = 0x88000000;
    private static final int FOOTER_H = 28;

    private float yaw = 0f;
    private float pitch = 0f;
    private float scale = 100f;

    public FaceInspectScreen() {
        super(Component.literal("YSM 正面检视"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);

        var bridge = ActiveBridge.get();
        var player = Minecraft.getInstance().player;

        if (bridge == null || player == null) {
            graphics.drawCenteredString(font, "YSM 不可用", width / 2, height / 2 - 10, 0xFFFF5555);
            return;
        }

        int cx = width / 2;
        int cy = height / 2 + 12;

        boolean ok = bridge.renderModelPreview(player, cx, cy, scale, pitch, yaw, partialTick);
        graphics.flush();
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

        if (!ok) {
            graphics.drawString(font, "（模型预览不可用）", 8, 24, 0xFFFF5555);
        }

        graphics.drawCenteredString(font, "YSM 正面检视", width / 2, 8, COLOR_TITLE);
        graphics.drawCenteredString(font,
                "视角 yaw=" + f1(yaw) + "° | 拖拽旋转 · ←/→ 转体 · ↑/↓ 缩放 · R 复位 · Esc 关闭",
                width / 2, 20, COLOR_DIM);

        String headBone = VrViewConfig.resolvedHeadBone();
        Bridge.BoneRow headRow = null;
        for (var row : bridge.boneRows(player)) {
            if (row.name().equals(headBone)) {
                headRow = row;
                break;
            }
        }

        graphics.fill(0, height - FOOTER_H, width, height, COLOR_BG);
        String headText;
        if (headRow != null) {
            headText = "头部骨骼 " + headBone
                    + " | 实时旋转: " + f1(deg(headRow.rotX())) + "°, "
                    + f1(deg(headRow.rotY())) + "°, " + f1(deg(headRow.rotZ())) + "°";
        } else {
            headText = "头部骨骼 " + headBone + "（未找到）";
        }
        graphics.drawString(font, headText, 8, height - FOOTER_H + 3, COLOR_INFO);
        String stateText = "头 yaw 补偿: " + f1(VrBodyConfig.headYawOffset) + "°"
                + " | 追踪: " + (ClientSetup.isTrackingEnabled() ? "开" : "关")
                + " | 把模型转到正脸，若头骨 ry 接近 ±90° 即为 90° 偏移";
        graphics.drawString(font, stateText, 8, height - FOOTER_H + 3 + 10, COLOR_DIM);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0) {
            yaw = clamp(yaw + (float) dragX * 0.8f, -180f, 180f);
            pitch = clamp(pitch + (float) dragY * 0.5f, -90f, 90f);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_A) {
            yaw = clamp(yaw - 15f, -180f, 180f);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT || keyCode == GLFW.GLFW_KEY_D) {
            yaw = clamp(yaw + 15f, -180f, 180f);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_W) {
            scale = clamp(scale + 10f, 30f, 400f);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_DOWN || keyCode == GLFW.GLFW_KEY_S) {
            scale = clamp(scale - 10f, 30f, 400f);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_R) {
            yaw = 0f;
            pitch = 0f;
            scale = 100f;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float deg(float rad) {
        return (float) Math.toDegrees(rad);
    }

    private static String f1(float v) {
        return String.format("%.1f", v);
    }
}
