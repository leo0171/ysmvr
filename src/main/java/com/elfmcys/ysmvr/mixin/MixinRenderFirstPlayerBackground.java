package com.elfmcys.ysmvr.mixin;

import com.elfmcys.ysmvr.VrBodyConfig;
import com.elfmcys.yesstevemodel.client.event.RenderFirstPlayerBackground;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.vivecraft.api.client.VRClientAPI;

/** v0.2.47：VR 中同时取消 RenderFirstPlayerBackground 的 YSM 第一人称手臂绘制。 */
@Mixin(value = RenderFirstPlayerBackground.class, remap = false)
public final class MixinRenderFirstPlayerBackground {

    @Inject(method = "onRenderHand", at = @At("HEAD"), cancellable = true, remap = false)
    private static void ysmvr$hideYsmArmInVr(PoseStack poseStack, MultiBufferSource bufferSource,
                                             int packedLight, float partialTick, CallbackInfo ci) {
        var local = net.minecraft.client.Minecraft.getInstance().player;
        if (!VrBodyConfig.shouldHideYsmFirstPersonArms(local)) {
            return;
        }
        try {
            var api = VRClientAPI.instance();
            if (api != null && api.isVRActive()) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // Vivecraft 未就绪：保持 YSM 原行为
        }
    }
}
