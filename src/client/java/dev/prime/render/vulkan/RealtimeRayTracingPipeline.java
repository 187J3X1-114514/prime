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
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/** Realtime-only pipeline, queue ABI and reconstruction outputs. */
public final class RealtimeRayTracingPipeline implements RealtimeIntegratorPipeline {
    static final int RAYGEN_GROUP_COUNT = RealtimeWavefrontGroups.GROUP_COUNT;
    static final int RAYGEN_MODULE_COUNT = RealtimeWavefrontGroups.MODULE_COUNT;
    static int dispatchCount(int rounds) {
        return 5 * rounds + 6;
    }
    static final int DESCRIPTOR_BINDING_COUNT = 26;
    private static final int STORAGE_IMAGE_DESCRIPTOR_COUNT = 23;
    private static final WavefrontLayout WAVEFRONT_LAYOUT = new WavefrontLayout(
            ShaderAbi.WAVEFRONT_PATH_SLOTS_PER_PIXEL,
            ShaderAbi.WAVEFRONT_QUEUE_ENTRIES_PER_PIXEL,
            ShaderAbi.WAVEFRONT_PATH_RECORD_SIZE,
            ShaderAbi.WAVEFRONT_AREA_RECORD_SIZE,
            ShaderAbi.WAVEFRONT_QUEUE_COUNT,
            ShaderAbi.WAVEFRONT_QUEUE_COMMAND_STRIDE,
            ShaderAbi.WAVEFRONT_QUEUE_INDEX_SIZE,
            "Realtime");

    private final VulkanContext context;
    private final TraceBackend backend;
    private final long descriptorSetLayout;
    private final long pipelineLayout;
    private final TraceProgram program;
    private final RealtimeSharcDiagnostics sharcDiagnostics;
    private VulkanBuffer wavefront;
    private OutputBindings bindings;
    private RealtimeSharc sharc;
    private PendingFrame pendingFrame;
    private long nextFrameToken = 1L;
    private int lastRecordedPassCount;
    private boolean destroyed;

    public RealtimeRayTracingPipeline(VulkanContext context, TraceBackend backend) {
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
                        "realtime");
            }
            String suffix = context.capabilities().wavefrontShaderSuffix();
            String prefix = "/prime/shaders/realtime_wavefront_";
            String[] raygenShaders = new String[] {
                prefix + "head" + suffix,
                prefix + "step" + suffix,
                prefix + "primary" + suffix,
                prefix + "primary_area" + suffix,
                prefix + "primary_sun" + suffix,
                prefix + "area" + suffix,
                prefix + "sun" + suffix,
                prefix + "shade" + suffix,
                prefix + "transparent_shade" + suffix,
                prefix + "resolve" + suffix,
                prefix + "transparent_resolve" + suffix
            };
            traceProgram = TraceProgram.create(
                    context,
                    layout,
                    raygenShaders,
                    RealtimeWavefrontGroups.MODULES,
                    RealtimeWavefrontGroups.CONTROLS,
                    "Prime realtime ray tracing pipeline",
                    "Prime realtime shader binding table");
            diagnostics = new RealtimeSharcDiagnostics(context);
            this.descriptorSetLayout = setLayout;
            this.pipelineLayout = layout;
            this.program = traceProgram;
            this.sharcDiagnostics = diagnostics;
            this.lastRecordedPassCount = dispatchCount(
                    dev.prime.render.WavefrontSettings.DEFAULT_ROUNDS);
        } catch (RuntimeException exception) {
            if (diagnostics != null) {
                diagnostics.destroy();
            }
            if (traceProgram != null) {
                traceProgram.destroy();
            }
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
    public int passCount() {
        return this.lastRecordedPassCount;
    }

    @Override
    public long sizedResourceBytes() {
        long wavefrontBytes = this.wavefront == null ? 0L : this.wavefront.size();
        return wavefrontBytes
                + this.sharcDiagnostics.resourceBytes()
                + (this.sharc == null ? 0L : this.sharc.resourceBytes());
    }

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
        int width = signals.noisyDiffuse().width();
        int height = signals.noisyDiffuse().height();
        long requiredBytes = wavefrontBytes(width, height);
        validateRanges(width, height, this.context.maxStorageBufferRange());
        validateDispatch(
                width,
                height,
                this.context.capabilities().maxRayDispatchInvocationCount());
        boolean sharcEffective = sharcRequested && this.context.capabilities().sharcSupported();
        if (sharcEffective && this.sharc == null) {
            this.sharc = new RealtimeSharc(
                    this.context,
                    this.pipelineLayout,
                    this.backend.bindings().descriptorSetLayout(),
                    this.descriptorSetLayout);
        } else if (!sharcEffective && this.sharc != null) {
            RealtimeSharc previous = this.sharc;
            this.sharc = null;
            this.context.defer(previous);
        }
        VulkanBuffer candidate = this.wavefront;
        boolean replaces = candidate == null || candidate.size() != requiredBytes;
        if (replaces) {
            candidate = this.context.createBuffer(
                    requiredBytes,
                    VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                            | VK12.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT
                            | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    false,
                    "Prime realtime wavefront slots");
        }
        if (this.bindings != null
                && this.bindings.matches(
                        stableRadiance,
                        signals,
                        candidate.handle(),
                        this.sharc == null ? 0L : this.sharc.frameConstants().handle())) {
            return;
        }
        OutputBindings replacement;
        try {
            replacement = OutputBindings.create(
                    this.context,
                    this.descriptorSetLayout,
                    stableRadiance,
                    signals,
                    candidate,
                    queueOffset(width, height),
                    this.sharc == null ? null : this.sharc.frameConstants());
        } catch (RuntimeException exception) {
            if (replaces) {
                candidate.destroy();
            }
            throw exception;
        }
        OutputBindings previousBindings = this.bindings;
        VulkanBuffer previousWavefront = this.wavefront;
        this.bindings = replacement;
        this.wavefront = candidate;
        if (previousBindings != null) {
            this.context.defer(previousBindings);
        }
        if (replaces && previousWavefront != null) {
            this.context.defer(previousWavefront);
        }
    }

    public long prepareFrame(
            VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization,
            RealtimeFramePlan plan,
            TerrainScene.ResidentSceneView scene,
            long textureRevision) {
        if (this.pendingFrame != null) {
            throw new IllegalStateException("Realtime pipeline already has a pending frame");
        }
        long backendToken = this.backend.prepareFrame(commandBuffer, initialization);
        RealtimeSharcDiagnostics.Capture diagnostics = null;
        RealtimeSharc.Prepared prepared = null;
        try {
            diagnostics = this.sharcDiagnostics.prepare(
                    commandBuffer,
                    plan.rendererDiagnostics(),
                    this.sharc != null);
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
            if (backendToken != 0L) {
                this.backend.abandon(backendToken);
            }
            throw exception;
        }
        if (this.sharc == null && diagnostics == null) {
            return backendToken;
        }
        long token = this.nextFrameToken++;
        if (token == 0L) token = this.nextFrameToken++;
        this.pendingFrame = new PendingFrame(
                token, backendToken, this.sharc, prepared, diagnostics);
        return token;
    }

    @Override
    public boolean sharcEffective() {
        return this.sharc != null;
    }

    @Override
    public SharcDiagnosticsSnapshot sharcDiagnostics() {
        return this.sharcDiagnostics.latest();
    }

    public void submitted(long token) {
        PendingFrame pending = this.pendingFrame;
        if (pending == null) {
            this.backend.submitted(token);
            return;
        }
        if (pending.token != token) {
            throw new IllegalStateException("Realtime pipeline frame token mismatch");
        }
        this.pendingFrame = null;
        this.backend.submitted(pending.backendToken);
        if (pending.owner != null) {
            pending.owner.submitted(pending.sharcFrame);
        }
        this.sharcDiagnostics.submitted(pending.diagnostics);
    }

    public void abandon(long token) {
        PendingFrame pending = this.pendingFrame;
        if (pending == null) {
            this.backend.abandon(token);
            return;
        }
        if (pending.token != token) {
            throw new IllegalStateException("Realtime pipeline frame token mismatch");
        }
        this.pendingFrame = null;
        if (pending.backendToken != 0L) {
            this.backend.abandon(pending.backendToken);
        }
        this.sharcDiagnostics.abandon(pending.diagnostics);
    }

    /** Releases descriptor bindings and wavefront backing after the device has become idle. */
    public void releaseSizedResourcesAfterIdle() {
        if (this.bindings != null) {
            this.bindings.destroy();
            this.bindings = null;
        }
        if (this.wavefront != null) {
            this.wavefront.destroy();
            this.wavefront = null;
        }
        if (this.sharc != null) {
            this.sharc.destroy();
            this.sharc = null;
        }
    }

    public void trace(
            VkCommandBuffer commandBuffer,
            IntegratorFrameInput input,
            TerrainScene.ResidentSceneView scene) {
        int width = input.width();
        int height = input.height();
        if (this.wavefront == null
                || this.wavefront.size() != wavefrontBytes(width, height)) {
            throw new IllegalStateException("Realtime wavefront extent mismatch");
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
                        width,
                        height,
                        this.sharcDiagnostics,
                        diagnostics);
                activeProgram = this.sharc.queryProgram();
            } else {
                this.sharcDiagnostics.recordReferenceQueryStart(
                        commandBuffer, diagnostics);
            }
            this.bind(commandBuffer, stack, pushConstants, activeProgram);
            long commandOffset = queueCommandOffset(width, height);
            this.initializeQueues(commandBuffer, stack, commandOffset);
            this.trace(
                    commandBuffer,
                    stack,
                    activeProgram,
                    width,
                    height,
                    RealtimeWavefrontGroups.HEAD);
            this.wavefrontResourceBarrier(
                    commandBuffer, stack, this.bindings.allImages);
            int dispatchCount = dispatchCount(input.wavefrontRounds());
            this.lastRecordedPassCount = dispatchCount;
            this.traceIndirect(
                    commandBuffer,
                    stack,
                    activeProgram,
                    RealtimeWavefrontGroups.PRIMARY_AREA,
                    commandOffset,
                    ShaderAbi.WAVEFRONT_AREA_QUEUE);
            this.wavefrontResourceBarrier(
                    commandBuffer, stack, this.bindings.primaryDirectImages);
            this.traceIndirect(
                    commandBuffer,
                    stack,
                    activeProgram,
                    RealtimeWavefrontGroups.PRIMARY_SUN,
                    commandOffset,
                    ShaderAbi.WAVEFRONT_AREA_QUEUE);
            this.wavefrontResourceBarrier(
                    commandBuffer, stack, this.bindings.primaryDirectImages);
            this.traceIndirect(
                    commandBuffer,
                    stack,
                    activeProgram,
                    RealtimeWavefrontGroups.PRIMARY,
                    commandOffset,
                    ShaderAbi.WAVEFRONT_SHADE_QUEUE);
            this.wavefrontResourceBarrier(
                    commandBuffer, stack, this.bindings.allImages);
            int traceQueue = ShaderAbi.WAVEFRONT_TRACE_QUEUE_0;
            for (int round = 0; round < input.wavefrontRounds(); round++) {
                this.recordRound(
                        commandBuffer,
                        stack,
                        activeProgram,
                        commandOffset,
                        traceQueue);
                traceQueue = traceQueue == ShaderAbi.WAVEFRONT_TRACE_QUEUE_0
                        ? ShaderAbi.WAVEFRONT_TRACE_QUEUE_1
                        : ShaderAbi.WAVEFRONT_TRACE_QUEUE_0;
            }
            this.traceIndirect(
                    commandBuffer,
                    stack,
                    activeProgram,
                    RealtimeWavefrontGroups.TRANSPARENT_RESOLVE,
                    commandOffset,
                    ShaderAbi.WAVEFRONT_TRANSPARENT_RESOLVE_QUEUE);
            this.trace(
                    commandBuffer,
                    stack,
                    activeProgram,
                    width,
                    height,
                    RealtimeWavefrontGroups.RESOLVE);
            this.sharcDiagnostics.finish(commandBuffer, diagnostics);
            this.lastRecordedPassCount = this.sharc == null
                    ? dispatchCount
                    : dispatchCount + 2;
        }
    }

    private void recordRound(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            TraceProgram activeProgram,
            long commandOffset,
            int traceQueue) {
        this.traceIndirect(
                commandBuffer,
                stack,
                activeProgram,
                RealtimeWavefrontGroups.step(traceQueue),
                commandOffset,
                traceQueue);
        this.wavefrontBarrier(commandBuffer, stack);
        this.traceIndirect(
                commandBuffer,
                stack,
                activeProgram,
                RealtimeWavefrontGroups.area(),
                commandOffset,
                ShaderAbi.WAVEFRONT_AREA_QUEUE);
        this.wavefrontResourceBarrier(
                commandBuffer, stack, this.bindings.directRadianceImages);
        this.traceIndirect(
                commandBuffer,
                stack,
                activeProgram,
                RealtimeWavefrontGroups.shade(traceQueue),
                commandOffset,
                ShaderAbi.WAVEFRONT_SHADE_QUEUE);
        this.traceIndirect(
                commandBuffer,
                stack,
                activeProgram,
                RealtimeWavefrontGroups.transparentShade(traceQueue),
                commandOffset,
                ShaderAbi.WAVEFRONT_TRANSPARENT_SHADE_QUEUE);
        this.wavefrontResourceBarrier(
                commandBuffer, stack, this.bindings.allImages);
        this.traceIndirect(
                commandBuffer,
                stack,
                activeProgram,
                RealtimeWavefrontGroups.sun(),
                commandOffset,
                ShaderAbi.WAVEFRONT_SUN_QUEUE);
        this.wavefrontResourceBarrier(
                commandBuffer, stack, this.bindings.directRadianceImages);
    }

    private void bind(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            ByteBuffer pushConstants,
            TraceProgram activeProgram) {
        if (this.bindings == null) {
            throw new IllegalStateException("Realtime descriptors have not been initialized");
        }
        VK12.vkCmdBindPipeline(
                commandBuffer,
                KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,
                activeProgram.pipeline);
        VK12.vkCmdBindDescriptorSets(
                commandBuffer,
                KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,
                this.pipelineLayout,
                0,
                stack.longs(this.backend.bindings().descriptorSet(), this.bindings.descriptorSet),
                null);
        VK12.vkCmdPushConstants(
                commandBuffer,
                this.pipelineLayout,
                TracePipelineLayouts.ALL_RT_STAGES,
                0,
                pushConstants);
    }

    private void trace(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            TraceProgram activeProgram,
            int width,
            int height,
            int group) {
        WavefrontCommands.trace(
                commandBuffer, stack, activeProgram, width, height, group);
    }

    private void traceIndirect(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            TraceProgram activeProgram,
            int group,
            long commandOffset,
            int commandQueue) {
        WavefrontCommands.traceIndirect(
                commandBuffer,
                stack,
                activeProgram,
                this.wavefront,
                group,
                commandOffset,
                commandQueue,
                ShaderAbi.WAVEFRONT_QUEUE_COMMAND_STRIDE);
    }

    private void initializeQueues(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            long commandOffset) {
        WavefrontCommands.initializeQueues(
                commandBuffer,
                stack,
                this.wavefront,
                commandOffset,
                ShaderAbi.WAVEFRONT_QUEUE_COUNT,
                ShaderAbi.WAVEFRONT_QUEUE_COMMAND_STRIDE);
    }

    private void wavefrontBarrier(VkCommandBuffer commandBuffer, MemoryStack stack) {
        WavefrontCommands.wavefrontBarrier(commandBuffer, stack, this.wavefront);
    }

    private void wavefrontResourceBarrier(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            long[] images) {
        VulkanSync.resourceBarrier(
                commandBuffer,
                stack,
                this.wavefront,
                images,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK12.VK_ACCESS_SHADER_WRITE_BIT,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                        | VK12.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,
                VK12.VK_ACCESS_SHADER_READ_BIT
                        | VK12.VK_ACCESS_SHADER_WRITE_BIT
                        | VK12.VK_ACCESS_INDIRECT_COMMAND_READ_BIT);
    }

    static long wavefrontBytes(int width, int height) {
        return WAVEFRONT_LAYOUT.wavefrontBytes(width, height);
    }

    static int raygenModule(int group) {
        return RealtimeWavefrontGroups.MODULES[group];
    }

    static int raygenControl(int group) {
        return RealtimeWavefrontGroups.CONTROLS[group];
    }

    static long queueOffset(int width, int height) {
        return WAVEFRONT_LAYOUT.queueOffset(width, height);
    }

    static long queueBytes(int width, int height) {
        return WAVEFRONT_LAYOUT.queueBytes(width, height);
    }

    static long queueCommandOffset(int width, int height) {
        return WAVEFRONT_LAYOUT.queueCommandOffset(width, height);
    }

    static void validateRanges(int width, int height, long maximumRange) {
        WAVEFRONT_LAYOUT.validateRanges(width, height, maximumRange);
    }

    static void validateDispatch(int width, int height, int maximumInvocations) {
        WAVEFRONT_LAYOUT.validateDispatch(width, height, maximumInvocations);
    }

    private static long createDescriptorSetLayout(
            VulkanContext context, MemoryStack stack) {
        VkDescriptorSetLayoutBinding.Buffer bindings =
                VkDescriptorSetLayoutBinding.calloc(DESCRIPTOR_BINDING_COUNT, stack);
        int cursor = 0;
        int[] imageBindings = imageBindings();
        for (int binding : imageBindings) {
            bindings.get(cursor++)
                    .binding(binding)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        }
        bindings.get(cursor++)
                .binding(ShaderAbi.DESCRIPTOR_WAVEFRONT_PATHS)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        bindings.get(cursor++)
                .binding(ShaderAbi.DESCRIPTOR_WAVEFRONT_QUEUE)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        bindings.get(cursor)
                .binding(ShaderAbi.DESCRIPTOR_SHARC_FRAME)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                        | VK12.VK_SHADER_STAGE_COMPUTE_BIT);
        VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .pBindings(bindings);
        LongBuffer pointer = stack.mallocLong(1);
        VulkanContext.check(
                VK12.vkCreateDescriptorSetLayout(
                        context.vkDevice(), info, null, pointer),
                "create realtime trace descriptor layout");
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
        if (!this.destroyed) {
            this.destroyed = true;
            if (this.bindings != null) {
                this.bindings.destroy();
                this.bindings = null;
            }
            if (this.wavefront != null) {
                this.wavefront.destroy();
                this.wavefront = null;
            }
            if (this.sharc != null) {
                this.sharc.destroy();
                this.sharc = null;
            }
            this.sharcDiagnostics.destroy();
            this.program.destroy();
            VK12.vkDestroyPipelineLayout(this.context.vkDevice(), this.pipelineLayout, null);
            VK12.vkDestroyDescriptorSetLayout(
                    this.context.vkDevice(), this.descriptorSetLayout, null);
        }
    }

    private static final class OutputBindings implements Destroyable {
        private final VulkanContext context;
        private final long descriptorPool;
        private final long descriptorSet;
        private final long stableRadiance;
        private final long[] images;
        private final long[] allImages;
        private final long[] primaryDirectImages;
        private final long[] directRadianceImages;
        private final long wavefront;
        private final long sharcFrame;
        private boolean destroyed;

        private OutputBindings(
                VulkanContext context,
                long descriptorPool,
                long descriptorSet,
                long stableRadiance,
                long[] images,
                long[] allImages,
                long wavefront,
                long sharcFrame) {
            this.context = context;
            this.descriptorPool = descriptorPool;
            this.descriptorSet = descriptorSet;
            this.stableRadiance = stableRadiance;
            this.images = images.clone();
            this.allImages = allImages.clone();
            this.primaryDirectImages = select(
                    this.allImages, 1, 2, 20, 21);
            this.directRadianceImages = select(
                    this.allImages, 0, 1, 2, 11, 12);
            this.wavefront = wavefront;
            this.sharcFrame = sharcFrame;
        }

        private static OutputBindings create(
                VulkanContext context,
                long layout,
                VulkanImage stableRadiance,
                RawWavefrontFrame signals,
                VulkanBuffer wavefront,
                long queueOffset,
                VulkanBuffer sharcFrame) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                int poolSizeCount = sharcFrame == null ? 2 : 3;
                VkDescriptorPoolSize.Buffer sizes =
                        VkDescriptorPoolSize.calloc(poolSizeCount, stack);
                sizes.get(0)
                        .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(STORAGE_IMAGE_DESCRIPTOR_COUNT);
                sizes.get(1)
                        .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .descriptorCount(2);
                if (sharcFrame != null) {
                    sizes.get(2)
                            .type(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                            .descriptorCount(1);
                }
                VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                        .sType$Default()
                        .maxSets(1)
                        .pPoolSizes(sizes);
                LongBuffer poolPointer = stack.mallocLong(1);
                VulkanContext.check(
                        VK12.vkCreateDescriptorPool(
                                context.vkDevice(), poolInfo, null, poolPointer),
                        "create realtime trace descriptor pool");
                long pool = poolPointer.get(0);
                try {
                    VkDescriptorSetAllocateInfo allocation =
                            VkDescriptorSetAllocateInfo.calloc(stack)
                                    .sType$Default()
                                    .descriptorPool(pool)
                                    .pSetLayouts(stack.longs(layout));
                    LongBuffer setPointer = stack.mallocLong(1);
                    VulkanContext.check(
                            VK12.vkAllocateDescriptorSets(
                                    context.vkDevice(), allocation, setPointer),
                            "allocate realtime trace descriptor set");
                    long set = setPointer.get(0);
                    VulkanImage[] images = outputImages(stableRadiance, signals);
                    long[] views = new long[images.length];
                    long[] imageHandles = new long[images.length];
                    VkDescriptorImageInfo.Buffer imageInfos =
                            VkDescriptorImageInfo.calloc(images.length, stack);
                    for (int index = 0; index < images.length; index++) {
                        views[index] = images[index].view();
                        imageHandles[index] = images[index].image();
                        imageInfos.get(index)
                                .imageView(views[index])
                                .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                    }
                    int bufferInfoCount = sharcFrame == null ? 2 : 3;
                    VkDescriptorBufferInfo.Buffer bufferInfos =
                            VkDescriptorBufferInfo.calloc(bufferInfoCount, stack);
                    bufferInfos.get(0)
                            .buffer(wavefront.handle())
                            .offset(0L)
                            .range(queueOffset);
                    bufferInfos.get(1)
                            .buffer(wavefront.handle())
                            .offset(queueOffset)
                            .range(wavefront.size() - queueOffset);
                    if (sharcFrame != null) {
                        bufferInfos.get(2)
                                .buffer(sharcFrame.handle())
                                .offset(0L)
                                .range(ShaderAbi.SHARC_FRAME_CONSTANT_SIZE);
                    }
                    int writeCount = DESCRIPTOR_BINDING_COUNT
                            - (sharcFrame == null ? 1 : 0);
                    VkWriteDescriptorSet.Buffer writes =
                            VkWriteDescriptorSet.calloc(writeCount, stack);
                    int[] imageBindings = imageBindings();
                    for (int index = 0; index < imageBindings.length; index++) {
                        writes.get(index)
                                .sType$Default()
                                .dstSet(set)
                                .dstBinding(imageBindings[index])
                                .descriptorCount(1)
                                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                                .pImageInfo(VkDescriptorImageInfo.create(
                                        imageInfos.get(index).address(), 1));
                    }
                    writes.get(imageBindings.length)
                            .sType$Default()
                            .dstSet(set)
                            .dstBinding(ShaderAbi.DESCRIPTOR_WAVEFRONT_PATHS)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                            .pBufferInfo(VkDescriptorBufferInfo.create(
                                    bufferInfos.get(0).address(), 1));
                    writes.get(imageBindings.length + 1)
                            .sType$Default()
                            .dstSet(set)
                            .dstBinding(ShaderAbi.DESCRIPTOR_WAVEFRONT_QUEUE)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                            .pBufferInfo(VkDescriptorBufferInfo.create(
                                    bufferInfos.get(1).address(), 1));
                    if (sharcFrame != null) {
                        writes.get(imageBindings.length + 2)
                                .sType$Default()
                                .dstSet(set)
                                .dstBinding(ShaderAbi.DESCRIPTOR_SHARC_FRAME)
                                .descriptorCount(1)
                                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                                .pBufferInfo(VkDescriptorBufferInfo.create(
                                        bufferInfos.get(2).address(), 1));
                    }
                    VK12.vkUpdateDescriptorSets(context.vkDevice(), writes, null);
                    return new OutputBindings(
                            context,
                            pool,
                            set,
                            stableRadiance.view(),
                            views,
                            imageHandles,
                            wavefront.handle(),
                            sharcFrame == null ? 0L : sharcFrame.handle());
                } catch (RuntimeException exception) {
                    VK12.vkDestroyDescriptorPool(context.vkDevice(), pool, null);
                    throw exception;
                }
            }
        }

        private boolean matches(
                VulkanImage candidateStableRadiance,
                RawWavefrontFrame signals,
                long candidateWavefront,
                long candidateSharcFrame) {
            if (this.stableRadiance != candidateStableRadiance.view()
                    || this.wavefront != candidateWavefront
                    || this.sharcFrame != candidateSharcFrame) {
                return false;
            }
            VulkanImage[] candidates = outputImages(candidateStableRadiance, signals);
            if (this.images.length != candidates.length) {
                return false;
            }
            for (int index = 0; index < candidates.length; index++) {
                if (this.images[index] != candidates[index].view()) {
                    return false;
                }
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

        private static long[] select(long[] values, int... indices) {
            long[] selected = new long[indices.length];
            for (int index = 0; index < indices.length; index++) {
                selected[index] = values[indices[index]];
            }
            return selected;
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
