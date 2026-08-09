package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.render.IntegratorFrameInput;
import dev.prime.render.RealtimeFramePlan;
import dev.prime.render.vulkan.terrain.TerrainScene;
import java.util.List;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Recording and lifetime boundary for the realtime integrator. */
public interface RealtimeIntegratorPipeline extends Destroyable {
    void ensureDescriptors(
            long tlas,
            VulkanImage stableRadiance,
            VulkanGpuTextureView atlasView,
            VulkanGpuSampler atlasSampler,
            List<TraceBackend.SceneTexture> sceneTextures,
            VulkanImage labPbrNormalAtlas,
            VulkanImage labPbrSpecularAtlas,
            AtmospherePipeline atmosphere,
            RawWavefrontFrame signals,
            boolean sharcRequested);

    long prepareFrame(
            VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization,
            RealtimeFramePlan plan,
            TerrainScene.ResidentSceneView scene,
            long textureRevision);

    void submitted(long token);

    void abandon(long token);

    void trace(
            VkCommandBuffer commandBuffer,
            IntegratorFrameInput input,
            TerrainScene.ResidentSceneView scene);

    int passCount();

    long sizedResourceBytes();

    boolean sharcEffective();

    SharcDiagnosticsSnapshot sharcDiagnostics();

    void releaseSizedResourcesAfterIdle();
}
