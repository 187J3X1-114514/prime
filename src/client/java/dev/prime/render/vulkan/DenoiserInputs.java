package dev.prime.render.vulkan;

/** Physical path signals and primary-surface guides written by the realtime ray-generation shader. */
public interface DenoiserInputs {
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
