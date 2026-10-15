package com.elfmcys.ysmvr;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端按键绑定。独立成类，避免专用服务器加载 KeyMapping（客户端专属类）。
 * 默认未绑定，玩家在"按键控制"里自行设置。
 */
public final class KeyBindings {
    public static final KeyMapping OPEN_BIND_SCREEN = new KeyMapping(
            "key.ysmvr.open_bind",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            "key.categories.ysmvr");

    /** 追踪模式开关：AUTO（自动检测）→ SIMPLE → EXTENDED 循环切换。 */
    public static final KeyMapping TOGGLE_TRACK_MODE = new KeyMapping(
            "key.ysmvr.toggle_track_mode",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            "key.categories.ysmvr");

    /** 打开 YSM VR 总设置 GUI（可绑到 Vivecraft 轮盘/手柄按键）。 */
    public static final KeyMapping OPEN_MAIN_SCREEN = new KeyMapping(
            "key.ysmvr.open_main",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            "key.categories.ysmvr");

    private KeyBindings() {
    }
}
