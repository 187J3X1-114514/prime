package dev.prime.render.vulkan.replay;

import dev.prime.render.replay.RenderPlatformFingerprint;
import dev.prime.render.vulkan.VulkanCapabilities;
import dev.prime.render.vulkan.VulkanContext;
import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

/** Captures Vulkan device attributes at the replay boundary without coupling the base context to replay. */
public final class PlatformFingerprintProbe {
    private PlatformFingerprintProbe() {
    }

    public static RenderPlatformFingerprint capture(VulkanContext context) {
        Objects.requireNonNull(context, "context");
        VulkanCapabilities capabilities = context.capabilities();
        if (!capabilities.available()) {
            throw new IllegalArgumentException(
                    "Cannot fingerprint an unavailable Vulkan device");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            VK12.vkGetPhysicalDeviceProperties(
                    context.vkDevice().getPhysicalDevice(), properties);
            byte[] uuid = new byte[VK10.VK_UUID_SIZE];
            ByteBuffer source = properties.pipelineCacheUUID();
            for (int index = 0; index < uuid.length; index++) {
                uuid[index] = source.get(index);
            }
            return new RenderPlatformFingerprint(
                    properties.deviceNameString(),
                    properties.vendorID(),
                    properties.deviceID(),
                    properties.deviceType(),
                    properties.driverVersion(),
                    properties.apiVersion(),
                    HexFormat.of().formatHex(uuid),
                    capabilities.shaderGroupHandleSize(),
                    capabilities.shaderGroupHandleAlignment(),
                    capabilities.shaderGroupBaseAlignment(),
                    capabilities.maxShaderGroupStride(),
                    capabilities.maxRayDispatchInvocationCount(),
                    capabilities.maxRayRecursionDepth(),
                    capabilities.maxAccelerationStructurePrimitiveCount(),
                    capabilities.maxAccelerationStructureInstanceCount(),
                    capabilities.accelerationStructureScratchAlignment(),
                    capabilities.wavefrontSubgroupSupported(),
                    capabilities.invocationReorderSupported(),
                    capabilities.opacityMicromapSupported(),
                    capabilities.maxOpacityMicromapSubdivisionLevel(),
                    capabilities.fsrFp16Supported());
        }
    }
}
