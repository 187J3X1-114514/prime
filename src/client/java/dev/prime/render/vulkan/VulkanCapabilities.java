package dev.prime.render.vulkan;

import dev.prime.render.WavefrontShaderPermutation;

public record VulkanCapabilities(
        boolean available,
        String deviceName,
        String unavailableReason,
        int shaderGroupHandleSize,
        int shaderGroupHandleAlignment,
        int shaderGroupBaseAlignment,
        int maxShaderGroupStride,
        int maxRayDispatchInvocationCount,
        int maxRayRecursionDepth,
        long maxAccelerationStructurePrimitiveCount,
        long maxAccelerationStructureInstanceCount,
        int accelerationStructureScratchAlignment,
        boolean wavefrontSubgroupSupported,
        boolean invocationReorderSupported,
        boolean opacityMicromapSupported,
        int maxOpacity2StateSubdivisionLevel,
        int maxOpacity4StateSubdivisionLevel,
        boolean fsrFp16Supported) {

    public String wavefrontShaderSuffix() {
        return WavefrontShaderPermutation.suffix(
                this.wavefrontSubgroupSupported,
                this.invocationReorderSupported);
    }

    public static VulkanCapabilities unavailable(String deviceName, String reason) {
        return new VulkanCapabilities(
                false,
                deviceName,
                reason,
                0,
                0,
                0,
                0,
                0,
                0,
                0L,
                0L,
                0,
                false,
                false,
                false,
                0,
                0,
                false);
    }
}
