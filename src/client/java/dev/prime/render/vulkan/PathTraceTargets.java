package dev.prime.render.vulkan;

/** Images written directly by the realtime ray-generation shader. */
public interface PathTraceTargets {
    VulkanImage noisyDiffuse();

    VulkanImage noisySpecular();

    VulkanImage normalRoughness();

    VulkanImage viewZ();

    VulkanImage motion();

    VulkanImage material();

    VulkanImage specularMaterial();

    VulkanImage primaryPosition();

    VulkanImage sunLighting();

    VulkanImage sunPenumbra();

    VulkanImage transparencyGuide();
}
