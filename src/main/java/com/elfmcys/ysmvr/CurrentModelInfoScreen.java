package com.elfmcys.ysmvr;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** v0.2.63：当前模型信息界面（总 GUI  界面打开）。 */
@OnlyIn(Dist.CLIENT)
public final class CurrentModelInfoScreen extends Screen {

    private static final String[] MODE_NAMES = {"简单（无全身）", "入门（腰追+脚追）", "腿部全追（无手追）", "Vivecraft全追", "全身触手（彩蛋）", "全身（T字校准实验）"};

    private final LocalPlayer player;
    private final Bridge bridge;
    private final String modelKey;
    private final List<String> lines = new ArrayList<>();

    public CurrentModelInfoScreen() {
        super(Component.literal("当前模型信息"));
        this.player = Minecraft.getInstance().player;
        this.bridge = ActiveBridge.get();
        this.modelKey = ModelProfileStore.currentKey(player, bridge);
        refresh();
    }

    private void refresh() {
        lines.clear();
        lines.add("模型 id: " + (modelKey == null ? "default" : modelKey));
        lines.add("动画 key: " + shortKey(ModelProfileStore.currentAnimationKey(player, bridge)));
        lines.add("追踪模式: " + MODE_NAMES[Math.max(0, Math.min(5, VrBodyConfig.trackingMode))]);
        lines.add("全局玩家大小: " + fmt(VrBodyConfig.playerSize)
                + "    全局模型大小: " + fmt(VrBodyConfig.modelScale));
        Float ms = ModelProfileStore.modelScaleOverride(player, bridge);
        Float ps = ModelProfileStore.playerSizeOverride(player, bridge);
        lines.add("本模型模型大小: " + (ms == null ? "无覆盖" : fmt(ms))
                + "    本模型玩家大小: " + (ps == null ? "无覆盖" : fmt(ps)));
        var profile = ModelProfileStore.getByKey(modelKey);
        lines.add("本模型总 GUI 配置项: " + profile.bodyConfig.size() + " 个");
        lines.add("骨骼绑定: " + (player == null ? "无玩家" : "已加载 " + countBones() + " 根可驱动骨骼"));
        lines.add("Vivecraft: " + (bridge == null ? "桥接不可用" : "ModernYSM 已连接"));
    }

    private int countBones() {
        if (bridge == null || player == null) {
            return 0;
        }
        var bones = bridge.listBones(player);
        return bones == null ? 0 : bones.size();
    }

    private static String fmt(float v) {
        return String.format(Locale.ROOT, "%.2fx", v);
    }

    private static String shortKey(String key) {
        if (key == null || key.isBlank()) {
            return "不可用";
        }
        return key.length() <= 80 ? key : key.substring(0, 77) + "...";
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal("刷新"), b -> refresh())
                .bounds(width / 2 - 105, height / 2 + 80, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose())
                .bounds(width / 2 + 5, height / 2 + 80, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.fill(0, 0, width, height, 0xE0101118);
        int x = width / 2 - 230;
        int y = height / 2 - 110;
        graphics.fill(x, y, x + 460, y + 210, 0xF01A1D26);
        graphics.fill(x, y, x + 460, y + 2, 0xFF3FB6FF);
        graphics.drawString(font, "当前模型信息", x + 12, y + 8, 0xFFFFFFFF, false);
        int ly = y + 28;
        for (String line : lines) {
            graphics.drawString(font, line, x + 12, ly, 0xFFDDE3EA, false);
            ly += 16;
        }
        graphics.drawString(font, "每模型单独文件: config/ysmvr-models/<模型id>.json",
                x + 12, y + 190, 0xFF9AA4B2, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}