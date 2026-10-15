package com.elfmcys.ysmvr;

import com.elfmcys.yesstevemodel.geckolib3.core.AnimatableEntity;
import com.elfmcys.yesstevemodel.geckolib3.core.controller.BoneTransformProvider;
import com.elfmcys.yesstevemodel.geckolib3.core.controller.IAnimationController;
import com.elfmcys.yesstevemodel.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.yesstevemodel.geckolib3.core.molang.context.AnimationContext;
import com.elfmcys.yesstevemodel.geckolib3.core.molang.util.StringPool;
import com.elfmcys.yesstevemodel.geckolib3.core.processor.IBone;
import com.elfmcys.yesstevemodel.geckolib3.core.molang.value.IValue;
import com.elfmcys.yesstevemodel.geckolib3.core.snapshot.BoneTopLevelSnapshot;
import com.elfmcys.yesstevemodel.geckolib3.core.util.TransitionVector3f;
import com.elfmcys.yesstevemodel.molang.runtime.ExpressionEvaluator;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * ModernYSM（2.6.x 逆向重建版）的骨骼覆盖 controller。
 * 与 3.0 版 {@link VrPoseController} 同构：后挂载 → processor 遍历顺序靠后 →
 * 队列覆盖 coded controller 的结果。
 * <p>
 * 队列旋转语义：store 写入值 − 骨骼初始旋转（{@code BoneTopLevelSnapshot.reset()}
 * 会加回初始旋转，percentCompleted=0 时直接替换），因此提交后骨骼的【局部】
 * 旋转 = store 写入值本身——是局部而非模型空间绝对旋转：仅当父骨骼链处于
 * 绑定姿态时才等价于模型空间绝对值，父骨骼一旦被驱动就会层级叠加到子骨骼
 * 上（tracker 侧的上臂补位按 half·half=full 拆分写入）。位置/缩放为绝对值。
 * {@code TransitionVector3f.percentCompleted} 必须为 0（完全生效，立即替换）。
 */
public final class ModernVrPoseController implements IAnimationController<AnimatableEntity<?>> {
    private final PoseOverrideStore store;

    private final Int2ReferenceMap<BoneTopLevelSnapshot> snapshotByName = new Int2ReferenceOpenHashMap<>();
    private final List<BoneTransform> transforms = new ArrayList<>(4);

    /** 快照映射对应的模型；模型重载后旧快照指向废弃的骨骼数组，必须重建。 */
    private com.elfmcys.yesstevemodel.geckolib3.geo.animated.AnimatedGeoModel snapshotsModel;

    /**
     * 是否已使用 AnimationProcessor 的【共享】BoneTopLevelSnapshot。
     * <p>
     * 关键：controller 若在模型加载后才挂载，processor 不会再回调 {@link #init}，
     * 旧兜底会给每根骨骼 new 一个独立快照；该快照与动画 controller 用的快照
     * 不是同一个对象，reset 时我们最后写入，会把动画的 scale/position（例如
     * 酒狐 parallel1 的 FOX.scale=0、pre_parallel4 的 Background1.scale=0）
     * 覆盖成自己快照的默认值 1/0 —— 这正是"仅旋转冻结仍显形"的根因。
     * 反射拿到 processor 的 bones 列表后共用同一批快照，未提交的通道才会
     * 真正留给动画。
     */
    private boolean sharedSnapshots;

    /** AnimationProcessor.bones（List&lt;BoneTopLevelSnapshot&gt;），反射一次。 */
    private static final Field PROCESSOR_BONES_FIELD;

    static {
        Field f = null;
        try {
            f = com.elfmcys.yesstevemodel.geckolib3.core.processor.AnimationProcessor.class
                    .getDeclaredField("bones");
            f.setAccessible(true);
        } catch (Throwable ignored) {
        }
        PROCESSOR_BONES_FIELD = f;
    }

    private int tickCounter;
    private int diagnosticCounter;
    private static int headCompLogCounter;

    public ModernVrPoseController(PoseOverrideStore store) {
        this.store = store;
    }

    @Override
    public String getName() {
        return ModernYsmBridge.CONTROLLER_NAME;
    }

    @Override
    public String getCurrentAnimation() {
        return "pose";
    }

    @Override
    public void init(List<BoneTopLevelSnapshot> list,
                     Object2ReferenceMap<String, List<IValue>> object2ReferenceMap) {
        snapshotByName.clear();
        for (var snapshot : list) {
            snapshotByName.put(snapshot.bone.getBoneId(), snapshot);
        }
        sharedSnapshots = true;
        snapshotsModel = null;
    }

    @Override
    public void process(AnimationEvent<AnimatableEntity<?>> event,
                        ExpressionEvaluator<AnimationContext<?>> evaluator,
                        boolean z) {
        transforms.clear();

        // 清掉 YSM 内置头部追踪数据，避免模型动画继续叠加 head_pitch/head_yaw。
        // applyHeadTracking 已被 Mixin 取消，头部三轴完全由 ysmvr 的 store 驱动。
        var modelData = event.getModelData();
        if (modelData != null) {
            modelData.netHeadYaw = 0.0f;
            modelData.headPitch = 0.0f;
        }
        var overrides = store.overrides();
        if (overrides.isEmpty()) {
            return;
        }

        var model = event.getAnimatable().getCurrentModel();
        // 模型切换/重载：旧快照指向废弃的骨骼数组（写入不可见），必须重建
        if (model != snapshotsModel) {
            snapshotsModel = model;
            snapshotByName.clear();
            sharedSnapshots = false;
        }
        // 优先共用 processor 的快照；拿不到时才退回独立快照（旧行为）
        if (!sharedSnapshots) {
            var shared = processorBoneSnapshots(event);
            if (shared != null && !shared.isEmpty()) {
                snapshotByName.clear();
                for (var snapshot : shared) {
                    if (snapshot == null) {
                        continue;
                    }
                    snapshotByName.put(snapshot.bone.getBoneId(), snapshot);
                }
                sharedSnapshots = true;
            } else if (snapshotByName.isEmpty() && model != null) {
                for (var bone : model.bones().values()) {
                    snapshotByName.put(bone.getBoneId(), new BoneTopLevelSnapshot(bone));
                }
            }
        }

        if (++tickCounter % 100 == 0) {
            YsmVrMod.LOGGER.info("[ysmvr] pre: overrides={} snapshots={}",
                    overrides.size(), snapshotByName.size());
        }

        IBone ysmHeadBone = null;
        if (model != null && !model.headBones().isEmpty()) {
            ysmHeadBone = model.headBones().get(model.headBones().size() - 1);
        }

        for (var entry : overrides.entrySet()) {
            var snapshot = snapshotByName.get(entry.getKey().intValue());
            if (snapshot == null) {
                continue;
            }
            var pose = entry.getValue();
            var initial = snapshot.bone.getInitialRotation();

            // 四类覆盖：
            //   完整：旋转+位置+缩放；仅旋转冻结：只提交旋转；仅缩放（强制大小）：只提交缩放；
            //   仅位置（手持物品锚点对齐）：只提交位置，旋转/缩放留给模型动画。
            boolean rotationOnly = store.isRotationOnly(entry.getKey().intValue());
            boolean scaleOnly = store.isScaleOnly(entry.getKey().intValue());
            boolean positionOnly = store.isPositionOnly(entry.getKey().intValue());
            boolean rotationPositionOnly = store.isRotationPositionOnly(entry.getKey().intValue());
            TransitionVector3f rotation = null;
            TransitionVector3f position = null;
            TransitionVector3f scale = null;
            if ((!scaleOnly && !positionOnly) || (rotationPositionOnly && !scaleOnly)) {
                rotation = new TransitionVector3f(
                        pose.rx() - initial.x, pose.ry() - initial.y, pose.rz() - initial.z);
                rotation.percentCompleted = 0;
            }
            if (!rotationOnly && ((!positionOnly && !rotationPositionOnly) || scaleOnly)) {
                // 动画冻结（仅旋转）/仅位置（强制对齐）/仅旋转+位置时其余通道不提交，
                // 留给模型动画，否则酒狐等用 scale 隐藏的第二形态/坐骑会被强制 scale=1 显形。
                // scaleOnly 与 positionOnly 可共存（模型大小 + 模型位置偏移同一根）。
                scale = new TransitionVector3f(pose.sx(), pose.sy(), pose.sz());
                scale.percentCompleted = 0;
            }
            if (!rotationOnly && (!scaleOnly || positionOnly || rotationPositionOnly)) {
                // scaleOnly 与 positionOnly 可共存；两者都有时位置、缩放都提交。
                position = new TransitionVector3f(pose.px(), pose.py(), pose.pz());
                position.percentCompleted = 0;
            }

            boolean isYsmHead = ysmHeadBone != null && snapshot.bone == ysmHeadBone;
            transforms.add(new BoneTransform(snapshot, rotation, position, scale, isYsmHead));
        }

        if (++diagnosticCounter % 100 == 0) {
            int mismatched = 0;
            var names = new StringBuilder();
            for (var entry : overrides.entrySet()) {
                if (snapshotByName.get(entry.getKey().intValue()) == null) {
                    if (mismatched < 12) {
                        names.append(StringPool.getString(entry.getKey().intValue())).append(' ');
                    }
                    mismatched++;
                }
            }
            boolean headInTransforms = false;
            if (model != null && !model.headBones().isEmpty()) {
                var it = model.headBones().get(model.headBones().size() - 1);
                for (var t : transforms) {
                    if (t.getBoneTarget().bone == it) {
                        headInTransforms = true;
                        break;
                    }
                }
            }
            YsmVrMod.LOGGER.info("[ysmvr] pose tick: overrides={} snapshots={} transforms={} mismatched={} headInTransforms={} headNames={}",
                    overrides.size(), snapshotByName.size(), transforms.size(), mismatched, headInTransforms, names);
        }
    }

    @Override
    public void forEachTransform(Consumer<BoneTransformProvider> consumer) {
        for (var transform : transforms) {
            consumer.accept(transform);
        }
    }

    /**
     * 反射取 AnimationProcessor 内部共享的 bones 快照列表。
     * <p>
     * 该列表与所有内建动画 controller 使用的是同一批 {@link BoneTopLevelSnapshot}；
     * 只有共用它们，"仅旋转覆盖"才会让动画的 position/scale 通道真正生效。
     * 取不到时返回 null（调用方退回独立快照，功能降级但不崩）。
     */
    @SuppressWarnings("unchecked")
    @Nullable
    private static List<BoneTopLevelSnapshot> processorBoneSnapshots(
            AnimationEvent<AnimatableEntity<?>> event) {
        if (PROCESSOR_BONES_FIELD == null) {
            return null;
        }
        try {
            Object processor = event.getAnimatable().getEvaluationContext();
            if (processor == null) {
                return null;
            }
            Object value = PROCESSOR_BONES_FIELD.get(processor);
            if (value instanceof List<?> list && !list.isEmpty()) {
                return (List<BoneTopLevelSnapshot>) list;
            }
        } catch (Throwable ignored) {
            // 版本不匹配：退回独立快照
        }
        return null;
    }

    @Override
    public void reset() {
        transforms.clear();
    }

    /**
     * 对 YSM 内置头部追踪做反补偿：如果无法可靠地让 applyHeadTracking 读到
     * netHeadYaw=0，就把 Head 骨骼的提交旋转预先减去 netHeadYaw/headPitch，
     * 这样 applyHeadTracking 最后加上去后，最终骨骼旋转恰好等于 store 写入值。
     */
    private static TransitionVector3f compensateYsmHeadRotation(
            ExpressionEvaluator<AnimationContext<?>> evaluator, TransitionVector3f base, String boneName) {
        if (evaluator == null) {
            return base;
        }
        var ctx = evaluator.entity();
        if (ctx == null) {
            return base;
        }
        var event = ctx.animationEvent();
        if (event == null) {
            return base;
        }
        var modelData = event.getModelData();
        if (modelData == null) {
            return base;
        }
        float yawRad = (float) Math.toRadians(modelData.netHeadYaw);
        float pitchRad = (float) Math.toRadians(modelData.headPitch);
        var adjusted = new TransitionVector3f(base.x - pitchRad, base.y - yawRad, base.z);
        adjusted.percentCompleted = 0;
        if (VrBodyConfig.debugLog && headCompLogCounter++ % 200 == 0) {
            String entityName = ctx.entity() != null ? String.valueOf(ctx.entity()) : "?";
            String animClass = ctx.instance != null ? ctx.instance.getClass().getSimpleName() : "?";
            YsmVrMod.LOGGER.info("[ysmvr][debug] headComp: thread={} entity={} anim={} bone={} netHeadYaw={} headPitch={} adj=({},{},{})°",
                    Thread.currentThread().getName(), entityName, animClass, boneName, modelData.netHeadYaw, modelData.headPitch,
                    Math.toDegrees(adjusted.x), Math.toDegrees(adjusted.y), Math.toDegrees(adjusted.z));
        }
        return adjusted;
    }

    private record BoneTransform(BoneTopLevelSnapshot snapshot,
                                 TransitionVector3f rotation,
                                 @Nullable TransitionVector3f position,
                                 @Nullable TransitionVector3f scale,
                                 boolean ysmHead) implements BoneTransformProvider {
        @Override
        public BoneTopLevelSnapshot getBoneTarget() {
            return snapshot;
        }

        @Override
        public TransitionVector3f getRotation(ExpressionEvaluator<AnimationContext<?>> evaluator) {
            // applyHeadTracking 已被 Mixin 取消，这里不再做任何 YSM 头部反补偿，
            // 否则非 process 帧会用未清零的 modelData 再次偏移 Head，造成闪烁。
            return rotation;
        }

        @Override
        public TransitionVector3f getPosition(ExpressionEvaluator<AnimationContext<?>> evaluator) {
            // null = 不提交位置通道，模型动画的位置继续生效（冻结骨骼用）
            return position;
        }

        @Override
        public TransitionVector3f getScale(ExpressionEvaluator<AnimationContext<?>> evaluator) {
            // null = 不提交缩放通道，模型动画的缩放继续生效（隐藏的第二形态/坐骑）
            return scale;
        }
    }
}
