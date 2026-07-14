package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ColorContractTest {
    private static final double[][] LINEAR_SRGB_TO_REC2020 = {
        {0.6274039, 0.3292830, 0.0433131},
        {0.0690973, 0.9195404, 0.0113623},
        {0.0163914, 0.0880133, 0.8955953}
    };

    private static final double[][] LINEAR_REC2020_TO_SRGB = {
        {1.6604910, -0.5876411, -0.0728499},
        {-0.1245505, 1.1328999, -0.0083494},
        {-0.0181508, -0.1005789, 1.1187297}
    };

    @Test
    void srgbTransferFunctionUsesLinearLight() {
        assertEquals(0.0, decodeSrgb(0.0), 0.0);
        assertEquals(0.0031308, decodeSrgb(0.040449936), 1.0e-7);
        assertEquals(0.21404114, decodeSrgb(0.5), 1.0e-8);
        assertEquals(1.0, decodeSrgb(1.0), 0.0);

        for (double encoded : new double[] {0.0, 0.02, 0.18, 0.5, 1.0}) {
            assertEquals(encoded, encodeSrgb(decodeSrgb(encoded)), 1.0e-7);
        }
    }

    @Test
    void rec2020MatricesPreserveNeutralAndRoundTripLinearSrgb() {
        assertArrayEquals(
                new double[] {1.0, 1.0, 1.0},
                transform(LINEAR_SRGB_TO_REC2020, new double[] {1.0, 1.0, 1.0}),
                3.0e-7);

        for (double[] linearSrgb : new double[][] {
            {1.0, 0.0, 0.0},
            {0.0, 1.0, 0.0},
            {0.0, 0.0, 1.0},
            {0.02, 0.18, 0.73},
            {1.0, 1.0, 1.0}
        }) {
            double[] rec2020 = transform(LINEAR_SRGB_TO_REC2020, linearSrgb);
            assertArrayEquals(
                    linearSrgb,
                    transform(LINEAR_REC2020_TO_SRGB, rec2020),
                    5.0e-7);
        }
    }

    @Test
    void shaderBoundariesCannotBypassTheDeclaredWorkingSpace() throws IOException {
        Path shaderRoot = Path.of(System.getProperty("user.dir"), "shaders");
        String material = Files.readString(shaderRoot.resolve("material.glsl"));
        String displayTransform = Files.readString(shaderRoot.resolve("display_transform.glsl"));
        String rayGeneration = Files.readString(shaderRoot.resolve("world.rgen"));
        String composite = Files.readString(shaderRoot.resolve("nrd_composite.comp"));

        assertTrue(material.contains("primeDecodeSrgb(textureSample.rgb)"));
        assertTrue(material.contains("primeLinearSrgbToLinearRec2020(linearSrgbAlbedo)"));
        assertTrue(displayTransform.contains("primeLinearRec2020ToLinearBt709(exposedRec2020)"));
        assertTrue(displayTransform.contains("PRIME_OKLAB_DISPLAY_EXPOSURE = 1.0"));
        assertTrue(rayGeneration.contains("imageStore(primeAccumulation"));
        assertFalse(rayGeneration.contains("primeDisplayTransformToSrgb"));
        assertTrue(composite.contains("primeDisplayTransformToSrgb(max(radiance, vec3(0.0)))"));
        assertTrue(
                composite.indexOf("vec3 radiance = diffuse + imageLoad(primeStableAccumulation")
                        < composite.indexOf("primeDisplayTransformToSrgb(max(radiance, vec3(0.0)))"));
    }

    @Test
    void generatedAbiSelectsTheSrgbRec709OklabDisplayContract() {
        assertEquals("linear-rec2020-d65", ShaderAbi.WORKING_COLOR_SPACE);
        assertEquals("srgb", ShaderAbi.DISPLAY_COLOR_ENCODING);
        assertEquals("rec709-d65", ShaderAbi.DISPLAY_COLOR_SPACE);
        assertEquals("oklab-drt", ShaderAbi.DEFAULT_DISPLAY_TRANSFORM);
    }

    private static double decodeSrgb(double encoded) {
        return encoded <= 0.04045
                ? encoded / 12.92
                : Math.pow((encoded + 0.055) / 1.055, 2.4);
    }

    private static double encodeSrgb(double linearValue) {
        return linearValue <= 0.0031308
                ? 12.92 * linearValue
                : 1.055 * Math.pow(linearValue, 1.0 / 2.4) - 0.055;
    }

    private static double[] transform(double[][] matrix, double[] color) {
        double[] result = new double[3];
        for (int row = 0; row < 3; row++) {
            result[row] = matrix[row][0] * color[0]
                    + matrix[row][1] * color[1]
                    + matrix[row][2] * color[2];
        }
        return result;
    }
}
