package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.KHRRayTracingPipeline;

final class RayTracingPipelineContractTest {
    @Test
    void blockAtlasIsVisibleToEveryShaderStageThatSamplesIt() {
        int expected = KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
                | KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR;

        assertEquals(expected, RayTracingPipeline.BLOCK_ATLAS_STAGES);
    }

    @Test
    void shadowRaysUseTheirOwnMinimalPayloadAndMissRecord() throws IOException {
        Path shaderRoot = Path.of(System.getProperty("user.dir"), "shaders");
        String integrator = Files.readString(shaderRoot.resolve("integrator.glsl"));
        String shadowMiss = Files.readString(shaderRoot.resolve("shadow.rmiss"));
        String closestHit = Files.readString(shaderRoot.resolve("world.rchit"));

        assertEquals(2, RayTracingPipeline.MISS_GROUP_COUNT);
        assertTrue(shadowMiss.contains("layout(location = 1) rayPayloadInEXT uint primeShadowOccluded"));
        assertTrue(integrator.contains("gl_RayFlagsSkipClosestHitShaderEXT"));
        assertTrue(integrator.contains("return primeShadowOccluded == 0u"));
        assertFalse(closestHit.contains("PRIME_TRACE_SHADOW"));
    }
}
