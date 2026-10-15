package com.elfmcys.ysmvr;

import net.minecraft.client.player.LocalPlayer;

import java.util.List;
import java.util.UUID;

/**
 * 版本无关的 YSM 桥接抽象。两个实现：
 * <ul>
 * <li>{@link YsmBridge} —— 官方 YSM 3.0（modId=ysm，包 com.elfmcys.ysm）</li>
 * <li>{@link ModernYsmBridge} —— ModernYSM/OpenYSM 2.6.x（modId=yes_steve_model，
 * 包 com.elfmcys.yesstevemodel，社区逆向重建的可读版）</li>
 * </ul>
 * 运行时由 {@link ActiveBridge} 按已加载的 mod 选择。
 */
public interface Bridge {

    String controllerName();

    void attach(LocalPlayer player);

    /**
     * 强制 YSM 的动画管线在本帧继续走完整 tickAnimation/applyHeadTracking。
     * ModernYSM 的异步工人线程以 isFirstPerson=true 处理本地模型，导致
     * wasAnimationActiveLastTick 经常为 false、z2=false：该帧 controller 不执行、
     * applyHeadTracking 又用旧 armorBoneOffset 写回头部。此方法在每帧 VR 追踪时
     * 把该标记置 true，保证我们的 Head 覆盖每帧都被提交。
     */
    default void forceAnimationActive(LocalPlayer player) {
    }

    /**
     * 在渲染帧开始前直接强制把当前模型的 Head 骨骼写成我们的 store 值。
     * 用于绕过 YSM 内部动画/applyHeadTracking 在上一阶段写入的污染值。
     */
    default void forceHeadBoneToStore(LocalPlayer player) {
    }

    /**
     * 本地玩家当前是否正在播放 YSM 的轮盘/额外动画（变身、乱舞、舞蹈等）。
     * 为 true 时 ysmvr 会撤掉全部 VR 姿态覆盖，让 YSM 动画完整播放到结束，
     * 否则 VR 姿态（store 覆盖）会把轮盘动画的骨骼动作压掉。
     * 默认 false；ModernYSM 用 PlayerCapability.isModelSwitching() 判定。
     */
    default boolean isPlayingYsmAnimation(LocalPlayer player) {
        return false;
    }

    /**
     * 当前 YSM 动画是否已经播完并处于"定格最后一帧"（loop=hold_on_last_frame）。
     * 为 true 时 ysmvr 会主动结束这次动画切换、恢复 VR 骨骼覆盖——否则这类
     * 动画（如酒狐轮盘的鼓掌 extra2）会一直卡在最后一帧，VR 姿态也被一直挂起。
     * 循环动画（loop=true）返回 false，保持播放。默认 false。
     */
    default boolean isYsmAnimationHolding(LocalPlayer player) {
        return false;
    }

    /** 主动结束当前 YSM 动画切换（清 isModelSwitching + 通知服务端回到默认动画）。 */
    default void endYsmAnimation(LocalPlayer player) {
    }

    /**
     * 动画/骨骼诊断信息（每行一条），供 /ysmvr animdiag 与调试日志使用：
     * 关键骨骼的实时 scale/rotation/position、store 覆盖与"仅旋转"标记、
     * 各动画 controller 当前播放的动画名等。默认空列表。
     */
    default List<String> animationDiagnostics(LocalPlayer player) {
        return List.of();
    }

    void detach(UUID playerId);

    void detachAll();

    PoseOverrideStore store(LocalPlayer player);

    /** 按名字解析骨骼池化 ID；未找到返回 -1。 */
    int resolveBone(LocalPlayer player, String boneName);

    /** 当前模型的骨骼名列表。 */
    List<String> listBones(LocalPlayer player);

    /** pose controller 是否已挂载到本地玩家。 */
    boolean isAttached(LocalPlayer player);

    /** 当前模型是否已加载。 */
    boolean isModelLoaded(LocalPlayer player);

    /** GUI 用的骨骼行；旋转为弧度，pivot 为模型单位（1 格 = 16），根骨骼 parent=""。 */
    record BoneRow(int id, String name, String parent,
                   float rotX, float rotY, float rotZ,
                   float posX, float posY, float posZ,
                   float scaleX, float scaleY, float scaleZ,
                   float pivotX, float pivotY, float pivotZ,
                   boolean overridden) {
    }

    /** GUI 用的模型内置肢体分组。 */
    record GroupRow(String name, List<Integer> boneIds) {
    }

    /** 当前模型全部骨骼（含层级信息与实时数值）。 */
    List<BoneRow> boneRows(LocalPlayer player);

    /** 当前模型的内置肢体分组（无分组时返回空列表）。 */
    List<GroupRow> groups(LocalPlayer player);

    /**
     * 求骨骼在模型根坐标系中的动画实时位置（模型单位，1 格 = 16）。
     * <p>
     * 坐标约定与 {@link PoseOverrideStore#setPosition} 一致（渲染器 R 系：
     * x 取反，y/z 原值，即 NativeModelRenderer stateBuffer 的存储约定），
     * 因此返回值可直接与绑定偏移做加减、再按 {@code (-x/16, y/16, z/16)}
     * 换算成模型空间的米制位置。
     * <p>
     * 实现尽力而为：ModernYSM 沿 parentName 祖先链精确求值（含实时动画值）；
     * 官方 YSM 3.0 因骨骼缺少父引用，只返回该骨骼自身 pivot+实时位移的近似值
     * （常数误差可用视角偏移配置吸收）。骨骼不存在 / 模型未加载返回 null。
     */
    float[] getBoneModelPosition(LocalPlayer player, String boneName);

    /**
     * YSM 实际渲染手持物品所用的锚点骨骼名（ModernYSM：
     * {@code AnimatedGeoModel.rightHandBones()/leftHandBones()} 路径的最后一根，
     * 即 {@code HandLocator}）；官方 YSM 3.0 无此查询返回 null，调用方回退
     * 名字启发式（见 VivecraftPoseTracker#findIkEndBone）。
     *
     * @param boneName 该侧任意手臂骨骼名（按 Left/Right 判断左右）
     */
    default String getItemAnchorBone(LocalPlayer player, String boneName) {
        return null;
    }

    /** 当前 YSM 模型 id（ModernYSM: ModelInfoCapability#getModelId）；不可用返回 null。 */
    default String getModelId(LocalPlayer player) {
        return null;
    }

    /**
     * 当前动画 key（ModernYSM：所有 controller 的
     * {@code name=currentAnimation} 拼接）；用于按动画保存模型位置偏移。
     * 不可用返回 null。
     */
    default String getCurrentAnimationKey(LocalPlayer player) {
        return null;
    }

    /**
     * 目标骨骼父链的累计旋转（模型空间，四元数 [qx,qy,qz,qw]）；用于把
     * "模型空间绝对旋转"换算成骨骼本地旋转，抵消 walk/run 等动画对父骨骼的旋转。
     * 不可用返回 null。
     */
    default float[] getBoneParentRotation(LocalPlayer player, String boneName) {
        return null;
    }

    /** 当前全部 controller 的 name=anim 拼接（用于识别 TACZ reload/aim/inspect 等动作）。 */
    default String getRawAnimationKey(LocalPlayer player) {
        return null;
    }

    /** 取消本地玩家当前正在播放的攻击/挥击动画（无攻击动画用）。 */
    default void suppressAttackAnimation(LocalPlayer player) {
    }

    /** 只把下半身骨骼的 Y 轴旋转清零（保留 X/Z 与位置动画）。 */
    default void zeroLowerBodyY(LocalPlayer player) {
    }

    /** 无攻击动画时把根/躯干的 Y 轴旋转清零，避免攻击动画控制整体朝向。 */
    default void zeroAttackBodyRotation(LocalPlayer player) {
    }

    /** 持械时取消 YSM 的手臂/手腕动画（VR store 已驱动的骨骼不覆盖）。 */
    default void zeroArmAnimation(LocalPlayer player) {
    }

    /** 无攻击动画：把非 VR 驱动骨骼全部恢复初始旋转（渲染前最终覆盖）。 */
    default void restoreAttackPose(LocalPlayer player) {
    }

    /** 按关键词取消 YSM controller 动画（潜行/游泳/爬行等）。 */
    default void suppressAnimationsMatching(LocalPlayer player, String... keywords) {
    }

    /** 把所有非 VR store 驱动骨骼恢复初始姿态（全身躺下用）。 */
    default void restoreNonDrivenPose(LocalPlayer player) {
    }

    /** 骨骼静止枢轴（模型单位，JSON 坐标系）；不可用返回 null。 */
    default float[] getBonePivot(LocalPlayer player, String boneName) {
        return null;
    }

    /** 骨骼当前实时本地旋转（弧度，[rx,ry,rz]）；不可用返回 null。 */
    default float[] getBoneLocalRotation(LocalPlayer player, String boneName) {
        return null;
    }

    /**
     * 在 GUI 中渲染玩家模型预览（模型中心位于 (x, y)，scale 为缩放倍率，
     * pitch 建议传 -10，yaw 控制水平旋转）。调用后 GUI 侧应执行
     * graphics.flush() 以确保模型像素先提交、后续覆盖层置顶。不支持返回 false。
     */
    boolean renderModelPreview(LocalPlayer player, float x, float y, float scale,
                               float pitch, float yaw, float partialTick);

    /**
     * 同 {@link #renderModelPreview}；{@code bindPose=true} 时显示模型的绑定/
     * 静止姿态（各骨骼初始旋转；具体实现可能按模型需要附加 T 字手臂，见 9 参
     * 重载），用于姿势校准 GUI 的目标纸娃娃。默认实现忽略该参数。
     */
    default boolean renderModelPreview(LocalPlayer player, float x, float y, float scale,
                                       float pitch, float yaw, float partialTick,
                                       boolean bindPose) {
        return renderModelPreview(player, x, y, scale, pitch, yaw, partialTick);
    }

    /**
     * 同 {@link #renderModelPreview}，并指定目标姿态：
     * <ul>
     * <li>{@code bindPose=true, forceTPose=true}：绑定姿态 + 上臂强制水平
     * （T 字目标，v0.2.15–v0.2.17 校准行为）；</li>
     * <li>{@code bindPose=true, forceTPose=false}：保持模型的自然绑定/站立姿态
     * （手臂自然下垂），用于 v0.2.18"自然站立"校准模式——玩家按原版模型的
     * 站立姿势站直即可对齐，避免 T 字目标的小偏差被放大；</li>
     * <li>{@code bindPose=false}：普通动画预览，forceTPose 无效。</li>
     * </ul>
     * 默认实现忽略 forceTPose。
     */
    default boolean renderModelPreview(LocalPlayer player, float x, float y, float scale,
                                       float pitch, float yaw, float partialTick,
                                       boolean bindPose, boolean forceTPose) {
        return renderModelPreview(player, x, y, scale, pitch, yaw, partialTick, bindPose);
    }

    /**
     * 当前模型的根骨骼名列表（父骨骼为空者）。缩放根骨骼即可整体缩放模型
     * （子骨骼随父骨骼链继承缩放）。无法确定层级时返回空列表
     * （官方 3.0 骨骼无父引用，不支持）。
     */
    List<String> rootBoneNames(LocalPlayer player);

    /** 骨骼的父骨骼名；未知或为根骨骼时返回 null。 */
    String getParentBoneName(LocalPlayer player, String boneName);

    /**
     * 隐藏/恢复骨骼（含子骨骼）。实现采用双保险：骨骼自身的隐藏标记 +
     * 缩放 0/1 覆盖（子骨骼随父骨骼缩放继承，头发等附属一并隐藏）。
     * 第一人称隐藏头部用——只在渲染眼 pass 时调用，第三人称/他人视角
     * 不受影响（覆盖是客户端本地的）。不支持返回 false。
     */
    boolean setBoneHidden(LocalPlayer player, String boneName, boolean hidden);

    /**
     * 把模型根坐标系中的一个点（模型单位，1 格 = 16，M 系：+X 玩家左、
     * +Y 上、+Z 后）换算成目标骨骼<b>父系</b>局部坐标（模型单位）。
     * <p>
     * 返回值即"要把骨骼枢轴放到该点"所需的动画位移的相反数：渲染器按
     * {@code translate((pivotX−tx), (pivotY+ty), (pivotZ+tz))} 合成，
     * 故 store 位置 = {@code (-x, y, z)}。
     * <p>
     * 实现沿 parentName 祖先链从根走到目标骨骼的父骨骼，对每个祖先骨骼
     * 应用实时变换的逆（平移取反、旋转求逆、非单位缩放取倒数），因此兼容
     * 任意层级与实时动画值。目标骨骼是根骨骼（无父）时输入点即父系
     * （= 模型根）局部坐标，原样返回。骨骼不存在 / 模型未加载 /
     * 层级无法确定（官方 YSM 3.0 骨骼无父引用）时返回 null。
     */
    float[] rootToParentLocal(LocalPlayer player, String boneName,
                              float x, float y, float z);

    /**
     * 骨骼初始旋转（绑定姿态，弧度，模型空间）。动画冻结用——把非驱动
     * 骨骼的旋转覆盖为初始值即可让模型动画"无动作"（覆盖队列最后写入，
     * 压过 coded controller 的动画）。骨骼不存在或模型未加载时返回 null。
     */
    float[] getBoneInitialRotation(LocalPlayer player, String boneName);

    /**
     * 模型整体缩放系数：{heightScale, widthScale}。渲染器对模型根应用
     * poseStack.scale(heightScale, widthScale, heightScale)——X/Z 按 heightScale、
     * Y 按 widthScale 缩放。把模型单位换算成世界米必须乘这个系数
     * （默认模型 0.7/0.7，玩家身高适配用）。无法获取时返回 null（调用方按 1 处理）。
     */
    default float[] getModelScale(LocalPlayer player) {
        return null;
    }

    /**
     * 用于"强制模型大小"的主根骨骼名：取头部骨骼沿父链上溯到的根祖先。
     * 给该骨骼写缩放覆盖即可整体缩放模型，且一般位于模型自带大小骨骼
     * （如 {@code MRoot.scale = v.player_size}）的祖先链上，两者层级相乘。
     * 无法确定（模型未加载 / 官方 3.0 骨骼无父引用）时返回 null。
     */
    default String modelScaleBone(LocalPlayer player) {
        return null;
    }
}
