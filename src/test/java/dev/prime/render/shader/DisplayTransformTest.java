package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class DisplayTransformTest {
    private static final double EPSILON = 2.0e-6;
    private static final double EXPOSURE = 1.0;

    @Test
    void oklabTransformMatchesReferenceCheckpoints() {
        assertArrayEquals(new double[] {0.0, 0.0, 0.0}, display(new double[] {0.0, 0.0, 0.0}), EPSILON);
        assertArrayEquals(
                new double[] {0.18926457, 0.25049044, 0.30329002},
                display(new double[] {0.035, 0.045, 0.065}),
                EPSILON);
        assertArrayEquals(
                new double[] {0.78464338, 0.78464335, 0.78464327},
                display(new double[] {1.0, 1.0, 1.0}),
                EPSILON);
        assertArrayEquals(
                new double[] {1.0, 0.79712696, 0.75392003},
                display(new double[] {4.0, 0.5, 0.1}),
                EPSILON);
    }

    @Test
    void neutralHdrRampIsMonotonicAndBoundedForSrgbDisplay() {
        double previous = -1.0;
        for (double value : new double[] {0.0, 0.01, 0.1, 0.2, 1.0, 4.0, 64.0}) {
            double[] transformed = display(new double[] {value, value, value});
            for (double channel : transformed) {
                assertTrue(Double.isFinite(channel));
                assertTrue(channel >= 0.0 && channel <= 1.0);
            }
            assertTrue(transformed[0] >= previous);
            assertArrayEquals(
                    new double[] {transformed[0], transformed[0], transformed[0]},
                    transformed,
                    2.0e-6);
            previous = transformed[0];
        }
    }

    @Test
    void shaderKeepsDisplayExposureOutOfLinearAccumulation() throws IOException {
        Path shaderRoot = Path.of(System.getProperty("user.dir"), "shaders");
        String displayTransform = Files.readString(shaderRoot.resolve("display_transform.glsl"));
        String rayGeneration = Files.readString(shaderRoot.resolve("world.rgen"));
        String composite = Files.readString(shaderRoot.resolve("nrd_composite.comp"));
        String fsrDisplay = Files.readString(shaderRoot.resolve("fsr_display.comp"));

        assertTrue(displayTransform.contains("const float PRIME_OKLAB_DISPLAY_EXPOSURE = 1.0"));
        assertTrue(displayTransform.contains("vec3 primeDisplayTransformToSrgb(vec3 hdrRec2020)"));
        assertTrue(displayTransform.contains("primeOklabTonemapCurve(linearBt709)"));
        assertTrue(rayGeneration.contains("imageStore(primeAccumulation"));
        assertFalse(rayGeneration.contains("primeDisplayTransformToSrgb"));
        assertTrue(composite.contains("vec3 radiance = surface + imageLoad(primeStableAccumulation"));
        assertFalse(composite.contains("primeDisplayTransformToSrgb"));
        assertTrue(fsrDisplay.contains("primeDisplayTransformToSrgb(max(radiance, vec3(0.0)))"));
    }

    private static double[] display(double[] hdrRec2020) {
        double[] exposed = new double[] {
            Math.max(hdrRec2020[0], 0.0) * EXPOSURE,
            Math.max(hdrRec2020[1], 0.0) * EXPOSURE,
            Math.max(hdrRec2020[2], 0.0) * EXPOSURE
        };
        double[] linearBt709 = rec2020ToBt709(exposed);
        for (int channel = 0; channel < linearBt709.length; channel++) {
            linearBt709[channel] = Math.max(linearBt709[channel], 0.0);
        }
        double[] mapped = tonemapCurve(linearBt709);
        for (int channel = 0; channel < mapped.length; channel++) {
            mapped[channel] = clamp(encodeSrgb(mapped[channel]), 0.0, 1.0);
        }
        return mapped;
    }

    private static double[] rec2020ToBt709(double[] color) {
        return new double[] {
            1.6604910 * color[0] - 0.5876411 * color[1] - 0.0728499 * color[2],
            -0.1245505 * color[0] + 1.1328999 * color[1] - 0.0083494 * color[2],
            -0.0181508 * color[0] - 0.1005789 * color[1] + 1.1187297 * color[2]
        };
    }

    private static double[] rgbToOklab(double[] color) {
        double l = 0.4122214708 * color[0] + 0.5363325363 * color[1] + 0.0514459929 * color[2];
        double m = 0.2119034982 * color[0] + 0.6806995451 * color[1] + 0.1073969566 * color[2];
        double s = 0.0883024619 * color[0] + 0.2817188376 * color[1] + 0.6299787005 * color[2];
        double lRoot = Math.cbrt(l);
        double mRoot = Math.cbrt(m);
        double sRoot = Math.cbrt(s);
        return new double[] {
            0.2104542553 * lRoot + 0.7936177850 * mRoot - 0.0040720468 * sRoot,
            1.9779984951 * lRoot - 2.4285922050 * mRoot + 0.4505937099 * sRoot,
            0.0259040371 * lRoot + 0.7827717662 * mRoot - 0.8086757660 * sRoot
        };
    }

    private static double[] oklabToRgb(double[] color) {
        double lRoot = color[0] + 0.3963377774 * color[1] + 0.2158037573 * color[2];
        double mRoot = color[0] - 0.1055613458 * color[1] - 0.0638541728 * color[2];
        double sRoot = color[0] - 0.0894841775 * color[1] - 1.2914855480 * color[2];
        double l = lRoot * lRoot * lRoot;
        double m = mRoot * mRoot * mRoot;
        double s = sRoot * sRoot * sRoot;
        return new double[] {
            4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
            -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
            -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
        };
    }

    private static double[] tonemapCurve(double[] color) {
        double maxChannel = Math.max(color[0], Math.max(color[1], color[2]));
        if (maxChannel <= 1.0e-6) {
            return color;
        }

        double[] oklab = rgbToOklab(color);
        double brightness = oklab[0] * oklab[0] * oklab[0];
        double targetLightness = Math.cbrt((37.0 / 32.0) * brightness / (1.0 + brightness));
        double[] truncated = rgbToOklab(scale(color, 1.0 / maxChannel));
        double[] start = scale(truncated, 0.875);
        double l0 = start[0];
        if (targetLightness <= l0) {
            return oklabToRgb(scale(oklab, targetLightness / oklab[0]));
        }

        double deltaLightness = clamp(targetLightness, l0, 1.0) - l0;
        double a = l0 - 2.0 * truncated[0] + 1.0;
        double b = 2.0 * (truncated[0] - l0);
        double denominator = b + Math.sqrt(Math.max(b * b + 4.0 * a * deltaLightness, 0.0));
        double t = denominator > 1.0e-6 ? clamp(2.0 * deltaLightness / denominator, 0.0, 1.0) : 0.0;
        double oneMinusT = 1.0 - t;
        return oklabToRgb(new double[] {
            oneMinusT * oneMinusT * start[0]
                    + 2.0 * t * oneMinusT * truncated[0]
                    + t * t,
            oneMinusT * oneMinusT * start[1] + 2.0 * t * oneMinusT * truncated[1],
            oneMinusT * oneMinusT * start[2] + 2.0 * t * oneMinusT * truncated[2]
        });
    }

    private static double[] scale(double[] vector, double scalar) {
        return new double[] {vector[0] * scalar, vector[1] * scalar, vector[2] * scalar};
    }

    private static double encodeSrgb(double linear) {
        double nonNegative = Math.max(linear, 0.0);
        return nonNegative <= 0.0031308
                ? nonNegative * 12.92
                : 1.055 * Math.pow(nonNegative, 1.0 / 2.4) - 0.055;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
