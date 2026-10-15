package com.elfmcys.ysmvr;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.joml.Matrix3f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 骨骼查看器：
 * <ul>
 * <li>左侧分组列表（全部骨骼树 / VR 绑定组 / 模型内置肢体组），滚轮翻页</li>
 * <li>中间骨骼树，悬停查看实时数值</li>
 * <li>右侧渲染玩家模型预览（拖拽旋转视角），骨骼投影点悬停显示名称</li>
 * </ul>
 * 不暂停游戏，数值与模型实时刷新。
 */
public final class BoneViewerScreen extends Screen {

    /** VR 绑定组定义，与 VivecraftPoseTracker 的映射表保持一致。 */
    private static final String[][] VR_GROUPS = {
            {"头部", "Head"},
            {"右手", "RightHand"},
            {"左手", "LeftHand"},
            {"身体", "UpBody"},
            {"右脚", "RightFoot"},
            {"左脚", "LeftFoot"},
            {"右膝", "RightLowerLeg"},
            {"左膝", "LeftLowerLeg"},
            {"右肘", "RightForeArm"},
            {"左肘", "LeftForeArm"},
    };

    private static final int LEFT_X = 8;
    private static final int LEFT_W = 148;
    private static final int TREE_X = 170;
    private static final int ROW_H = 11;
    private static final int TOP_Y = 24;
    private static final int FOOTER_H = 14;

    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_GROUP = 0xFFBBBBBB;
    private static final int COLOR_GROUP_SEL = 0xFFFFD700;
    private static final int COLOR_BONE = 0xFF8F8F8F;
    private static final int COLOR_BONE_HOVER = 0xFFFFFF55;
    private static final int COLOR_OVERRIDE = 0xFF55FF55;
    private static final int COLOR_BG = 0x88000000;

    /** -1 = 全部骨骼树；否则为 displayGroups 的下标。 */
    private int selectedGroup = -1;
    private int hoveredGroup = -1;
    private int scrollTree;
    private int scrollLeft;

    /** 模型预览旋转（拖拽调整）。 */
    private float previewYaw = 25f;

    /** 右侧悬停命中的骨骼（渲染时填充），预览悬停优先。 */
    private Bridge.BoneRow hoveredBone;

    private String footerText = "";

    public BoneViewerScreen() {
        super(Component.literal("YSM 骨骼查看器"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        var bridge = ActiveBridge.get();
        var player = Minecraft.getInstance().player;

        renderBackground(graphics);

        if (bridge == null || player == null) {
            graphics.drawCenteredString(font, "YSM 不可用", width / 2, height / 2 - 10, 0xFFFF5555);
            return;
        }

        var rows = bridge.boneRows(player);
        var modelGroups = bridge.groups(player);
        var byName = new HashMap<String, Bridge.BoneRow>();
        for (var row : rows) {
            byName.putIfAbsent(row.name(), row);
        }

        // 组合分组列表：VR 绑定组 + 模型内置组
        var displayGroups = new ArrayList<Bridge.GroupRow>();
        for (var vg : VR_GROUPS) {
            var target = byName.get(vg[1]);
            displayGroups.add(new Bridge.GroupRow(vg[0],
                    target == null ? List.of() : List.of(target.id())));
        }
        displayGroups.addAll(modelGroups);
        if (selectedGroup >= displayGroups.size()) {
            selectedGroup = -1;
        }

        graphics.drawCenteredString(font, "YSM 骨骼查看器（共 " + rows.size() + " 骨骼）",
                width / 2, 8, COLOR_TITLE);

        // ── 模型预览（右侧）──
        int previewW = 300;
        int previewH = 300;
        int previewX = width - 8 - previewW;
        int previewY = TOP_Y;
        int previewCx = previewX + previewW / 2;
        int previewCy = previewY + previewH / 2;
        graphics.fill(previewX - 2, previewY - 2, previewX + previewW + 2, previewY + previewH + 2, 0x22FFFFFF);
        float previewScale = 60f;
        boolean previewOk = bridge.renderModelPreview(player,
                previewCx, previewCy, previewScale, -10f, previewYaw, partialTick);
        // 模型像素先提交，之后的 GUI 覆盖层（白点/文字）绘制在其上
        graphics.flush();
        // 实体渲染会残留 3D 渲染状态（深度测试开启），GUI 顶点会被深度剔除，
        // 恢复 GUI 状态保证白点/文字正常显示
        com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        if (!previewOk) {
            graphics.drawString(font, "（模型预览不可用）", previewX + 8, previewY + 8, 0xFFFF5555);
        }

        // ── 左列：分组（带滚动）──
        int visibleHeight = height - TOP_Y - FOOTER_H;
        int leftRows = 1 + displayGroups.size();
        scrollLeft = Math.min(scrollLeft, Math.max(0, leftRows * ROW_H - visibleHeight));
        int groupY = TOP_Y - scrollLeft;
        drawGroupRow(graphics, mouseX, mouseY, -1, groupY, "全部骨骼树",
                selectedGroup < 0, hoveredGroup == -1);
        for (int i = 0; i < displayGroups.size(); i++) {
            groupY += ROW_H;
            if (groupY < TOP_Y - ROW_H || groupY > height - FOOTER_H) {
                continue;
            }
            drawGroupRow(graphics, mouseX, mouseY, i, groupY,
                    displayGroups.get(i).name() + " (" + displayGroups.get(i).boneIds().size() + ")",
                    selectedGroup == i, hoveredGroup == i);
        }

        // ── 中间：骨骼树/分组内容 ──
        hoveredBone = null;
        int treeW = previewX - 10 - TREE_X;
        int totalRows = selectedGroup < 0 ? rows.size() : displayGroups.get(selectedGroup).boneIds().size();
        scrollTree = Math.min(scrollTree, Math.max(0, totalRows * ROW_H - visibleHeight));

        if (selectedGroup < 0) {
            var children = new HashMap<String, List<Bridge.BoneRow>>();
            for (var row : rows) {
                children.computeIfAbsent(row.parent() == null ? "" : row.parent(),
                        k -> new ArrayList<>()).add(row);
            }
            treeCounter[0] = 0;
            drawTree(graphics, mouseX, mouseY, children, "", 0, treeW);
        } else {
            var ids = displayGroups.get(selectedGroup).boneIds();
            var byId = new HashMap<Integer, Bridge.BoneRow>();
            for (var row : rows) {
                byId.putIfAbsent(row.id(), row);
            }
            var list = new ArrayList<Bridge.BoneRow>();
            for (var id : ids) {
                var row = byId.get(id);
                if (row != null) {
                    list.add(row);
                }
            }
            if (list.isEmpty()) {
                graphics.drawString(font, "（模型中没有该组骨骼）", TREE_X, TOP_Y, 0xFFFF5555);
            } else {
                int y = TOP_Y - scrollTree;
                for (var row : list) {
                    if (y > TOP_Y - ROW_H && y < height - FOOTER_H) {
                        drawBoneRow(graphics, mouseX, mouseY, row, TREE_X, y, treeW);
                    }
                    y += ROW_H;
                }
            }
        }

        // ── 模型骨骼投影点 + 悬停拾取 ──
        drawBoneDots(graphics, mouseX, mouseY, rows, previewCx, previewCy, previewScale);

        // ── 底部信息栏 ──
        graphics.fill(0, height - FOOTER_H, width, height, COLOR_BG);
        footerText = hoveredBone != null
                ? formatBoneDetail(hoveredBone)
                : "悬停骨骼（列表或模型）查看实时数值 | 左键选分组 | 滚轮滚动 | 拖拽旋转模型 | Esc 关闭";
        graphics.drawString(font, footerText, 8, height - FOOTER_H + 3, 0xFFEEEEEE);

        if (hoveredBone != null) {
            graphics.renderTooltip(font, Component.literal(formatBoneDetail(hoveredBone)),
                    mouseX + 12, mouseY + 12);
        }
    }

    // ── 分组绘制 ──

    private void drawGroupRow(GuiGraphics graphics, int mouseX, int mouseY,
                              int index, int y, String text, boolean selected, boolean hovered) {
        if (y < TOP_Y - ROW_H || y > height - FOOTER_H) {
            return;
        }
        var over = mouseX >= LEFT_X - 2 && mouseX <= LEFT_X + LEFT_W
                && mouseY >= y && mouseY < y + ROW_H;
        if (hovered) {
            graphics.fill(LEFT_X - 2, y, LEFT_X + LEFT_W, y + ROW_H, 0x33FFFFFF);
        } else if (over) {
            graphics.fill(LEFT_X - 2, y, LEFT_X + LEFT_W, y + ROW_H, 0x22FFFFFF);
        }
        if (selected) {
            graphics.fill(LEFT_X - 2, y - 1, LEFT_X + 2, y + ROW_H, COLOR_GROUP_SEL);
        }
        graphics.drawString(font, text, LEFT_X + 6, y + 1,
                selected ? COLOR_GROUP_SEL : (hovered || over ? 0xFFFFFFFF : COLOR_GROUP));
    }

    // ── 骨骼树 ──

    private final int[] treeCounter = new int[1];

    private void drawTree(GuiGraphics graphics, int mouseX, int mouseY,
                          Map<String, List<Bridge.BoneRow>> children, String parent,
                          int depth, int treeW) {
        var list = children.get(parent);
        if (list == null) {
            return;
        }
        for (var row : list) {
            int x = TREE_X + depth * 10;
            int y = TOP_Y - scrollTree + treeCounter[0] * ROW_H;
            treeCounter[0]++;
            if (y > TOP_Y - ROW_H && y < height - FOOTER_H) {
                drawBoneRow(graphics, mouseX, mouseY, row, x, y, treeW - depth * 10);
            }
            drawTree(graphics, mouseX, mouseY, children, row.name(), depth + 1, treeW);
        }
    }

    private void drawBoneRow(GuiGraphics graphics, int mouseX, int mouseY,
                             Bridge.BoneRow row, int x, int y, int maxW) {
        var over = mouseX >= x - 2 && mouseX <= x + maxW && mouseY >= y && mouseY < y + ROW_H;
        String text = row.overridden() ? row.name() + " ●" : row.name();
        int color = row.overridden() ? COLOR_OVERRIDE
                : over ? COLOR_BONE_HOVER : COLOR_BONE;
        if (over) {
            graphics.fill(x - 2, y, x + maxW, y + ROW_H, 0x22FFFFFF);
            hoveredBone = row;
        }
        graphics.drawString(font, text, x, y + 1, color);
    }

    // ── 模型骨骼投影与拾取 ──

    /**
     * 计算每根骨骼的模型空间锚点（米制，原点在脚底）并投影到预览框屏幕坐标。
     * 预览矩阵链（renderEntityPreview，pitch 固定 -10 时 Rx 恒等）化简为
     * Rx(180)·Ry(yaw)：sx = cx − s·(x·cosY + z·sinY)，sy = cy + s·(0.8 − y)。
     */
    private void drawBoneDots(GuiGraphics graphics, int mouseX, int mouseY,
                              List<Bridge.BoneRow> rows, int cx, int cy, float s) {
        if (rows.isEmpty()) {
            return;
        }
        var children = new HashMap<String, List<Bridge.BoneRow>>();
        var rowById = new HashMap<Integer, Bridge.BoneRow>();
        for (var row : rows) {
            children.computeIfAbsent(row.parent() == null ? "" : row.parent(),
                    k -> new ArrayList<>()).add(row);
            rowById.put(row.id(), row);
        }

        float cosY = (float) Math.cos(Math.toRadians(previewYaw));
        float sinY = (float) Math.sin(Math.toRadians(previewYaw));

        var points = new HashMap<Integer, int[]>();
        for (var root : children.getOrDefault("", List.of())) {
            walkAnchors(root, new Matrix3f(), new Vector3f(), children, points, cosY, sinY, cx, cy, s);
        }
        // 父名未挂树的骨骼按根处理
        for (var row : rows) {
            if (!points.containsKey(row.id()) && rowById.get(row.id()) == row
                    && !hasParent(row.parent(), children)) {
                walkAnchors(row, new Matrix3f(), new Vector3f(), children, points, cosY, sinY, cx, cy, s);
            }
        }

        // 悬停拾取：最近的投影点
        Bridge.BoneRow nearest = null;
        double nearestDist = 8 * 8;
        for (var entry : points.entrySet()) {
            int dx = entry.getValue()[0] - mouseX;
            int dy = entry.getValue()[1] - mouseY;
            double d = dx * dx + dy * dy;
            if (d < nearestDist) {
                nearestDist = d;
                nearest = rowById.get(entry.getKey());
            }
        }
        if (nearest != null) {
            hoveredBone = nearest;
        }

        // 绘制投影点（覆盖中的绿色，悬停的加大并标注名称）
        for (var entry : points.entrySet()) {
            var row = rowById.get(entry.getKey());
            if (row == null) {
                continue;
            }
            int px = entry.getValue()[0];
            int py = entry.getValue()[1];
            boolean hovered = row == nearest;
            int dotColor = row.overridden() ? COLOR_OVERRIDE : 0xFFFFFFFF;
            int dotSize = hovered ? 4 : 2;
            graphics.fill(px - dotSize / 2, py - dotSize / 2, px + dotSize / 2, py + dotSize / 2, dotColor);
            if (hovered) {
                graphics.drawString(font, row.name(), px + 6, py - 4, 0xFFFFFF55);
            }
        }
    }

    private static boolean hasParent(String parent, Map<String, List<Bridge.BoneRow>> children) {
        return parent != null && !parent.isEmpty() && children.containsKey(parent);
    }

    private void walkAnchors(Bridge.BoneRow row, Matrix3f parentRot, Vector3f parentPos,
                             Map<String, List<Bridge.BoneRow>> children,
                             Map<Integer, int[]> points,
                             float cosY, float sinY, int cx, int cy, float s) {
        var rot = new Matrix3f(parentRot).rotateZYX(row.rotZ(), row.rotY(), row.rotX());
        var offset = rot.transform(new Vector3f(
                (row.pivotX() + row.posX()) / 16f,
                (row.pivotY() + row.posY()) / 16f,
                (row.pivotZ() + row.posZ()) / 16f));
        var pos = new Vector3f(parentPos).add(offset);

        // 预览矩阵链化简：sx = cx − s·(x·cosY + z·sinY)，sy = cy + s·(0.8 − y)
        int sx = Math.round(cx - s * (pos.x * cosY + pos.z * sinY));
        int sy = Math.round(cy + s * (0.8f - pos.y));
        points.put(row.id(), new int[]{sx, sy});

        for (var child : children.getOrDefault(row.name(), List.of())) {
            walkAnchors(child, rot, pos, children, points, cosY, sinY, cx, cy, s);
        }
    }

    // ── 格式化 ──

    private static String formatBoneDetail(Bridge.BoneRow row) {
        return row.name()
                + "\nID: " + row.id()
                + "\n旋转: " + deg(row.rotX()) + "°, " + deg(row.rotY()) + "°, " + deg(row.rotZ()) + "°"
                + "\n位置: " + f1(row.posX()) + ", " + f1(row.posY()) + ", " + f1(row.posZ())
                + "\n缩放: " + f2(row.scaleX()) + ", " + f2(row.scaleY()) + ", " + f2(row.scaleZ())
                + (row.parent().isEmpty() ? "" : "\n父级: " + row.parent())
                + (row.overridden() ? "\n[覆盖中]" : "");
    }

    private static String deg(float rad) {
        return f1((float) Math.toDegrees(rad));
    }

    private static String f1(float v) {
        return String.format("%.1f", v);
    }

    private static String f2(float v) {
        return String.format("%.2f", v);
    }

    // ── 鼠标交互 ──

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        int previewX = width - 8 - 300;
        int previewY = TOP_Y;
        if (button == 0 && mouseX >= previewX && mouseX <= previewX + 300
                && mouseY >= previewY && mouseY <= previewY + 300) {
            previewYaw = clampYaw(previewYaw + (float) dragX * 0.8f);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    private static float clampYaw(float yaw) {
        return Math.max(-180f, Math.min(180f, yaw));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseX >= LEFT_X - 2 && mouseX <= LEFT_X + LEFT_W) {
            int index = (int) ((mouseY - TOP_Y + scrollLeft) / ROW_H); // 0 = 全部骨骼树，1..n = 分组
            selectedGroup = index - 1;
            if (selectedGroup < -1) {
                selectedGroup = -1;
            }
            scrollTree = 0;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX < LEFT_X + LEFT_W) {
            scrollLeft = Math.max(0, scrollLeft - (int) (delta * 2 * ROW_H));
        } else {
            scrollTree = Math.max(0, scrollTree - (int) (delta * 2 * ROW_H));
        }
        return true;
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        // 左列悬停高亮（右侧悬停在 render 中逐行判定）
        hoveredGroup = -1;
        if (mouseX >= LEFT_X - 2 && mouseX <= LEFT_X + LEFT_W) {
            int index = (int) ((mouseY - TOP_Y + scrollLeft) / ROW_H);
            if (index >= 0) {
                hoveredGroup = index - 1;
            }
        }
    }
}
