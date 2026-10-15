package com.elfmcys.ysmvr.mixin;

import com.elfmcys.ysmvr.VrBodyConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 让 Vivecraft 的 GUI/HUD 层始终通过深度测试（GL_ALWAYS）。
 * <p>
 * Vivecraft 是把整个 GUI framebuffer 作为一张世界空间贴图叠到场景上的
 * （{@code VREffectsHelper.renderGuiLayer → renderScreen}）。YSM 模型在
 * 世界深度里写深度后，这块 GUI 贴图就会被模型挡住。把 renderGuiLayer 的
 * {@code depthAlways} 参数强制为 true 后，快捷栏/HUD 会穿透 YSM 模型显示。
 * <p>
 * 目标类来自 Vivecraft，编译期不可见，因此用 targets 字符串 + remap=false。
 */
@Mixin(targets = "org.vivecraft.client_vr.render.helpers.VREffectsHelper", remap = false)
public abstract class MixinVivecraftGuiLayer {

    @ModifyVariable(method = "renderGuiLayer", at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = false)
    private static boolean ysmvr$forceGuiDepthAlways(boolean depthAlways) {
        return VrBodyConfig.hotbarOverModel || depthAlways;
    }
}
