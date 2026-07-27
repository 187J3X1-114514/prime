package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.SplittableRandom;
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

    @Test
    void shaderPushDirectionRemainsUnitLengthForTheWholeOrbit() {
        SplittableRandom random = new SplittableRandom(0x5A17_D1AEC710L);
        for (int index = 0; index < 16_384; index++) {
            SunDirection direction = SunDirection.fromVanillaAngle(
                    random.nextFloat() * (float) (Math.PI * 2.0));
            float lengthSquared = direction.x() * direction.x()
                    + direction.y() * direction.y()
                    + direction.z() * direction.z();
            assertEquals(1.0F, lengthSquared, 2.0e-7F);
        }
    }

    @Test
    void rejectsFiniteButNonUnitDirectionsAtTheSemanticBoundary() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SunDirection(0.0F, 0.0F, 0.0F));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SunDirection(0.0F, 2.0F, 0.0F));
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
