package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

final class CameraDiscontinuityTest {
    @Test
    void ordinaryMovementAndTurningKeepTemporalHistory() {
        FrameCamera previous = camera(0.0, 64.0, 0.0, 0.0F, 70.0F);
        assertFalse(CameraDiscontinuity.isCut(
                previous,
                camera(1.0, 64.5, -2.0, 20.0F, 70.0F)));
    }

    @Test
    void teleportAbruptTurnAndProjectionJumpResetHistory() {
        FrameCamera previous = camera(0.0, 64.0, 0.0, 0.0F, 70.0F);
        assertTrue(CameraDiscontinuity.isCut(
                previous,
                camera(33.0, 64.0, 0.0, 0.0F, 70.0F)));
        assertTrue(CameraDiscontinuity.isCut(
                previous,
                camera(0.0, 64.0, 0.0, 61.0F, 70.0F)));
        assertTrue(CameraDiscontinuity.isCut(
                previous,
                camera(0.0, 64.0, 0.0, 0.0F, 95.0F)));
    }

    private static FrameCamera camera(
            double x,
            double y,
            double z,
            float yawDegrees,
            float fovDegrees) {
        Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(fovDegrees), 16.0F / 9.0F, 0.05F, 1000.0F);
        Matrix4f view = new Matrix4f().rotateY((float) Math.toRadians(yawDegrees));
        Matrix4f inverseViewProjection = new Matrix4f(projection).mul(view).invert();
        return new FrameCamera(
                projection,
                view,
                inverseViewProjection,
                x,
                y,
                z,
                x,
                y,
                z);
    }
}
