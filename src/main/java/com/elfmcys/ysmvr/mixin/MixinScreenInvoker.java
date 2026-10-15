package com.elfmcys.ysmvr.mixin;

import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 让本模组 mixin 能向任意 Screen 添加 widget（1.20.1 SRG 名）。 */
@Mixin(Screen.class)
public interface MixinScreenInvoker {
    @Invoker(value = "m_142416_", remap = false)
    GuiEventListener ysmvr$add(GuiEventListener widget);
}