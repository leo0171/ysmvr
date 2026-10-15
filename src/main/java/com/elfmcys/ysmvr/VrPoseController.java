package com.elfmcys.ysmvr;

import com.elfmcys.ysm.geckolib3.core.controller.IBoneAnimationQueue;
import com.elfmcys.ysm.geckolib3.core.controller.IAnimationController;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.geckolib3.core.keyframe.AnimationVec3;
import com.elfmcys.ysm.geckolib3.core.molang.context.MolangContext;
import com.elfmcys.ysm.geckolib3.core.molang.value.IValue;
import com.elfmcys.ysm.geckolib3.core.snapshot.BoneTopLevelSnapshot;
import com.elfmcys.ysm.geckolib3.model.AnimatableEntity;
import com.elfmcys.ysm.molang.runtime.ExpressionEvaluator;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 把 {@link PoseOverrideStore} 中的覆盖值作为骨骼动画队列提交给 YSM 的
 * {@code AnimationProcessor}。该 controller 必须最后加入（挂载时机晚于 YSM 内建
 * controller），这样队列在 processor 中的提交顺序靠后，覆盖 coded controller 的结果。
 * <p>
 * 队列旋转语义与 YSM 动画一致：模型空间旋转减去骨骼初始旋转（{@code BoneTopLevelSnapshot.commit()}
 * 会加回初始旋转），位置/缩放为绝对值。
 */
public final class VrPoseController implements IAnimationController<AnimatableEntity<?>> {
    private final PoseOverrideStore store;

    /** processor 在模型加载时通过 {@link #updateModel} 注入的快照；为空时按需自建。 */
    private final Int2ReferenceMap<BoneTopLevelSnapshot> snapshotByName = new Int2ReferenceOpenHashMap<>();
    private final List<BoneQueue> queues = new ArrayList<>(4);

    /** 快照映射对应的模型；模型重载后旧快照指向废弃的骨骼数组，必须重建。 */
    private com.elfmcys.ysm.geckolib3.model.AnimatedGeoModel snapshotsModel;

    /** 是否已使用 processor 的共享快照（否则"仅旋转覆盖"会用自己的默认 scale/pos 覆盖动画通道）。 */
    private boolean sharedSnapshots;

    /** 官方 YSM 3.0 AnimationProcessor.bones（List&lt;BoneTopLevelSnapshot&gt;），反射一次。 */
    private static final java.lang.reflect.Field PROCESSOR_BONES_FIELD;

    static {
        java.lang.reflect.Field f = null;
        try {
            Class<?> processor = Class.forName("com.elfmcys.ysm.geckolib3.core.processor.AnimationProcessor");
            f = processor.getDeclaredField("modelBones");
            f.setAccessible(true);
        } catch (Throwable ignored) {
        }
        PROCESSOR_BONES_FIELD = f;
    }

    private int tickCounter;

    public VrPoseController(PoseOverrideStore store) {
        this.store = store;
    }

    @Override
    public String getName() {
        return YsmBridge.CONTROLLER_NAME;
    }

    @Override
    public String getStateName() {
        return "pose";
    }

    @Override
    public void updateModel(List<BoneTopLevelSnapshot> modelRendererList,
                            Object2ReferenceMap<String, List<IValue>> eventHandlers) {
        snapshotByName.clear();
        for (var snapshot : modelRendererList) {
            snapshotByName.put(snapshot.bone.getPooledName(), snapshot);
        }
        sharedSnapshots = true;
        snapshotsModel = null;
    }

    @Override
    public void process(AnimationEvent<AnimatableEntity<?>> event,
                        ExpressionEvaluator<MolangContext<?>> evaluator,
                        boolean allowEmitting) {
        queues.clear();
        var overrides = store.overrides();
        if (overrides.isEmpty()) {
            return;
        }

        // 中和 YSM 3.0 内置头部追踪：codeAnimation 在动画末尾把 netHeadYaw/headPitch 叠加到
        // Head 骨骼上，会与 headDirect 的 HMD 直驱双重旋转（头部跟随右手/第三人称相机、恒定偏
        // 90° 的根因）。清零后 YSM 不再叠加，tracker 成为唯一头部驱动源。3.0 用 getExtraData()
        //（ModernYSM 用 getModelData()，见 ModernVrPoseController）。
        var modelData = event.getExtraData();
        if (modelData != null) {
            modelData.netHeadYaw = 0.0f;
            modelData.headPitch = 0.0f;
        }

        var model = event.getAnimatableEntity().getLoadedGeoModel();
        // 模型切换/重载：旧快照指向废弃的骨骼数组（写入不可见），必须重建
        if (model != snapshotsModel) {
            snapshotsModel = model;
            snapshotByName.clear();
            sharedSnapshots = false;
        }
        // 优先共用 processor 的快照；拿不到时才退回独立快照
        if (!sharedSnapshots) {
            var shared = processorBoneSnapshots(event);
            if (shared != null && !shared.isEmpty()) {
                snapshotByName.clear();
                for (var snapshot : shared) {
                    if (snapshot == null) {
                        continue;
                    }
                    snapshotByName.put(snapshot.bone.getPooledName(), snapshot);
                }
                sharedSnapshots = true;
            } else if (snapshotByName.isEmpty() && model != null) {
                for (var bone : model.getSortedBones()) {
                    snapshotByName.put(bone.getPooledName(), new BoneTopLevelSnapshot(bone));
                }
            }
        }

        if (++tickCounter % 100 == 0) {
            YsmVrMod.LOGGER.info("[ysmvr] pose tick: overrides={} snapshots={} queues={}",
                    overrides.size(), snapshotByName.size(), queues.size());
        }

        for (var entry : overrides.entrySet()) {
            var snapshot = snapshotByName.get(entry.getKey().intValue());
            if (snapshot == null) {
                continue;
            }
            var pose = entry.getValue();
            var initial = snapshot.bone.getInitialRotation();

            // endingTransitionPercentProgress = 0：始终处于"完全生效"状态，立即替换
            var rotation = new AnimationVec3(
                    pose.rx() - initial.x, pose.ry() - initial.y, pose.rz() - initial.z);
            rotation.endingTransitionPercentProgress = 0;
            // 动画冻结（仅旋转）：位置/缩放留空，继续由模型动画提供
            boolean rotationOnly = store.isRotationOnly(entry.getKey().intValue());
            AnimationVec3 position = null;
            AnimationVec3 scale = null;
            if (!rotationOnly) {
                position = new AnimationVec3(pose.px(), pose.py(), pose.pz());
                position.endingTransitionPercentProgress = 0;
                scale = new AnimationVec3(pose.sx(), pose.sy(), pose.sz());
                scale.endingTransitionPercentProgress = 0;
            }

            queues.add(new BoneQueue(snapshot, rotation, position, scale));
        }
    }

    @Override
    public void visitBoneAnimationQueues(Consumer<IBoneAnimationQueue> visitor) {
        for (var queue : queues) {
            visitor.accept(queue);
        }
    }

    @Override
    public void clear() {
        queues.clear();
    }

    /** 反射取官方 YSM AnimationProcessor 的共享 bones 快照；失败返回 null。 */
    @SuppressWarnings("unchecked")
    private static List<BoneTopLevelSnapshot> processorBoneSnapshots(
            AnimationEvent<AnimatableEntity<?>> event) {
        if (PROCESSOR_BONES_FIELD == null) {
            return null;
        }
        try {
            Object processor = event.getAnimatableEntity().getAnimationProcessor();
            if (processor == null) {
                return null;
            }
            Object value = PROCESSOR_BONES_FIELD.get(processor);
            if (value instanceof List<?> list && !list.isEmpty()) {
                return (List<BoneTopLevelSnapshot>) list;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private record BoneQueue(BoneTopLevelSnapshot snapshot,
                             AnimationVec3 rotation,
                             AnimationVec3 position,
                             AnimationVec3 scale) implements IBoneAnimationQueue {
        @Override
        public BoneTopLevelSnapshot getSnapshot() {
            return snapshot;
        }

        @Override
        public Optional<AnimationVec3> pollRotationPoint(ExpressionEvaluator<MolangContext<?>> evaluator) {
            return Optional.ofNullable(rotation);
        }

        @Override
        public Optional<AnimationVec3> pollPositionPoint(ExpressionEvaluator<MolangContext<?>> evaluator) {
            return Optional.ofNullable(position);
        }

        @Override
        public Optional<AnimationVec3> pollScalePoint(ExpressionEvaluator<MolangContext<?>> evaluator) {
            return Optional.ofNullable(scale);
        }
    }
}
