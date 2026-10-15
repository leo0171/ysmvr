package com.elfmcys.ysmvr.mixin;

import com.elfmcys.ysmvr.VrBodyConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** v0.2.55：可选隐藏 Vivecraft 手持物品，避免与 YSM 模型物品重叠。 */
@Mixin(targets = "org.vivecraft.client_vr.render.helpers.VRArmHelper", remap = false)
public final class MixinVRArmHelper {

    @Inject(method = "renderVRHands", at = @At("HEAD"), cancellable = true, remap = false)
    private static void ysmvr$hideVrHeldItem(float partialTick, boolean renderMain, boolean renderOff,
                                              boolean menuHandMain, boolean menuHandOff,
                                              PoseStack poseStack, CallbackInfo ci) {
        if (!VrBodyConfig.hideVrHeldItem) {
            return;
        }
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        if (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty()) {
            ci.cancel();
        }
    }
}