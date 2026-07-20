package dev.prime.render.vulkan.dlss;

import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import dev.prime.PrimeClient;
import dev.prime.render.vulkan.VulkanContext;
import java.nio.IntBuffer;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkExtensionProperties;

/** Session-scoped, fail-safe NGX capability negotiation. */
public final class DlssRrBootstrap {
    private static List<String> instanceExtensions = List.of();
    private static String unavailableReason = "DLSS RR has not been probed";
    private static boolean instanceReady;
    private static boolean deviceReady;
    private static boolean initializationAttempted;

    private DlssRrBootstrap() {}

    public static synchronized void enableRequiredInstanceExtensions(
            Collection<String> enabledExtensions) {
        if (instanceReady) {
            enabledExtensions.addAll(instanceExtensions);
            return;
        }
        if (!DlssRrNative.isSupportedPlatform()) {
            disable("DLSS RR currently supports Windows x86-64 only", null);
            return;
        }
        try {
            List<String> required = DlssRrNative.instanceExtensions();
            Set<String> supported = supportedInstanceExtensions();
            List<String> missing = required.stream()
                    .filter(extension -> !supported.contains(extension))
                    .toList();
            if (!missing.isEmpty()) {
                disable("Missing NGX Vulkan instance extensions: " + String.join(", ", missing), null);
                return;
            }
            instanceExtensions = required;
            enabledExtensions.addAll(required);
            instanceReady = true;
            unavailableReason = "DLSS RR Vulkan device has not been probed";
            PrimeClient.LOGGER.info(
                    "Enabled {} NVIDIA NGX Vulkan instance extension(s)", required.size());
        } catch (RuntimeException | LinkageError exception) {
            disable("Unable to query NVIDIA NGX Vulkan instance requirements", exception);
        }
    }

    public static synchronized void enableRequiredDeviceExtensions(
            VulkanPhysicalDevice physicalDevice, Collection<String> enabledExtensions) {
        if (!instanceReady) {
            return;
        }
        try {
            List<String> required = DlssRrNative.deviceExtensions(
                    physicalDevice.vkPhysicalDevice().getInstance().address(),
                    physicalDevice.vkPhysicalDevice().address());
            List<String> missing = required.stream()
                    // LWJGL already cached the physical device's advertised extension set.
                    // Re-enumerating hundreds of extension properties on MemoryStack can
                    // exhaust its fixed per-thread arena while device negotiation is active.
                    .filter(extension -> !physicalDevice.hasDeviceExtension(extension))
                    .toList();
            if (!missing.isEmpty()) {
                disable("Missing NGX Vulkan device extensions: " + String.join(", ", missing), null);
                return;
            }
            enabledExtensions.addAll(required);
            deviceReady = true;
            unavailableReason = "NVIDIA NGX has not been initialized";
            PrimeClient.LOGGER.info(
                    "Enabled {} NVIDIA NGX Vulkan device extension(s)", required.size());
        } catch (RuntimeException | LinkageError exception) {
            disable("Unable to query NVIDIA NGX Vulkan device requirements", exception);
        }
    }

    public static synchronized Optional<DlssRrNative.Context> initialize(VulkanContext context) {
        if (!deviceReady || initializationAttempted) {
            return Optional.empty();
        }
        initializationAttempted = true;
        try {
            DlssRrNative.Context ngx = DlssRrNative.initialize(context);
            unavailableReason = "";
            PrimeClient.LOGGER.info("NVIDIA NGX DLSS Ray Reconstruction is available");
            return Optional.of(ngx);
        } catch (RuntimeException | LinkageError exception) {
            disable("NVIDIA NGX DLSS RR initialization failed", exception);
            return Optional.empty();
        }
    }

    public static synchronized boolean deviceReady() {
        return deviceReady;
    }

    public static synchronized String unavailableReason() {
        return unavailableReason;
    }

    public static synchronized void failSession(String reason, Throwable cause) {
        disable(reason, cause);
    }

    private static Set<String> supportedInstanceExtensions() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.callocInt(1);
            int result = VK12.vkEnumerateInstanceExtensionProperties((String) null, count, null);
            if (result != VK12.VK_SUCCESS) {
                throw new IllegalStateException(
                        "vkEnumerateInstanceExtensionProperties failed with " + result);
            }
            VkExtensionProperties.Buffer properties = VkExtensionProperties.calloc(count.get(0), stack);
            result = VK12.vkEnumerateInstanceExtensionProperties((String) null, count, properties);
            if (result != VK12.VK_SUCCESS) {
                throw new IllegalStateException(
                        "vkEnumerateInstanceExtensionProperties failed with " + result);
            }
            HashSet<String> supported = new HashSet<>();
            for (int index = 0; index < count.get(0); index++) {
                supported.add(properties.get(index).extensionNameString());
            }
            return Set.copyOf(supported);
        }
    }

    private static void disable(String reason, Throwable cause) {
        instanceReady = false;
        deviceReady = false;
        unavailableReason = reason;
        if (cause == null) {
            PrimeClient.LOGGER.warn("DLSS RR unavailable for this session: {}", reason);
        } else {
            PrimeClient.LOGGER.warn("DLSS RR unavailable for this session: {}", reason, cause);
        }
    }
}
