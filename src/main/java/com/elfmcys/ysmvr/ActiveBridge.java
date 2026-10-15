package com.elfmcys.ysmvr;

import net.minecraftforge.fml.ModList;

/**
 * 运行时选择当前平台对应的 {@link Bridge} 实现。
 */
public final class ActiveBridge {
    private static Bridge current;

    private ActiveBridge() {
    }

    /**
     * 选择运行时桥接。
     *
     * @param ysm3Compatible 官方 3.0 的注解处理器 checker 结果
     */
    public static void init(boolean ysm3Compatible) {
        if (ModList.get().isLoaded("ysm") && ysm3Compatible) {
            current = YsmBridge.INSTANCE;
            YsmVrMod.LOGGER.info("[ysmvr] 选择官方 YSM 3.0 桥接");
        } else if (ModList.get().isLoaded("yes_steve_model") && isModernYsm()) {
            current = ModernYsmBridge.INSTANCE;
            YsmVrMod.LOGGER.info("[ysmvr] 选择 ModernYSM 桥接");
        } else {
            current = null;
        }
    }

    /**
     * 探测 ModernYSM 特有符号。官方混淆 2.6.5 的 modId 同样是 yes_steve_model，
     * 但类名全部混淆，不存在可读的 PlayerCapability——借此区分两者。
     */
    private static boolean isModernYsm() {
        try {
            Class.forName("com.elfmcys.yesstevemodel.capability.PlayerCapability",
                    false, ActiveBridge.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            YsmVrMod.LOGGER.warn("[ysmvr] 检测到 yes_steve_model 但缺少 ModernYSM 符号"
                    + "（可能是官方混淆 2.6.5），跳过 ModernYSM 桥接");
            return false;
        }
    }

    /** null 表示两个平台都不存在。 */
    public static Bridge get() {
        return current;
    }
}
