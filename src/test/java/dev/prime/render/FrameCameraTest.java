package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.junit.jupiter.api.Test;

final class FrameCameraTest {
    @Test
    void acceptsFiniteInvertibleCameraTransform() {
        assertNotNull(FrameCamera.tryCreate(
                new Matrix4f(), new Matrix4f(), new Matrix4f(), 1.0, 2.0, 3.0));
    }

    @Test
    void rejectsSingularOrNonFiniteResizeFrames() {
        assertNull(FrameCamera.tryCreate(
                new Matrix4f().zero(),
                new Matrix4f(),
                new Matrix4f(),
                1.0,
                2.0,
                3.0));
        assertNull(FrameCamera.tryCreate(
                new Matrix4f(),
                new Matrix4f(),
                new Matrix4f(),
                Double.NaN,
                2.0,
                3.0));
    }

    @Test
    void extractsRigidViewEffectsWithoutChangingMojangsRenderedTransform() {
        Matrix4f baseProjection = new Matrix4f().perspective(
                (float) Math.toRadians(70.0), 16.0F / 9.0F, 512.0F, 0.05F, true);
        Matrix4f cameraViewRotation = new Matrix4f()
                .rotateY((float) Math.toRadians(37.0))
                .rotateX((float) Math.toRadians(-21.0));
        Matrix4f viewEffect = new Matrix4f()
                .translate(0.08F, -0.13F, 0.02F)
                .rotateZ((float) Math.toRadians(2.5))
                .rotateX((float) Math.toRadians(4.0));
        Matrix4f renderedProjection = new Matrix4f(baseProjection).mul(viewEffect);

        FrameCamera camera = FrameCamera.tryCreate(
                renderedProjection,
                baseProjection,
                cameraViewRotation,
                100.0,
                64.0,
                -30.0);
        assertNotNull(camera);

        Matrix4f canonicalFromPhysicalCamera = new Matrix4f(camera.projection())
                .mul(camera.viewRotation())
                .translate(
                        (float) (100.0 - camera.renderX()),
                        (float) (64.0 - camera.renderY()),
                        (float) (-30.0 - camera.renderZ()));
        Matrix4f exactMojangTransform = new Matrix4f(renderedProjection).mul(cameraViewRotation);
        assertMatrixEquals(exactMojangTransform, canonicalFromPhysicalCamera, 2.0e-5F);

        Matrix4f expectedInverse = exactMojangTransform.invert(new Matrix4f());
        assertMatrixEquals(expectedInverse, camera.inverseViewProjection(), 2.0e-5F);
    }

    private static void assertMatrixEquals(Matrix4fc expected, Matrix4fc actual, float tolerance) {
        float[] expectedValues = expected.get(new float[16]);
        float[] actualValues = actual.get(new float[16]);
        for (int index = 0; index < expectedValues.length; index++) {
            int element = index;
            assertTrue(
                    Math.abs(expectedValues[index] - actualValues[index]) <= tolerance,
                    () -> "matrix element " + element + " differs: expected "
                            + expectedValues[element] + ", actual " + actualValues[element]);
        }
    }
}
