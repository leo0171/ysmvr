package com.elfmcys.ysmvr.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v0.2.58：把本模组所有 GUI（除 debug 打开的骨骼/面部 GUI）的原版按钮
 * 统一绘成总 GUI 的 VRChat 深色风格。
 */
@Mixin(Button.class)
public abstract class MixinButtonVrchatStyle {

    @Inject(method = "m_87963_", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ysmvr$vrchatStyle(GuiGraphics graphics, int mouseX, int mouseY,
                                   float partialTick, CallbackInfo ci) {
        Screen screen = Minecraft.getInstance().screen;
        if (screen == null || !screen.getClass().getName().startsWith("com.elfmcys.ysmvr.")) {
            return;
        }
        String screenName = screen.getClass().getSimpleName();
        // 调试栏里打开的界面保持原版样式
        if ("BoneViewerScreen".equals(screenName) || "FaceInspectScreen".equals(screenName)) {
            return;
        }
        Button self = (Button) (Object) this;
        int x = self.getX();
        int y = self.getY();
        int w = self.getWidth();
        int h = self.getHeight();
        boolean hover = (self.isHovered() || self.isFocused()) && self.active;
        int bg = !self.active ? 0xFF262A32 : hover ? 0xFF2A3040 : 0xFF20242E;
        graphics.fill(x, y, x + w, y + h, bg);
        graphics.fill(x, y, x + 3, y + h, self.active ? 0xFF3FB6FF : 0xFF4A4F59);
        if (hover) {
            graphics.fill(x, y, x + w, y + 1, 0x80FFFFFF);
            graphics.fill(x, y + h - 1, x + w, y + h, 0x80FFFFFF);
        }
        var font = Minecraft.getInstance().font;
        String label = self.getMessage().getString();
        graphics.drawString(font, label, x + 8, y + (h - 8) / 2,
                self.active ? 0xFFF2F5F8 : 0xFF8A9099, false);
        ci.cancel();
    }
}