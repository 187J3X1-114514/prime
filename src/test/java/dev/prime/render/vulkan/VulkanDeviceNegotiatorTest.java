package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.WavefrontShaderPermutation;
import dev.prime.render.shader.ShaderAbi;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK11;

final class VulkanDeviceNegotiatorTest {
    @Test
    void wavefrontShaderPermutationUsesScalarFallbackWithoutSer() {
        assertEquals(".rgen.spv",
                WavefrontShaderPermutation.suffix(false, false));
        assertEquals(".rgen.spv",
                WavefrontShaderPermutation.suffix(false, true));
        assertEquals(".rgen.spv",
                WavefrontShaderPermutation.suffix(true, false));
        assertEquals("_ser.rgen.spv",
                WavefrontShaderPermutation.suffix(true, true));
    }

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

    @Test
    void sceneTextureDescriptorLimitsAreUnsignedAndCoverBothDescriptorClasses() {
        int required = ShaderAbi.SCENE_TEXTURE_COUNT + 4;

        assertTrue(VulkanDeviceNegotiator.supportsSceneTextureDescriptors(
                -1, -1, -1, -1));
        assertTrue(VulkanDeviceNegotiator.supportsSceneTextureDescriptors(
                required, required, required, required));
        assertFalse(VulkanDeviceNegotiator.supportsSceneTextureDescriptors(
                required - 1, required, required, required));
        assertFalse(VulkanDeviceNegotiator.supportsSceneTextureDescriptors(
                required, required - 1, required, required));
        assertFalse(VulkanDeviceNegotiator.supportsSceneTextureDescriptors(
                required, required, required - 1, required));
        assertFalse(VulkanDeviceNegotiator.supportsSceneTextureDescriptors(
                required, required, required, required - 1));
    }

    @Test
    void sharcRequiresNativeFp16StorageAndBufferInt64Atomics() {
        assertTrue(VulkanDeviceNegotiator.supportsSharc(true, true, true));
        assertFalse(VulkanDeviceNegotiator.supportsSharc(false, true, true));
        assertFalse(VulkanDeviceNegotiator.supportsSharc(true, false, true));
        assertFalse(VulkanDeviceNegotiator.supportsSharc(true, true, false));
        assertEquals(
                "Missing SHARC Vulkan capabilities: storageBuffer16BitAccess, "
                        + "shaderBufferInt64Atomics",
                VulkanDeviceNegotiator.sharcUnavailableReason(false, true, false));
    }
}
