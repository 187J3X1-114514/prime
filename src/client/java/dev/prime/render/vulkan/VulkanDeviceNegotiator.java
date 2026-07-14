package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRDeferredHostOperations;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructurePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelineFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelinePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkFormatProperties;

public final class VulkanDeviceNegotiator {
    private static final List<String> REQUIRED_EXTENSIONS = List.of(
            KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,
            KHRRayTracingPipeline.VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME,
            KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME);

    private static final VulkanPNextStruct ACCELERATION_STRUCTURE_FEATURES = new VulkanPNextStruct(
            KHRAccelerationStructure.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR,
            VkPhysicalDeviceAccelerationStructureFeaturesKHR.SIZEOF);
    private static final VulkanPNextStruct RAY_TRACING_PIPELINE_FEATURES = new VulkanPNextStruct(
            KHRRayTracingPipeline.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR,
            VkPhysicalDeviceRayTracingPipelineFeaturesKHR.SIZEOF);

    private static final VulkanFeature SHADER_INT64 = new VulkanFeature(
            VulkanBackend.VK10_FEATURES_STRUCT,
            "shaderInt64",
            VkPhysicalDeviceFeatures.SHADERINT64);
    private static final VulkanFeature STORAGE_IMAGE_EXTENDED_FORMATS = new VulkanFeature(
            VulkanBackend.VK10_FEATURES_STRUCT,
            "shaderStorageImageExtendedFormats",
            VkPhysicalDeviceFeatures.SHADERSTORAGEIMAGEEXTENDEDFORMATS);
    private static final VulkanFeature STORAGE_IMAGE_READ_WITHOUT_FORMAT = new VulkanFeature(
            VulkanBackend.VK10_FEATURES_STRUCT,
            "shaderStorageImageReadWithoutFormat",
            VkPhysicalDeviceFeatures.SHADERSTORAGEIMAGEREADWITHOUTFORMAT);
    private static final VulkanFeature STORAGE_IMAGE_WRITE_WITHOUT_FORMAT = new VulkanFeature(
            VulkanBackend.VK10_FEATURES_STRUCT,
            "shaderStorageImageWriteWithoutFormat",
            VkPhysicalDeviceFeatures.SHADERSTORAGEIMAGEWRITEWITHOUTFORMAT);
    private static final VulkanFeature BUFFER_DEVICE_ADDRESS = new VulkanFeature(
            VulkanBackend.VK12_FEATURES_STRUCT,
            "bufferDeviceAddress",
            VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESS);
    private static final VulkanFeature ACCELERATION_STRUCTURE = new VulkanFeature(
            ACCELERATION_STRUCTURE_FEATURES,
            "accelerationStructure",
            VkPhysicalDeviceAccelerationStructureFeaturesKHR.ACCELERATIONSTRUCTURE);
    private static final VulkanFeature RAY_TRACING_PIPELINE = new VulkanFeature(
            RAY_TRACING_PIPELINE_FEATURES,
            "rayTracingPipeline",
            VkPhysicalDeviceRayTracingPipelineFeaturesKHR.RAYTRACINGPIPELINE);

    private VulkanDeviceNegotiator() {
    }

    public static VulkanCapabilities negotiate(
            VulkanPhysicalDevice physicalDevice,
            Collection<String> enabledExtensions,
            Set<VulkanFeature> enabledFeatures) {
        String deviceName = physicalDevice.deviceName();
        List<String> missing = new ArrayList<>();
        for (String extension : REQUIRED_EXTENSIONS) {
            if (!physicalDevice.hasDeviceExtension(extension)) {
                missing.add(extension);
            }
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceFeatures2 features = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            VkPhysicalDeviceVulkan12Features vulkan12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
            VkPhysicalDeviceAccelerationStructureFeaturesKHR acceleration =
                    VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack).sType$Default();
            VkPhysicalDeviceRayTracingPipelineFeaturesKHR rayTracing =
                    VkPhysicalDeviceRayTracingPipelineFeaturesKHR.calloc(stack).sType$Default();
            features.pNext(vulkan12.address());
            vulkan12.pNext(acceleration.address());
            acceleration.pNext(rayTracing.address());
            VK12.vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice(), features);

            if (!features.features().shaderInt64()) {
                missing.add("shaderInt64");
            }
            if (!features.features().shaderStorageImageExtendedFormats()) {
                missing.add("shaderStorageImageExtendedFormats");
            }
            if (!features.features().shaderStorageImageReadWithoutFormat()) {
                missing.add("shaderStorageImageReadWithoutFormat");
            }
            if (!features.features().shaderStorageImageWriteWithoutFormat()) {
                missing.add("shaderStorageImageWriteWithoutFormat");
            }
            if (!vulkan12.bufferDeviceAddress()) {
                missing.add("bufferDeviceAddress");
            }
            if (!acceleration.accelerationStructure()) {
                missing.add("accelerationStructure");
            }
            if (!rayTracing.rayTracingPipeline()) {
                missing.add("rayTracingPipeline");
            }

            if (!missing.isEmpty()) {
                return VulkanCapabilities.unavailable(deviceName, "Missing Vulkan capabilities: " + String.join(", ", missing));
            }

            VkPhysicalDeviceProperties2 properties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
            VkPhysicalDeviceRayTracingPipelinePropertiesKHR rayProperties =
                    VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc(stack).sType$Default();
            VkPhysicalDeviceAccelerationStructurePropertiesKHR accelerationProperties =
                    VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
            properties.pNext(rayProperties.address());
            rayProperties.pNext(accelerationProperties.address());
            VK12.vkGetPhysicalDeviceProperties2(physicalDevice.vkPhysicalDevice(), properties);

            if (rayProperties.maxRayRecursionDepth() < 1) {
                return VulkanCapabilities.unavailable(deviceName, "Ray tracing recursion depth 1 is not supported");
            }
            VkFormatProperties accumulationFormat = VkFormatProperties.calloc(stack);
            VK12.vkGetPhysicalDeviceFormatProperties(
                    physicalDevice.vkPhysicalDevice(),
                    VK12.VK_FORMAT_R32G32B32A32_SFLOAT,
                    accumulationFormat);
            if ((accumulationFormat.optimalTilingFeatures() & VK12.VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) == 0) {
                return VulkanCapabilities.unavailable(
                        deviceName,
                        "RGBA32F storage images required for path accumulation are not supported");
            }
            VkFormatProperties nrdNormalFormat = VkFormatProperties.calloc(stack);
            VK12.vkGetPhysicalDeviceFormatProperties(
                    physicalDevice.vkPhysicalDevice(),
                    VK12.VK_FORMAT_A2B10G10R10_UNORM_PACK32,
                    nrdNormalFormat);
            int requiredNrdNormalFeatures =
                    VK12.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK12.VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT;
            if ((nrdNormalFormat.optimalTilingFeatures() & requiredNrdNormalFeatures)
                    != requiredNrdNormalFeatures) {
                return VulkanCapabilities.unavailable(
                        deviceName,
                        "A2B10G10R10 UNORM sampled/storage images required for NRD normals are not supported");
            }
            if (rayProperties.shaderGroupHandleSize() <= 0
                    || rayProperties.maxShaderGroupStride() == 0
                    || rayProperties.maxRayDispatchInvocationCount() == 0
                    || !isPositivePowerOfTwo(rayProperties.shaderGroupHandleAlignment())
                    || !isPositivePowerOfTwo(rayProperties.shaderGroupBaseAlignment())
                    || !isPositivePowerOfTwo(accelerationProperties.minAccelerationStructureScratchOffsetAlignment())) {
                return VulkanCapabilities.unavailable(deviceName, "Vulkan reported invalid ray tracing alignment properties");
            }

            enabledExtensions.addAll(REQUIRED_EXTENSIONS);
            enabledFeatures.add(SHADER_INT64);
            enabledFeatures.add(STORAGE_IMAGE_EXTENDED_FORMATS);
            enabledFeatures.add(STORAGE_IMAGE_READ_WITHOUT_FORMAT);
            enabledFeatures.add(STORAGE_IMAGE_WRITE_WITHOUT_FORMAT);
            enabledFeatures.add(BUFFER_DEVICE_ADDRESS);
            enabledFeatures.add(ACCELERATION_STRUCTURE);
            enabledFeatures.add(RAY_TRACING_PIPELINE);

            return new VulkanCapabilities(
                    true,
                    deviceName,
                    "",
                    rayProperties.shaderGroupHandleSize(),
                    rayProperties.shaderGroupHandleAlignment(),
                    rayProperties.shaderGroupBaseAlignment(),
                    rayProperties.maxShaderGroupStride(),
                    rayProperties.maxRayDispatchInvocationCount(),
                    rayProperties.maxRayRecursionDepth(),
                    accelerationProperties.minAccelerationStructureScratchOffsetAlignment());
        }
    }

    private static boolean isPositivePowerOfTwo(int value) {
        return value > 0 && (value & value - 1) == 0;
    }
}
