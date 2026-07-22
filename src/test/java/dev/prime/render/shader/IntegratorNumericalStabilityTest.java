package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class IntegratorNumericalStabilityTest {
    @Test
    void responseContractDoesNotDivideAndRestoreCosine() {
        float response = Float.MAX_VALUE * 0.25F;
        float cosine = 0.1F;

        float reconstructed = (response / cosine) * cosine;
        assertFalse(Float.isFinite(reconstructed));
        assertTrue(Float.isFinite(response));
    }

    @Test
    void exponentScaledProductPreservesRepresentableQuotients() {
        float large = Float.MAX_VALUE * 0.25F;
        assertFalse(Float.isFinite(large * 8.0F / 16.0F));
        assertEquals(large * 0.5F, scaledProductOver(large, 8.0F, 16.0F), 0.0F);

        assertEquals(0.0F, Float.MIN_VALUE * 0.5F / 0.25F, 0.0F);
        assertEquals(
                Math.scalb(Float.MIN_VALUE, 1),
                scaledProductOver(Float.MIN_VALUE, 0.5F, 0.25F),
                0.0F);
    }

    @Test
    void normalizedLightScoresAvoidCommonOverflow() {
        float firstPower = Float.MAX_VALUE * 0.25F;
        float secondPower = Float.MAX_VALUE * 0.125F;
        float firstDistance = Float.MAX_VALUE * 0.5F;
        float secondDistance = Float.MAX_VALUE * 0.5F;

        float firstDirect = firstPower * secondDistance;
        float secondDirect = secondPower * firstDistance;
        assertTrue(Float.isNaN(firstDirect / (firstDirect + secondDirect)));

        float powerScale = Math.max(firstPower, secondPower);
        float distanceScale = Math.max(firstDistance, secondDistance);
        float first = firstPower / powerScale * (secondDistance / distanceScale);
        float second = secondPower / powerScale * (firstDistance / distanceScale);
        assertEquals(2.0F / 3.0F, first / (first + second), 0.0F);
    }

    @Test
    void crossProductCoordinatesPreserveParallelogramParameters() {
        Vec3 first = new Vec3(1.0e8F, 1.0F, 0.0F);
        Vec3 second = new Vec3(1.0e8F, 0.0F, 1.0F);
        float expectedFirst = 0.25F;
        float expectedSecond = 0.75F;
        Vec3 relative = first.scale(expectedFirst).add(second.scale(expectedSecond));
        Vec3 edgeCross = first.cross(second);
        float denominator = edgeCross.dot(edgeCross);

        float actualFirst = relative.cross(second).dot(edgeCross) / denominator;
        float actualSecond = first.cross(relative).dot(edgeCross) / denominator;
        assertEquals(expectedFirst, actualFirst, 2.0e-7F);
        assertEquals(expectedSecond, actualSecond, 2.0e-7F);
    }

    private static float scaledProductOver(float first, float second, float denominator) {
        Frexp a = frexp(first);
        Frexp b = frexp(second);
        Frexp d = frexp(denominator);
        float significand = (a.significand * b.significand) / d.significand;
        return Math.scalb(significand, a.exponent + b.exponent - d.exponent);
    }

    private static Frexp frexp(float value) {
        if (value == 0.0F) return new Frexp(0.0F, 0);
        int exponent = Math.getExponent((double) value) + 1;
        return new Frexp((float) Math.scalb((double) value, -exponent), exponent);
    }

    private record Frexp(float significand, int exponent) {}

    private record Vec3(float x, float y, float z) {
        Vec3 add(Vec3 other) {
            return new Vec3(x + other.x, y + other.y, z + other.z);
        }

        Vec3 scale(float value) {
            return new Vec3(x * value, y * value, z * value);
        }

        float dot(Vec3 other) {
            return x * other.x + y * other.y + z * other.z;
        }

        Vec3 cross(Vec3 other) {
            return new Vec3(
                    y * other.z - z * other.y,
                    z * other.x - x * other.z,
                    x * other.y - y * other.x);
        }
    }
}
