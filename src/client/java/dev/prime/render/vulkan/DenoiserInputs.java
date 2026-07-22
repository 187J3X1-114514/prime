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

    /** Raygen writes these only when {@link #usesShInputs()} is true. */
    default VulkanImage diffuseDirection() {
        return noisyDiffuse();
    }

    default VulkanImage specularDirection() {
        return noisySpecular();
    }

    default boolean usesShInputs() {
        return false;
    }

    /** Raygen writes this alias only while raw numerical diagnostics are selected. */
    default VulkanImage rawNumericalDiagnostic() {
        return noisyDiffuse();
    }
}
