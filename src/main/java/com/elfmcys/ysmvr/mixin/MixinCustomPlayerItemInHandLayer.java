package com.elfmcys.ysmvr.mixin;

import com.elfmcys.ysmvr.YsmItemAnchorCapture;
import com.elfmcys.yesstevemodel.client.renderer.layer.CustomPlayerItemInHandLayer;
import com.elfmcys.yesstevemodel.geckolib3.geo.animated.AnimatedGeoModel;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 捕获 YSM 真正渲染手持物品时使用的锚点世界位置（v0.2.30）。
 * <p>
 * {@link CustomPlayerItemInHandLayer#renderItem} 会先调用
 * {@code applyItemBoneTransform()} → {@code RenderUtils.prepMatrixForLocator(
 * poseStack, rightHandBones()/leftHandBones())}，此时 poseStack 被平移到
 * 物品锚点。这里在 {@code applyItemBoneTransform} RETURN 时读取该平移矩阵
 * （加上相机位置得到绝对世界坐标），写入 {@link YsmItemAnchorCapture}。
 * <p>
 * tracker 下一帧用这个真实锚点算修正，不再依赖静态骨骼层级
 * {@code getBoneModelPosition}（后者对动画/约束驱动的 HandLocator 会测出
 * Jacobian 全 0，或硬改 locator 导致脱手）。
 * <p>
 * 只处理本地玩家的模型；renderItem 会被主手/副手各调用一次，
 * {@link HumanoidArm#LEFT} 对应左手（副手）。
 */
@Mixin(value = CustomPlayerItemInHandLayer.class, remap = false)
public abstract class MixinCustomPlayerItemInHandLayer {

    // v0.2.55：可选隐藏 YSM 模型手持物品。
    // （在 renderItem HEAD 判断，见下方注入）

    @Unique
    private static final ThreadLocal<LivingEntity> ysmvr$capturedEntity = new ThreadLocal<>();

    @Inject(method = "renderItem", at = @At("HEAD"), cancellable = true, remap = false)
    private void ysmvr$enterRenderItem(AnimatedGeoModel model, LivingEntity entity,
                                       ItemStack itemStack, ItemDisplayContext ctx, HumanoidArm arm,
                                       PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                                       CallbackInfo ci) {
        if (com.elfmcys.ysmvr.VrBodyConfig.hideYsmHeldItem
                && itemStack != null && !itemStack.isEmpty()
                && entity == Minecraft.getInstance().player
                && com.elfmcys.ysmvr.VrHeadHide.isHeadsetPass()) {
            ci.cancel();
            return;
        }
        ysmvr$capturedEntity.set(entity);
    }

    @Inject(method = "renderItem", at = @At("RETURN"), remap = false)
    private void ysmvr$exitRenderItem(AnimatedGeoModel model, LivingEntity entity,
                                      ItemStack itemStack, ItemDisplayContext ctx, HumanoidArm arm,
                                      PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                                      CallbackInfo ci) {
        ysmvr$capturedEntity.remove();
    }

    @Inject(method = "applyItemBoneTransform", at = @At("RETURN"), remap = false)
    private void ysmvr$captureItemAnchor(HumanoidArm arm, PoseStack poseStack,
                                         AnimatedGeoModel model,
                                         CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()) {
            return;
        }
        LivingEntity entity = ysmvr$capturedEntity.get();
        if (!(entity instanceof LocalPlayer local) || local != Minecraft.getInstance().player) {
            return;
        }
        var m = poseStack.last().pose();
        var cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        YsmItemAnchorCapture.set(arm == HumanoidArm.LEFT,
                (float) (m.m30() + cam.x),
                (float) (m.m31() + cam.y),
                (float) (m.m32() + cam.z));
    }
}
