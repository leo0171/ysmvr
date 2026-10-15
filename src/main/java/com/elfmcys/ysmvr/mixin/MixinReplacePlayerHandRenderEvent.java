package com.elfmcys.ysmvr.mixin;

import com.elfmcys.ysmvr.VrBodyConfig;
import com.elfmcys.yesstevemodel.client.event.ReplacePlayerHandRenderEvent;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.vivecraft.api.client.VRClientAPI;

/**
 * v0.2.47：VR 中隐藏 YSM 独立第一人称手臂模型（arm.json）。
 * <p>
 * 12_little 等模型的主模型与 arm 模型骨头同名但枢轴不同（arm.json 的肩/手
 * 枢轴 y 比 main.json 高 8.5）；VR 下若继续渲染 arm 模型，它会盖在主模型
 * 手臂外侧，看起来就是"YSM 手不跟控制器"。VR 中交给主模型手臂（我们的 IK
 * 已驱动）显示；桌面第一人称仍保留 YSM 自带手。
 */
@Mixin(value = ReplacePlayerHandRenderEvent.class, remap = false)
public final class MixinReplacePlayerHandRenderEvent {

    @Inject(method = "onRenderArm", at = @At("HEAD"), cancellable = true, remap = false)
    private static void ysmvr$hideYsmArmInVr(Player player, HumanoidArm arm, PoseStack poseStack,
                                              MultiBufferSource bufferSource, int packedLight,
                                              CallbackInfoReturnable<Boolean> cir) {
        net.minecraft.client.player.LocalPlayer local = player instanceof net.minecraft.client.player.LocalPlayer lp ? lp : null;
        if (!VrBodyConfig.shouldHideYsmFirstPersonArms(local)) {
            return;
        }
        try {
            var api = VRClientAPI.instance();
            if (api != null && api.isVRActive()) {
                cir.setReturnValue(false);
            }
        } catch (Throwable ignored) {
            // Vivecraft 未就绪：保持 YSM 原行为
        }
    }
}
