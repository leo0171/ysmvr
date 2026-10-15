package com.elfmcys.ysmvr.mixin;

import com.elfmcys.yesstevemodel.client.entity.LivingAnimatable;
import com.elfmcys.yesstevemodel.geckolib3.core.event.predicate.AnimationEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 彻底取消 YSM 内置头部追踪对 Head 骨骼的最终写入。
 * <p>
 * ysmvr 的 {@code ModernVrPoseController} 已经在每帧提交 Head 的 store 值，
 * 但 YSM 的 {@code applyHeadTracking} 会在动画管线最后再次写 Head，
 * 并在过渡帧使用 {@code armorBoneOffset}，导致正面↔左侧过渡出现翻转/反向旋转。
 * 这里直接取消 applyHeadTracking / resetHeadTracking，让本模组成为唯一头部驱动源。
 */
@Mixin(value = LivingAnimatable.class, remap = false)
public abstract class MixinLivingAnimatable {

    @Inject(method = "applyHeadTracking", at = @At("HEAD"), cancellable = true, remap = false)
    private void ysmvr$cancelApplyHeadTracking(AnimationEvent<?> event, boolean wasAnimEvaluated,
                                               CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "resetHeadTracking", at = @At("HEAD"), cancellable = true, remap = false)
    private void ysmvr$cancelResetHeadTracking(boolean wasAnimEvaluated, CallbackInfo ci) {
        ci.cancel();
    }
}
