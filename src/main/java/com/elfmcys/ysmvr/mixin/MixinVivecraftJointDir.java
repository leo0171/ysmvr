// SPDX-License-Identifier: LGPL-3.0-or-later
// Derived from / designed to modify Vivecraft (LGPL-3.0):
// https://github.com/Vivecraft/VivecraftMod
// See THIRD-PARTY.md.

package com.elfmcys.ysmvr.mixin;

import com.elfmcys.ysmvr.VrBodyConfig;
import com.elfmcys.ysmvr.YsmVrMod;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.vivecraft.client.ClientVRPlayers;
import org.vivecraft.client.utils.ModelUtils;

/**
 * Vivecraft 1.3.x estimateJointDir 热修：
 * jointPos != null 时，原版把算好的关节方向写进 tempV2 后又被 arm dir 覆盖，
 * 实际用的是"肩手中点垂线"，导致肘/膝约一半姿势翻折。
 * 这里在 HEAD 接管完整逻辑，让 tempV 最终装正确的关节方向。
 */
@Mixin(targets = "org.vivecraft.client.utils.ModelUtils", remap = false)
public final class MixinVivecraftJointDir {

    private static int ysmvr$armLog;
    private static int ysmvr$legLog;
    private static int ysmvr$armResultLog;
    private static int ysmvr$diagLog;
    private static final org.joml.Vector3f[] ysmvr$lastDir = {new org.joml.Vector3f(), new org.joml.Vector3f()};
    private static final boolean[] ysmvr$hasDir = new boolean[2];

    @Inject(method = "estimateJointDir", at = @At("HEAD"), cancellable = true, remap = false)
    private static void ysmvr$estimateJointDir(ModelPart upper, ModelPart lower, Quaternionfc lowerRot,
                                               float bodyYaw, boolean jointDown, Vector3fc jointPos,
                                               LivingEntity player, ClientVRPlayers.RotInfo rotInfo,
                                               boolean useWorldScale, Vector3f tempV, Vector3f tempV2,
                                               CallbackInfo ci) {
        if (VrBodyConfig.debugLog && jointDown && (++ysmvr$armLog <= 30 || ysmvr$armLog % 30 == 0)) {
YsmVrMod.LOGGER.info(
                    "[ysmvr][debug] estimateJointDirARM leftHanded={} lowerX={} upper=({},{},{}) lower=({},{},{}) joint={}",
                    rotInfo.leftHanded, lower.x, upper.x, upper.y, upper.z, lower.x, lower.y, lower.z,
                    jointPos == null ? "null" : String.format(java.util.Locale.ROOT, "(%.2f,%.2f,%.2f)", jointPos.x(), jointPos.y(), jointPos.z()));
        }
        if (VrBodyConfig.debugLog && jointDown && (++ysmvr$diagLog <= 60 || ysmvr$diagLog % 60 == 0)) {
            Vector3f jointM = new Vector3f();
            if (jointPos != null) {
                ModelUtils.worldToModel(player, jointPos, rotInfo, bodyYaw, useWorldScale, jointM);
            }
            Vector3f leftM = new Vector3f();
            if (rotInfo.leftElbowPos != null) {
                ModelUtils.worldToModel(player, rotInfo.leftElbowPos, rotInfo, bodyYaw, useWorldScale, leftM);
            }
            Vector3f rightM = new Vector3f();
            if (rotInfo.rightElbowPos != null) {
                ModelUtils.worldToModel(player, rotInfo.rightElbowPos, rotInfo, bodyYaw, useWorldScale, rightM);
            }
            YsmVrMod.LOGGER.info(
                    "[ysmvr][debug] estimateJointDirDIAG leftHanded={} upperX={} joint={} leftElbow={} rightElbow={} dUpper={} dMid={} dLower={} dLeft={} dRight={}",
                    rotInfo.leftHanded, upper.x,
                    jointPos == null ? "null" : String.format(java.util.Locale.ROOT, "(%.2f,%.2f,%.2f)", jointM.x, jointM.y, jointM.z),
                    rotInfo.leftElbowPos == null ? "null" : String.format(java.util.Locale.ROOT, "(%.2f,%.2f,%.2f)", leftM.x, leftM.y, leftM.z),
                    rotInfo.rightElbowPos == null ? "null" : String.format(java.util.Locale.ROOT, "(%.2f,%.2f,%.2f)", rightM.x, rightM.y, rightM.z),
                    jointM.distance(upper.x, upper.y, upper.z),
                    jointM.distance((upper.x + lower.x) * 0.5F, (upper.y + lower.y) * 0.5F, (upper.z + lower.z) * 0.5F),
                    jointM.distance(lower.x, lower.y, lower.z),
                    rotInfo.leftElbowPos == null ? Float.NaN : leftM.distance(upper.x, upper.y, upper.z),
                    rotInfo.rightElbowPos == null ? Float.NaN : rightM.distance(upper.x, upper.y, upper.z));
        }        boolean left = jointDown && upper.x > 0F;
        int leftMode = Math.max(0, Math.min(5, VrBodyConfig.leftJointMode));
Vector3fc useJoint = jointPos;
        if (rotInfo.leftHanded) {
            Vector3fc swapped = left ? rotInfo.leftElbowPos : rotInfo.rightElbowPos;
            if (swapped != null) {
                useJoint = swapped;
            }
        }
        if (left && leftMode == 5) {
            useJoint = null;
        }
        if (jointDown && useJoint != null) {
            Vector3f jointCheck = new Vector3f();
            ModelUtils.worldToModel(player, useJoint, rotInfo, bodyYaw, useWorldScale, jointCheck);
            float armLen = (float) Math.sqrt((lower.x - upper.x) * (lower.x - upper.x)
                    + (lower.y - upper.y) * (lower.y - upper.y)
                    + (lower.z - upper.z) * (lower.z - upper.z));
            float dLower = jointCheck.distance(lower.x, lower.y, lower.z);
            float dUpperChk = jointCheck.distance(upper.x, upper.y, upper.z);
            if (armLen > 0.5F && (dLower < armLen * 0.22F || dUpperChk > armLen * 0.75F || dLower > armLen * 1.25F)) {
                useJoint = null;
                if (VrBodyConfig.debugLog && ysmvr$armLog <= 30) {
                    YsmVrMod.LOGGER.info(
                            "[ysmvr][debug] estimateJointDirFALLBACK left={} armLen={} dLower={}",
                            left, armLen, dLower);
                }
            }
        }
        float ux = upper.x;
        float lx = lower.x;
        if (useJoint != null) {
            ModelUtils.worldToModel(player, useJoint, rotInfo, bodyYaw, useWorldScale, tempV);
            tempV.sub((ux + lx) * 0.5F, (upper.y + lower.y) * 0.5F, (upper.z + lower.z) * 0.5F);
            float ax = lx - ux;
            float ay = lower.y - upper.y;
            float az = lower.z - upper.z;
            float axisSq = ax * ax + ay * ay + az * az;
            if (tempV.lengthSquared() < Math.max(1.0e-8F, axisSq * 0.0025F)) {
                lowerRot.transform(0F, jointDown ? -1F : 1F, jointDown ? 1F : -1F, tempV);
                ModelUtils.worldToModelDirection(tempV, bodyYaw, tempV);
            }
        } else {
            lowerRot.transform(0F, jointDown ? -1F : 1F, jointDown ? 1F : -1F, tempV);
            ModelUtils.worldToModelDirection(tempV, bodyYaw, tempV);
        }
        tempV2.set(lx - ux, lower.y - upper.y, lower.z - upper.z);
        float dot = tempV2.dot(tempV) / tempV2.dot(tempV2);
        tempV2.mul(dot);
        tempV.sub(tempV2).normalize();
        int sideMode = left ? leftMode : Math.max(0, Math.min(5, VrBodyConfig.rightJointMode));
        if (jointDown && sideMode != 5) {
            if (sideMode == 1) {
                tempV.x = -tempV.x;
            } else if (sideMode == 2) {
                tempV.y = -tempV.y;
            } else if (sideMode == 3) {
                tempV.z = -tempV.z;
            } else if (sideMode == 4) {
                tempV.mul(-1F);
            }
        }        int sideIndex = left ? 1 : 0;
        if (ysmvr$hasDir[sideIndex]) {
            if (ysmvr$lastDir[sideIndex].dot(tempV) < 0F) {
                tempV.lerp(ysmvr$lastDir[sideIndex], 0.5F).normalize();
            } else {
                ysmvr$lastDir[sideIndex].set(tempV);
            }
        } else {
            ysmvr$lastDir[sideIndex].set(tempV);
            ysmvr$hasDir[sideIndex] = true;
        }
        if (VrBodyConfig.debugLog && jointDown
                && (++ysmvr$armResultLog <= 30 || ysmvr$armResultLog % 30 == 0)) {
            YsmVrMod.LOGGER.info(
                    "[ysmvr][debug] estimateJointDirARMresult upperX={} dir=({},{},{})",
                    upper.x, tempV.x, tempV.y, tempV.z);
        }        ci.cancel();
    }
}
