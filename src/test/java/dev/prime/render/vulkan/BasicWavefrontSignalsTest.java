package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK12;

final class BasicWavefrontSignalsTest {
    @Test
    void imageUsageAndSynchronizationFollowTheOutputBoundary() {
        long rayTracing =
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
        long compute = VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;

        assertEquals(
                VK12.VK_IMAGE_USAGE_STORAGE_BIT,
                BasicWavefrontSignals.imageUsage(false));
        assertEquals(
                VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT,
                BasicWavefrontSignals.imageUsage(true));
        assertEquals(rayTracing, BasicWavefrontSignals.destinationStages(false, false));
        assertEquals(
                rayTracing | compute,
                BasicWavefrontSignals.destinationStages(true, false));
        assertEquals(compute, BasicWavefrontSignals.destinationStages(true, true));
        assertThrows(
                IllegalArgumentException.class,
                () -> BasicWavefrontSignals.destinationStages(false, true));
    }
}
