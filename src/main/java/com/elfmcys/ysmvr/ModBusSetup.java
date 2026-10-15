package com.elfmcys.ysmvr;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * MOD 总线（生命周期）事件。注意：FMLClientSetupEvent 走的是 MOD 总线，
 * 必须用 {@code bus = Bus.MOD} 注册，默认的 FORGE 总线收不到。
 */
@Mod.EventBusSubscriber(modid = YsmVrMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ModBusSetup {

    private ModBusSetup() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // YSM 3.0 兼容性检查：checker 由注解处理器生成，内部全部走反射，YSM 缺失时可安全调用
        boolean ysm3Compatible = false;
        if (ModList.get().isLoaded("ysm")) {
            try {
                var result = YsmBridgeCompatibilityChecker.check();
                ysm3Compatible = result.isCompatible();
                if (ysm3Compatible) {
                    YsmVrMod.LOGGER.info("[ysmvr] YSM 3.0 兼容性检查通过: {}", result.status());
                } else {
                    YsmVrMod.LOGGER.warn("[ysmvr] YSM 3.0 兼容性检查未通过: {} {}",
                            result.status(), result.issues());
                }
            } catch (Throwable t) {
                YsmVrMod.LOGGER.warn("[ysmvr] YSM 3.0 兼容性检查失败", t);
            }
        }

        // 选择运行时桥接（ysm 3.0 或 ModernYSM 2.6.x）
        ActiveBridge.init(ysm3Compatible);

        // Vivecraft tracker 注册：必须在游戏循环开始前完成
        if (FMLEnvironment.dist == Dist.CLIENT && ModList.get().isLoaded("vivecraft")) {
            VrBindings.load();
            VrViewConfig.load();
            VrBodyConfig.load();
            try {
                var api = org.vivecraft.api.client.VRClientAPI.instance();
                if (api != null) {
                    var tracker = new VivecraftPoseTracker();
                    api.addClientRegistrationHandler(handler -> handler.registerTrackers(tracker));
                    YsmVrMod.LOGGER.info("[ysmvr] Vivecraft tracker 已注册");
                }
            } catch (Throwable t) {
                YsmVrMod.LOGGER.warn("[ysmvr] Vivecraft tracker 注册失败", t);
            }
        }
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(net.minecraftforge.client.event.RegisterKeyMappingsEvent event) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            event.register(KeyBindings.OPEN_BIND_SCREEN);
            event.register(KeyBindings.TOGGLE_TRACK_MODE);
            event.register(KeyBindings.OPEN_MAIN_SCREEN);
        }
    }
}
