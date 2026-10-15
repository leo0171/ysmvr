package com.elfmcys.ysmvr.mixin;

import com.elfmcys.ysmvr.VrBodyConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 快捷栏/HUD 覆盖 YSM 模型。
 * <p>
 * Vivecraft 的 VR 快捷栏走原版 {@link Gui#renderHotbar}，但 YSM 玩家模型会先写入
 * 世界深度缓冲，导致快捷栏被模型挡住。开启 {@link VrBodyConfig#hotbarOverModel}
 * 时，在 renderHotbar 期间临时关闭深度测试/深度写入，让快捷栏画在模型之上；
 * 方法返回时恢复，避免影响后续 HUD。
 */
@Mixin(Gui.class)
public abstract class MixinGuiHotbar {

    @Unique
    private boolean ysmvr$hotbarDepthDisabled;

    // 生产环境 SRG 名：renderHotbar(float, GuiGraphics) -> m_280518_
    @Inject(method = "m_280518_(FLnet/minecraft/client/gui/GuiGraphics;)V", at = @At("HEAD"), remap = false)
    private void ysmvr$hotbarOverModelHead(CallbackInfo ci) {
        if (!VrBodyConfig.hotbarOverModel) {
            return;
        }
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        this.ysmvr$hotbarDepthDisabled = true;
    }

    @Inject(method = "m_280518_(FLnet/minecraft/client/gui/GuiGraphics;)V", at = @At("RETURN"), remap = false)
    private void ysmvr$hotbarOverModelReturn(CallbackInfo ci) {
        if (!this.ysmvr$hotbarDepthDisabled) {
            return;
        }
        this.ysmvr$hotbarDepthDisabled = false;
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }
}
