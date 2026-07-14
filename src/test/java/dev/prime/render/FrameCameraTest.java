package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

final class FrameCameraTest {
    @Test
    void acceptsFiniteInvertibleCameraTransform() {
        assertNotNull(FrameCamera.tryCreate(new Matrix4f(), new Matrix4f(), 1.0, 2.0, 3.0));
    }

    @Test
    void rejectsSingularOrNonFiniteResizeFrames() {
        assertNull(FrameCamera.tryCreate(new Matrix4f().zero(), new Matrix4f(), 1.0, 2.0, 3.0));
        assertNull(FrameCamera.tryCreate(new Matrix4f(), new Matrix4f(), Double.NaN, 2.0, 3.0));
    }
}
