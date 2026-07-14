package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class SunDirectionTest {
    private static final float EPSILON = 1.0e-6F;

    @Test
    void preservesVanillaElevationWithThirtyDegreeAzimuthOffset() {
        float cosine = (float) Math.cos(Math.toRadians(30.0));
        float sine = (float) Math.sin(Math.toRadians(30.0));
        assertDirection(SunDirection.fromVanillaAngle(0.0F), 0.0F, 1.0F, 0.0F);
        assertDirection(
                SunDirection.fromVanillaAngle((float) (Math.PI * 0.5)),
                -cosine,
                0.0F,
                sine);
        assertDirection(
                SunDirection.fromVanillaAngle((float) Math.PI),
                0.0F,
                -1.0F,
                0.0F);
        assertDirection(
                SunDirection.fromVanillaAngle((float) (Math.PI * 1.5)),
                cosine,
                0.0F,
                -sine);
    }

    private static void assertDirection(
            SunDirection actual,
            float expectedX,
            float expectedY,
            float expectedZ) {
        assertEquals(expectedX, actual.x(), EPSILON);
        assertEquals(expectedY, actual.y(), EPSILON);
        assertEquals(expectedZ, actual.z(), EPSILON);
    }
}
