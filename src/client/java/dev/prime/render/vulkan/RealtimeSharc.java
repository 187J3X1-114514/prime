package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.IntegratorFrameInput;
import dev.prime.render.LightingSettings;
import dev.prime.render.MaterialSettings;
import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.vulkan.terrain.TerrainScene;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;

/** Device-local SHARC cache, programs, and submission-transactional temporal identity. */
final class RealtimeSharc implements Destroyable {
    static final int CAPACITY = 1 << 22;
    static final long HASH_BYTES = (long) CAPACITY * Long.BYTES;
    static final long ACCUMULATION_BYTES = (long) CAPACITY * 32L;
    static final long RESOLVED_BYTES = (long) CAPACITY * 24L;
    static final long CACHE_BYTES = HASH_BYTES + ACCUMULATION_BYTES + RESOLVED_BYTES;
    static final int TRAINING_MAX_EVENTS = 4;
    static final int TRAINING_EVENT_BYTES = 80;
    private static final int ACCUMULATION_FRAMES = 64;
    private static final int STALE_FRAMES = 128;
    private static final float LOGARITHM_BASE = 2.0F;
    private static final float SCENE_SCALE = 50.0F;
    private static final String INTEGRATED_UPDATE_SHADER =
            "/prime/shaders/sharc_integrated_update.comp.spv";
    private static final String RESOLVE_SHADER = "/prime/shaders/sharc_resolve.comp.spv";

    private final VulkanContext context;
    private final TraceProgram queryProgram;
    private final long resolvePipelineLayout;
    private final long integratedUpdatePipeline;
    private final long resolvePipeline;
    private final VulkanBuffer hashEntries;
    private final VulkanBuffer accumulation;
    private final VulkanBuffer resolved;
    private final VulkanBuffer frameConstants;
    private VulkanBuffer trainingEvents;
    private int trainingWidth;
    private int trainingHeight;
    private Accepted accepted;
    private boolean destroyed;

    RealtimeSharc(
            VulkanContext context,
            long rayTracingPipelineLayout,
            long sharedSetLayout,
            long integratorSetLayout) {
        this.context = Objects.requireNonNull(context, "context");
        VulkanBuffer hash = null;
        VulkanBuffer accumulationBuffer = null;
        VulkanBuffer resolvedBuffer = null;
        VulkanBuffer constants = null;
        TraceProgram query = null;
        long computeLayout = 0L;
        long integratedUpdate = 0L;
        long computePipeline = 0L;
        try {
            hash = cacheBuffer(HASH_BYTES, "Prime SHARC hash entries");
            accumulationBuffer = cacheBuffer(
                    ACCUMULATION_BYTES, "Prime SHARC accumulation");
            resolvedBuffer = cacheBuffer(RESOLVED_BYTES, "Prime SHARC resolved radiance");
            constants = this.context.createBuffer(
                    ShaderAbi.SHARC_FRAME_CONSTANT_SIZE,
                    VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT
                            | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    false,
                    "Prime SHARC frame constants");
            String suffix = context.capabilities().wavefrontShaderSuffix();
            String prefix = "/prime/shaders/realtime_wavefront_";
            query = TraceProgram.create(
                    context,
                    rayTracingPipelineLayout,
                    new String[] {
                        prefix + "head" + suffix,
                        prefix + "step" + suffix,
                        prefix + "primary" + suffix,
                        prefix + "primary_area" + suffix,
                        prefix + "primary_sun" + suffix,
                        "/prime/shaders/realtime_wavefront_sharc_light" + suffix,
                        "/prime/shaders/realtime_wavefront_sharc_shade" + suffix,
                        prefix + "transparent_shade" + suffix,
                        prefix + "resolve" + suffix,
                        prefix + "transparent_resolve" + suffix
                    },
                    RealtimeWavefrontGroups.MODULES,
                    RealtimeWavefrontGroups.CONTROLS,
                    "Prime SHARC query pipeline",
                    "Prime SHARC query shader binding table");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer pointer = stack.mallocLong(1);
                VulkanContext.check(
                        VK12.vkCreatePipelineLayout(
                                context.vkDevice(),
                                VkPipelineLayoutCreateInfo.calloc(stack)
                                        .sType$Default()
                                        .pSetLayouts(stack.longs(
                                                sharedSetLayout, integratorSetLayout)),
                                null,
                                pointer),
                        "create Prime SHARC compute pipeline layout");
                computeLayout = pointer.get(0);
                integratedUpdate = createComputePipeline(
                        context,
                        stack,
                        computeLayout,
                        INTEGRATED_UPDATE_SHADER,
                        "Prime integrated SHARC update pipeline");
                computePipeline = createComputePipeline(
                        context,
                        stack,
                        computeLayout,
                        RESOLVE_SHADER,
                        "Prime SHARC resolve pipeline");
            }
            this.hashEntries = hash;
            this.accumulation = accumulationBuffer;
            this.resolved = resolvedBuffer;
            this.frameConstants = constants;
            this.queryProgram = query;
            this.resolvePipelineLayout = computeLayout;
            this.integratedUpdatePipeline = integratedUpdate;
            this.resolvePipeline = computePipeline;
        } catch (RuntimeException exception) {
            if (computePipeline != 0L) {
                VK12.vkDestroyPipeline(context.vkDevice(), computePipeline, null);
            }
            if (integratedUpdate != 0L) {
                VK12.vkDestroyPipeline(context.vkDevice(), integratedUpdate, null);
            }
            if (computeLayout != 0L) {
                VK12.vkDestroyPipelineLayout(context.vkDevice(), computeLayout, null);
            }
            if (query != null) query.destroy();
            if (constants != null) constants.destroy();
            if (resolvedBuffer != null) resolvedBuffer.destroy();
            if (accumulationBuffer != null) accumulationBuffer.destroy();
            if (hash != null) hash.destroy();
            throw exception;
        }
    }

    private static long createComputePipeline(
            VulkanContext context,
            MemoryStack stack,
            long layout,
            String shaderResource,
            String label) {
        long shader = VulkanShaderModules.create(context, stack, shaderResource);
        try {
            VkPipelineShaderStageCreateInfo stage =
                    VkPipelineShaderStageCreateInfo.calloc(stack)
                            .sType$Default()
                            .stage(VK12.VK_SHADER_STAGE_COMPUTE_BIT)
                            .module(shader)
                            .pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer info =
                    VkComputePipelineCreateInfo.calloc(1, stack);
            info.get(0).sType$Default().stage(stage).layout(layout);
            LongBuffer pointer = stack.mallocLong(1);
            context.createComputePipeline(info, pointer, label);
            return pointer.get(0);
        } finally {
            VK12.vkDestroyShaderModule(context.vkDevice(), shader, null);
        }
    }

    private VulkanBuffer cacheBuffer(long bytes, String label) {
        return this.context.createBuffer(
                bytes,
                VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                        | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                false,
                label);
    }

    VulkanBuffer frameConstants() {
        return this.frameConstants;
    }

    TraceProgram queryProgram() {
        return this.queryProgram;
    }

    long resourceBytes() {
        return CACHE_BYTES
                + this.frameConstants.size()
                + (this.trainingEvents == null ? 0L : this.trainingEvents.size());
    }

    void ensureTrainingExtent(int width, int height) {
        int candidateWidth = trainingWidth(width);
        int candidateHeight = trainingHeight(height);
        long bytes = trainingBytes(width, height);
        VulkanBuffer current = this.trainingEvents;
        if (current == null || current.size() != bytes) {
            VulkanBuffer replacement = this.context.createBuffer(
                    bytes,
                    VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                            | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    false,
                    "Prime integrated SHARC training events");
            this.trainingEvents = replacement;
            if (current != null) {
                this.context.defer(current);
            }
        }
        this.trainingWidth = candidateWidth;
        this.trainingHeight = candidateHeight;
    }

    static long trainingBytes(int width, int height) {
        long paths = Math.multiplyExact(
                (long) trainingWidth(width), trainingHeight(height));
        return Math.multiplyExact(
                Math.multiplyExact(paths, TRAINING_MAX_EVENTS),
                TRAINING_EVENT_BYTES);
    }

    private static int trainingWidth(int width) {
        if (width <= 0) {
            throw new IllegalArgumentException("SHARC width must be positive");
        }
        return Math.floorDiv(width - 1, 5) + 1;
    }

    private static int trainingHeight(int height) {
        if (height <= 0) {
            throw new IllegalArgumentException("SHARC height must be positive");
        }
        return Math.floorDiv(height - 1, 5) + 1;
    }

    Prepared prepare(
            VkCommandBuffer commandBuffer,
            IntegratorFrameInput input,
            TerrainScene.ResidentSceneView scene,
            long textureRevision,
            boolean reconstructionReset,
            long diagnosticsAddress,
            boolean diagnosticsEnabled) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(scene, "scene");
        if (this.trainingEvents == null
                || this.trainingWidth != trainingWidth(input.width())
                || this.trainingHeight != trainingHeight(input.height())) {
            throw new IllegalStateException("Integrated SHARC training extent mismatch");
        }
        float cameraX = (float) (input.camera().renderX() - scene.originX());
        float cameraY = (float) (input.camera().renderY() - scene.originY());
        float cameraZ = (float) (input.camera().renderZ() - scene.originZ());
        boolean clear = requiresClear(
                this.accepted,
                scene.originX(),
                scene.originY(),
                scene.originZ(),
                scene.resetRevision(),
                textureRevision,
                input.material());
        int frameIndex = this.accepted == null ? 0 : this.accepted.frameIndex + 1;
        float previousX = clear || reconstructionReset ? cameraX : this.accepted.cameraX;
        float previousY = clear || reconstructionReset ? cameraY : this.accepted.cameraY;
        float previousZ = clear || reconstructionReset ? cameraZ : this.accepted.cameraZ;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanSync.bufferBarrier(
                    commandBuffer,
                    stack,
                    this.frameConstants,
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                            | VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_ACCESS_SHADER_READ_BIT,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
            VulkanSync.bufferBarriers(
                    commandBuffer,
                    stack,
                    this.cacheBuffers(),
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                            | VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT,
                    clear
                            ? VK12.VK_PIPELINE_STAGE_TRANSFER_BIT
                            : KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                                    | VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    clear
                            ? VK12.VK_ACCESS_TRANSFER_WRITE_BIT
                            : VK12.VK_ACCESS_SHADER_READ_BIT
                                    | VK12.VK_ACCESS_SHADER_WRITE_BIT);
            VulkanSync.bufferBarrier(
                    commandBuffer,
                    stack,
                    this.trainingEvents,
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                            | VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
            VK12.vkCmdFillBuffer(
                    commandBuffer,
                    this.trainingEvents.handle(),
                    0L,
                    this.trainingEvents.size(),
                    0);
            if (clear) {
                VK12.vkCmdFillBuffer(
                        commandBuffer, this.hashEntries.handle(), 0L, HASH_BYTES, 0);
                VK12.vkCmdFillBuffer(
                        commandBuffer, this.accumulation.handle(), 0L, ACCUMULATION_BYTES, 0);
                VK12.vkCmdFillBuffer(
                        commandBuffer, this.resolved.handle(), 0L, RESOLVED_BYTES, 0);
            }
            ByteBuffer constants = stack.calloc(ShaderAbi.SHARC_FRAME_CONSTANT_SIZE)
                    .order(ByteOrder.nativeOrder());
            constants.putLong(
                    ShaderAbi.SHARC_FRAME_HASH_ENTRIES_ADDRESS_OFFSET,
                    this.hashEntries.deviceAddress());
            constants.putLong(
                    ShaderAbi.SHARC_FRAME_ACCUMULATION_ADDRESS_OFFSET,
                    this.accumulation.deviceAddress());
            constants.putLong(
                    ShaderAbi.SHARC_FRAME_RESOLVED_ADDRESS_OFFSET,
                    this.resolved.deviceAddress());
            constants.putLong(
                    ShaderAbi.SHARC_FRAME_DIAGNOSTICS_ADDRESS_OFFSET,
                    diagnosticsAddress);
            putVec3(constants, ShaderAbi.SHARC_FRAME_CAMERA_POSITION_OFFSET,
                    cameraX, cameraY, cameraZ);
            constants.putFloat(
                    ShaderAbi.SHARC_FRAME_LOGARITHM_BASE_OFFSET, LOGARITHM_BASE);
            putVec3(constants, ShaderAbi.SHARC_FRAME_PREVIOUS_CAMERA_POSITION_OFFSET,
                    previousX, previousY, previousZ);
            constants.putFloat(ShaderAbi.SHARC_FRAME_SCENE_SCALE_OFFSET, SCENE_SCALE);
            constants.putFloat(ShaderAbi.SHARC_FRAME_LEVEL_BIAS_OFFSET, 0.0F);
            constants.putFloat(
                    ShaderAbi.SHARC_FRAME_RADIANCE_SCALE_OFFSET,
                    radianceScale(input.lighting()));
            constants.putInt(ShaderAbi.SHARC_FRAME_CAPACITY_OFFSET, CAPACITY);
            constants.putInt(ShaderAbi.SHARC_FRAME_FRAME_INDEX_OFFSET, frameIndex);
            constants.putInt(
                    ShaderAbi.SHARC_FRAME_ACCUMULATION_FRAMES_OFFSET,
                    ACCUMULATION_FRAMES);
            constants.putInt(ShaderAbi.SHARC_FRAME_STALE_FRAMES_OFFSET, STALE_FRAMES);
            constants.putInt(
                    ShaderAbi.SHARC_FRAME_UPDATE_PHASE_OFFSET,
                    updatePhase(frameIndex));
            constants.putInt(
                    ShaderAbi.SHARC_FRAME_FLAGS_OFFSET,
                    diagnosticsEnabled ? 1 : 0);
            constants.putLong(
                    ShaderAbi.SHARC_FRAME_TRAINING_EVENTS_ADDRESS_OFFSET,
                    this.trainingEvents.deviceAddress());
            constants.putInt(
                    ShaderAbi.SHARC_FRAME_TRAINING_WIDTH_OFFSET,
                    this.trainingWidth);
            constants.putInt(
                    ShaderAbi.SHARC_FRAME_TRAINING_HEIGHT_OFFSET,
                    this.trainingHeight);
            VK12.vkCmdUpdateBuffer(
                    commandBuffer, this.frameConstants.handle(), 0L, constants);
            VulkanSync.bufferBarrier(
                    commandBuffer,
                    stack,
                    this.frameConstants,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                            | VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_ACCESS_SHADER_READ_BIT);
            if (clear) {
                VulkanSync.bufferBarriers(
                        commandBuffer,
                        stack,
                        this.cacheBuffers(),
                        VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                        KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                                | VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
            }
            VulkanSync.bufferBarrier(
                    commandBuffer,
                    stack,
                    this.trainingEvents,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    VK12.VK_ACCESS_SHADER_WRITE_BIT);
        }
        Accepted candidate = new Accepted(
                cameraX,
                cameraY,
                cameraZ,
                scene.originX(),
                scene.originY(),
                scene.originZ(),
                scene.resetRevision(),
                textureRevision,
                input.material(),
                frameIndex);
        return new Prepared(candidate);
    }

    void submitted(Prepared prepared) {
        this.accepted = Objects.requireNonNull(prepared, "prepared").candidate;
    }

    void recordIntegratedUpdateAndResolve(
            VkCommandBuffer commandBuffer,
            long integratorDescriptorSet,
            int width,
            int height,
            RealtimeSharcDiagnostics diagnostics,
            RealtimeSharcDiagnostics.Capture capture) {
        if (this.trainingEvents == null
                || this.trainingWidth != trainingWidth(width)
                || this.trainingHeight != trainingHeight(height)) {
            throw new IllegalStateException("Integrated SHARC training extent mismatch");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanSync.bufferBarrier(
                    commandBuffer,
                    stack,
                    this.trainingEvents,
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    VK12.VK_ACCESS_SHADER_WRITE_BIT,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_ACCESS_SHADER_READ_BIT);
            diagnostics.recordUpdateStart(commandBuffer, capture);
            VK12.vkCmdBindPipeline(
                    commandBuffer,
                    VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                    this.integratedUpdatePipeline);
            VK12.vkCmdBindDescriptorSets(
                    commandBuffer,
                    VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                    this.resolvePipelineLayout,
                    1,
                    stack.longs(integratorDescriptorSet),
                    null);
            int pathCount = Math.multiplyExact(this.trainingWidth, this.trainingHeight);
            VK12.vkCmdDispatch(commandBuffer, Math.floorDiv(pathCount - 1, 64) + 1, 1, 1);
            diagnostics.recordUpdateEnd(commandBuffer, capture);
            VulkanSync.bufferBarriers(
                    commandBuffer,
                    stack,
                    new VulkanBuffer[] {this.hashEntries, this.accumulation},
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
            VK12.vkCmdBindPipeline(
                    commandBuffer, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, this.resolvePipeline);
            VK12.vkCmdBindDescriptorSets(
                    commandBuffer,
                    VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                    this.resolvePipelineLayout,
                    1,
                    stack.longs(integratorDescriptorSet),
                    null);
            VK12.vkCmdDispatch(commandBuffer, (CAPACITY + 255) / 256, 1, 1);
            diagnostics.recordResolveEnd(commandBuffer, capture);
            VulkanSync.bufferBarriers(
                    commandBuffer,
                    stack,
                    new VulkanBuffer[] {this.hashEntries, this.resolved},
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_ACCESS_SHADER_WRITE_BIT,
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    VK12.VK_ACCESS_SHADER_READ_BIT);
        }
    }

    private VulkanBuffer[] cacheBuffers() {
        return new VulkanBuffer[] {
            this.hashEntries,
            this.accumulation,
            this.resolved
        };
    }

    private static void putVec3(
            ByteBuffer buffer, int offset, float x, float y, float z) {
        buffer.putFloat(offset, x);
        buffer.putFloat(offset + Float.BYTES, y);
        buffer.putFloat(offset + 2 * Float.BYTES, z);
    }

    static float radianceScale(LightingSettings.Snapshot lighting) {
        Objects.requireNonNull(lighting, "lighting");
        float maximumLight = Math.max(
                1.0F,
                Math.max(
                        lighting.sunMultiplier(),
                        Math.max(
                                lighting.starMultiplier(),
                                lighting.blockLightMultiplier())));
        return 1000.0F / maximumLight;
    }

    static int updatePhase(int frameIndex) {
        return Integer.remainderUnsigned(frameIndex, 25);
    }

    static boolean requiresClear(
            Accepted accepted,
            int originX,
            int originY,
            int originZ,
            long resetRevision,
            long textureRevision,
            MaterialSettings.Snapshot material) {
        Objects.requireNonNull(material, "material");
        return accepted == null
                || accepted.resetRevision != resetRevision
                || accepted.originX != originX
                || accepted.originY != originY
                || accepted.originZ != originZ
                || accepted.textureRevision != textureRevision
                || !accepted.material.equals(material);
    }

    @Override
    public void destroy() {
        if (this.destroyed) return;
        this.destroyed = true;
        this.frameConstants.destroy();
        if (this.trainingEvents != null) {
            this.trainingEvents.destroy();
        }
        this.resolved.destroy();
        this.accumulation.destroy();
        this.hashEntries.destroy();
        VK12.vkDestroyPipeline(this.context.vkDevice(), this.resolvePipeline, null);
        VK12.vkDestroyPipeline(
                this.context.vkDevice(), this.integratedUpdatePipeline, null);
        VK12.vkDestroyPipelineLayout(
                this.context.vkDevice(), this.resolvePipelineLayout, null);
        this.queryProgram.destroy();
    }

    record Prepared(Accepted candidate) {
        Prepared {
            Objects.requireNonNull(candidate, "candidate");
        }
    }

    record Accepted(
            float cameraX,
            float cameraY,
            float cameraZ,
            int originX,
            int originY,
            int originZ,
            long resetRevision,
            long textureRevision,
            MaterialSettings.Snapshot material,
            int frameIndex) {
        Accepted {
            Objects.requireNonNull(material, "material");
        }
    }
}
