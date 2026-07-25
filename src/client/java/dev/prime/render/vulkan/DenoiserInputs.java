package dev.prime.render.vulkan;

/** Physical path signals and primary-surface guides used as wavefront intermediate storage. */
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

    default VulkanImage reflectionNoisyDiffuse() { return noisyDiffuse(); }

    default VulkanImage reflectionNoisySpecular() { return noisySpecular(); }

    default VulkanImage reflectionNormalRoughness() { return normalRoughness(); }

    default VulkanImage reflectionMaterial() { return material(); }

    default VulkanImage reflectionSpecularMaterial() { return specularMaterial(); }

    default VulkanImage reflectionPosition() { return primaryPosition(); }

    default VulkanImage reflectionDiffuseDirection() { return diffuseDirection(); }

    default VulkanImage reflectionSpecularDirection() { return specularDirection(); }

    default VulkanImage displayPosition() { return primaryPosition(); }

    default boolean usesShInputs() {
        return false;
    }

    /** Raygen writes this alias only while raw numerical diagnostics are selected. */
    default VulkanImage rawNumericalDiagnostic() {
        return noisyDiffuse();
    }
}
