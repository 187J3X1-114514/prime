package dev.prime.render;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

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
public record FrameCamera(
        Matrix4f projection,
        Matrix4f viewRotation,
        Matrix4f inverseViewProjection,
        double x,
        double y,
        double z) {
    FrameCamera(Matrix4f inverseViewProjection, double x, double y, double z) {
        this(new Matrix4f(), new Matrix4f(), inverseViewProjection, x, y, z);
    }

    static FrameCamera tryCreate(
            Matrix4fc projection,
            Matrix4fc viewRotation,
            double x,
            double y,
            double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return null;
        }
        Matrix4f projectionCopy = new Matrix4f(projection);
        Matrix4f viewRotationCopy = new Matrix4f(viewRotation);
        Matrix4f viewProjection = new Matrix4f(projectionCopy).mul(viewRotationCopy);
        float determinant = viewProjection.determinant();
        if (!viewProjection.isFinite()
                || !Float.isFinite(determinant)
                || Math.abs(determinant) < 1.0e-20F) {
            return null;
        }
        Matrix4f inverse = viewProjection.invert();
        return inverse.isFinite()
                ? new FrameCamera(projectionCopy, viewRotationCopy, inverse, x, y, z)
                : null;
    }
}
