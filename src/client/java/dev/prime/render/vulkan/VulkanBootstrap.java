package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public final class VulkanBootstrap {
    // Vulkan construction publishes these transitions; the client thread consumes one coherent
    // pair after device creation instead of racing two independently visible fields.
    private static volatile Snapshot snapshot = new Snapshot(
            VulkanCapabilities.unavailable(
                    "unknown", "Vulkan device negotiation has not run"),
            null);

    private VulkanBootstrap() {
    }

    public static void recordNegotiation(VulkanCapabilities capabilities) {
        Snapshot current = snapshot;
        snapshot = new Snapshot(capabilities, current.device());
    }

    public static void attachDevice(VulkanDevice vulkanDevice) {
        Snapshot current = snapshot;
        snapshot = new Snapshot(current.capabilities(), vulkanDevice);
    }

    public static Snapshot snapshot() {
        return snapshot;
    }

    public record Snapshot(VulkanCapabilities capabilities, @Nullable VulkanDevice device) {
        public Snapshot {
            capabilities = Objects.requireNonNull(capabilities, "capabilities");
        }
    }
}
