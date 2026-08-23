package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.render.RealtimeFramePlan;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.diagnostic.ImageDiagnosticSelection;
import dev.prime.render.vulkan.reconstruction.VulkanReconstructionProcessor;
import dev.prime.render.vulkan.terrain.TerrainScene;
import java.util.List;
import java.util.Objects;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Sole device executor for one planned interactive frame.
 *
 * <p>All frame-scalar semantics are already fixed by {@link RealtimeFramePlan}. This class only
 * binds captured asset/scene residency, records Vulkan work, submits it and commits backend-owned
 * GPU histories.
 */
public final class RealtimeFrameExecutor {
    private final VulkanContext context;
    private final VulkanImageInitializationBatch imageInitialization =
            new VulkanImageInitializationBatch();

    public RealtimeFrameExecutor(VulkanContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    public void execute(
            String debugLabel,
            RealtimeIntegratorPipeline pipeline,
            SunShadowPipeline sunShadow,
            AtmospherePipeline atmosphere,
            MaterialTexturePages materialTextures,
            TerrainScene.ResidentSceneView scene,
            RealtimeFramePlan plan,
            VulkanReconstructionProcessor processor,
            VulkanReconstructionProcessor.Frame processorFrame,
            VulkanImage output,
            VulkanImage stableRadiance,
            ImageDiagnosticSelection diagnostics,
            DisplayExposureDiagnostics exposureDiagnostics,
            VulkanGpuTextureView atlasView,
            List<TraceBackend.SceneTexture> sceneTextures,
            long textureRevision,
            VulkanGpuTexture mainColor) {
        Objects.requireNonNull(processor, "processor");
        Objects.requireNonNull(processorFrame, "processorFrame");
        long atmosphereFrame = 0L;
        long pipelineFrame = 0L;
        MaterialTexturePages.FrameToken materialFrame = null;
        DisplayExposureDiagnostics.Capture exposureCapture = null;
        VulkanFrameSubmission submission =
                new VulkanFrameSubmission(this.imageInitialization);
        try {
            Objects.requireNonNull(debugLabel, "debugLabel");
            Objects.requireNonNull(pipeline, "pipeline");
            Objects.requireNonNull(sunShadow, "sunShadow");
            Objects.requireNonNull(atmosphere, "atmosphere");
            Objects.requireNonNull(materialTextures, "materialTextures");
            Objects.requireNonNull(scene, "scene");
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(output, "output");
            Objects.requireNonNull(stableRadiance, "stableRadiance");
            Objects.requireNonNull(diagnostics, "diagnostics");
            Objects.requireNonNull(exposureDiagnostics, "exposureDiagnostics");
            Objects.requireNonNull(atlasView, "atlasView");
            Objects.requireNonNull(sceneTextures, "sceneTextures");
            Objects.requireNonNull(mainColor, "mainColor");
            plan.requireSceneRevision(scene.revision());
            plan.requireTextureRevision(textureRevision);
            validateExtents(plan, processor, output, stableRadiance, mainColor);
            submission.begin();

            var encoder = this.context.commandEncoder();
            VkCommandBuffer commandBuffer =
                    encoder.allocateAndBeginTransientCommandBuffer();
            pipelineFrame = pipeline.prepareFrame(
                    commandBuffer,
                    this.imageInitialization,
                    plan,
                    scene,
                    textureRevision);
            this.context.device().instance().debug().beginDebugGroup(
                    commandBuffer, () -> debugLabel);
            VulkanImageTransitions.prepareOutputForComposite(
                    commandBuffer, this.imageInitialization, output);
            VulkanImageTransitions.prepareAccumulationForTrace(
                    commandBuffer, this.imageInitialization, stableRadiance);
            processor.prepareForRayTrace(
                    commandBuffer, this.imageInitialization);
            VulkanImageTransitions.prepareAtlasForTrace(
                    commandBuffer, atlasView.texture());
            VulkanImageTransitions.prepareSceneTexturesForTrace(
                    commandBuffer, sceneTextures);
            materialFrame = materialTextures.prepare(commandBuffer);
            // Atmosphere preparation traces the sun cache through the shared RT descriptor set.
            // Every image named by that set must have its declared layout before this call.
            atmosphereFrame = atmosphere.prepare(
                    commandBuffer,
                    sunShadow,
                    plan.integrator(),
                    scene,
                    false);
            pipeline.trace(commandBuffer, plan.integrator(), scene);
            processor.captureRendererDiagnostic(
                    commandBuffer, this.imageInitialization, diagnostics.renderer());
            processor.record(
                    commandBuffer,
                    processorFrame,
                    plan.reconstruction(),
                    this.imageInitialization);
            processor.presentRendererDiagnostic(commandBuffer, diagnostics.renderer());
            exposureCapture = exposureDiagnostics.record(
                    commandBuffer, processor.displayExposureStateBuffer());
            VulkanImageTransitions.finishAtlasRead(
                    commandBuffer, atlasView.texture());
            VulkanImageTransitions.finishSceneTextureReads(
                    commandBuffer, sceneTextures);
            submission.copyToMinecraft(
                    commandBuffer,
                    output,
                    mainColor,
                    processor.displayWidth(),
                    processor.displayHeight());
            this.context.device().instance().debug().endDebugGroup(
                    commandBuffer);
            submission.submit(
                    encoder,
                    commandBuffer,
                    "end Prime realtime command buffer");
            HdrPresentation.publish(this.context, processor.hdrDisplayOutput(), output);
            // A normal return transfers command/resource ownership and advances Prime histories.
            submission.submitted();
            exposureDiagnostics.submitted(exposureCapture);
            long submittedPipelineFrame = pipelineFrame;
            RuntimeException commitFailure = ResourceCleanup.run(
                    () -> pipeline.submitted(submittedPipelineFrame), null);
            long submittedAtmosphereFrame = atmosphereFrame;
            commitFailure = ResourceCleanup.run(
                    () -> atmosphere.submitted(submittedAtmosphereFrame),
                    commitFailure);
            commitFailure = ResourceCleanup.run(
                    () -> processor.submitted(processorFrame), commitFailure);
            MaterialTexturePages.FrameToken submittedMaterialFrame =
                    materialFrame;
            commitFailure = ResourceCleanup.run(
                    () -> materialTextures.submitted(submittedMaterialFrame),
                    commitFailure);
            ResourceCleanup.throwIfFailed(commitFailure);
        } catch (RuntimeException exception) {
            if (!submission.wasAcceptedByMinecraftHostSubmission()) {
                RuntimeException failure = submission.abandon(exception);
                if (pipelineFrame != 0L) {
                    long abandonedPipelineFrame = pipelineFrame;
                    failure = ResourceCleanup.run(
                            () -> pipeline.abandon(abandonedPipelineFrame),
                            failure);
                }
                if (atmosphereFrame != 0L) {
                    long abandonedAtmosphereFrame = atmosphereFrame;
                    failure = ResourceCleanup.run(
                            () -> atmosphere.abandon(abandonedAtmosphereFrame),
                            failure);
                }
                if (materialFrame != null) {
                    MaterialTexturePages.FrameToken abandonedMaterialFrame =
                            materialFrame;
                    failure = ResourceCleanup.run(
                            () -> materialTextures.abandon(abandonedMaterialFrame),
                            failure);
                }
                failure = ResourceCleanup.run(
                        () -> processor.abandon(processorFrame),
                        failure);
                DisplayExposureDiagnostics.Capture abandonedExposureCapture =
                        exposureCapture;
                failure = ResourceCleanup.run(
                        () -> exposureDiagnostics.abandon(abandonedExposureCapture),
                        failure);
                throw failure;
            }
            throw exception;
        }
    }

    private static void validateExtents(
            RealtimeFramePlan plan,
            VulkanReconstructionProcessor processor,
            VulkanImage output,
            VulkanImage stableRadiance,
            VulkanGpuTexture mainColor) {
        if (plan.integrator().width() != processor.renderWidth()
                || plan.integrator().height() != processor.renderHeight()
                || stableRadiance.width() != processor.renderWidth()
                || stableRadiance.height() != processor.renderHeight()
                || output.width() != processor.displayWidth()
                || output.height() != processor.displayHeight()
                || mainColor.getWidth(0) != processor.displayWidth()
                || mainColor.getHeight(0) != processor.displayHeight()) {
            throw new IllegalArgumentException(
                    "Realtime device resources do not match the semantic frame extents");
        }
    }
}
