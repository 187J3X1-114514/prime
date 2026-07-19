package dev.prime.render.vulkan;

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
        int accelerationStructureScratchAlignment,
        boolean invocationReorderSupported,
        boolean opacityMicromapSupported,
        int maxOpacityMicromapSubdivisionLevel,
        boolean fsrFp16Supported) {

    public static VulkanCapabilities unavailable(String deviceName, String reason) {
        return new VulkanCapabilities(
                false, deviceName, reason, 0, 0, 0, 0, 0, 0, 0, false, false, 0, false);
    }
}
