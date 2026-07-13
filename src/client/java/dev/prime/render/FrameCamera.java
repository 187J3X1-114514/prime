package dev.prime.render;

import org.joml.Matrix4f;

/**
 * Camera data shared by the Minecraft integration and the ray-generation shader.
 *
 * <p>Prime keeps Minecraft world axes unchanged: +X, +Y, and +Z in terrain data remain +X, +Y,
 * and +Z in the acceleration structures. {@code inverseViewProjection} is the inverse of
 * {@code projection * worldToViewRotation}; camera translation is supplied separately because
 * both the TLAS and ray origin are relative to the current render origin. JOML and GLSL both
 * consume the matrix in column-major, column-vector form, so no transpose is applied.
 *
 * <p>Minecraft 26.2's Vulkan projection uses an NDC depth range of [0, 1] with reversed-Z:
 * near is 1 and far is 0. Its internal Vulkan render target uses a positive-height viewport and
 * is flipped once during presentation, so internal image row zero maps to NDC y = -1. Ray
 * generation must preserve both conventions.
 */
public record FrameCamera(Matrix4f inverseViewProjection, double x, double y, double z) {
}
