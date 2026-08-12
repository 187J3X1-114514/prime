package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.render.IntegratorFrameInput;
import dev.prime.render.RealtimeFramePlan;
import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.vulkan.terrain.TerrainScene;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/** Single-dispatch realtime integrator with no per-pixel transport backing. */
public final class RealtimeMegakernelPipeline implements RealtimeIntegratorPipeline {
    private static final int STORAGE_IMAGE_COUNT = 23;
    private static final int DESCRIPTOR_BINDING_COUNT = STORAGE_IMAGE_COUNT + 1;
    private static final String QUERY_SHADER =
            "/prime/shaders/realtime_megakernel.rgen.spv";
    private static final String SHARC_QUERY_SHADER =
            "/prime/shaders/realtime_megakernel_sharc.rgen.spv";

    private final VulkanContext context;
    private final TraceBackend backend;
    private final long descriptorSetLayout;
    private final long pipelineLayout;
    private final TraceProgram program;
    private final RealtimeSharcDiagnostics sharcDiagnostics;
    private Bindings bindings;
    private RealtimeSharc sharc;
    private PendingFrame pendingFrame;
    private long nextFrameToken = 1L;
    private int lastRecordedPassCount = 1;
    private boolean destroyed;

    public RealtimeMegakernelPipeline(VulkanContext context, TraceBackend backend) {
        this.context = context;
        this.backend = java.util.Objects.requireNonNull(backend, "backend");
        long setLayout = 0L;
        long layout = 0L;
        TraceProgram traceProgram = null;
        RealtimeSharcDiagnostics diagnostics = null;
        try {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                setLayout = createDescriptorSetLayout(context, stack);
                layout = TracePipelineLayouts.create(
                        context,
                        stack,
                        backend.bindings().descriptorSetLayout(),
                        setLayout,
                        "realtime megakernel");
            }
            traceProgram = TraceProgram.create(
                    context,
                    layout,
                    new String[] {QUERY_SHADER},
                    new int[] {0},
                    new int[] {0},
                    "Prime realtime megakernel pipeline",
                    "Prime realtime megakernel shader binding table");
            diagnostics = new RealtimeSharcDiagnostics(context);
            this.descriptorSetLayout = setLayout;
            this.pipelineLayout = layout;
            this.program = traceProgram;
            this.sharcDiagnostics = diagnostics;
        } catch (RuntimeException exception) {
            if (diagnostics != null) diagnostics.destroy();
            if (traceProgram != null) traceProgram.destroy();
            if (layout != 0L) {
                VK12.vkDestroyPipelineLayout(context.vkDevice(), layout, null);
            }
            if (setLayout != 0L) {
                VK12.vkDestroyDescriptorSetLayout(context.vkDevice(), setLayout, null);
            }
            throw exception;
        }
    }

    @Override
    public void ensureDescriptors(
            long tlas,
            VulkanImage stableRadiance,
            VulkanGpuTextureView atlasView,
            VulkanGpuSampler atlasSampler,
            List<TraceBackend.SceneTexture> sceneTextures,
            VulkanImage labPbrNormalAtlas,
            VulkanImage labPbrSpecularAtlas,
            AtmospherePipeline atmosphere,
            RawWavefrontFrame signals,
            boolean sharcRequested) {
        this.backend.ensureSceneDescriptors(
                tlas,
                atlasView,
                atlasSampler,
                sceneTextures,
                labPbrNormalAtlas,
                labPbrSpecularAtlas,
                atmosphere);
        boolean sharcEffective = sharcRequested && this.context.capabilities().sharcSupported();
        if (sharcEffective && this.sharc == null) {
            this.sharc = new RealtimeSharc(
                    this.context,
                    this.pipelineLayout,
                    this.backend.bindings().descriptorSetLayout(),
                    this.descriptorSetLayout,
                    SHARC_QUERY_SHADER);
        } else if (!sharcEffective && this.sharc != null) {
            RealtimeSharc previous = this.sharc;
            this.sharc = null;
            this.context.defer(previous);
        }
        long sharcFrame = this.sharc == null ? 0L : this.sharc.frameConstants().handle();
        if (this.bindings != null
                && this.bindings.matches(stableRadiance, signals, sharcFrame)) {
            return;
        }
        Bindings replacement = Bindings.create(
                this.context,
                this.descriptorSetLayout,
                stableRadiance,
                signals,
                this.sharc == null ? null : this.sharc.frameConstants());
        Bindings previous = this.bindings;
        this.bindings = replacement;
        if (previous != null) this.context.defer(previous);
    }

    @Override
    public long prepareFrame(
            VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization,
            RealtimeFramePlan plan,
            TerrainScene.ResidentSceneView scene,
            long textureRevision) {
        if (this.pendingFrame != null) {
            throw new IllegalStateException("Megakernel pipeline already has a pending frame");
        }
        long backendToken = this.backend.prepareFrame(commandBuffer, initialization);
        RealtimeSharcDiagnostics.Capture diagnostics = null;
        RealtimeSharc.Prepared prepared = null;
        try {
            diagnostics = this.sharcDiagnostics.prepare(
                    commandBuffer, plan.rendererDiagnostics(), this.sharc != null);
            if (this.sharc != null) {
                prepared = this.sharc.prepare(
                        commandBuffer,
                        plan.integrator(),
                        scene,
                        textureRevision,
                        plan.reconstructionReset(),
                        this.sharcDiagnostics.counterAddress(diagnostics),
                        diagnostics != null);
            }
        } catch (RuntimeException exception) {
            this.sharcDiagnostics.abandon(diagnostics);
            if (backendToken != 0L) this.backend.abandon(backendToken);
            throw exception;
        }
        if (this.sharc == null && diagnostics == null) return backendToken;
        long token = this.nextFrameToken++;
        if (token == 0L) token = this.nextFrameToken++;
        this.pendingFrame = new PendingFrame(
                token, backendToken, this.sharc, prepared, diagnostics);
        return token;
    }

    @Override
    public void submitted(long token) {
        PendingFrame pending = this.pendingFrame;
        if (pending == null) {
            this.backend.submitted(token);
            return;
        }
        if (pending.token != token) {
            throw new IllegalStateException("Megakernel frame token mismatch");
        }
        this.pendingFrame = null;
        this.backend.submitted(pending.backendToken);
        if (pending.owner != null) pending.owner.submitted(pending.sharcFrame);
        this.sharcDiagnostics.submitted(pending.diagnostics);
    }

    @Override
    public void abandon(long token) {
        PendingFrame pending = this.pendingFrame;
        if (pending == null) {
            this.backend.abandon(token);
            return;
        }
        if (pending.token != token) {
            throw new IllegalStateException("Megakernel frame token mismatch");
        }
        this.pendingFrame = null;
        if (pending.backendToken != 0L) this.backend.abandon(pending.backendToken);
        this.sharcDiagnostics.abandon(pending.diagnostics);
    }

    @Override
    public void trace(
            VkCommandBuffer commandBuffer,
            IntegratorFrameInput input,
            TerrainScene.ResidentSceneView scene) {
        if (this.bindings == null) {
            throw new IllegalStateException("Megakernel descriptors are not initialized");
        }
        if (!this.backend.bindings().ready()) {
            throw new IllegalStateException("Trace-backend resources are not prepared");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer pushConstants = RayTracingPushConstants.encode(stack, input, scene);
            TraceProgram activeProgram = this.program;
            RealtimeSharcDiagnostics.Capture diagnostics = this.pendingFrame == null
                    ? null
                    : this.pendingFrame.diagnostics;
            if (this.sharc != null) {
                this.sharc.recordUpdateAndResolve(
                        commandBuffer,
                        this.backend.bindings().descriptorSet(),
                        this.bindings.descriptorSet,
                        pushConstants,
                        input.width(),
                        input.height(),
                        this.sharcDiagnostics,
                        diagnostics);
                activeProgram = this.sharc.queryProgram();
                this.lastRecordedPassCount = 3;
            } else {
                this.sharcDiagnostics.recordReferenceQueryStart(commandBuffer, diagnostics);
                this.lastRecordedPassCount = 1;
            }
            bind(commandBuffer, stack, pushConstants, activeProgram);
            WavefrontCommands.trace(
                    commandBuffer,
                    stack,
                    activeProgram,
                    input.width(),
                    input.height(),
                    0);
            this.sharcDiagnostics.finish(commandBuffer, diagnostics);
        }
    }

    private void bind(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            ByteBuffer pushConstants,
            TraceProgram activeProgram) {
        VK12.vkCmdBindPipeline(
                commandBuffer,
                KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,
                activeProgram.pipeline);
        VK12.vkCmdBindDescriptorSets(
                commandBuffer,
                KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,
                this.pipelineLayout,
                0,
                stack.longs(
                        this.backend.bindings().descriptorSet(),
                        this.bindings.descriptorSet),
                null);
        VK12.vkCmdPushConstants(
                commandBuffer,
                this.pipelineLayout,
                TracePipelineLayouts.ALL_RT_STAGES,
                0,
                pushConstants);
    }

    @Override
    public int passCount() {
        return this.lastRecordedPassCount;
    }

    @Override
    public long sizedResourceBytes() {
        return this.sharcDiagnostics.resourceBytes()
                + (this.sharc == null ? 0L : this.sharc.resourceBytes());
    }

    @Override
    public boolean sharcEffective() {
        return this.sharc != null;
    }

    @Override
    public SharcDiagnosticsSnapshot sharcDiagnostics() {
        return this.sharcDiagnostics.latest();
    }

    @Override
    public void releaseSizedResourcesAfterIdle() {
        if (this.bindings != null) {
            this.bindings.destroy();
            this.bindings = null;
        }
        if (this.sharc != null) {
            this.sharc.destroy();
            this.sharc = null;
        }
    }

    private static long createDescriptorSetLayout(
            VulkanContext context, MemoryStack stack) {
        VkDescriptorSetLayoutBinding.Buffer bindings =
                VkDescriptorSetLayoutBinding.calloc(DESCRIPTOR_BINDING_COUNT, stack);
        int cursor = 0;
        for (int binding : imageBindings()) {
            bindings.get(cursor++)
                    .binding(binding)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        }
        bindings.get(cursor)
                .binding(ShaderAbi.DESCRIPTOR_SHARC_FRAME)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                        | VK12.VK_SHADER_STAGE_COMPUTE_BIT);
        LongBuffer pointer = stack.mallocLong(1);
        VulkanContext.check(
                VK12.vkCreateDescriptorSetLayout(
                        context.vkDevice(),
                        VkDescriptorSetLayoutCreateInfo.calloc(stack)
                                .sType$Default()
                                .pBindings(bindings),
                        null,
                        pointer),
                "create realtime megakernel descriptor layout");
        return pointer.get(0);
    }

    private static int[] imageBindings() {
        return new int[] {
            ShaderAbi.DESCRIPTOR_STABLE_RADIANCE,
            ShaderAbi.DESCRIPTOR_NRD_NOISY_DIFFUSE,
            ShaderAbi.DESCRIPTOR_NRD_NOISY_SPECULAR,
            ShaderAbi.DESCRIPTOR_NRD_NORMAL_ROUGHNESS,
            ShaderAbi.DESCRIPTOR_NRD_VIEW_Z,
            ShaderAbi.DESCRIPTOR_WAVEFRONT_TRANSPORT_METADATA,
            ShaderAbi.DESCRIPTOR_NRD_MATERIAL,
            ShaderAbi.DESCRIPTOR_NRD_SPECULAR_MATERIAL,
            ShaderAbi.DESCRIPTOR_NRD_PRIMARY_POSITION,
            ShaderAbi.DESCRIPTOR_NRD_DIFFUSE_DIRECTION,
            ShaderAbi.DESCRIPTOR_NRD_SPECULAR_DIRECTION,
            ShaderAbi.DESCRIPTOR_NRD_REFLECTION_NOISY_DIFFUSE,
            ShaderAbi.DESCRIPTOR_NRD_REFLECTION_NOISY_SPECULAR,
            ShaderAbi.DESCRIPTOR_NRD_REFLECTION_NORMAL_ROUGHNESS,
            ShaderAbi.DESCRIPTOR_NRD_REFLECTION_MATERIAL,
            ShaderAbi.DESCRIPTOR_NRD_REFLECTION_SPECULAR_MATERIAL,
            ShaderAbi.DESCRIPTOR_NRD_REFLECTION_POSITION,
            ShaderAbi.DESCRIPTOR_NRD_REFLECTION_DIFFUSE_DIRECTION,
            ShaderAbi.DESCRIPTOR_NRD_REFLECTION_SPECULAR_DIRECTION,
            ShaderAbi.DESCRIPTOR_NRD_DISPLAY_POSITION,
            ShaderAbi.DESCRIPTOR_NRD_SUN_LIGHTING,
            ShaderAbi.DESCRIPTOR_NRD_SUN_PENUMBRA,
            ShaderAbi.DESCRIPTOR_RAW_NUMERICAL_DIAGNOSTIC
        };
    }

    @Override
    public void destroy() {
        if (this.destroyed) return;
        this.destroyed = true;
        if (this.bindings != null) this.bindings.destroy();
        if (this.sharc != null) this.sharc.destroy();
        this.sharcDiagnostics.destroy();
        this.program.destroy();
        VK12.vkDestroyPipelineLayout(this.context.vkDevice(), this.pipelineLayout, null);
        VK12.vkDestroyDescriptorSetLayout(
                this.context.vkDevice(), this.descriptorSetLayout, null);
    }

    private static final class Bindings implements Destroyable {
        private final VulkanContext context;
        private final long descriptorPool;
        private final long descriptorSet;
        private final long stableRadiance;
        private final long[] views;
        private final long sharcFrame;
        private boolean destroyed;

        private Bindings(
                VulkanContext context,
                long descriptorPool,
                long descriptorSet,
                long stableRadiance,
                long[] views,
                long sharcFrame) {
            this.context = context;
            this.descriptorPool = descriptorPool;
            this.descriptorSet = descriptorSet;
            this.stableRadiance = stableRadiance;
            this.views = views;
            this.sharcFrame = sharcFrame;
        }

        static Bindings create(
                VulkanContext context,
                long layout,
                VulkanImage stableRadiance,
                RawWavefrontFrame signals,
                VulkanBuffer sharcFrame) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                int poolCount = sharcFrame == null ? 1 : 2;
                VkDescriptorPoolSize.Buffer sizes =
                        VkDescriptorPoolSize.calloc(poolCount, stack);
                sizes.get(0)
                        .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(STORAGE_IMAGE_COUNT);
                if (sharcFrame != null) {
                    sizes.get(1)
                            .type(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                            .descriptorCount(1);
                }
                LongBuffer pointer = stack.mallocLong(1);
                VulkanContext.check(
                        VK12.vkCreateDescriptorPool(
                                context.vkDevice(),
                                VkDescriptorPoolCreateInfo.calloc(stack)
                                        .sType$Default()
                                        .maxSets(1)
                                        .pPoolSizes(sizes),
                                null,
                                pointer),
                        "create realtime megakernel descriptor pool");
                long pool = pointer.get(0);
                try {
                    pointer.clear();
                    VulkanContext.check(
                            VK12.vkAllocateDescriptorSets(
                                    context.vkDevice(),
                                    VkDescriptorSetAllocateInfo.calloc(stack)
                                            .sType$Default()
                                            .descriptorPool(pool)
                                            .pSetLayouts(stack.longs(layout)),
                                    pointer),
                            "allocate realtime megakernel descriptor set");
                    long set = pointer.get(0);
                    VulkanImage[] images = outputImages(stableRadiance, signals);
                    long[] views = new long[images.length];
                    VkDescriptorImageInfo.Buffer infos =
                            VkDescriptorImageInfo.calloc(images.length, stack);
                    for (int index = 0; index < images.length; index++) {
                        views[index] = images[index].view();
                        infos.get(index)
                                .imageView(views[index])
                                .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                    }
                    int writeCount = images.length + (sharcFrame == null ? 0 : 1);
                    VkWriteDescriptorSet.Buffer writes =
                            VkWriteDescriptorSet.calloc(writeCount, stack);
                    int[] imageBindings = imageBindings();
                    for (int index = 0; index < images.length; index++) {
                        writes.get(index)
                                .sType$Default()
                                .dstSet(set)
                                .dstBinding(imageBindings[index])
                                .descriptorCount(1)
                                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                                .pImageInfo(VkDescriptorImageInfo.create(
                                        infos.get(index).address(), 1));
                    }
                    if (sharcFrame != null) {
                        VkDescriptorBufferInfo.Buffer bufferInfo =
                                VkDescriptorBufferInfo.calloc(1, stack);
                        bufferInfo.get(0)
                                .buffer(sharcFrame.handle())
                                .offset(0L)
                                .range(ShaderAbi.SHARC_FRAME_CONSTANT_SIZE);
                        writes.get(images.length)
                                .sType$Default()
                                .dstSet(set)
                                .dstBinding(ShaderAbi.DESCRIPTOR_SHARC_FRAME)
                                .descriptorCount(1)
                                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                                .pBufferInfo(bufferInfo);
                    }
                    VK12.vkUpdateDescriptorSets(context.vkDevice(), writes, null);
                    return new Bindings(
                            context,
                            pool,
                            set,
                            stableRadiance.view(),
                            views,
                            sharcFrame == null ? 0L : sharcFrame.handle());
                } catch (RuntimeException exception) {
                    VK12.vkDestroyDescriptorPool(context.vkDevice(), pool, null);
                    throw exception;
                }
            }
        }

        boolean matches(
                VulkanImage candidateStableRadiance,
                RawWavefrontFrame signals,
                long candidateSharcFrame) {
            if (this.stableRadiance != candidateStableRadiance.view()
                    || this.sharcFrame != candidateSharcFrame) {
                return false;
            }
            VulkanImage[] images = outputImages(candidateStableRadiance, signals);
            if (images.length != this.views.length) return false;
            for (int index = 0; index < images.length; index++) {
                if (images[index].view() != this.views[index]) return false;
            }
            return true;
        }

        private static VulkanImage[] outputImages(
                VulkanImage stableRadiance, RawWavefrontFrame signals) {
            return new VulkanImage[] {
                stableRadiance,
                signals.noisyDiffuse(),
                signals.noisySpecular(),
                signals.normalRoughness(),
                signals.viewZ(),
                signals.transportMetadata(),
                signals.material(),
                signals.specularMaterial(),
                signals.primaryPosition(),
                signals.diffuseDirection(),
                signals.specularDirection(),
                signals.reflectionNoisyDiffuse(),
                signals.reflectionNoisySpecular(),
                signals.reflectionNormalRoughness(),
                signals.reflectionMaterial(),
                signals.reflectionSpecularMaterial(),
                signals.reflectionPosition(),
                signals.reflectionDiffuseDirection(),
                signals.reflectionSpecularDirection(),
                signals.displayPosition(),
                signals.sunLighting(),
                signals.sunPenumbra(),
                signals.rawNumericalDiagnostic()
            };
        }

        @Override
        public void destroy() {
            if (!this.destroyed) {
                this.destroyed = true;
                VK12.vkDestroyDescriptorPool(
                        this.context.vkDevice(), this.descriptorPool, null);
            }
        }
    }

    private record PendingFrame(
            long token,
            long backendToken,
            RealtimeSharc owner,
            RealtimeSharc.Prepared sharcFrame,
            RealtimeSharcDiagnostics.Capture diagnostics) {
    }
}
