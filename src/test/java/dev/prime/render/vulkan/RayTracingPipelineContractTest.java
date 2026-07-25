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
        assertEquals(9, RayTracingPipeline.RAYGEN_GROUP_COUNT);
        assertEquals(2, RayTracingPipeline.RAYGEN_MODULE_COUNT);
        assertEquals(5, RayTracingPipeline.RAYGEN_SHADER_STAGE_COUNT);
        assertEquals(11, RayTracingPipeline.WAVEFRONT_STEP_DISPATCH_COUNT);
        assertEquals(15, RayTracingPipeline.REALTIME_DISPATCH_COUNT);
        assertEquals(0, RayTracingPipeline.raygenShaderStage(0));
        assertEquals(1, RayTracingPipeline.raygenShaderStage(1));
        assertEquals(2, RayTracingPipeline.raygenShaderStage(2));
        assertEquals(2, RayTracingPipeline.raygenShaderStage(3));
        assertEquals(2, RayTracingPipeline.raygenShaderStage(4));
        assertEquals(2, RayTracingPipeline.raygenShaderStage(5));
        assertEquals(3, RayTracingPipeline.raygenShaderStage(6));
        assertEquals(3, RayTracingPipeline.raygenShaderStage(7));
        assertEquals(4, RayTracingPipeline.raygenShaderStage(8));
        assertEquals(0, RayTracingPipeline.raygenSpecializationStage(1));
        assertEquals(1, RayTracingPipeline.raygenSpecializationStage(2));
        assertEquals(3, RayTracingPipeline.raygenSpecializationStage(3));
        assertEquals(4, RayTracingPipeline.raygenSpecializationStage(4));
        assertThrows(
                IllegalArgumentException.class,
                () -> RayTracingPipeline.raygenSpecializationStage(0));
        assertEquals(0, RayTracingPipeline.raygenRecordStage(1));
        assertEquals(1, RayTracingPipeline.raygenRecordStage(2));
        assertEquals(257, RayTracingPipeline.raygenRecordStage(3));
        assertEquals(2, RayTracingPipeline.raygenRecordStage(4));
        assertEquals(258, RayTracingPipeline.raygenRecordStage(5));
        assertEquals(3, RayTracingPipeline.raygenRecordStage(6));
        assertEquals(259, RayTracingPipeline.raygenRecordStage(7));
        assertEquals(4, RayTracingPipeline.raygenRecordStage(8));
        assertEquals(2, RayTracingPipeline.wavefrontStepGroup(0));
        assertEquals(3, RayTracingPipeline.wavefrontStepGroup(1));
        assertEquals(4, RayTracingPipeline.wavefrontTransitionGroup(0));
        assertEquals(5, RayTracingPipeline.wavefrontTransitionGroup(1));
        assertEquals(6, RayTracingPipeline.wavefrontTailGroup(0));
        assertEquals(7, RayTracingPipeline.wavefrontTailGroup(1));
    }

    @Test
    void fixedWavefrontSlotsScaleExactlyWithTheRenderExtent() {
        assertEquals(
                2_521_497_632L,
                RayTracingPipeline.wavefrontPathBytes(3840, 2160));
        assertEquals(
                2_388_787_200L,
                RayTracingPipeline.wavefrontQueueOffset(3840, 2160));
        assertEquals(
                132_710_432L,
                RayTracingPipeline.wavefrontQueueBytes(3840, 2160));
        assertThrows(
                IllegalArgumentException.class,
                () -> RayTracingPipeline.wavefrontPathBytes(0, 2160));
        assertThrows(
                ArithmeticException.class,
                () -> RayTracingPipeline.wavefrontPathBytes(Integer.MAX_VALUE, Integer.MAX_VALUE));
        RayTracingPipeline.validateWavefrontRanges(3840, 2160, 0xffff_ffffL);
        assertThrows(
                IllegalStateException.class,
                () -> RayTracingPipeline.validateWavefrontRanges(
                        1920, 1080, 128L * 1024L * 1024L));
        RayTracingPipeline.validateWavefrontDispatch(3840, 2160, 1 << 25);
        assertThrows(
                IllegalStateException.class,
                () -> RayTracingPipeline.validateWavefrontDispatch(
                        3840, 2160, 1 << 23));
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
