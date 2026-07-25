package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void labPbrSpecularAtlasIsVisibleToSurfaceAndEmissionConsumers() {
        int expected = KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR;

        assertEquals(expected, RayTracingPipeline.LABPBR_SPECULAR_STAGES);
    }

    @Test
    void runtimeTransmissionLookupHasTheExpectedShape() {
        assertEquals(44, BsdfLookupTable.WIDTH);
        assertEquals(32, BsdfLookupTable.HEIGHT);
        assertEquals(159, BsdfLookupTable.DEPTH);
        assertEquals(44 * 32 * 159 * 4 * Short.BYTES, BsdfLookupTable.BYTE_SIZE);
    }

    @Test
    void rayTracingShaderGroupsHaveTheExpectedShape() {
        assertEquals(2, RayTracingPipeline.MISS_GROUP_COUNT);
        assertEquals(6, RayTracingPipeline.HIT_GROUP_COUNT);
        assertEquals(5, RayTracingPipeline.RAYGEN_GROUP_COUNT);
        assertEquals(7, RayTracingPipeline.WAVEFRONT_STEP_DISPATCH_COUNT);
        assertEquals(10, RayTracingPipeline.REALTIME_DISPATCH_COUNT);
    }

    @Test
    void fixedWavefrontSlotsScaleExactlyWithTheRenderExtent() {
        assertEquals(
                928_972_800L,
                RayTracingPipeline.wavefrontPathBytes(3840, 2160));
        assertThrows(
                IllegalArgumentException.class,
                () -> RayTracingPipeline.wavefrontPathBytes(0, 2160));
        assertThrows(
                ArithmeticException.class,
                () -> RayTracingPipeline.wavefrontPathBytes(Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test
    void deferredCompilationClampsDriverConcurrencyToTheHost() {
        assertEquals(1, RayTracingPipeline.deferredWorkerCount(0, 32));
        assertEquals(2, RayTracingPipeline.deferredWorkerCount(2, 32));
        assertEquals(8, RayTracingPipeline.deferredWorkerCount(32, 8));
        assertEquals(32, RayTracingPipeline.deferredWorkerCount(-1, 32));
        assertEquals(1, RayTracingPipeline.deferredWorkerCount(8, 0));
    }
}
