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
    void runtimeTransmissionLookupMatchesTheImportedBsdfTable() throws IOException {
        assertEquals(32, BsdfLookupTable.RESOLUTION);
        assertEquals(32 * 32 * 32 * 4 * Float.BYTES, BsdfLookupTable.BYTE_SIZE);
        String pipeline = Files.readString(Path.of(
                System.getProperty("user.dir"),
                "src/client/java/dev/prime/render/vulkan/RayTracingPipeline.java"));
        assertTrue(pipeline.contains("ShaderAbi.DESCRIPTOR_TRANSMISSION_GGX_ENERGY"));
        assertTrue(pipeline.contains("bsdfLookup.prepare(commandBuffer)"));
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

    @Test
    void serPermutationReordersOnlySurfaceContinuationsAndKeepsFallback() throws IOException {
        Path root = Path.of(System.getProperty("user.dir"));
        String rayGeneration = Files.readString(root.resolve("shaders/world.rgen"));
        String integrator = Files.readString(root.resolve("shaders/integrator.glsl"))
                .replace("\r\n", "\n");
        String pipeline = Files.readString(root.resolve(
                "src/client/java/dev/prime/render/vulkan/RayTracingPipeline.java"));

        assertTrue(rayGeneration.contains("GL_EXT_shader_invocation_reorder"));
        assertTrue(integrator.contains("hitObjectTraceRayEXT"));
        assertTrue(integrator.contains("reorderThreadEXT(hitObject, coherenceHint, 8u)"));
        assertTrue(integrator.contains("hitObjectExecuteShaderEXT(hitObject, 0)"));
        assertTrue(integrator.contains("#else\n    traceRayEXT"));
        assertEquals(1, countOccurrences(integrator, "hitObjectTraceRayEXT"));
        assertTrue(pipeline.contains("world_ser.rgen.spv"));
        assertTrue(pipeline.contains("world.rgen.spv"));
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
