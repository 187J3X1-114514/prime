package dev.prime.mixin;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.prime.render.RayTracingRuntime;
import dev.prime.render.scene.vanilla.DynamicSceneCapture;
import dev.prime.render.scene.vanilla.PrimeEntityRenderState;
import dev.prime.render.scene.vanilla.VanillaSceneBoundary;
import net.minecraft.client.Options;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Redirect(
            method = "invalidateCompiledGeometry",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Options;getEffectiveRenderDistance()I"))
    private int prime$routeVanillaTerrainDistance(Options options) {
        return RayTracingRuntime.instance().vanillaTerrainDistance(
                options.getEffectiveRenderDistance());
    }

    @Inject(method = "compileSections", at = @At("HEAD"), cancellable = true)
    private void prime$skipVanillaTerrainCompilation(
            net.minecraft.client.renderer.state.level.CameraRenderState camera,
            CallbackInfo callback) {
        if (!RayTracingRuntime.instance().shouldMaintainVanillaTerrain()) {
            callback.cancel();
        }
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;uploadTerrainBuffersToGpu()V"))
    private void prime$routeVanillaTerrainUpload(SectionRenderDispatcher dispatcher) {
        if (RayTracingRuntime.instance().shouldMaintainVanillaTerrain()) {
            dispatcher.uploadTerrainBuffersToGpu();
        }
    }

    @Inject(method = "submitFeatures", at = @At("HEAD"))
    private void prime$beginDynamicCapture(
            LevelRenderState levelRenderState,
            SubmitNodeCollector submitNodeCollector,
            boolean renderOutline,
            CallbackInfo ci) {
        if (RayTracingRuntime.instance().shouldCaptureDynamicScene()) {
            DynamicSceneCapture.begin(levelRenderState.cameraRenderState.pos);
        }
    }

    @Inject(method = "submitFeatures", at = @At("RETURN"))
    private void prime$finishDynamicCapture(
            LevelRenderState levelRenderState,
            SubmitNodeCollector submitNodeCollector,
            boolean renderOutline,
            CallbackInfo ci) {
        if (DynamicSceneCapture.active()) {
            RayTracingRuntime.instance().captureDynamicScene(
                    DynamicSceneCapture.finish());
        }
    }

    @Inject(method = "submitEntities", at = @At("HEAD"))
    private void prime$beginEntityCapture(
            PoseStack poseStack,
            LevelRenderState levelRenderState,
            SubmitNodeCollector submitNodeCollector,
            CallbackInfo ci) {
        DynamicSceneCapture.beginElement(VanillaSceneBoundary.Element.ENTITY);
    }

    @Inject(method = "submitEntities", at = @At("RETURN"))
    private void prime$finishEntityCapture(
            PoseStack poseStack,
            LevelRenderState levelRenderState,
            SubmitNodeCollector submitNodeCollector,
            CallbackInfo ci) {
        DynamicSceneCapture.endElement(VanillaSceneBoundary.Element.ENTITY);
    }

    @Redirect(
            method = "submitEntities",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;DDDLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V"))
    private void prime$captureEntityObject(
            EntityRenderDispatcher dispatcher,
            EntityRenderState state,
            CameraRenderState camera,
            double x,
            double y,
            double z,
            PoseStack poseStack,
            SubmitNodeCollector collector) {
        long key = ((PrimeEntityRenderState) state).prime$entityId();
        DynamicSceneCapture.beginMotionObject(
                VanillaSceneBoundary.Element.ENTITY, key);
        try {
            dispatcher.submit(state, camera, x, y, z, poseStack, collector);
        } finally {
            DynamicSceneCapture.endMotionObject(
                    VanillaSceneBoundary.Element.ENTITY, key);
        }
    }

    @Inject(method = "submitBlockEntities", at = @At("HEAD"))
    private void prime$beginBlockEntityCapture(
            PoseStack poseStack,
            LevelRenderState levelRenderState,
            SubmitNodeCollector submitNodeCollector,
            CallbackInfo ci) {
        DynamicSceneCapture.beginElement(
                VanillaSceneBoundary.Element.BLOCK_ENTITY);
    }

    @Inject(method = "submitBlockEntities", at = @At("RETURN"))
    private void prime$finishBlockEntityCapture(
            PoseStack poseStack,
            LevelRenderState levelRenderState,
            SubmitNodeCollector submitNodeCollector,
            CallbackInfo ci) {
        DynamicSceneCapture.endElement(
                VanillaSceneBoundary.Element.BLOCK_ENTITY);
    }

    @Redirect(
            method = "submitBlockEntities",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;submit(Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"))
    private void prime$captureBlockEntityObject(
            BlockEntityRenderDispatcher dispatcher,
            BlockEntityRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            CameraRenderState camera) {
        long key = state.blockPos.asLong();
        DynamicSceneCapture.beginMotionObject(
                VanillaSceneBoundary.Element.BLOCK_ENTITY, key);
        try {
            dispatcher.submit(state, poseStack, collector, camera);
        } finally {
            DynamicSceneCapture.endMotionObject(
                    VanillaSceneBoundary.Element.BLOCK_ENTITY, key);
        }
    }

    @Inject(
            method = "submitFeatures",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/state/level/ParticlesRenderState;submit(Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"))
    private void prime$beginParticleCapture(
            LevelRenderState levelRenderState,
            SubmitNodeCollector submitNodeCollector,
            boolean renderOutline,
            CallbackInfo ci) {
        DynamicSceneCapture.beginElement(VanillaSceneBoundary.Element.PARTICLE);
    }

    @Inject(
            method = "submitFeatures",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/state/level/ParticlesRenderState;submit(Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
                    shift = At.Shift.AFTER))
    private void prime$finishParticleCapture(
            LevelRenderState levelRenderState,
            SubmitNodeCollector submitNodeCollector,
            boolean renderOutline,
            CallbackInfo ci) {
        DynamicSceneCapture.endElement(VanillaSceneBoundary.Element.PARTICLE);
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V"))
    private void prime$skipVanillaWorldRaster(
            FrameGraphBuilder frame,
            GraphicsResourceAllocator resourceAllocator,
            FrameGraphBuilder.Inspector inspector) {
        if (!RayTracingRuntime.instance().shouldReplaceWorld()) {
            frame.execute(resourceAllocator, inspector);
        }
    }
}
