package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK11;

final class VulkanDeviceNegotiatorTest {
    @Test
    void wavefrontSubgroupsRequireRaygenBallotAndBasicOperations() {
        int raygen = KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR;
        int required = VK11.VK_SUBGROUP_FEATURE_BASIC_BIT
                | VK11.VK_SUBGROUP_FEATURE_BALLOT_BIT;

        assertTrue(VulkanDeviceNegotiator.supportsWavefrontSubgroups(
                raygen, required));
        assertFalse(VulkanDeviceNegotiator.supportsWavefrontSubgroups(
                0, required));
        assertFalse(VulkanDeviceNegotiator.supportsWavefrontSubgroups(
                raygen, VK11.VK_SUBGROUP_FEATURE_BASIC_BIT));
    }
}
