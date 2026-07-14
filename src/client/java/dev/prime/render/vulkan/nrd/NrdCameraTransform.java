package dev.prime.render.vulkan.nrd;

import dev.prime.render.FrameCamera;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

/**
 * The single coordinate-system boundary between Minecraft/JOML and NRD.
 *
 * <p>Both APIs store column-major matrices and multiply column vectors. Minecraft's internal
 * Vulkan image, however, maps image row zero to clip {@code y = -1}, while NRD's D3D-style screen
 * helper maps texture {@code y = 0} to clip {@code y = +1}. Prime left-multiplies every projection
 * passed to NRD by {@code diag(1, -1, 1, 1)}. The two flips then cancel, so an NRD screen UV names
 * the same image row as a Prime raygen UV. This is not a presentation flip and must not be moved to
 * the final copy.
 *
 * <p>World positions are camera-relative for floating-point precision. A position relative to the
 * current effective pinhole is transformed into the previous view by adding
 * {@code currentCamera - previousCamera} before applying the previous rotation. The sign follows
 * directly from {@code world = currentRelative + currentCamera}.
 */
final class NrdCameraTransform {
    private NrdCameraTransform() {}

    static Matrix4f projectionForNrd(Matrix4fc minecraftProjection) {
        Matrix4f result = new Matrix4f(minecraftProjection);
        return result
                .m01(-result.m01())
                .m11(-result.m11())
                .m21(-result.m21())
                .m31(-result.m31());
    }

    static Matrix4f currentClipToWorld(FrameCamera current) {
        return projectionForNrd(current.projection())
                .mul(current.viewRotation())
                .invert();
    }

    static Matrix4f previousWorldToView(FrameCamera current, FrameCamera previous) {
        return new Matrix4f(previous.viewRotation()).translate(
                (float) (current.renderX() - previous.renderX()),
                (float) (current.renderY() - previous.renderY()),
                (float) (current.renderZ() - previous.renderZ()));
    }

    static Matrix4f previousWorldToClip(FrameCamera current, FrameCamera previous) {
        return projectionForNrd(previous.projection())
                .mul(previousWorldToView(current, previous));
    }

    /**
     * Projects a current-effective-camera-relative point through the exact transform used by the
     * previous raygen frame. This deliberately bypasses the canonical NRD camera decomposition so
     * the reprojection diagnostic can detect an error in that decomposition instead of repeating
     * it. The exact rendered transform is relative to Minecraft's physical camera, hence the
     * current effective pinhole to previous physical camera translation.
     */
    static Matrix4f previousRenderedWorldToClip(FrameCamera current, FrameCamera previous) {
        return new Matrix4f(previous.inverseViewProjection())
                .invert()
                .translate(
                        (float) (current.renderX() - previous.x()),
                        (float) (current.renderY() - previous.y()),
                        (float) (current.renderZ() - previous.z()));
    }

    static Vector2f screenUv(Matrix4fc worldToClip, Vector3fc position) {
        Vector4f clip = worldToClip.transform(
                new Vector4f(position.x(), position.y(), position.z(), 1.0F));
        float inverseW = 1.0F / clip.w;
        return new Vector2f(
                clip.x * inverseW * 0.5F + 0.5F,
                clip.y * inverseW * -0.5F + 0.5F);
    }
}
