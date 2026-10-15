package com.elfmcys.ysmvr.mixin;

import com.elfmcys.ysmvr.ActiveBridge;
import com.elfmcys.ysmvr.VrBodyConfig;
import com.elfmcys.ysmvr.VrHeadHide;
import com.elfmcys.ysmvr.VrViewConfig;
import com.elfmcys.yesstevemodel.client.renderer.CustomPlayerRenderer;
import com.elfmcys.yesstevemodel.client.renderer.ModelPreviewRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.vivecraft.api.client.VRClientAPI;

/**
 * 在 YSM 玩家模型真正开始渲染前，按当前 Vivecraft RenderPass 实时设置头部隐藏。
 * <p>
 * LEFT/RIGHT 眼 pass 隐藏头部；CENTER 桌面镜像、THIRD/CAMERA 等保持可见。
 * 之前用 RenderLevelStageEvent 全局切换会在左右眼/镜像多个 pass 间来回覆盖，
 * 导致头部闪烁；这里每次模型 draw 前重设，状态精确对应本 pass。
 * <p>
 * GUI 纸娃娃（物品栏左上角的玩家模型预览、HUD 覆盖层）例外：这些预览走的是
 * 同一个 {@link CustomPlayerRenderer#render}，而 Vivecraft 在 VR 里把 GUI 也画在
 * 左右眼 pass 中，若照常隐藏，纸娃娃会没有头。预览模式（YSM 的
 * {@link ModelPreviewRenderer#isPreview()} / {@link ModelPreviewRenderer#isExtraPlayer()}）
 * 下强制显示头部，保证物品栏预览完整。
 * <p>
 * v0.2.24：Vivecraft 的 {@code VRPlayerRenderer.render} 会先给 poseStack 乘上
 * {@code rotInfo.worldScale}（玩家世界缩放），YSM 模型经 Forge RenderPlayerEvent
 * 渲染在这个 poseStack 里，于是"改玩家大小"会把 YSM 模型一起缩放（用户反馈
 * issue 3）。这里在 YSM 渲染期间乘 {@code 1/worldScale} 把外层缩放抵消：
 * 玩家大小只改玩家，YSM 模型保持自己的世界尺寸；返回值处恢复 pose。
 */
@Mixin(value = CustomPlayerRenderer.class, remap = false)
public abstract class MixinCustomPlayerRenderer {

    /** 抵消 Vivecraft 外层 worldScale 的 push 深度（渲染嵌套安全）。 */
    @Unique
    private static final ThreadLocal<Integer> ysmvr$worldScaleDepth = ThreadLocal.withInitial(() -> 0);
    /** 模型整体旋转（单模型适配 GUI）的 push 深度。 */
    @Unique
    private static final ThreadLocal<Integer> ysmvr$modelRotDepth = ThreadLocal.withInitial(() -> 0);

    @Inject(method = "render", at = @At("HEAD"), remap = false, cancellable = true)
    private void ysmvr$applyPerPassHeadHide(Player player, float entityYaw, float partialTick,
                                            PoseStack poseStack, MultiBufferSource bufferSource,
                                            int packedLight, CallbackInfo ci) {
        if (!(player instanceof LocalPlayer local) || local != Minecraft.getInstance().player) {
            return;
        }
        // v0.2.39：潜行移动时在头显内隐藏本地玩家 YSM 模型（默认开启）。
        // 只作用于左右眼 pass；桌面镜像/第三人称/相机 pass 保持可见。
        boolean hideSneak = ((VrBodyConfig.hideModelSneakMoving && local.walkAnimation.speed() > 0.05f)
                || VrBodyConfig.hideModelWhenSneaking) && local.isCrouching();
        boolean hideFlight = (VrBodyConfig.hideModelCreativeFlying && local.isCreative()
                && local.getAbilities().flying)
                || (VrBodyConfig.hideModelElytraFlying && local.isFallFlying());
        boolean fullBodyLying = VrBodyConfig.fullBodyLyingMode
                && (local.isCrouching() || local.isSwimming() || local.isVisuallySwimming());
        if ((hideSneak || hideFlight) && !fullBodyLying
                && !ModelPreviewRenderer.isPreview()
                && !ModelPreviewRenderer.isExtraPlayer()
                && VrHeadHide.shouldHideHeadForCurrentPass()) {
            ci.cancel();
            return;
        }
        // 抵消 Vivecraft VRPlayerRenderer 外层施加的 worldScale：玩家大小不影响 YSM 模型
        float worldScale = ysmvr$currentWorldScale();
        if (Float.isFinite(worldScale) && Math.abs(worldScale - 1.0f) > 1.0e-3f) {
            poseStack.pushPose();
            poseStack.scale(1.0f / worldScale, 1.0f / worldScale, 1.0f / worldScale);
            ysmvr$worldScaleDepth.set(ysmvr$worldScaleDepth.get() + 1);
        }
        var bridge = ActiveBridge.get();
        if (bridge == null) {
            return;
        }
        boolean holding = !local.getMainHandItem().isEmpty() || !local.getOffhandItem().isEmpty();
        if (com.elfmcys.ysmvr.VrBodyConfig.pauseLowerBodyOnHold && holding) {
            bridge.zeroLowerBodyY(local);
        }
        if (com.elfmcys.ysmvr.VrBodyConfig.cancelArmAnimation && holding) {
            bridge.zeroArmAnimation(local);
        }
        if (com.elfmcys.ysmvr.VrBodyConfig.fullBodyLyingMode) {
            boolean lying = local.isCrouching() || local.isSwimming()
                    || local.isVisuallySwimming() || local.isFallFlying();
            if (lying) {
                bridge.suppressAnimationsMatching(local, "sneak", "sneaking", "swim", "swimming", "crawl");
            }
        }
        if (!com.elfmcys.ysmvr.VrBodyConfig.attackAnimationEnabled) {
            String raw = bridge.getRawAnimationKey(local);
            String rawLower = raw == null ? "" : raw.toLowerCase(java.util.Locale.ROOT);
            boolean attacking = local.swinging || local.swingTime > 0
                    || local.getAttackAnim(Minecraft.getInstance().getFrameTime()) > 0.0f
                    || rawLower.contains("swing") || rawLower.contains("attack")
                    || rawLower.contains("sword") || rawLower.contains("melee");
            if (attacking) {
                bridge.suppressAttackAnimation(local);
                bridge.zeroAttackBodyRotation(local);
                bridge.restoreAttackPose(local);
            }
        }
        float[] modelRot = com.elfmcys.ysmvr.ModelProfileStore.effectiveRotation(local, bridge);
        if (modelRot != null) {
            poseStack.pushPose();
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(modelRot[1]));
            poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(modelRot[0]));
            poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(modelRot[2]));
            ysmvr$modelRotDepth.set(ysmvr$modelRotDepth.get() + 1);
        }
        boolean guiPreview = ModelPreviewRenderer.isPreview() || ModelPreviewRenderer.isExtraPlayer();
        bridge.setBoneHidden(local, VrViewConfig.resolvedHeadBone(),
                !guiPreview && VrHeadHide.shouldHideHeadForCurrentPass());
    }

    /** 模型渲染结束（含提前 return 的路径）：恢复抵消 worldScale 时 push 的 pose。 */
    @Inject(method = "render", at = @At("RETURN"), remap = false)
    private void ysmvr$popWorldScaleInverse(Player player, float entityYaw, float partialTick,
                                            PoseStack poseStack, MultiBufferSource bufferSource,
                                            int packedLight, CallbackInfo ci) {
        int rotDepth = ysmvr$modelRotDepth.get();
        if (rotDepth > 0) {
            poseStack.popPose();
            ysmvr$modelRotDepth.set(rotDepth - 1);
        }
        int depth = ysmvr$worldScaleDepth.get();
        if (depth > 0) {
            poseStack.popPose();
            ysmvr$worldScaleDepth.set(depth - 1);
        }
    }

    @Unique
    private static float ysmvr$currentWorldScale() {
        try {
            var api = VRClientAPI.instance();
            if (api != null && api.isVRActive()) {
                return api.getWorldScale();
            }
        } catch (Throwable ignored) {
            // Vivecraft 未就绪/版本不匹配：不做抵消
        }
        return 1.0f;
    }
}

