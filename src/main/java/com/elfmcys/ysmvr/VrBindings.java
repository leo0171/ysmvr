package com.elfmcys.ysmvr;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import net.minecraftforge.fml.loading.FMLPaths;
import org.vivecraft.api.data.VRBodyPart;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * VR 追踪部位 → YSM 骨骼的绑定配置（含每根骨骼的偏移）。
 * 一个 VR 部位可绑定多根骨骼（多选）。
 * 默认绑定与旧版硬编码映射一致，运行时可通过 /ysmvr bind 的 GUI 修改，
 * 持久化到 config/ysmvr-bindings.json。
 * <p>
 * 默认绑定分两套，运行时按追踪数据动态切换（{@link #setMode}）：
 * <ul>
 * <li>{@link Mode#SIMPLE}：无肘/膝追踪器（3 点追踪）——手柄→整臂骨骼
 * （RightArm/LeftArm）、脚→大腿（RightLeg/LeftLeg），不绑肘/膝</li>
 * <li>{@link Mode#EXTENDED}：有肘+膝追踪器（6 点追踪）——手柄→前臂、
 * 脚→小腿、肘→上臂、膝→大腿</li>
 * </ul>
 * 切换只作用于"未被自定义"的部位（与任一默认集相同的部位）。
 */
public final class VrBindings {

    /** 追踪模式：无肘/膝追踪器 → SIMPLE，有肘+膝追踪器 → EXTENDED。 */
    public enum Mode { SIMPLE, EXTENDED }

    /** 单根骨骼的绑定条目（含位置/旋转偏移）。 */
    public static final class Binding {
        public String boneName = "";
        /** 位置偏移（模型单位，1 格 = 16）。 */
        public float posX;
        public float posY;
        public float posZ;
        /** 旋转偏移（度）。 */
        public float rotX;
        public float rotY;
        public float rotZ;

        public Binding() {
        }

        public Binding(String boneName) {
            this.boneName = boneName;
        }

        public Binding copy() {
            var b = new Binding(boneName);
            b.posX = posX;
            b.posY = posY;
            b.posZ = posZ;
            b.rotX = rotX;
            b.rotY = rotY;
            b.rotZ = rotZ;
            return b;
        }
    }

    private static final Map<VRBodyPart, List<Binding>> BINDINGS = new EnumMap<>(VRBodyPart.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FMLPaths.CONFIGDIR.get().resolve("ysmvr-bindings.json");

    /**
     * 绑定配置格式版本：2 = 原版姿态复制改为 D_y 共轭公式（旋转偏移的语义随映射
     * 整体变更，v1 时代校准/手调的旋转偏移一律作废，加载时清零一次）。
     */
    private static final int SCHEMA_VERSION = 2;
    private static final String SCHEMA_KEY = "schemaVersion";

    /** 当前追踪模式（启动默认 SIMPLE，首帧由追踪数据检测校正）。 */
    private static Mode mode = Mode.SIMPLE;

    /** 是否自动检测追踪模式（false = 手动锁定，按键/指令切换）。 */
    private static boolean modeAuto = true;

    /** 扩展默认绑定快照（有肘/膝追踪器；用于旧配置迁移与模式切换）。 */
    private static final Map<VRBodyPart, List<Binding>> NEW_DEFAULTS = new EnumMap<>(VRBodyPart.class);
    /** 简单默认绑定：无肘/膝追踪器——手柄→整臂、脚→大腿。 */
    private static final Map<VRBodyPart, List<Binding>> SIMPLE_DEFAULTS = new EnumMap<>(VRBodyPart.class);
    /** 旧版默认绑定：手柄→手掌、脚→脚、膝→小腿、肘→前臂。 */
    private static final Map<VRBodyPart, List<Binding>> LEGACY_DEFAULTS = new EnumMap<>(VRBodyPart.class);

    static {
        applyDefaults();
        NEW_DEFAULTS.putAll(snapshot());
        putSimple(VRBodyPart.HEAD, "Head");
        putSimple(VRBodyPart.MAIN_HAND, "RightArm");
        putSimple(VRBodyPart.OFF_HAND, "LeftArm");
        putSimple(VRBodyPart.WAIST, "UpBody");
        putSimple(VRBodyPart.RIGHT_FOOT, "RightLeg");
        putSimple(VRBodyPart.LEFT_FOOT, "LeftLeg");
        putLegacy(VRBodyPart.HEAD, "Head");
        putLegacy(VRBodyPart.MAIN_HAND, "RightHand");
        putLegacy(VRBodyPart.OFF_HAND, "LeftHand");
        putLegacy(VRBodyPart.WAIST, "UpBody");
        putLegacy(VRBodyPart.RIGHT_FOOT, "RightFoot");
        putLegacy(VRBodyPart.LEFT_FOOT, "LeftFoot");
        putLegacy(VRBodyPart.RIGHT_KNEE, "RightLowerLeg");
        putLegacy(VRBodyPart.LEFT_KNEE, "LeftLowerLeg");
        putLegacy(VRBodyPart.RIGHT_ELBOW, "RightForeArm");
        putLegacy(VRBodyPart.LEFT_ELBOW, "LeftForeArm");
    }

    private VrBindings() {
    }

    /**
     * 默认绑定（按 SlimeVR 常见追踪器布局）：
     * 手柄 → 前臂，上臂追踪器（肘部位）→ 上臂，大腿追踪器（膝部位）→ 大腿，
     * 小腿追踪器（脚部位）→ 小腿。这样前臂/上臂、大腿/小腿各自独立跟随。
     */
    private static void applyDefaults() {
        put(VRBodyPart.HEAD, "Head");
        put(VRBodyPart.MAIN_HAND, "RightForeArm");
        put(VRBodyPart.OFF_HAND, "LeftForeArm");
        put(VRBodyPart.WAIST, "UpBody");
        put(VRBodyPart.RIGHT_FOOT, "RightLowerLeg");
        put(VRBodyPart.LEFT_FOOT, "LeftLowerLeg");
        put(VRBodyPart.RIGHT_KNEE, "RightLeg");
        put(VRBodyPart.LEFT_KNEE, "LeftLeg");
    }

    private static void put(VRBodyPart part, String boneName) {
        var list = new ArrayList<Binding>(1);
        list.add(new Binding(boneName));
        BINDINGS.put(part, list);
    }

    private static void putLegacy(VRBodyPart part, String boneName) {
        var list = new ArrayList<Binding>(1);
        list.add(new Binding(boneName));
        LEGACY_DEFAULTS.put(part, list);
    }

    private static void putSimple(VRBodyPart part, String boneName) {
        var list = new ArrayList<Binding>(1);
        list.add(new Binding(boneName));
        SIMPLE_DEFAULTS.put(part, list);
    }

    private static int trackingLayout = Integer.MIN_VALUE;

    /**
     * GUI 追踪模式 0=简单（仅头/手，脚大腿）、1=入门（腰+脚小腿，无膝）、
     * 2=全身（膝大腿、脚小腿、肘上臂）。切换时直接替换绑定，保证骨骼追踪随模式变。
     */
    public static synchronized void applyTrackingLayout(int mode) {
        if (mode == trackingLayout) {
            return;
        }
        trackingLayout = mode;
        put(VRBodyPart.HEAD, "Head");
        if (mode <= 0) {
            put(VRBodyPart.MAIN_HAND, "RightArm");
            put(VRBodyPart.OFF_HAND, "LeftArm");
            put(VRBodyPart.WAIST, "UpBody");
            put(VRBodyPart.RIGHT_FOOT, "RightLeg");
            put(VRBodyPart.LEFT_FOOT, "LeftLeg");
            BINDINGS.remove(VRBodyPart.RIGHT_KNEE);
            BINDINGS.remove(VRBodyPart.LEFT_KNEE);
            BINDINGS.remove(VRBodyPart.RIGHT_ELBOW);
            BINDINGS.remove(VRBodyPart.LEFT_ELBOW);
        } else if (mode == 1) {
            put(VRBodyPart.MAIN_HAND, "RightForeArm");
            put(VRBodyPart.OFF_HAND, "LeftForeArm");
            put(VRBodyPart.WAIST, "UpBody");
            put(VRBodyPart.RIGHT_FOOT, "RightLowerLeg");
            put(VRBodyPart.LEFT_FOOT, "LeftLowerLeg");
            BINDINGS.remove(VRBodyPart.RIGHT_KNEE);
            BINDINGS.remove(VRBodyPart.LEFT_KNEE);
            BINDINGS.remove(VRBodyPart.RIGHT_ELBOW);
            BINDINGS.remove(VRBodyPart.LEFT_ELBOW);
        } else {
            put(VRBodyPart.MAIN_HAND, "RightForeArm");
            put(VRBodyPart.OFF_HAND, "LeftForeArm");
            put(VRBodyPart.WAIST, "UpBody");
            put(VRBodyPart.RIGHT_FOOT, "RightLowerLeg");
            put(VRBodyPart.LEFT_FOOT, "LeftLowerLeg");
            put(VRBodyPart.RIGHT_KNEE, "RightLeg");
            put(VRBodyPart.LEFT_KNEE, "LeftLeg");
        }
        save();
    }
    /** 当前追踪模式。 */
    public static synchronized Mode mode() {
        return mode;
    }

    /** 是否处于自动检测模式（false = 手动锁定，按键/指令切换）。 */
    public static synchronized boolean modeAuto() {
        return modeAuto;
    }

    /** 手动锁定追踪模式（关掉自动检测；再用 {@link #setAuto()} 恢复）。 */
    public static synchronized void setManualMode(Mode newMode) {
        modeAuto = false;
        setMode(newMode);
    }

    /** 恢复自动检测（下一帧由追踪数据重新判定 SIMPLE/EXTENDED）。 */
    public static synchronized void setAuto() {
        modeAuto = true;
        YsmVrMod.LOGGER.info("[ysmvr] 追踪模式切换：AUTO（自动检测）");
    }

    /**
     * 按追踪数据切换默认绑定集（运行时动态，不写入配置文件）：
     * 与任一默认集（扩展/简单/旧版）相同的部位视为"未被自定义"，
     * 切到新模式的默认（新模式没有该部位时解除绑定）；
     * 用户自定义过的部位原样保留。
     */
    public static synchronized void setMode(Mode newMode) {
        if (newMode == null || newMode == mode) {
            return;
        }
        var to = newMode == Mode.SIMPLE ? SIMPLE_DEFAULTS : NEW_DEFAULTS;
        for (VRBodyPart part : VRBodyPart.values()) {
            var current = BINDINGS.get(part);
            boolean untouched = sameBindings(current, NEW_DEFAULTS.get(part))
                    || sameBindings(current, SIMPLE_DEFAULTS.get(part))
                    || sameBindings(current, LEGACY_DEFAULTS.get(part));
            if (!untouched) {
                continue;
            }
            var fresh = to.get(part);
            if (fresh == null) {
                BINDINGS.remove(part);
                continue;
            }
            var copy = new ArrayList<Binding>(fresh.size());
            for (var b : fresh) {
                copy.add(b.copy());
            }
            BINDINGS.put(part, copy);
        }
        mode = newMode;
        YsmVrMod.LOGGER.info("[ysmvr] 追踪模式切换：{}", newMode);
    }

    /**
     * 旧默认值迁移：部位仍保持旧默认映射（未被用户改动）时升级到新默认，
     * 用户自定义过的部位原样保留。这样更新 mod 后新默认自动生效。
     */
    private static void migrateLegacyDefaults() {
        for (var entry : LEGACY_DEFAULTS.entrySet()) {
            if (!sameBindings(BINDINGS.get(entry.getKey()), entry.getValue())) {
                continue;
            }
            var fresh = new ArrayList<Binding>(1);
            for (var b : NEW_DEFAULTS.get(entry.getKey())) {
                fresh.add(b.copy());
            }
            BINDINGS.put(entry.getKey(), fresh);
        }
    }

    private static boolean sameBindings(List<Binding> a, List<Binding> b) {
        if (a == null || b == null || a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            var x = a.get(i);
            var y = b.get(i);
            if (!x.boneName.equals(y.boneName)
                    || x.posX != y.posX || x.posY != y.posY || x.posZ != y.posZ
                    || x.rotX != y.rotX || x.rotY != y.rotY || x.rotZ != y.rotZ) {
                return false;
            }
        }
        return true;
    }

    /** 恢复默认绑定（清空全部自定义绑定）。 */
    public static synchronized void resetDefaults() {
        BINDINGS.clear();
        applyDefaults();
    }

    /** 部位当前绑定的骨骼列表；未绑定返回 null。 */
    public static synchronized List<Binding> get(VRBodyPart part) {
        return BINDINGS.get(part);
    }

    /** 查找部位下某骨骼的条目；无则 null。 */
    public static synchronized Binding findBone(VRBodyPart part, String boneName) {
        var list = BINDINGS.get(part);
        if (list == null) {
            return null;
        }
        for (var b : list) {
            if (b.boneName.equals(boneName)) {
                return b;
            }
        }
        return null;
    }

    /** 给部位追加一根骨骼（已存在则忽略）。 */
    public static synchronized void addBone(VRBodyPart part, String boneName) {
        if (findBone(part, boneName) != null) {
            return;
        }
        BINDINGS.computeIfAbsent(part, k -> new ArrayList<>()).add(new Binding(boneName));
    }

    /** 从部位移除一根骨骼；列表清空时移除部位。 */
    public static synchronized boolean removeBone(VRBodyPart part, String boneName) {
        var list = BINDINGS.get(part);
        if (list == null) {
            return false;
        }
        boolean removed = list.removeIf(b -> b.boneName.equals(boneName));
        if (list.isEmpty()) {
            BINDINGS.remove(part);
        }
        return removed;
    }

    /** 骨骼被哪些 VR 部位绑定（支持多选后同一骨骼可属于多个部位）。 */
    public static synchronized List<VRBodyPart> findPartsByBone(String boneName) {
        var parts = new ArrayList<VRBodyPart>(1);
        for (var entry : BINDINGS.entrySet()) {
            for (var b : entry.getValue()) {
                if (b.boneName.equals(boneName)) {
                    parts.add(entry.getKey());
                }
            }
        }
        return parts;
    }

    /** 深拷贝快照，供追踪线程安全遍历。 */
    public static synchronized Map<VRBodyPart, List<Binding>> snapshot() {
        var copy = new EnumMap<VRBodyPart, List<Binding>>(VRBodyPart.class);
        for (var entry : BINDINGS.entrySet()) {
            var list = new ArrayList<Binding>(entry.getValue().size());
            for (var b : entry.getValue()) {
                list.add(b.copy());
            }
            copy.put(entry.getKey(), list);
        }
        return copy;
    }

    // ── JSON 持久化 ──

    public static synchronized void save() {
        // 保存前把所有浮点字段转成有限值，避免 NaN/Infinity 让 Gson 抛异常崩溃
        for (var list : BINDINGS.values()) {
            for (var b : list) {
                if (!Float.isFinite(b.posX)) b.posX = 0f;
                if (!Float.isFinite(b.posY)) b.posY = 0f;
                if (!Float.isFinite(b.posZ)) b.posZ = 0f;
                if (!Float.isFinite(b.rotX)) b.rotX = 0f;
                if (!Float.isFinite(b.rotY)) b.rotY = 0f;
                if (!Float.isFinite(b.rotZ)) b.rotZ = 0f;
            }
        }
        var map = new LinkedHashMap<String, Object>();
        map.put(SCHEMA_KEY, SCHEMA_VERSION);
        for (var entry : BINDINGS.entrySet()) {
            map.put(entry.getKey().name(), entry.getValue());
        }
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(map), StandardCharsets.UTF_8);
        } catch (IOException e) {
            YsmVrMod.LOGGER.warn("[ysmvr] 绑定配置保存失败", e);
        }
    }

    public static synchronized void load() {
        if (!Files.exists(CONFIG_PATH)) {
            applyDefaults();
            return;
        }
        try {
            var text = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            try {
                loadListFormat(text);
            } catch (JsonParseException legacy) {
                loadLegacyFormat(text); // 旧版：单个 Binding 对象 → 包装成单元素列表
            }
        } catch (Exception e) {
            YsmVrMod.LOGGER.warn("[ysmvr] 绑定配置加载失败，使用默认绑定", e);
            applyDefaults();
        }
    }

    private static void loadListFormat(String text) {
        // 用 JsonParser 先解析成树：文件里除部位映射外还有 schemaVersion 等标量键
        var root = JsonParser.parseString(text).getAsJsonObject();
        int version = root.has(SCHEMA_KEY) ? root.get(SCHEMA_KEY).getAsInt() : 1;
        var map = new LinkedHashMap<String, List<Binding>>();
        for (var entry : root.entrySet()) {
            if (SCHEMA_KEY.equals(entry.getKey())) {
                continue;
            }
            map.put(entry.getKey(), GSON.fromJson(entry.getValue(),
                    new TypeToken<List<Binding>>() { }.getType()));
        }
        if (map.isEmpty()) {
            applyDefaults();
            return;
        }
        BINDINGS.clear();
        for (var entry : map.entrySet()) {
            try {
                var part = VRBodyPart.valueOf(entry.getKey());
                var list = entry.getValue();
                if (list == null) {
                    continue;
                }
                var cleaned = new ArrayList<Binding>();
                for (var b : list) {
                    if (b != null && b.boneName != null && !b.boneName.isEmpty()) {
                        cleaned.add(b);
                    }
                }
                if (!cleaned.isEmpty()) {
                    BINDINGS.put(part, cleaned);
                }
            } catch (IllegalArgumentException ignored) {
                // 未知部位名，跳过
            }
        }
        migrateLegacyDefaults();
        if (version < SCHEMA_VERSION) {
            resetV1RotationOffsets();
        }
        normalizeRotationOffsets();
    }

    private static void loadLegacyFormat(String text) {
        Map<String, Binding> map = GSON.fromJson(text,
                new TypeToken<Map<String, Binding>>() { }.getType());
        if (map == null || map.isEmpty()) {
            applyDefaults();
            return;
        }
        BINDINGS.clear();
        for (var entry : map.entrySet()) {
            try {
                var part = VRBodyPart.valueOf(entry.getKey());
                var b = entry.getValue();
                if (b != null && b.boneName != null && !b.boneName.isEmpty()) {
                    var list = new ArrayList<Binding>(1);
                    list.add(b);
                    BINDINGS.put(part, list);
                }
            } catch (IllegalArgumentException ignored) {
                // 未知部位名，跳过
            }
        }
        migrateLegacyDefaults();
        resetV1RotationOffsets(); // 旧格式必是 v1
        normalizeRotationOffsets();
    }

    /**
     * 加载时归一化所有骨骼的旋转偏移到 [-180,180) 单圈范围。
     * 校准（VrCalibration.apply）通过累加把上一步姿态写进 rotY，若记录的是多圈值
     * （如 -710°）会随校准不断累加，形成永久污染；归一化到单圈范围可切断此累积，
     * 且不影响渲染（渲染按 sin/cos 处理，多圈值与单圈值等价）。归一化后落盘清洗配置。
     */
    private static void normalizeRotationOffsets() {
        boolean changed = false;
        for (var list : BINDINGS.values()) {
            for (var b : list) {
                float nx = normDeg(b.rotX);
                float ny = normDeg(b.rotY);
                float nz = normDeg(b.rotZ);
                if (nx != b.rotX || ny != b.rotY || nz != b.rotZ) {
                    b.rotX = nx;
                    b.rotY = ny;
                    b.rotZ = nz;
                    changed = true;
                }
            }
        }
        if (changed) {
            YsmVrMod.LOGGER.info("[ysmvr] 绑定配置旋转偏移已归一化到单圈范围（清洗校准多圈污染值）");
            save();
        }
    }

    /** 角度（度）归一化到 [-180,180)。 */
    private static float normDeg(float deg) {
        return ((deg + 180f) % 360f + 360f) % 360f - 180f;
    }

    /**
     * v1 → v2 迁移：旧公式下校准/手调的旋转偏移随映射变更作废，清零一次
     * 并立即落盘（写入 schemaVersion，后续加载不再重复清零）。
     */
    private static void resetV1RotationOffsets() {
        for (var list : BINDINGS.values()) {
            for (var b : list) {
                b.rotX = 0;
                b.rotY = 0;
                b.rotZ = 0;
            }
        }
        YsmVrMod.LOGGER.info("[ysmvr] 绑定配置 v1 → v2：旋转偏移已清零（如需微调请重新 /ysmvr calibrate）");
        save();
    }
}
