package com.elfmcys.ysmvr;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FORGE 总线（运行时）事件。
 * <p>
 * 注意：不限定 Dist。RegisterCommandsEvent 走的是服务端总线（单机集成服务器同样如此），
 * 限定 CLIENT 会收不到；与 YSM 自身的 CommandRegistry 保持同一注册方式，
 * 各处理器内部再按 FMLEnvironment.dist 判断。
 */
@Mod.EventBusSubscriber(modid = YsmVrMod.MOD_ID)
public final class ClientSetup {

    /** Vivecraft 追踪总开关（放在此处避免 Vivecraft 缺失时的类加载失败）。 */
    private static final AtomicBoolean TRACKING_ENABLED = new AtomicBoolean(true);
    /** 模型切换检测：每模型总 GUI 配置自动保存/加载。 */
    private static String lastModelKey;

    private ClientSetup() {
    }

    public static boolean isTrackingEnabled() {
        return TRACKING_ENABLED.get();
    }

    public static void setTrackingEnabled(boolean enabled) {
        TRACKING_ENABLED.set(enabled);
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        var bridge = ActiveBridge.get();
        YsmVrMod.LOGGER.info("[ysmvr] LoggingIn: bridge={}", bridge == null ? "无" : bridge.getClass().getSimpleName());
        if (bridge != null) {
            try {
                bridge.attach(event.getPlayer());
            } catch (Throwable t) {
                YsmVrMod.LOGGER.warn("[ysmvr] 挂载 pose controller 失败", t);
            }
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // 退出世界/返回主菜单时 event.getPlayer() 可能为 null（Minecraft.disconnect 阶段）
        var bridge = ActiveBridge.get();
        if (bridge == null) {
            return;
        }
        var player = event.getPlayer();
        if (player != null) {
            bridge.detach(player.getUUID());
        } else {
            bridge.detachAll();
        }
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        var player = Minecraft.getInstance().player;
        var bridge = ActiveBridge.get();
        if (player == null || bridge == null) {
            return;
        }
        try {
            if (!Float.isNaN(VivecraftPoseTracker.latestBodyYawDeg)) {
                player.yBodyRotO = VivecraftPoseTracker.latestBodyYawDeg;
                player.yBodyRot = VivecraftPoseTracker.latestBodyYawDeg;
            }
            bridge.forceHeadBoneToStore(player);
        } catch (Throwable t) {
            YsmVrMod.LOGGER.warn("[ysmvr] 渲染前强制 Head 失败", t);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        var mc = Minecraft.getInstance();
        while (KeyBindings.OPEN_MAIN_SCREEN.consumeClick()) {
            if (mc.screen == null) {
                mc.setScreen(new YsmVrMainScreen());
            }
        }
        var bridge = ActiveBridge.get();
        if (bridge == null) {
            return;
        }
        String modelKey = ModelProfileStore.currentKey(player, bridge);
        if (!modelKey.equals(lastModelKey)) {
            ModelProfileStore.switchBodyConfig(player, bridge, lastModelKey, modelKey);
            lastModelKey = modelKey;
        }
        if (player.tickCount % 200 == 0) {
            ModelProfileStore.saveCurrentBodyConfig(player, bridge);
        }
        // YSM 在模型加载/重载时会 AnimatableEntity.reset() -> AnimationData.clear()
        // 清空全部 controller（只重新注册它自己的），因此挂载必须能在模型加载后重来。
        // 每 1 秒检查一次；VrPoseController/ModernVrPoseController 自带快照自建兜底，
        // 不依赖 processor 的 init 回调。
        if (player.tickCount % 20 == 0 && bridge.isModelLoaded(player) && !bridge.isAttached(player)) {
            try {
                bridge.attach(player);
            } catch (Throwable t) {
                YsmVrMod.LOGGER.warn("[ysmvr] 重新挂载 pose controller 失败", t);
            }
        }
        try {
            bridge.forceHeadBoneToStore(player);
        } catch (Throwable t) {
            YsmVrMod.LOGGER.warn("[ysmvr] 每 tick 强制 Head 失败", t);
        }
    }

    @SubscribeEvent
    public static void onKeyInput(net.minecraftforge.client.event.InputEvent.Key event) {
        if (event.getAction() != org.lwjgl.glfw.GLFW.GLFW_PRESS) {
            return;
        }
        var mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (KeyBindings.OPEN_MAIN_SCREEN.consumeClick()) {
            if (mc.screen == null) {
                mc.setScreen(new YsmVrMainScreen());
            }
            return;
        }
        if (KeyBindings.TOGGLE_TRACK_MODE.consumeClick()) {
            if (ActiveBridge.get() == null
                    || !net.minecraftforge.fml.ModList.get().isLoaded("vivecraft")) {
                return;
            }
            // AUTO → SIMPLE → EXTENDED → AUTO 循环切换默认绑定模式
            String state;
            if (VrBindings.modeAuto()) {
                VrBindings.setManualMode(VrBindings.Mode.SIMPLE);
                state = "SIMPLE（手动，手柄→整臂、脚→大腿）";
            } else if (VrBindings.mode() == VrBindings.Mode.SIMPLE) {
                VrBindings.setManualMode(VrBindings.Mode.EXTENDED);
                state = "EXTENDED（手动，手柄→前臂、脚→小腿、肘→上臂、膝→大腿）";
            } else {
                VrBindings.setAuto();
                state = "AUTO（自动检测）";
            }
            mc.player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal("[ysmvr] 追踪模式 → " + state), false);
            return;
        }
        if (!KeyBindings.OPEN_BIND_SCREEN.consumeClick()) {
            return;
        }
        if (mc.screen != null) {
            return;
        }
        if (ActiveBridge.get() == null || !net.minecraftforge.fml.ModList.get().isLoaded("vivecraft")) {
            return;
        }
        mc.execute(() -> mc.setScreen(new VrBindScreen()));
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        // 与 YSM 客户端指令相同的注册路径：单机下 source.getEntity() 即本地玩家
        if (FMLEnvironment.dist != Dist.CLIENT) {
            YsmVrMod.LOGGER.info("[ysmvr] RegisterCommandsEvent: 跳过（dist={}）", FMLEnvironment.dist);
            return;
        }
        try {
            if (ActiveBridge.get() != null) {
                YsmVrCommands.register(event.getDispatcher());
                YsmVrMod.LOGGER.info("[ysmvr] /ysmvr 指令已注册（{}）",
                        ActiveBridge.get().getClass().getSimpleName());
            } else {
                YsmVrCommands.registerPlaceholder(event.getDispatcher());
                YsmVrMod.LOGGER.info("[ysmvr] /ysmvr 占位指令已注册（无可用 YSM）");
            }
        } catch (Throwable t) {
            YsmVrMod.LOGGER.error("[ysmvr] /ysmvr 指令注册失败", t);
        }
    }
}
