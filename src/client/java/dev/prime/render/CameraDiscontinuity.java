package dev.prime.render;

import org.joml.Matrix4fc;

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

        float forwardCosine = forwardCosine(previous, current);
        if (!Float.isFinite(forwardCosine) || forwardCosine < MINIMUM_FORWARD_COSINE) {
            return true;
        }

        return scaleChanged(tanHalfFovX(previous), tanHalfFovX(current))
                || scaleChanged(tanHalfFovY(previous), tanHalfFovY(current));
    }

    private static float forwardCosine(FrameCamera first, FrameCamera second) {
        Matrix4fc firstMatrix = first.inverseViewProjection();
        float firstNearW = firstMatrix.m23() + firstMatrix.m33();
        float firstFarW = firstMatrix.m33();
        Matrix4fc secondMatrix = second.inverseViewProjection();
        float secondNearW = secondMatrix.m23() + secondMatrix.m33();
        float secondFarW = secondMatrix.m33();
        if (!Float.isFinite(firstNearW)
                || !Float.isFinite(firstFarW)
                || !Float.isFinite(secondNearW)
                || !Float.isFinite(secondFarW)
                || Math.abs(firstNearW) < 1.0e-20F
                || Math.abs(firstFarW) < 1.0e-20F
                || Math.abs(secondNearW) < 1.0e-20F
                || Math.abs(secondFarW) < 1.0e-20F) {
            return Float.NaN;
        }
        float firstX = firstMatrix.m30() / firstFarW
                - (firstMatrix.m20() + firstMatrix.m30()) / firstNearW;
        float firstY = firstMatrix.m31() / firstFarW
                - (firstMatrix.m21() + firstMatrix.m31()) / firstNearW;
        float firstZ = firstMatrix.m32() / firstFarW
                - (firstMatrix.m22() + firstMatrix.m32()) / firstNearW;
        float secondX = secondMatrix.m30() / secondFarW
                - (secondMatrix.m20() + secondMatrix.m30()) / secondNearW;
        float secondY = secondMatrix.m31() / secondFarW
                - (secondMatrix.m21() + secondMatrix.m31()) / secondNearW;
        float secondZ = secondMatrix.m32() / secondFarW
                - (secondMatrix.m22() + secondMatrix.m32()) / secondNearW;
        float firstLengthSquared = firstX * firstX + firstY * firstY + firstZ * firstZ;
        float secondLengthSquared = secondX * secondX + secondY * secondY + secondZ * secondZ;
        if (!Float.isFinite(firstLengthSquared)
                || !Float.isFinite(secondLengthSquared)
                || firstLengthSquared <= 1.0e-20F
                || secondLengthSquared <= 1.0e-20F) {
            return Float.NaN;
        }
        float inverseFirstLength = 1.0F / (float) Math.sqrt(firstLengthSquared);
        float inverseSecondLength = 1.0F / (float) Math.sqrt(secondLengthSquared);
        firstX *= inverseFirstLength;
        firstY *= inverseFirstLength;
        firstZ *= inverseFirstLength;
        secondX *= inverseSecondLength;
        secondY *= inverseSecondLength;
        secondZ *= inverseSecondLength;
        return firstX * secondX + firstY * secondY + firstZ * secondZ;
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
