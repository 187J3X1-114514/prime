package dev.prime.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.PrimeClient;
import dev.prime.render.terrain.TerrainScene;
import dev.prime.render.terrain.TerrainStreamer;
import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.vulkan.AtmospherePipeline;
import dev.prime.render.vulkan.RayTracingPipeline;
import dev.prime.render.vulkan.StagingArena;
import dev.prime.render.vulkan.VulkanCapabilities;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.render.vulkan.nrd.NrdDenoiser;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.data.AtlasIds;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;

public final class VulkanRenderer implements AutoCloseable {
    private final VulkanContext context;
    private final StagingArena stagingArena;
    private final TerrainStreamer terrain;
    private final AccumulationState accumulationState = new AccumulationState();
    private RayTracingPipeline pipeline;
    private AtmospherePipeline atmosphere;
    private RenderImages renderImages;
    private FrameCamera camera;
    private SunDirection sunDirection;
    private boolean shaderReloadRequested;
    private boolean closed;

    public VulkanRenderer(com.mojang.blaze3d.vulkan.VulkanDevice device, VulkanCapabilities capabilities) {
        VulkanContext newContext = new VulkanContext(device, capabilities);
        StagingArena newStagingArena = null;
        AtmospherePipeline newAtmosphere = null;
        RayTracingPipeline newPipeline = null;
        TerrainStreamer newTerrain = null;
        try {
            newStagingArena = new StagingArena(newContext);
            newAtmosphere = new AtmospherePipeline(newContext);
            newPipeline = new RayTracingPipeline(newContext);
            newTerrain = new TerrainStreamer(newContext, newStagingArena);
            this.context = newContext;
            this.stagingArena = newStagingArena;
            this.pipeline = newPipeline;
            this.atmosphere = newAtmosphere;
            this.terrain = newTerrain;
        } catch (RuntimeException exception) {
            if (newTerrain != null) {
                newTerrain.close();
            }
            if (newPipeline != null) {
                newPipeline.destroy();
            }
            if (newAtmosphere != null) {
                newAtmosphere.destroy();
            }
            if (newStagingArena != null) {
                newStagingArena.close();
            }
            newContext.close();
            throw exception;
        }
    }

    public void beginFrame(Minecraft minecraft) {
        this.reloadPipelineIfRequested();
        FrameCamera frameCamera = this.camera;
        if (frameCamera != null) {
            this.terrain.update(minecraft, frameCamera.x(), frameCamera.y(), frameCamera.z());
        } else if (minecraft.player != null) {
            this.terrain.update(
                    minecraft,
                    minecraft.player.getX(),
                    minecraft.player.getY(),
                    minecraft.player.getZ());
        } else {
            this.terrain.update(minecraft, 0.0, 0.0, 0.0);
        }
    }

    public void captureCamera(
            Matrix4fc projection,
            Matrix4fc viewRotation,
            double x,
            double y,
            double z,
            float sunAngleRadians) {
        this.camera = FrameCamera.tryCreate(projection, viewRotation, x, y, z);
        this.sunDirection = SunDirection.fromVanillaAngle(sunAngleRadians);
    }

    public boolean isReady() {
        return this.terrain.isNearCameraReady() && this.terrain.sceneView() != null;
    }

    public void render(RenderTarget mainTarget) {
        TerrainScene.SceneView scene = this.terrain.sceneView();
        FrameCamera frameCamera = this.camera;
        SunDirection frameSunDirection = this.sunDirection;
        if (scene == null || frameCamera == null || frameSunDirection == null) {
            return;
        }
        if (!(mainTarget.getColorTexture() instanceof VulkanGpuTexture mainColor)) {
            throw new IllegalStateException("Prime expected a Vulkan main color texture");
        }
        if (mainColor.getFormat() != GpuFormat.RGBA8_UNORM) {
            throw new IllegalStateException("Prime requires an RGBA8_UNORM main target");
        }
        int width = mainColor.getWidth(0);
        int height = mainColor.getHeight(0);
        if (width <= 0
                || height <= 0
                || mainTarget.width != width
                || mainTarget.height != height) {
            return;
        }
        long invocationCount = (long) width * height;
        if (invocationCount > Integer.toUnsignedLong(this.context.capabilities().maxRayDispatchInvocationCount())) {
            throw new IllegalStateException("Window dimensions exceed the Vulkan ray dispatch limit");
        }

        Minecraft minecraft = Minecraft.getInstance();
        TextureAtlas atlas = minecraft.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
        if (!(atlas.getTextureView() instanceof VulkanGpuTextureView atlasView)
                || !(atlas.getSampler() instanceof VulkanGpuSampler atlasSampler)) {
            throw new IllegalStateException("Prime expected Vulkan block atlas resources");
        }
        long atlasViewHandle = atlasView.vkImageView();
        long atlasSamplerHandle = atlasSampler.vkSampler();
        boolean resized = this.ensureRenderImages(
                width,
                height,
                scene.tlas(),
                atlasView,
                atlasSampler);
        RenderImages images = this.renderImages;
        if (images == null) {
            return;
        }
        VulkanImage target = images.output;
        VulkanImage history = images.accumulation;
        NrdDenoiser denoiser = images.denoiser;
        this.accumulationState.prepare(
                frameCamera,
                scene.revision(),
                scene.resetRevision(),
                atlasViewHandle,
                atlasSamplerHandle,
                frameSunDirection,
                resized);
        float[] cameraSample = IntegratorSettings.sobolSample2D(
                0,
                0,
                this.accumulationState.sampleIndex(),
                this.accumulationState.epoch(),
                0,
                IntegratorSettings.SAMPLE_EFFECT_CAMERA,
                0);

        var encoder = this.context.commandEncoder();
        VkCommandBuffer commandBuffer = encoder.allocateAndBeginTransientCommandBuffer();
        this.context.device().instance().debug().beginDebugGroup(commandBuffer, () -> "Prime path tracing and NRD");
        this.atmosphere.prepare(commandBuffer, frameCamera, frameSunDirection);
        this.prepareOutputForComposite(commandBuffer, target);
        this.prepareAccumulationForTrace(commandBuffer, history);
        denoiser.prepareForRayTrace(commandBuffer);
        this.prepareAtlasForTrace(commandBuffer, atlasView.texture());
        NrdDenoiser.FrameToken nrdFrame;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer pushConstants = this.createPushConstants(
                    stack,
                    frameCamera,
                    scene,
                    width,
                    height,
                    frameSunDirection);
            this.pipeline.trace(commandBuffer, pushConstants, width, height);
            this.finishAtlasRead(commandBuffer, atlasView.texture());
            nrdFrame = denoiser.record(
                    commandBuffer,
                    frameCamera,
                    scene.resetRevision(),
                    atlasViewHandle,
                    atlasSamplerHandle,
                    frameSunDirection,
                    cameraSample[0] - 0.5f,
                    cameraSample[1] - 0.5f);
            this.prepareImagesForCopy(commandBuffer, target, mainColor);
            VkImageCopy.Buffer copy = VkImageCopy.calloc(1, stack);
            copy.get(0).srcSubresource()
                    .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0)
                    .baseArrayLayer(0)
                    .layerCount(1);
            copy.get(0).dstSubresource()
                    .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0)
                    .baseArrayLayer(0)
                    .layerCount(1);
            copy.get(0).extent().set(width, height, 1);
            VK12.vkCmdCopyImage(
                    commandBuffer,
                    target.image(),
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    mainColor.vkImage(),
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    copy);
            this.finishImageCopy(commandBuffer, target, mainColor);
        }
        this.context.device().instance().debug().endDebugGroup(commandBuffer);
        VulkanContext.check(VK12.vkEndCommandBuffer(commandBuffer), "end Prime ray tracing command buffer");
        encoder.execute(commandBuffer);
        denoiser.submitted(nrdFrame);
        this.accumulationState.submitted(
                frameCamera,
                atlasViewHandle,
                atlasSamplerHandle,
                frameSunDirection);
        int accumulatedSampleCount = this.accumulationState.sampleIndex();
        if (accumulatedSampleCount >= 16
                && (accumulatedSampleCount & (accumulatedSampleCount - 1)) == 0) {
            PrimeClient.LOGGER.debug(
                    "Prime accumulation reached {} samples for scene revision {}",
                    accumulatedSampleCount,
                    scene.revision());
        }
    }

    public void invalidateSection(int sectionX, int sectionY, int sectionZ) {
        this.terrain.invalidateSection(sectionX, sectionY, sectionZ);
    }

    public void invalidateAll() {
        this.terrain.invalidateAll();
    }

    public void requestShaderReload() {
        this.shaderReloadRequested = true;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.context.awaitIdle();
        this.context.drainDeferredAfterIdle();
        this.terrain.close();
        this.pipeline.destroy();
        if (this.renderImages != null) {
            this.renderImages.destroy();
            this.renderImages = null;
        }
        this.atmosphere.destroy();
        this.stagingArena.close();
        this.context.close();
    }

    private void reloadPipelineIfRequested() {
        if (!this.shaderReloadRequested) {
            return;
        }
        this.shaderReloadRequested = false;
        AtmospherePipeline replacementAtmosphere = null;
        RayTracingPipeline replacementPipeline = null;
        NrdDenoiser replacementDenoiser = null;
        try {
            replacementAtmosphere = new AtmospherePipeline(this.context);
            replacementPipeline = new RayTracingPipeline(this.context);
            RenderImages currentImages = this.renderImages;
            if (currentImages != null) {
                replacementDenoiser = NrdDenoiser.create(
                        this.context,
                        currentImages.output.width(),
                        currentImages.output.height(),
                        currentImages.output,
                        currentImages.accumulation,
                        replacementAtmosphere);
            }
        } catch (RuntimeException exception) {
            if (replacementDenoiser != null) {
                replacementDenoiser.destroy();
            }
            if (replacementPipeline != null) {
                replacementPipeline.destroy();
            }
            if (replacementAtmosphere != null) {
                replacementAtmosphere.destroy();
            }
            PrimeClient.LOGGER.error("Prime shader reload failed; keeping the previous pipeline", exception);
            return;
        }
        RayTracingPipeline previousPipeline = this.pipeline;
        AtmospherePipeline previousAtmosphere = this.atmosphere;
        RenderImages currentImages = this.renderImages;
        NrdDenoiser previousDenoiser = currentImages == null ? null : currentImages.denoiser;
        this.pipeline = replacementPipeline;
        this.atmosphere = replacementAtmosphere;
        if (currentImages != null) {
            currentImages.denoiser = replacementDenoiser;
        }
        this.context.defer(previousPipeline);
        if (previousDenoiser != null) {
            this.context.defer(previousDenoiser);
        }
        this.context.defer(previousAtmosphere);
        this.accumulationState.invalidate();
        PrimeClient.LOGGER.info("Reloaded Prime ray tracing and atmosphere shaders");
    }

    private boolean ensureRenderImages(
            int width,
            int height,
            long tlas,
            VulkanGpuTextureView atlasView,
            VulkanGpuSampler atlasSampler) {
        RenderImages current = this.renderImages;
        if (current != null && current.matches(width, height)) {
            this.pipeline.ensureDescriptors(
                    tlas,
                    current.output,
                    current.accumulation,
                    atlasView,
                    atlasSampler,
                    this.atmosphere,
                    current.denoiser);
            return false;
        }
        VulkanImage replacementOutput = null;
        VulkanImage replacementAccumulation = null;
        NrdDenoiser replacementDenoiser = null;
        try {
            replacementOutput = this.context.createOutputImage(width, height);
            replacementAccumulation = this.context.createAccumulationImage(width, height);
            replacementDenoiser = NrdDenoiser.create(
                    this.context,
                    width,
                    height,
                    replacementOutput,
                    replacementAccumulation,
                    this.atmosphere);
        } catch (RuntimeException exception) {
            if (replacementDenoiser != null) {
                replacementDenoiser.destroy();
            }
            if (replacementAccumulation != null) {
                replacementAccumulation.destroy();
            }
            if (replacementOutput != null) {
                replacementOutput.destroy();
            }
            throw exception;
        }
        RenderImages replacement = new RenderImages(
                replacementOutput, replacementAccumulation, replacementDenoiser);
        try {
            this.pipeline.ensureDescriptors(
                    tlas,
                    replacement.output,
                    replacement.accumulation,
                    atlasView,
                    atlasSampler,
                    this.atmosphere,
                    replacement.denoiser);
        } catch (RuntimeException exception) {
            replacement.destroy();
            throw exception;
        }
        this.renderImages = replacement;
        if (current != null) {
            // The pipeline retires its old descriptor set before these referenced image views.
            this.context.defer(current);
        }
        PrimeClient.LOGGER.debug(
                "Recreated Prime render images at {}x{} "
                        + "(output image={}, view={}; accumulation image={}, view={}; atlas image={}, view={}, sampler={})",
                width,
                height,
                hex(replacement.output.image()),
                hex(replacement.output.view()),
                hex(replacement.accumulation.image()),
                hex(replacement.accumulation.view()),
                hex(atlasView.texture().vkImage()),
                hex(atlasView.vkImageView()),
                hex(atlasSampler.vkSampler()));
        return true;
    }

    private void prepareAtlasForTrace(VkCommandBuffer commandBuffer, VulkanGpuTexture atlas) {
        // Minecraft updates animated atlas regions in place and keeps the image in GENERAL.
        // Queue order alone is not a memory dependency: without this availability/visibility
        // barrier, ray tracing can observe an atlas render pass while it is still writing. The
        // resulting corruption is especially reproducible when a swapchain resize changes GPU
        // scheduling. Do not remove this half of the pair unless atlas ownership and submissions
        // are replaced by an equivalent explicit synchronization protocol.
        imageBarrier(
                commandBuffer,
                atlas.vkImage(),
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK12.VK_ACCESS_MEMORY_WRITE_BIT,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK12.VK_ACCESS_SHADER_READ_BIT);
    }

    private void finishAtlasRead(VkCommandBuffer commandBuffer, VulkanGpuTexture atlas) {
        // Complete Prime's sampled reads before a later Minecraft render/transfer pass writes the
        // atlas again. This is the reverse dependency of prepareAtlasForTrace; both barriers are
        // required even though the layout remains GENERAL throughout.
        imageBarrier(
                commandBuffer,
                atlas.vkImage(),
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK12.VK_ACCESS_SHADER_READ_BIT,
                VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK12.VK_ACCESS_MEMORY_WRITE_BIT);
    }

    private void prepareOutputForComposite(VkCommandBuffer commandBuffer, VulkanImage image) {
        int oldLayout = image.initialized() ? VK12.VK_IMAGE_LAYOUT_GENERAL : VK12.VK_IMAGE_LAYOUT_UNDEFINED;
        long sourceStage = image.initialized() ? VK12.VK_PIPELINE_STAGE_TRANSFER_BIT : VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
        long sourceAccess = image.initialized() ? VK12.VK_ACCESS_TRANSFER_READ_BIT : 0L;
        imageBarrier(
                commandBuffer,
                image.image(),
                oldLayout,
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                sourceStage,
                sourceAccess,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_WRITE_BIT);
        image.markInitialized();
    }

    private void prepareAccumulationForTrace(VkCommandBuffer commandBuffer, VulkanImage image) {
        int oldLayout = image.initialized() ? VK12.VK_IMAGE_LAYOUT_GENERAL : VK12.VK_IMAGE_LAYOUT_UNDEFINED;
        long sourceStage = image.initialized()
                // The stable remainder is written by raygen, then read by NRD's composite pass.
                // Both accesses belong to the image's cross-frame lifetime: omitting the compute
                // read here would allow the next raygen dispatch to overwrite a pixel still being
                // consumed by the previous frame on an asynchronously executing Vulkan queue.
                ? KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                        | VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                : VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
        long sourceAccess = image.initialized()
                ? VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT
                : 0L;
        imageBarrier(
                commandBuffer,
                image.image(),
                oldLayout,
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                sourceStage,
                sourceAccess,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
        image.markInitialized();
    }

    private void prepareImagesForCopy(VkCommandBuffer commandBuffer, VulkanImage source, VulkanGpuTexture destination) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(2, stack);
            fillImageBarrier(
                    barriers.get(0),
                    source.image(),
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_ACCESS_SHADER_WRITE_BIT,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_TRANSFER_READ_BIT);
            fillImageBarrier(
                    barriers.get(1),
                    destination.vkImage(),
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK12.VK_ACCESS_MEMORY_WRITE_BIT,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
            VkDependencyInfo dependency = VkDependencyInfo.calloc(stack)
                    .sType$Default()
                    .pImageMemoryBarriers(barriers);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer, dependency);
        }
    }

    private void finishImageCopy(VkCommandBuffer commandBuffer, VulkanImage source, VulkanGpuTexture destination) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(2, stack);
            fillImageBarrier(
                    barriers.get(0),
                    source.image(),
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_TRANSFER_READ_BIT,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_ACCESS_SHADER_WRITE_BIT);
            fillImageBarrier(
                    barriers.get(1),
                    destination.vkImage(),
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK12.VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT,
                    VK12.VK_ACCESS_MEMORY_READ_BIT | VK12.VK_ACCESS_MEMORY_WRITE_BIT);
            VkDependencyInfo dependency = VkDependencyInfo.calloc(stack)
                    .sType$Default()
                    .pImageMemoryBarriers(barriers);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer, dependency);
        }
    }

    private ByteBuffer createPushConstants(
            MemoryStack stack,
            FrameCamera camera,
            TerrainScene.SceneView scene,
            int width,
            int height,
            SunDirection sunDirection) {
        ByteBuffer buffer = stack.calloc(ShaderAbi.PUSH_CONSTANT_SIZE).order(ByteOrder.nativeOrder());
        float[] matrix = new float[16];
        camera.inverseViewProjection().get(matrix);
        for (int index = 0; index < matrix.length; index++) {
            buffer.putFloat(ShaderAbi.PUSH_INVERSE_VIEW_PROJECTION_OFFSET + index * Float.BYTES, matrix[index]);
        }
        int cameraOffset = ShaderAbi.PUSH_CAMERA_POSITION_OFFSET;
        buffer.putFloat(cameraOffset, (float) (camera.x() - scene.originX()));
        buffer.putFloat(cameraOffset + Float.BYTES, (float) (camera.y() - scene.originY()));
        buffer.putFloat(cameraOffset + 2 * Float.BYTES, (float) (camera.z() - scene.originZ()));
        buffer.putFloat(
                ShaderAbi.PUSH_ATMOSPHERE_EYE_RADIUS_KM_OFFSET,
                AtmospherePipeline.eyeRadiusKm(camera.y()));
        buffer.putLong(ShaderAbi.PUSH_SECTION_TABLE_ADDRESS_OFFSET, scene.sectionTableAddress());
        buffer.putInt(ShaderAbi.PUSH_OUTPUT_EXTENT_OFFSET, width);
        buffer.putInt(ShaderAbi.PUSH_OUTPUT_EXTENT_OFFSET + Integer.BYTES, height);
        int sunOffset = ShaderAbi.PUSH_SUN_DIRECTION_OFFSET;
        buffer.putFloat(sunOffset, sunDirection.x());
        buffer.putFloat(sunOffset + Float.BYTES, sunDirection.y());
        buffer.putFloat(sunOffset + 2 * Float.BYTES, sunDirection.z());
        int pathOffset = ShaderAbi.PUSH_PATH_OFFSET;
        buffer.putInt(pathOffset, this.accumulationState.sampleIndex());
        buffer.putInt(pathOffset + Integer.BYTES, this.accumulationState.epoch());
        buffer.putInt(pathOffset + 2 * Integer.BYTES, IntegratorSettings.MAXIMUM_BOUNCES);
        buffer.putInt(pathOffset + 3 * Integer.BYTES, IntegratorSettings.RUSSIAN_ROULETTE_START);
        return buffer.position(0).limit(ShaderAbi.PUSH_CONSTANT_SIZE);
    }

    private static void imageBarrier(
            VkCommandBuffer commandBuffer,
            long image,
            int oldLayout,
            int newLayout,
            long sourceStage,
            long sourceAccess,
            long destinationStage,
            long destinationAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(1, stack);
            fillImageBarrier(
                    barrier.get(0),
                    image,
                    oldLayout,
                    newLayout,
                    sourceStage,
                    sourceAccess,
                    destinationStage,
                    destinationAccess);
            VkDependencyInfo dependency = VkDependencyInfo.calloc(stack)
                    .sType$Default()
                    .pImageMemoryBarriers(barrier);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer, dependency);
        }
    }

    private static void fillImageBarrier(
            VkImageMemoryBarrier2 barrier,
            long image,
            int oldLayout,
            int newLayout,
            long sourceStage,
            long sourceAccess,
            long destinationStage,
            long destinationAccess) {
        barrier.sType$Default()
                .srcStageMask(sourceStage)
                .srcAccessMask(sourceAccess)
                .dstStageMask(destinationStage)
                .dstAccessMask(destinationAccess)
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                .image(image);
        barrier.subresourceRange()
                .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1);
    }

    private static String hex(long handle) {
        return "0x" + Long.toUnsignedString(handle, 16);
    }

    private static final class RenderImages implements Destroyable {
        private final VulkanImage output;
        private final VulkanImage accumulation;
        private NrdDenoiser denoiser;
        private boolean destroyed;

        private RenderImages(
                VulkanImage output,
                VulkanImage accumulation,
                NrdDenoiser denoiser) {
            this.output = output;
            this.accumulation = accumulation;
            this.denoiser = denoiser;
        }

        private boolean matches(int width, int height) {
            return this.output.width() == width
                    && this.output.height() == height
                    && this.accumulation.width() == width
                    && this.accumulation.height() == height;
        }

        @Override
        public void destroy() {
            if (!this.destroyed) {
                this.destroyed = true;
                this.denoiser.destroy();
                this.accumulation.destroy();
                this.output.destroy();
            }
        }
    }
}
