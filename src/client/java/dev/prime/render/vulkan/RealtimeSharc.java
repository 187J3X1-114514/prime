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
    private static final int ACCUMULATION_FRAMES = 64;
    private static final int STALE_FRAMES = 128;
    private static final float LOGARITHM_BASE = 2.0F;
    private static final float SCENE_SCALE = 50.0F;
    private static final String RESOLVE_SHADER = "/prime/shaders/sharc_resolve.comp.spv";

    private final VulkanContext context;
    private final long rayTracingPipelineLayout;
    private final TraceProgram updateProgram;
    private final TraceProgram queryProgram;
    private final long resolvePipelineLayout;
    private final long resolvePipeline;
    private final VulkanBuffer hashEntries;
    private final VulkanBuffer accumulation;
    private final VulkanBuffer resolved;
    private final VulkanBuffer frameConstants;
    private Accepted accepted;
    private boolean destroyed;

    RealtimeSharc(
            VulkanContext context,
            long rayTracingPipelineLayout,
            long sharedSetLayout,
            long integratorSetLayout) {
        this.context = Objects.requireNonNull(context, "context");
        this.rayTracingPipelineLayout = rayTracingPipelineLayout;
        VulkanBuffer hash = null;
        VulkanBuffer accumulationBuffer = null;
        VulkanBuffer resolvedBuffer = null;
        VulkanBuffer constants = null;
        TraceProgram update = null;
        TraceProgram query = null;
        long computeLayout = 0L;
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
            update = TraceProgram.create(
                    context,
                    rayTracingPipelineLayout,
                    new String[] {"/prime/shaders/sharc_update.rgen.spv"},
                    new int[] {0},
                    new int[] {0},
                    "Prime SHARC update pipeline",
                    "Prime SHARC update shader binding table");
            String prefix = "/prime/shaders/realtime_wavefront_";
            query = TraceProgram.create(
                    context,
                    rayTracingPipelineLayout,
                    new String[] {
                        prefix + "head" + suffix,
                        prefix + "step" + suffix,
                        "/prime/shaders/realtime_wavefront_sharc_area" + suffix,
                        "/prime/shaders/realtime_wavefront_sharc_shade" + suffix,
                        prefix + "resolve" + suffix
                    },
                    RealtimeWavefrontGroups.MODULES,
                    new int[] {0, 1, 257, 4, 260, 2, 258, 3},
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
                        "create Prime SHARC resolve pipeline layout");
                computeLayout = pointer.get(0);
                long shader = VulkanShaderModules.create(context, stack, RESOLVE_SHADER);
                try {
                    VkPipelineShaderStageCreateInfo stage =
                            VkPipelineShaderStageCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .stage(VK12.VK_SHADER_STAGE_COMPUTE_BIT)
                                    .module(shader)
                                    .pName(stack.UTF8("main"));
                    VkComputePipelineCreateInfo.Buffer info =
                            VkComputePipelineCreateInfo.calloc(1, stack);
                    info.get(0).sType$Default().stage(stage).layout(computeLayout);
                    pointer.clear();
                    VulkanContext.check(
                            VK12.vkCreateComputePipelines(
                                    context.vkDevice(), 0L, info, null, pointer),
                            "create Prime SHARC resolve pipeline");
                    computePipeline = pointer.get(0);
                } finally {
                    VK12.vkDestroyShaderModule(context.vkDevice(), shader, null);
                }
            }
            this.hashEntries = hash;
            this.accumulation = accumulationBuffer;
            this.resolved = resolvedBuffer;
            this.frameConstants = constants;
            this.updateProgram = update;
            this.queryProgram = query;
            this.resolvePipelineLayout = computeLayout;
            this.resolvePipeline = computePipeline;
        } catch (RuntimeException exception) {
            if (computePipeline != 0L) {
                VK12.vkDestroyPipeline(context.vkDevice(), computePipeline, null);
            }
            if (computeLayout != 0L) {
                VK12.vkDestroyPipelineLayout(context.vkDevice(), computeLayout, null);
            }
            if (query != null) query.destroy();
            if (update != null) update.destroy();
            if (constants != null) constants.destroy();
            if (resolvedBuffer != null) resolvedBuffer.destroy();
            if (accumulationBuffer != null) accumulationBuffer.destroy();
            if (hash != null) hash.destroy();
            throw exception;
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
        return CACHE_BYTES + this.frameConstants.size();
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
            VulkanSync.memoryBarrier(
                    commandBuffer,
                    stack,
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                            | VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
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
            VK12.vkCmdUpdateBuffer(
                    commandBuffer, this.frameConstants.handle(), 0L, constants);
            VulkanSync.memoryBarrier(
                    commandBuffer,
                    stack,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                            | VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
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

    void recordUpdateAndResolve(
            VkCommandBuffer commandBuffer,
            long sharedDescriptorSet,
            long integratorDescriptorSet,
            ByteBuffer pushConstants,
            int width,
            int height,
            RealtimeSharcDiagnostics diagnostics,
            RealtimeSharcDiagnostics.Capture capture) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            diagnostics.recordUpdateStart(commandBuffer, capture);
            VK12.vkCmdBindPipeline(
                    commandBuffer,
                    KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,
                    this.updateProgram.pipeline);
            VK12.vkCmdBindDescriptorSets(
                    commandBuffer,
                    KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,
                    this.rayTracingPipelineLayout,
                    0,
                    stack.longs(sharedDescriptorSet, integratorDescriptorSet),
                    null);
            VK12.vkCmdPushConstants(
                    commandBuffer,
                    this.rayTracingPipelineLayout,
                    TracePipelineLayouts.ALL_RT_STAGES,
                    0,
                    pushConstants);
            WavefrontCommands.trace(
                    commandBuffer,
                    stack,
                    this.updateProgram,
                    (width + 4) / 5,
                    (height + 4) / 5,
                    0);
            diagnostics.recordUpdateEnd(commandBuffer, capture);
            VulkanSync.memoryBarrier(
                    commandBuffer,
                    stack,
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
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
            VulkanSync.memoryBarrier(
                    commandBuffer,
                    stack,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_ACCESS_SHADER_WRITE_BIT,
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
        }
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
        this.resolved.destroy();
        this.accumulation.destroy();
        this.hashEntries.destroy();
        VK12.vkDestroyPipeline(this.context.vkDevice(), this.resolvePipeline, null);
        VK12.vkDestroyPipelineLayout(
                this.context.vkDevice(), this.resolvePipelineLayout, null);
        this.queryProgram.destroy();
        this.updateProgram.destroy();
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
