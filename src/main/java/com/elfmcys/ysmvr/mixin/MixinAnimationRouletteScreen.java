package com.elfmcys.ysmvr.mixin;

import com.elfmcys.ysmvr.YsmVrMainScreen;
import com.elfmcys.yesstevemodel.client.gui.AnimationRouletteScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** v0.2.53：在 YSM 轮盘动作 GUI 左下角加YSM VR 设置按钮（该界面空位多）。 */
@Mixin(value = AnimationRouletteScreen.class, remap = false)
public final class MixinAnimationRouletteScreen {

    @Inject(method = "m_7856_", at = @At("TAIL"), remap = false)
    private void ysmvr$addMainButton(CallbackInfo ci) {
        Screen self = (Screen) (Object) this;
        ((MixinScreenInvoker) (Object) this).ysmvr$add(
                Button.builder(Component.literal("YSM VR 设置"), b ->
                                Minecraft.getInstance().setScreen(new YsmVrMainScreen()))
                        .bounds(10, self.height - 28, 140, 20)
                        .build());
    }
}