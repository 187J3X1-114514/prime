package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.render.IntegratorFrameInput;
import dev.prime.render.RealtimeIntegratorMode;
import dev.prime.render.vulkan.terrain.TerrainScene;
import java.util.List;
import java.util.Objects;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Experimental bounded single-path realtime wavefront integrator. */
public final class LightweightRayTracingPipeline implements RealtimeIntegratorPipeline {
    static final int RAYGEN_GROUP_COUNT = 4;
    static final int RAYGEN_MODULE_COUNT = 3;
    static final int MAXIMUM_DISPATCH_COUNT = 9;
    static final int[] RAYGEN_MODULES = {0, 1, 1, 2};
    static final int[] RAYGEN_CONTROLS = {0, 1, 257, 2};

    private final RealtimeRayTracingPipeline scheduler;

    public LightweightRayTracingPipeline(VulkanContext context, TraceBackend backend) {
        this.scheduler = new RealtimeRayTracingPipeline(
                Objects.requireNonNull(context, "context"),
                Objects.requireNonNull(backend, "backend"),
                true);
    }

    @Override
    public RealtimeIntegratorMode mode() {
        return RealtimeIntegratorMode.LIGHTWEIGHT;
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
            RawWavefrontFrame signals) {
        this.scheduler.ensureDescriptors(
                tlas,
                stableRadiance,
                atlasView,
                atlasSampler,
                sceneTextures,
                labPbrNormalAtlas,
                labPbrSpecularAtlas,
                atmosphere,
                signals);
    }

    @Override
    public long prepareFrame(
            VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization) {
        return this.scheduler.prepareFrame(commandBuffer, initialization);
    }

    @Override
    public void submitted(long token) {
        this.scheduler.submitted(token);
    }

    @Override
    public void abandon(long token) {
        this.scheduler.abandon(token);
    }

    @Override
    public void trace(
            VkCommandBuffer commandBuffer,
            IntegratorFrameInput input,
            TerrainScene.ResidentSceneView scene) {
        this.scheduler.trace(commandBuffer, input, scene);
    }

    @Override
    public int passCount() {
        return this.scheduler.passCount();
    }

    @Override
    public long sizedResourceBytes() {
        return this.scheduler.sizedResourceBytes();
    }

    @Override
    public void releaseSizedResourcesAfterIdle() {
        this.scheduler.releaseSizedResourcesAfterIdle();
    }

    static long wavefrontBytes(int width, int height) {
        return RealtimeRayTracingPipeline.lightweightWavefrontBytes(width, height);
    }

    static int raygenModule(int group) {
        return RAYGEN_MODULES[group];
    }

    static int raygenControl(int group) {
        return RAYGEN_CONTROLS[group];
    }

    static long queueOffset(int width, int height) {
        return RealtimeRayTracingPipeline.lightweightQueueOffset(width, height);
    }

    static long queueBytes(int width, int height) {
        return RealtimeRayTracingPipeline.lightweightQueueBytes(width, height);
    }

    static long queueCommandOffset(int width, int height) {
        return RealtimeRayTracingPipeline.lightweightQueueCommandOffset(width, height);
    }

    @Override
    public void destroy() {
        this.scheduler.destroy();
    }
}
