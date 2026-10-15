package com.elfmcys.ysmvr;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 视角绑定配置：把 VR 第一人称视角绑定到 YSM 模型头部骨骼的动画实时位置
 * （解决模型移动动画把头推前、第一视角看到头发的问题）。
 * <p>
 * 偏移单位为米，模型坐标系（随身体朝向旋转）。头部骨骼留空 = 自动：
 * HEAD 部位绑定的第一根骨骼，兜底 "Head"。
 * 持久化到 config/ysmvr-view.json。
 */
public final class VrViewConfig {

    /** 是否启用视角绑定。 */
    public static boolean enabled = false;

    /** 视角偏移（米，模型坐标系，随身体朝向旋转）。 */
    public static float offsetX;
    public static float offsetY;
    public static float offsetZ;

    /** 头部骨骼名；空字符串 = 自动。 */
    public static String headBone = "";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FMLPaths.CONFIGDIR.get().resolve("ysmvr-view.json");

    private VrViewConfig() {
    }

    private static final class Fields {
        boolean enabled;
        float offsetX;
        float offsetY;
        float offsetZ;
        String headBone = "";
    }

    public static synchronized void setEnabled(boolean value) {
        enabled = value;
    }

    public static synchronized void setOffset(float x, float y, float z) {
        offsetX = x;
        offsetY = y;
        offsetZ = z;
    }

    public static synchronized void setHeadBone(String name) {
        headBone = name == null ? "" : name;
    }

    /** 实际生效的头部骨骼：配置名 → HEAD 部位第一根绑定 → "Head"。 */
    public static synchronized String resolvedHeadBone() {
        if (headBone != null && !headBone.isEmpty()) {
            return headBone;
        }
        var headList = VrBindings.get(org.vivecraft.api.data.VRBodyPart.HEAD);
        if (headList != null && !headList.isEmpty()) {
            return headList.get(0).boneName;
        }
        return "Head";
    }

    public static synchronized void save() {
        var fields = new Fields();
        fields.enabled = enabled;
        fields.offsetX = offsetX;
        fields.offsetY = offsetY;
        fields.offsetZ = offsetZ;
        fields.headBone = headBone;
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(fields), StandardCharsets.UTF_8);
        } catch (IOException e) {
            YsmVrMod.LOGGER.warn("[ysmvr] 视角配置保存失败", e);
        }
    }

    public static synchronized void load() {
        if (!Files.exists(CONFIG_PATH)) {
            return;
        }
        try {
            var fields = GSON.fromJson(
                    Files.readString(CONFIG_PATH, StandardCharsets.UTF_8), Fields.class);
            if (fields == null) {
                return;
            }
            enabled = fields.enabled;
            offsetX = fields.offsetX;
            offsetY = fields.offsetY;
            offsetZ = fields.offsetZ;
            headBone = fields.headBone == null ? "" : fields.headBone;
        } catch (Exception e) {
            YsmVrMod.LOGGER.warn("[ysmvr] 视角配置加载失败，使用默认值", e);
        }
    }
}
