package dev.prime.render.vulkan.nrd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.FrameCamera;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

final class NrdCameraTransformTest {
    private static final float EPSILON = 2.0e-5F;
    private static final Matrix4f PROJECTION = new Matrix4f().perspective(
            (float) Math.toRadians(70.0), 16.0F / 9.0F, 512.0F, 0.05F, true);
    private static final Vector3f STATIC_WORLD_POINT = new Vector3f(0.0F, 0.0F, -10.0F);

    @Test
    void nrdProjectionNamesTheSameImageRowsAsPrime() {
        Matrix4f nrdProjection = NrdCameraTransform.projectionForNrd(PROJECTION);
        Vector2f upperInPrimeImage = NrdCameraTransform.screenUv(
                nrdProjection, new Vector3f(0.0F, -1.0F, -10.0F));
        Vector2f lowerInPrimeImage = NrdCameraTransform.screenUv(
                nrdProjection, new Vector3f(0.0F, 1.0F, -10.0F));

        assertTrue(upperInPrimeImage.y < 0.5F);
        assertTrue(lowerInPrimeImage.y > 0.5F);
    }

    @Test
    void forwardAndUpwardCameraMotionReprojectsToThePreviousPixel() {
        FrameCamera previous = camera(new Matrix4f(), 0.0, 0.0, 0.0);

        FrameCamera movedForward = camera(new Matrix4f(), 0.0, 0.0, -1.0);
        assertReprojectsStaticPoint(previous, movedForward);
        float forwardDepthDelta = previousViewZ(previous, movedForward)
                - currentViewZ(movedForward);
        assertTrue(forwardDepthDelta > 0.0F, "approaching geometry must increase previous-current view Z");

        FrameCamera movedUp = camera(new Matrix4f(), 0.0, 1.0, 0.0);
        Motion upwardMotion = motion(previous, movedUp);
        assertTrue(upwardMotion.currentUv.y < upwardMotion.previousUv.y);
        assertTrue(upwardMotion.vector.y > 0.0F);
        assertReprojectsStaticPoint(previous, movedUp);
    }

    @Test
    void yawAndPitchReprojectInTheExactOppositeDirectionOfCurrentImageMotion() {
        FrameCamera previous = camera(new Matrix4f(), 0.0, 0.0, 0.0);

        FrameCamera yawed = camera(
                new Matrix4f().rotateY((float) Math.toRadians(12.0)), 0.0, 0.0, 0.0);
        Motion yawMotion = motion(previous, yawed);
        assertTrue(yawMotion.currentUv.x < yawMotion.previousUv.x);
        assertTrue(yawMotion.vector.x > 0.0F);
        assertReprojectsStaticPoint(previous, yawed);

        FrameCamera pitched = camera(
                new Matrix4f().rotateX((float) Math.toRadians(9.0)), 0.0, 0.0, 0.0);
        Motion pitchMotion = motion(previous, pitched);
        assertTrue(pitchMotion.currentUv.y > pitchMotion.previousUv.y);
        assertTrue(pitchMotion.vector.y < 0.0F);
        assertReprojectsStaticPoint(previous, pitched);
    }

    @Test
    void exactDiagnosticProjectionBypassesCanonicalViewEffectDecomposition() {
        Matrix4f previousRenderedViewProjection = new Matrix4f(PROJECTION)
                .translate(0.2F, -0.1F, 0.0F);
        FrameCamera previous = new FrameCamera(
                new Matrix4f(PROJECTION),
                new Matrix4f(),
                new Matrix4f(previousRenderedViewProjection).invert(),
                0.0,
                0.0,
                0.0,
                -0.2,
                0.1,
                0.0);
        FrameCamera current = new FrameCamera(
                new Matrix4f(PROJECTION),
                new Matrix4f(),
                new Matrix4f(PROJECTION).invert(),
                1.0,
                0.0,
                0.0,
                1.15,
                -0.05,
                0.0);
        Vector3f currentRelative = new Vector3f(
                STATIC_WORLD_POINT.x - (float) current.renderX(),
                STATIC_WORLD_POINT.y - (float) current.renderY(),
                STATIC_WORLD_POINT.z - (float) current.renderZ());
        Vector3f previousPhysicalRelative = new Vector3f(
                STATIC_WORLD_POINT.x - (float) previous.x(),
                STATIC_WORLD_POINT.y - (float) previous.y(),
                STATIC_WORLD_POINT.z - (float) previous.z());

        Vector2f diagnosticUv = renderedScreenUv(
                NrdCameraTransform.previousRenderedWorldToClip(current, previous),
                currentRelative);
        Vector2f directUv = renderedScreenUv(
                previousRenderedViewProjection, previousPhysicalRelative);
        assertEquals(directUv.x, diagnosticUv.x, EPSILON);
        assertEquals(directUv.y, diagnosticUv.y, EPSILON);
    }

    private static void assertReprojectsStaticPoint(FrameCamera previous, FrameCamera current) {
        Motion motion = motion(previous, current);
        assertEquals(motion.previousUv.x, motion.currentUv.x + motion.vector.x, EPSILON);
        assertEquals(motion.previousUv.y, motion.currentUv.y + motion.vector.y, EPSILON);

        Vector3f directPreviousRelative = new Vector3f(
                STATIC_WORLD_POINT.x - (float) previous.renderX(),
                STATIC_WORLD_POINT.y - (float) previous.renderY(),
                STATIC_WORLD_POINT.z - (float) previous.renderZ());
        Vector2f directPreviousUv = NrdCameraTransform.screenUv(
                NrdCameraTransform.projectionForNrd(previous.projection())
                        .mul(previous.viewRotation()),
                directPreviousRelative);
        assertEquals(directPreviousUv.x, motion.previousUv.x, EPSILON);
        assertEquals(directPreviousUv.y, motion.previousUv.y, EPSILON);

        Vector2f exactRenderedPreviousUv = renderedScreenUv(
                NrdCameraTransform.previousRenderedWorldToClip(current, previous),
                new Vector3f(
                        STATIC_WORLD_POINT.x - (float) current.renderX(),
                        STATIC_WORLD_POINT.y - (float) current.renderY(),
                        STATIC_WORLD_POINT.z - (float) current.renderZ()));
        assertEquals(directPreviousUv.x, exactRenderedPreviousUv.x, EPSILON);
        assertEquals(directPreviousUv.y, exactRenderedPreviousUv.y, EPSILON);
    }

    private static Motion motion(FrameCamera previous, FrameCamera current) {
        Vector3f currentRelative = new Vector3f(
                STATIC_WORLD_POINT.x - (float) current.renderX(),
                STATIC_WORLD_POINT.y - (float) current.renderY(),
                STATIC_WORLD_POINT.z - (float) current.renderZ());
        Matrix4f currentWorldToClip = NrdCameraTransform.projectionForNrd(current.projection())
                .mul(current.viewRotation());
        Vector2f currentUv = NrdCameraTransform.screenUv(currentWorldToClip, currentRelative);
        Vector2f previousUv = NrdCameraTransform.screenUv(
                NrdCameraTransform.previousWorldToClip(current, previous), currentRelative);
        return new Motion(currentUv, previousUv, new Vector2f(previousUv).sub(currentUv));
    }

    private static float currentViewZ(FrameCamera current) {
        Vector3f currentRelative = new Vector3f(
                STATIC_WORLD_POINT.x - (float) current.renderX(),
                STATIC_WORLD_POINT.y - (float) current.renderY(),
                STATIC_WORLD_POINT.z - (float) current.renderZ());
        return Math.abs(current.viewRotation().transformPosition(currentRelative).z);
    }

    private static float previousViewZ(FrameCamera previous, FrameCamera current) {
        Vector3f currentRelative = new Vector3f(
                STATIC_WORLD_POINT.x - (float) current.renderX(),
                STATIC_WORLD_POINT.y - (float) current.renderY(),
                STATIC_WORLD_POINT.z - (float) current.renderZ());
        return Math.abs(NrdCameraTransform.previousWorldToView(current, previous)
                .transformPosition(currentRelative)
                .z);
    }

    private static Vector2f renderedScreenUv(Matrix4f worldToClip, Vector3f position) {
        Vector4f clip = worldToClip.transform(
                new Vector4f(position.x, position.y, position.z, 1.0F));
        float inverseW = 1.0F / clip.w;
        return new Vector2f(
                clip.x * inverseW * 0.5F + 0.5F,
                clip.y * inverseW * 0.5F + 0.5F);
    }

    private static FrameCamera camera(Matrix4f viewRotation, double x, double y, double z) {
        Matrix4f inverseViewProjection = new Matrix4f(PROJECTION)
                .mul(viewRotation)
                .invert();
        return new FrameCamera(
                new Matrix4f(PROJECTION),
                new Matrix4f(viewRotation),
                inverseViewProjection,
                x,
                y,
                z,
                x,
                y,
                z);
    }

    private record Motion(Vector2f currentUv, Vector2f previousUv, Vector2f vector) {}
}
