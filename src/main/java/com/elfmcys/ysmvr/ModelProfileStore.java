package com.elfmcys.ysmvr;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.fml.loading.FMLPaths;
import org.vivecraft.api.data.VRBodyPart;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按 YSM 模型 id 保存的单模型适配配置（v0.2.37）。
 * <p>
 * 用途：
 * <ul>
 * <li>模型整体位置偏移（替代全局 {@link VrBodyConfig#modelOffsetX} 等，专治
 * 斯塔·柏持武器时向后偏）；</li>
 * <li>SIMPLE 模式手臂骨骼覆盖：主手/副手分别指定驱动的 YSM 骨骼名
 * （留空=按 VrBindings + 自动 ForeArm 升级）。</li>
 * </ul>
 * 存储路径：config/ysmvr-model-profiles.json；模型 id 不可用时使用 key
 * {@code "default"}。
 */
public final class ModelProfileStore {

    public static final String DEFAULT_KEY = "default";
    public static final String SPECIAL_ATTACK = "__attack__";
    public static final String SPECIAL_HOLD = "__hold__";

    public static final class Profile {
        public float offsetX;
        public float offsetY;
        public float offsetZ;
        /** 主手覆盖骨骼名（空=不覆盖，走 VrBindings）。 */
        public String mainArmBone = "";
        /** 副手覆盖骨骼名（空=不覆盖）。 */
        public String offArmBone = "";
        /** 按动画 key 的位置偏移（模型单位），key 见 Bridge#getCurrentAnimationKey。 */
        public Map<String, float[]> animationOffsets = new LinkedHashMap<>();
        /** 按动画 key 的旋转偏移（角度，X/Y/Z）。 */
        public Map<String, float[]> animationRotOffsets = new LinkedHashMap<>();
        /** 模型整体旋转（角度）。 */
        public float rotX;
        public float rotY;
        public float rotZ;
        public float moveRotX;
        public float moveRotY;
        public float moveRotZ;
        /** 移动时的模型位置偏移（walkAnimation.speed > 0.05 时叠加）。 */
        public float moveOffsetX;
        public float moveOffsetY;
        public float moveOffsetZ;
        /** 主/副手手持物品锚点局部位置偏移（模型单位，写 HandLocator position-only）。 */
        public float mainItemOffsetX;
        public float mainItemOffsetY;
        public float mainItemOffsetZ;
        public float offItemOffsetX;
        public float offItemOffsetY;
        public float offItemOffsetZ;
        /** 每模型模型大小覆盖（null=用全局 VrBodyConfig.modelScale）。 */
        public Float modelScaleOverride;
        /** 每模型玩家大小覆盖（null=用全局 VrBodyConfig.playerSize）。 */
        public Float playerSizeOverride;
        /** 每模型的总 GUI 开关/配置快照（key = VrBodyConfig 字段名）。 */
        public Map<String, Object> bodyConfig = new LinkedHashMap<>();

        public boolean hasOffset() {
            return Math.abs(offsetX) > 1.0e-4f || Math.abs(offsetY) > 1.0e-4f
                    || Math.abs(offsetZ) > 1.0e-4f;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_DIR =
            FMLPaths.CONFIGDIR.get().resolve("ysmvr-models");
    private static final Path LEGACY_CONFIG_PATH =
            FMLPaths.CONFIGDIR.get().resolve("ysmvr-model-profiles.json");
    private static final Map<String, Profile> PROFILES = new LinkedHashMap<>();
    private static boolean loaded;

    private ModelProfileStore() {
    }

    public static synchronized String currentKey(LocalPlayer player, Bridge bridge) {
        load();
        String id = bridge == null || player == null ? null : bridge.getModelId(player);
        if (id == null || id.isBlank()) {
            return DEFAULT_KEY;
        }
        return id;
    }

    public static synchronized Profile get(LocalPlayer player, Bridge bridge) {
        return getByKey(currentKey(player, bridge));
    }

    public static synchronized Profile getByKey(String key) {
        load();
        if (key == null || key.isBlank()) {
            key = DEFAULT_KEY;
        }
        return PROFILES.computeIfAbsent(key, k -> new Profile());
    }

    /** 有效偏移：本模型偏移 + 当前动画偏移；都没有则回退全局 modelOffset。 */
    public static synchronized float[] effectiveOffset(LocalPlayer player, Bridge bridge) {
        Profile p = get(player, bridge);
        float x;
        float y;
        float z;
        if (p.hasOffset()) {
            x = p.offsetX;
            y = p.offsetY;
            z = p.offsetZ;
        } else {
            x = VrBodyConfig.modelOffsetX;
            y = VrBodyConfig.modelOffsetY;
            z = VrBodyConfig.modelOffsetZ;
        }
        for (String key : effectiveAnimationKeys(player, bridge)) {
            float[] anim = p.animationOffsets.get(key);
            if (anim != null) {
                x += anim[0];
                y += anim[1];
                z += anim[2];
            }
        }
        if (player != null && player.walkAnimation.speed() > 0.05f) {
            x += p.moveOffsetX;
            y += p.moveOffsetY;
            z += p.moveOffsetZ;
        }
        return new float[]{x, y, z};
    }

    /** 当前动画 key + 攻击/持械特殊 key 列表。 */
    public static synchronized List<String> effectiveAnimationKeys(LocalPlayer player, Bridge bridge) {
        List<String> keys = new java.util.ArrayList<>();
        String key = currentAnimationKey(player, bridge);
        if (key != null && !key.isBlank()) {
            keys.add(key);
            String lower = key.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("swing") || lower.contains("attack")) {
                keys.add(SPECIAL_ATTACK);
            }
            if (lower.contains("hold")) {
                keys.add(SPECIAL_HOLD);
            }
        }
        return keys;
    }

    /** 有效模型旋转（角度 X/Y/Z）；全 0 返回 null。 */
    public static synchronized float[] effectiveRotation(LocalPlayer player, Bridge bridge) {
        Profile p = get(player, bridge);
        float x = p.rotX;
        float y = p.rotY;
        float z = p.rotZ;
        for (String key : effectiveAnimationKeys(player, bridge)) {
            float[] rot = p.animationRotOffsets.get(key);
            if (rot != null) {
                x += rot[0];
                y += rot[1];
                z += rot[2];
            }
        }
        if (player != null && player.walkAnimation.speed() > 0.05f) {
            x += p.moveRotX;
            y += p.moveRotY;
            z += p.moveRotZ;
        }
        if (Math.abs(x) < 1.0e-3f && Math.abs(y) < 1.0e-3f && Math.abs(z) < 1.0e-3f) {
            return null;
        }
        return new float[]{x, y, z};
    }

    public static synchronized float[] overallRotation(LocalPlayer player, Bridge bridge) {
        Profile p = get(player, bridge);
        return new float[]{p.rotX, p.rotY, p.rotZ};
    }

    public static synchronized void setOverallRotation(LocalPlayer player, Bridge bridge,
                                                       float x, float y, float z) {
        Profile p = get(player, bridge);
        p.rotX = clampDeg(x);
        p.rotY = clampDeg(y);
        p.rotZ = clampDeg(z);
        save();
    }

    public static synchronized float[] moveRotation(LocalPlayer player, Bridge bridge) {
        Profile p = get(player, bridge);
        return new float[]{p.moveRotX, p.moveRotY, p.moveRotZ};
    }

    public static synchronized void setMoveRotation(LocalPlayer player, Bridge bridge,
                                                    float x, float y, float z) {
        Profile p = get(player, bridge);
        p.moveRotX = clampDeg(x);
        p.moveRotY = clampDeg(y);
        p.moveRotZ = clampDeg(z);
        save();
    }

    public static synchronized float[] animationRotationByKey(LocalPlayer player, Bridge bridge,
                                                              String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        float[] v = get(player, bridge).animationRotOffsets.get(key);
        return v == null ? null : new float[]{v[0], v[1], v[2]};
    }

    public static synchronized void setAnimationRotationByKey(LocalPlayer player, Bridge bridge,
                                                              String key, float x, float y, float z) {
        if (key == null || key.isBlank()) {
            return;
        }
        Profile p = get(player, bridge);
        if (Math.abs(x) < 1.0e-3f && Math.abs(y) < 1.0e-3f && Math.abs(z) < 1.0e-3f) {
            p.animationRotOffsets.remove(key);
        } else {
            p.animationRotOffsets.put(key,
                    new float[]{clampDeg(x), clampDeg(y), clampDeg(z)});
        }
        save();
    }

    private static float clampDeg(float v) {
        if (!Float.isFinite(v)) {
            return 0f;
        }
        return Math.max(-180f, Math.min(180f, v));
    }
    public static synchronized void setMoveOffset(LocalPlayer player, Bridge bridge,
                                                  float x, float y, float z) {
        Profile p = get(player, bridge);
        p.moveOffsetX = clampOffset(x);
        p.moveOffsetY = clampOffset(y);
        p.moveOffsetZ = clampOffset(z);
        save();
    }

    /** 主/副手手持物品锚点偏移；无设置返回 null。 */
    public static synchronized float[] itemOffset(LocalPlayer player, Bridge bridge,
                                                  VRBodyPart part) {
        Profile p = get(player, bridge);
        float x;
        float y;
        float z;
        if (part == VRBodyPart.MAIN_HAND) {
            x = p.mainItemOffsetX;
            y = p.mainItemOffsetY;
            z = p.mainItemOffsetZ;
        } else if (part == VRBodyPart.OFF_HAND) {
            x = p.offItemOffsetX;
            y = p.offItemOffsetY;
            z = p.offItemOffsetZ;
        } else {
            return null;
        }
        if (Math.abs(x) < 1.0e-4f && Math.abs(y) < 1.0e-4f && Math.abs(z) < 1.0e-4f) {
            return null;
        }
        return new float[]{x, y, z};
    }

    public static synchronized void setItemOffset(LocalPlayer player, Bridge bridge,
                                                 VRBodyPart part, float x, float y, float z) {
        Profile p = get(player, bridge);
        x = clampOffset(x);
        y = clampOffset(y);
        z = clampOffset(z);
        if (part == VRBodyPart.MAIN_HAND) {
            p.mainItemOffsetX = x;
            p.mainItemOffsetY = y;
            p.mainItemOffsetZ = z;
        } else if (part == VRBodyPart.OFF_HAND) {
            p.offItemOffsetX = x;
            p.offItemOffsetY = y;
            p.offItemOffsetZ = z;
        }
        save();
    }

    /** 当前动画 key（Bridge 不支持时 null）。 */
    public static synchronized String currentAnimationKey(LocalPlayer player, Bridge bridge) {
        return bridge == null || player == null ? null : bridge.getCurrentAnimationKey(player);
    }

    /** 当前动画的偏移；没有返回 null。 */
    public static synchronized float[] animationOffset(LocalPlayer player, Bridge bridge) {
        String key = currentAnimationKey(player, bridge);
        if (key == null || key.isBlank()) {
            return null;
        }
        float[] v = get(player, bridge).animationOffsets.get(key);
        return v == null ? null : new float[]{v[0], v[1], v[2]};
    }

    /** 每模型模型大小覆盖；null=用全局。 */
    public static synchronized Float modelScaleOverride(LocalPlayer player, Bridge bridge) {
        return get(player, bridge).modelScaleOverride;
    }

    /** 每模型玩家大小覆盖；null=用全局。 */
    public static synchronized Float playerSizeOverride(LocalPlayer player, Bridge bridge) {
        return get(player, bridge).playerSizeOverride;
    }

    public static synchronized void setModelScaleOverride(LocalPlayer player, Bridge bridge,
                                                          Float value) {
        get(player, bridge).modelScaleOverride = value == null ? null
                : Math.max(VrBodyConfig.MODEL_SCALE_MIN,
                Math.min(VrBodyConfig.MODEL_SCALE_MAX, value));
        save();
    }

    public static synchronized void setPlayerSizeOverride(LocalPlayer player, Bridge bridge,
                                                          Float value) {
        get(player, bridge).playerSizeOverride = value == null ? null
                : Math.max(VrBodyConfig.PLAYER_SIZE_MIN,
                Math.min(VrBodyConfig.PLAYER_SIZE_MAX, value));
        save();
    }

    public static synchronized void setAnimationOffset(LocalPlayer player, Bridge bridge,
                                                       float x, float y, float z) {
        String key = currentAnimationKey(player, bridge);
        if (key == null || key.isBlank()) {
            return;
        }
        setAnimationOffsetByKey(player, bridge, key, x, y, z);
    }

    public static synchronized void setAnimationOffsetByKey(LocalPlayer player, Bridge bridge,
                                                            String key, float x, float y, float z) {
        if (key == null || key.isBlank()) {
            return;
        }
        Profile p = get(player, bridge);
        if (Math.abs(x) < 1.0e-4f && Math.abs(y) < 1.0e-4f && Math.abs(z) < 1.0e-4f) {
            p.animationOffsets.remove(key);
        } else {
            p.animationOffsets.put(key, new float[]{clampOffset(x), clampOffset(y), clampOffset(z)});
        }
        save();
    }

    public static synchronized void clearAnimationOffset(LocalPlayer player, Bridge bridge) {
        String key = currentAnimationKey(player, bridge);
        if (key == null || key.isBlank()) {
            return;
        }
        Profile p = get(player, bridge);
        p.animationOffsets.remove(key);
        save();
    }

    /** 该 VR 部位在本模型下要覆盖的骨骼名（空/无覆盖返回 null）。 */
    public static synchronized String armBoneOverride(LocalPlayer player, Bridge bridge,
                                                      VRBodyPart part) {
        Profile p = get(player, bridge);
        String name = part == VRBodyPart.MAIN_HAND ? p.mainArmBone
                : part == VRBodyPart.OFF_HAND ? p.offArmBone : null;
        if (name == null || name.isBlank()) {
            return null;
        }
        return name;
    }

    public static synchronized void setOffset(LocalPlayer player, Bridge bridge,
                                              float x, float y, float z) {
        Profile p = get(player, bridge);
        p.offsetX = clampOffset(x);
        p.offsetY = clampOffset(y);
        p.offsetZ = clampOffset(z);
        save();
    }

    public static synchronized void setArmBones(LocalPlayer player, Bridge bridge,
                                                String mainBone, String offBone) {
        Profile p = get(player, bridge);
        p.mainArmBone = mainBone == null ? "" : mainBone.trim();
        p.offArmBone = offBone == null ? "" : offBone.trim();
        save();
    }

    public static synchronized void reset(LocalPlayer player, Bridge bridge) {
        Profile p = get(player, bridge);
        p.offsetX = 0f;
        p.offsetY = 0f;
        p.offsetZ = 0f;
        p.mainArmBone = "";
        p.offArmBone = "";
        p.animationOffsets.clear();
        p.animationRotOffsets.clear();
        p.rotX = 0f;
        p.rotY = 0f;
        p.rotZ = 0f;
        p.moveRotX = 0f;
        p.moveRotY = 0f;
        p.moveRotZ = 0f;
        p.moveOffsetX = 0f;
        p.moveOffsetY = 0f;
        p.moveOffsetZ = 0f;
        p.mainItemOffsetX = 0f;
        p.mainItemOffsetY = 0f;
        p.mainItemOffsetZ = 0f;
        p.offItemOffsetX = 0f;
        p.offItemOffsetY = 0f;
        p.offItemOffsetZ = 0f;
        p.modelScaleOverride = null;
        p.playerSizeOverride = null;
        save();
    }

    private static float clampOffset(float v) {
        if (!Float.isFinite(v)) {
            return 0f;
        }
        return Math.max(VrBodyConfig.MODEL_OFFSET_MIN,
                Math.min(VrBodyConfig.MODEL_OFFSET_MAX, v));
    }

    private static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            if (Files.isDirectory(CONFIG_DIR)) {
                try (var stream = Files.list(CONFIG_DIR)) {
                    for (Path path : stream.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                        try {
                            String key = path.getFileName().toString();
                            key = key.substring(0, key.length() - 5);
                            Profile profile = GSON.fromJson(
                                    Files.readString(path, StandardCharsets.UTF_8), Profile.class);
                            if (profile != null) {
                                PROFILES.put(key, profile);
                            }
                        } catch (Exception e) {
                            YsmVrMod.LOGGER.warn("[ysmvr] 单模型文件加载失败: {}", path, e);
                        }
                    }
                }
            } else if (Files.exists(LEGACY_CONFIG_PATH)) {
                // 旧版单文件迁移到每模型单文件
                String json = Files.readString(LEGACY_CONFIG_PATH, StandardCharsets.UTF_8);
                Map<String, Profile> map = GSON.fromJson(json,
                        new com.google.gson.reflect.TypeToken<Map<String, Profile>>() {
                        }.getType());
                if (map != null) {
                    PROFILES.putAll(map);
                }
                save();
            }
        } catch (IOException | JsonParseException e) {
            YsmVrMod.LOGGER.warn("[ysmvr] 单模型适配配置加载失败，使用默认值", e);
        }
    }

    /** 模型切换：保存旧模型开关快照，应用新模型快照。 */
    public static synchronized void switchBodyConfig(LocalPlayer player, Bridge bridge,
                                                     String oldKey, String newKey) {
        if (oldKey != null && !oldKey.equals(newKey)) {
            Profile old = getByKey(oldKey);
            old.bodyConfig = VrBodyConfig.snapshot();
        }
        Profile next = getByKey(newKey);
        if (next.bodyConfig != null && !next.bodyConfig.isEmpty()) {
            VrBodyConfig.applySnapshot(next.bodyConfig);
        }
        save();
    }
    /** 每个模型单独一个 json 文件，便于分享。 */
    public static synchronized void save() {
        try {
            Files.createDirectories(CONFIG_DIR);
            for (var entry : PROFILES.entrySet()) {
                String file = entry.getKey() == null ? DEFAULT_KEY : entry.getKey();
                file = file.replaceAll("[\\\\/:*?\"<>|]", "_");
                Files.writeString(CONFIG_DIR.resolve(file + ".json"),
                        GSON.toJson(entry.getValue()), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            YsmVrMod.LOGGER.warn("[ysmvr] 单模型适配配置保存失败", e);
        }
    }

    /** 把当前总 GUI 开关快照保存到当前模型。 */
    public static synchronized void saveCurrentBodyConfig(LocalPlayer player, Bridge bridge) {
        Profile p = get(player, bridge);
        p.bodyConfig = VrBodyConfig.snapshot();
        save();
    }

    /** 切换模型时把该模型的总 GUI 快照应用回全局配置。 */
    public static synchronized void applyCurrentBodyConfig(LocalPlayer player, Bridge bridge) {
        Profile p = get(player, bridge);
        if (p.bodyConfig != null && !p.bodyConfig.isEmpty()) {
            VrBodyConfig.applySnapshot(p.bodyConfig);
        }
    }
}
