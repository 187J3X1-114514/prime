package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.jspecify.annotations.Nullable;

public final class VulkanBootstrap {
    private static volatile VulkanCapabilities negotiatedCapabilities =
            VulkanCapabilities.unavailable("unknown", "Vulkan device negotiation has not run");
    private static volatile @Nullable VulkanDevice device;

    private VulkanBootstrap() {
    }

    public static void recordNegotiation(VulkanCapabilities capabilities) {
        negotiatedCapabilities = capabilities;
    }

    public static void attachDevice(VulkanDevice vulkanDevice) {
        device = vulkanDevice;
    }

    public static VulkanCapabilities capabilities() {
        return negotiatedCapabilities;
    }

    public static @Nullable VulkanDevice device() {
        return device;
    }
}
