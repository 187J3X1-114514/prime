package dev.prime.render.vulkan.nrd;

import dev.prime.render.vulkan.VulkanImage;
import java.util.Objects;

/** Lifetime-stable images after NRD reconstruction and Prime composition. */
public record NrdCompositeFrame(
        VulkanImage color,
        VulkanImage fsrReactive,
        VulkanImage fsrTransparencyComposition) {
    public NrdCompositeFrame {
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(fsrReactive, "fsrReactive");
        Objects.requireNonNull(
                fsrTransparencyComposition,
                "fsrTransparencyComposition");
    }
}
