package dev.prime.render;

import org.joml.Vector3f;
import org.joml.Vector4f;

/** Detects camera changes for which temporal reprojection has no useful overlapping history. */
public final class CameraDiscontinuity {
    static final double TELEPORT_DISTANCE = 32.0;
    static final float MINIMUM_FORWARD_COSINE = 0.5F;
    static final float MAXIMUM_FOV_SCALE_CHANGE = 0.25F;

    private CameraDiscontinuity() {
    }

    public static boolean isCut(FrameCamera previous, FrameCamera current) {
        if (previous == null || current == null) {
            return true;
        }
        double dx = current.renderX() - previous.renderX();
        double dy = current.renderY() - previous.renderY();
        double dz = current.renderZ() - previous.renderZ();
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        if (!Double.isFinite(distanceSquared)
                || distanceSquared > TELEPORT_DISTANCE * TELEPORT_DISTANCE) {
            return true;
        }

        Vector3f previousForward = forward(previous);
        Vector3f currentForward = forward(current);
        if (previousForward == null
                || currentForward == null
                || previousForward.dot(currentForward) < MINIMUM_FORWARD_COSINE) {
            return true;
        }

        return scaleChanged(tanHalfFovX(previous), tanHalfFovX(current))
                || scaleChanged(tanHalfFovY(previous), tanHalfFovY(current));
    }

    private static Vector3f forward(FrameCamera camera) {
        Vector4f near = camera.inverseViewProjection().transform(new Vector4f(0.0F, 0.0F, 1.0F, 1.0F));
        Vector4f far = camera.inverseViewProjection().transform(new Vector4f(0.0F, 0.0F, 0.0F, 1.0F));
        if (!near.isFinite() || !far.isFinite() || Math.abs(near.w) < 1.0e-20F || Math.abs(far.w) < 1.0e-20F) {
            return null;
        }
        Vector3f direction = new Vector3f(
                far.x / far.w - near.x / near.w,
                far.y / far.w - near.y / near.w,
                far.z / far.w - near.z / near.w);
        return direction.isFinite() && direction.lengthSquared() > 1.0e-20F
                ? direction.normalize()
                : null;
    }

    private static float tanHalfFovX(FrameCamera camera) {
        return Math.abs(1.0F / camera.projection().m00());
    }

    private static float tanHalfFovY(FrameCamera camera) {
        return Math.abs(1.0F / camera.projection().m11());
    }

    private static boolean scaleChanged(float previous, float current) {
        if (!Float.isFinite(previous) || !Float.isFinite(current) || previous <= 0.0F || current <= 0.0F) {
            return true;
        }
        return Math.abs(current / previous - 1.0F) > MAXIMUM_FOV_SCALE_CHANGE;
    }
}
