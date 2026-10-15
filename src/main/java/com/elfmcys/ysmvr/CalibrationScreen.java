package com.elfmcys.ysmvr;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Quaternionf;

/**
 * 姿势校准 GUI（v0.2.17 布局改版）：
 * <ul>
 * <li><b>中央叠加</b>：YSM 目标姿势模型（后层）+ 原版（Vivecraft VR）模型
 * 实时姿势（前层，同一位置、同一比例）。玩家姿势没摆对时会从目标轮廓里露出来，
 * 直接叠在身上比"上下两个纸娃娃"直观得多；</li>
 * <li><b>左上角</b>：纯 YSM 目标姿势模型；<b>右上角</b>：纯原版模型（独立对照）；</li>
 * <li><b>目标姿势</b>按钮（v0.2.18 新增）：
 * <ul>
 * <li><b>自然站立</b>（默认）：YSM 模型显示自带绑定/站立姿态（手臂自然下垂），
 * 玩家按原版模型的站立姿势站直即可——对应"2.13 前"不强制 T 字的校准方式，
 * 避免 T 字目标的小偏差在校准后被放大成大幅手臂旋转；</li>
 * <li><b>T 字</b>：YSM 模型强制上臂水平（v0.2.15–v0.2.17 行为），玩家举臂摆 T 字；</li>
 * </ul></li>
 * <li>"开始校准"点击后 3 秒倒计时，玩家保持当前目标姿势不动；倒计时结束自动
 * 进入 {@link VrCalibration} 采集流程（把当前骨骼旋转取反并入绑定偏移）。</li>
 * </ul>
 * 鼠标拖动旋转所有纸娃娃；滚轮缩放中央的原版叠加模型（用于对齐两者身高）。
 * 从绑定 GUI 的"姿势校准"按钮或 /ysmvr calibrate gui 打开。
 */
@OnlyIn(Dist.CLIENT)
public final class CalibrationScreen extends Screen {

    /** 点击开始后的摆姿势时间（秒）。 */
    private static final float COUNTDOWN_SECONDS = 3.0f;

    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_LABEL = 0xFFBBBBBB;
    private static final int COLOR_HINT = 0xFF888888;
    private static final int COLOR_GOOD = 0xFF55FF55;
    private static final int COLOR_WARN = 0xFFFFAA55;

    private static final int STATE_IDLE = 0;
    private static final int STATE_COUNTDOWN = 1;
    private static final int STATE_CAPTURING = 2;
    private static final int STATE_DONE = 3;

    /** YSM 预览默认朝向：模型本体朝向与预览镜头相反，+180° 后正面朝玩家。 */
    private static final float DEFAULT_DOLL_YAW = 205f;
    /** 默认角度下原版纸娃娃的实体 yaw（与 {@link #DEFAULT_DOLL_YAW} 对应、同向朝镜头）。 */
    private static final float VANILLA_BASE_YAW = 180f;
    /** 中央 YSM 目标模型显示比例。 */
    private static final float CENTER_YSM_SCALE = 44f;
    /** 中央原版叠加模型的基准显示比例（滚轮在其上乘 0.50–2.00）。 */
    private static final int CENTER_VANILLA_SCALE = 44;
    /** 左上/右上角纯模型显示比例。 */
    private static final int CORNER_SCALE = 23;
    /**
     * YSM 预览（{@code ModelPreviewRenderer}）在缩放后会把模型原点向下偏
     * {@code 0.8×scale}：传入 y 时，脚底（实体原点）实际画在 {@code y + 0.8×scale}。
     * 原版库存渲染的 y 就是脚底。叠加/并排时必须按此补偿
     * （YSM 传 {@code y = 脚底线 - 0.8×scale}），否则 YSM 会比原版低约 0.8×scale 像素。
     */
    private static final float YSM_PREVIEW_FEET_OFFSET = 0.8f;
    /**
     * 传给 {@link Bridge#renderModelPreview} 的 pitch。预览内部会把 pitch 再减
     * 10° 作为模型绕 X 的倾角；叠加对比要求与原版一样正视（无俯仰），故传 +10
     * 抵消（内部实际倾角 0°），否则 YSM 模型会有约 20° 的前后倾、身高被压短。
     */
    private static final float YSM_PREVIEW_PITCH = 10f;
    /**
     * 叠加层向镜头方向的深度补偿（GUI 单位）。
     * YSM 预览的模型深度约为 GUI 基准 +250，原版纸娃娃固定在 GUI 基准 +50；
     * 若开启深度测试，只靠绘制顺序不够，必须把原版再向镜头推 310（&gt;200 的差）
     * 才能稳定盖在 YSM 目标模型上面。
     */
    private static final float VANILLA_OVERLAY_Z_NUDGE = 310f;

    private int state = STATE_IDLE;
    private long countdownStartNanos;
    private Button startButton;
    /** 目标姿势切换按钮（自然站立 / T 字）。 */
    private Button poseModeButton;
    /** 当前校准目标是否为"自然站立"（绑定姿态）：默认 true，持久化在 VrBodyConfig。 */
    private boolean standingPose = true;
    /** 所有纸娃娃的水平旋转角；默认朝玩家，可拖动调整。 */
    private float dollYaw = DEFAULT_DOLL_YAW;
    private boolean draggingDoll;
    /** 中央原版叠加模型的大小倍率（滚轮调整，用于对齐 YSM 模型与原版模型的身高）。 */
    private float vanillaOverlayScale = 1.0f;

    /**
     * 原版模型纸娃娃用的假玩家：UUID 与本地玩家相同，Vivecraft 的
     * {@code ClientVRPlayers.getRotationsForPlayer(uuid)} 会让它的 VR 模型
     * 摆出玩家当前姿势；它没有 YSM 模型（capability 未初始化），YSM 的
     * 玩家渲染替换不会生效，因此走原版/Vivecraft 渲染路径。
     */
    private RemotePlayer poseDoll;
    private ClientLevel poseDollLevel;

    public CalibrationScreen() {
        super(Component.literal("姿势校准"));
    }

    @Override
    public boolean isPauseScreen() {
        return false; // 世界/追踪继续运行，玩家才能摆姿势
    }

    @Override
    protected void init() {
        standingPose = VrBodyConfig.calibrationStandingPose;
        // 第 1 行：自动校准 / 手臂微调
        addRenderableWidget(Button.builder(Component.literal("自动校准（保持当前姿势）"),
                        b -> startAutoCalibration())
                .bounds(width / 2 - 150, height - 74, 145, 20).build());
        addRenderableWidget(Button.builder(Component.literal("手臂微调"), b ->
                        Minecraft.getInstance().setScreen(new ArmTuneScreen()))
                .bounds(width / 2 + 5, height - 74, 145, 20).build());
        // 第 2 行：目标姿势
        poseModeButton = Button.builder(modeButtonLabel(), b -> togglePoseMode())
                .bounds(width / 2 - 150, height - 50, 240, 20).build();
        addRenderableWidget(poseModeButton);
        // 第 3 行：开始校准 / 关闭
        startButton = Button.builder(Component.literal(startButtonText()),
                        b -> startCountdown())
                .bounds(width / 2 - 150, height - 26, 240, 20).build();
        addRenderableWidget(startButton);
        addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose())
                .bounds(width / 2 + 96, height - 26, 56, 20).build());
        refreshButtonLabel();
    }

    /** 一键自动校准：不等待摆姿势，立即对当前姿势多帧采集并取反写入偏移。 */
    private void startAutoCalibration() {
        VrCalibration.requestAuto();
        state = STATE_CAPTURING;
        refreshButtonLabel();
    }

    /** 目标姿势在"自然站立（绑定姿态）"与"T 字"之间切换，并持久化。 */
    private void togglePoseMode() {
        standingPose = !standingPose;
        VrBodyConfig.setCalibrationStandingPose(standingPose);
        VrBodyConfig.save();
        if (poseModeButton != null) {
            poseModeButton.setMessage(modeButtonLabel());
        }
        refreshButtonLabel();
    }

    private Component modeButtonLabel() {
        return Component.literal(standingPose
                ? "目标姿势：自然站立（旧版，点击切 T 字）"
                : "目标姿势：T 字（点击切换自然站立）");
    }

    /** 当前目标姿势名，用于标题/提示文案。 */
    private String modeName() {
        return standingPose ? "自然站立" : "T 字";
    }

    private String startButtonText() {
        return standingPose
                ? "开始校准（3 秒内保持站立）"
                : "开始校准（3 秒内摆好 T 字）";
    }

    private void startCountdown() {
        countdownStartNanos = System.nanoTime();
        state = STATE_COUNTDOWN;
        refreshButtonLabel();
    }

    private float remainingSeconds() {
        return COUNTDOWN_SECONDS - (System.nanoTime() - countdownStartNanos) / 1.0e9f;
    }

    private void refreshButtonLabel() {
        if (startButton == null) {
            return;
        }
        String text = switch (state) {
            case STATE_COUNTDOWN -> standingPose
                    ? String.format("倒计时 %.1f 秒…（保持站立）", Math.max(0f, remainingSeconds()))
                    : String.format("倒计时 %.1f 秒…", Math.max(0f, remainingSeconds()));
            case STATE_CAPTURING -> VrCalibration.isAuto()
                    ? "自动校准采集中…" : "正在采集姿势…";
            case STATE_DONE -> "再次校准";
            default -> startButtonText();
        };
        startButton.setMessage(Component.literal(text));
    }

    @Override
    public void tick() {
        super.tick();
        if (state == STATE_COUNTDOWN && remainingSeconds() <= 0f) {
            VrCalibration.requestNow();
            state = STATE_CAPTURING;
        } else if (state == STATE_CAPTURING && !VrCalibration.isPending()) {
            state = STATE_DONE;
        }
        refreshButtonLabel();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);

        var mc = Minecraft.getInstance();
        var player = mc.player;
        var bridge = ActiveBridge.get();

        float centerX = width / 2f;
        // 两个模型以"脚底"对齐：原版库存渲染的 y 就是脚底；YSM 预览的脚底会落在
        // 传入 y + 0.8×scale，故 YSM 的 y = 脚底线 - 0.8×scale。
        float cornerFeetY = Math.max(78f, Math.min(98f, height * 0.40f));
        float centerFeetY = Math.min(height - 106f, Math.max(132f, height * 0.60f));
        float centerYsmY = centerFeetY - YSM_PREVIEW_FEET_OFFSET * CENTER_YSM_SCALE;
        float cornerYsmY = cornerFeetY - YSM_PREVIEW_FEET_OFFSET * CORNER_SCALE;
        float leftX = Math.max(58f, width * 0.18f);
        float rightX = Math.min(width - 58f, width * 0.82f);

        graphics.drawCenteredString(font, "姿势校准 · 目标：" + modeName(), width / 2, 6, COLOR_TITLE);
        graphics.drawCenteredString(font, "中央＝原版实时姿势叠在 YSM " + modeName() + " 上 · 两角为纯模型",
                width / 2, 18, COLOR_HINT);

        RemotePlayer doll = poseDoll(player);

        // ── 左上角：纯 YSM 目标模型（自然站立或 T 字）──
        graphics.drawCenteredString(font, "YSM " + modeName() + "（纯）", (int) leftX, 34, COLOR_LABEL);
        if (bridge != null && player != null) {
            renderYsmDoll(bridge, player, leftX, cornerYsmY, CORNER_SCALE, partialTick, !standingPose);
        } else {
            graphics.drawCenteredString(font, "YSM 模型未加载",
                    (int) leftX, (int) (cornerFeetY - 20f), COLOR_WARN);
        }
        graphics.flush();

        // ── 右上角：纯原版模型（当前姿势）──
        graphics.drawCenteredString(font, "原版模型（纯）", (int) rightX, 34, COLOR_LABEL);
        if (doll != null) {
            renderVanillaDoll(graphics, doll, rightX, cornerFeetY, CORNER_SCALE);
        } else {
            graphics.drawCenteredString(font, "无追踪数据",
                    (int) rightX, (int) (cornerFeetY - 20f), COLOR_WARN);
        }
        graphics.flush();

        // ── 中央：YSM 目标姿势（后层）＋ 原版实时姿势（前层，脚底对齐、同位置）──
        graphics.drawCenteredString(font,
                String.format(java.util.Locale.ROOT,
                        "叠加：原版（前）＋ YSM %s（后） · 滚轮 %.2fx", modeName(), vanillaOverlayScale),
                width / 2, (int) Math.max(40f, centerFeetY - 96f), COLOR_LABEL);
        if (bridge != null && player != null) {
            renderYsmDoll(bridge, player, centerX, centerYsmY, CENTER_YSM_SCALE, partialTick, !standingPose);
        }
        graphics.flush();
        if (doll != null) {
            // 向镜头推 310 GUI 单位，确保原版模型压在 YSM 目标模型上面（见常量注释）。
            var pose = graphics.pose();
            pose.pushPose();
            pose.translate(0f, 0f, VANILLA_OVERLAY_Z_NUDGE);
            try {
                renderVanillaDoll(graphics, doll, centerX, centerFeetY,
                        Math.max(1, Math.round(CENTER_VANILLA_SCALE * vanillaOverlayScale)));
            } finally {
                pose.popPose();
            }
        } else {
            graphics.drawCenteredString(font, "无追踪数据（VR 追踪未开启？）",
                    width / 2, (int) (centerFeetY - 30f), COLOR_WARN);
        }
        graphics.flush();
        // 实体预览可能残留深度测试状态，避免后面的状态文字/按钮被深度剔除
        com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();

        // ── 状态提示 ──
        String status = switch (state) {
            case STATE_COUNTDOWN -> standingPose
                    ? String.format("请在 %.1f 秒内保持自然站立（手臂自然下垂）…",
                            Math.max(0f, remainingSeconds()))
                    : String.format("请在 %.1f 秒内摆好 T 字…", Math.max(0f, remainingSeconds()));
            case STATE_CAPTURING -> VrCalibration.isAuto()
                    ? "自动校准采集中（保持当前姿势）…" : "正在采集姿势…";
            case STATE_DONE -> VrCalibration.lastSuccess()
                    ? "校准完成：已更新绑定偏移（可再次校准微调）"
                    : "校准结束：查看聊天栏提示（可能需要开启 VR 追踪）";
            default -> standingPose
                    ? "站直让原版模型与后面的 YSM 站立姿势重合；点\"自动校准\"可立即取当前姿势，或点\"开始校准\"走 3 秒流程"
                    : "中央错位处会露出 YSM 轮廓；点\"自动校准\"立即取当前 T 字，或点\"开始校准\"走 3 秒流程";
        };
        graphics.drawCenteredString(font, status, width / 2, height - 94,
                state == STATE_DONE ? (VrCalibration.lastSuccess() ? COLOR_GOOD : COLOR_WARN) : COLOR_HINT);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * YSM 目标姿势预览（失败时退回普通预览，至少能看到模型）。
     *
     * @param forceTPose 是否附加 T 字手臂（{@link Bridge#renderModelPreview} 9 参重载）
     */
    private void renderYsmDoll(Bridge bridge, LocalPlayer player, float x, float y,
                               float scale, float partialTick, boolean forceTPose) {
        boolean ok = bridge.renderModelPreview(player, x, y, scale, YSM_PREVIEW_PITCH, dollYaw,
                partialTick, true, forceTPose);
        if (!ok) {
            bridge.renderModelPreview(player, x, y, scale, YSM_PREVIEW_PITCH, dollYaw,
                    partialTick, false, false);
        }
    }

    /** 原版 VR 模型纸娃娃；渲染失败只记日志，不让 GUI 崩掉。 */
    private void renderVanillaDoll(GuiGraphics graphics, RemotePlayer doll, float x, float y, int scale) {
        try {
            InventoryScreen.renderEntityInInventory(graphics, (int) x, (int) y, scale,
                    new Quaternionf().rotateZ((float) Math.PI), new Quaternionf(), doll);
        } catch (Throwable t) {
            YsmVrMod.LOGGER.warn("[ysmvr] 原版姿势纸娃娃渲染失败", t);
        }
    }

    /** 维护原版模型纸娃娃假玩家：本地 UUID + 与 YSM 同向的朝向（面向镜头）。 */
    private RemotePlayer poseDoll(LocalPlayer player) {
        var mc = Minecraft.getInstance();
        if (player == null || mc.level == null) {
            return null;
        }
        if (poseDoll == null || poseDollLevel != mc.level) {
            poseDoll = new RemotePlayer(mc.level, player.getGameProfile());
            poseDollLevel = mc.level;
        }
        RemotePlayer doll = poseDoll;
        // YSM 预览内部用 yBodyRot = -yaw，视觉旋转 = 180 + yaw；原版实体视觉旋转
        // = 180 - entityYaw。要让两者随拖动同向转，取 entityYaw = 180 - (yaw-205)。
        float vanillaYaw = VANILLA_BASE_YAW - (dollYaw - DEFAULT_DOLL_YAW);
        doll.setPos(player.getX(), player.getY(), player.getZ());
        // 面向 GUI 镜头；四肢/头部旋转由 Vivecraft 的 rotInfo（本地 UUID）提供
        doll.yBodyRot = vanillaYaw;
        doll.yBodyRotO = vanillaYaw;
        doll.setYRot(vanillaYaw);
        doll.yRotO = vanillaYaw;
        doll.setXRot(0f);
        doll.xRotO = 0f;
        doll.yHeadRot = vanillaYaw;
        doll.yHeadRotO = vanillaYaw;
        doll.tickCount = player.tickCount;
        doll.setDeltaMovement(0, 0, 0);
        doll.setSprinting(false);
        doll.setSwimming(false);
        doll.setPose(player.getPose());
        doll.setInvisible(false);
        doll.walkAnimation.setSpeed(0f);
        return doll;
    }

    // ── 鼠标拖动旋转纸娃娃 / 滚轮缩放中央原版叠加模型 ──

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button == 0) {
            draggingDoll = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingDoll && button == 0) {
            dollYaw = (float) (dollYaw + dragX * 0.8);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingDoll = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (super.mouseScrolled(mouseX, mouseY, delta)) {
            return true;
        }
        vanillaOverlayScale = (float) Math.max(0.50,
                Math.min(2.00, vanillaOverlayScale + delta * 0.05));
        return true;
    }
}
