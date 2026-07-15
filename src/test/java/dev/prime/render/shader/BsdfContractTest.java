package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class BsdfContractTest {
    private static final double PI = Math.PI;

    @Test
    void dielectricFresnelHasCorrectLimitsAndTotalInternalReflection() {
        assertEquals(0.04, fresnelDielectric(1.0, 1.0, 1.5), 1.0e-12);
        assertEquals(1.0, fresnelDielectric(0.0, 1.0, 1.5), 0.0);
        assertEquals(1.0, fresnelDielectric(-0.5, 1.0, 1.5), 0.0);

        for (int index = 0; index <= 1000; index++) {
            double cosine = index / 1000.0;
            double reflectance = fresnelDielectric(cosine, 1.0, 1.5);
            assertTrue(reflectance >= 0.0 && reflectance <= 1.0);
        }
    }

    @Test
    void conductorFresnelAtNormalIncidenceMatchesComplexIorDefinition() {
        double[] eta = {0.18, 0.92, 1.50};
        double[] k = {3.42, 2.45, 1.90};
        for (int channel = 0; channel < eta.length; channel++) {
            double expected = (square(eta[channel] - 1.0) + square(k[channel]))
                    / (square(eta[channel] + 1.0) + square(k[channel]));
            assertEquals(expected, fresnelConductor(1.0, eta[channel], k[channel]), 1.0e-12);
        }
    }

    @Test
    void isotropicGgxNormalDistributionIsNormalized() {
        for (double alpha : new double[] {0.1, 0.35, 0.64, 1.0}) {
            int steps = 200_000;
            double integral = 0.0;
            for (int index = 0; index < steps; index++) {
                double theta = (index + 0.5) * (0.5 * PI / steps);
                double cosine = Math.cos(theta);
                integral += ggxD(alpha, cosine) * cosine * Math.sin(theta);
            }
            integral *= 2.0 * PI * (0.5 * PI / steps);
            assertEquals(1.0, integral, 2.0e-6, "alpha=" + alpha);
        }
    }

    @Test
    void ggxVisibleNormalPdfIsNormalizedForObliqueViews() {
        for (double alpha : new double[] {0.15, 0.64, 1.0}) {
            for (double viewCosine : new double[] {0.2, 0.6, 1.0}) {
                double viewSine = Math.sqrt(1.0 - viewCosine * viewCosine);
                int thetaSteps = 512;
                int azimuthSteps = 1024;
                double integral = 0.0;
                for (int thetaIndex = 0; thetaIndex < thetaSteps; thetaIndex++) {
                    double theta = (thetaIndex + 0.5) * (0.5 * PI / thetaSteps);
                    double normalCosine = Math.cos(theta);
                    double normalSine = Math.sin(theta);
                    for (int azimuthIndex = 0; azimuthIndex < azimuthSteps; azimuthIndex++) {
                        double azimuth = (azimuthIndex + 0.5) * (2.0 * PI / azimuthSteps);
                        double viewDotNormal = viewSine * normalSine * Math.cos(azimuth)
                                + viewCosine * normalCosine;
                        if (viewDotNormal > 0.0) {
                            integral += ggxD(alpha, normalCosine)
                                    * ggxG1(alpha, viewCosine)
                                    * viewDotNormal / viewCosine
                                    * normalSine;
                        }
                    }
                }
                integral *= (0.5 * PI / thetaSteps) * (2.0 * PI / azimuthSteps);
                assertEquals(1.0, integral, 2.0e-4,
                        "alpha=" + alpha + ", cos(view)=" + viewCosine);
            }
        }
    }

    @Test
    void inferredVanillaRoughnessIsBoundedAndMonotonic() {
        assertEquals(0.90, defaultLinearRoughness(0.0), 1.0e-12);
        assertEquals(0.70, defaultLinearRoughness(1.0), 1.0e-12);
        double previous = Double.POSITIVE_INFINITY;
        for (int index = 0; index <= 1000; index++) {
            double roughness = defaultLinearRoughness(index / 1000.0);
            assertTrue(roughness <= previous);
            assertTrue(roughness >= 0.70 && roughness <= 0.90);
            double alpha = roughness * roughness;
            assertTrue(alpha >= 0.49 - 1.0e-12 && alpha <= 0.81 + 1.0e-12);
            previous = roughness;
        }
    }

    @Test
    void henyeyGreensteinIsNormalizedAndPreservesMeanCosine() {
        for (double anisotropy : new double[] {-0.6, 0.0, 0.7}) {
            int steps = 200_000;
            double normalization = 0.0;
            double meanCosine = 0.0;
            for (int index = 0; index < steps; index++) {
                double cosine = -1.0 + 2.0 * (index + 0.5) / steps;
                double phase = henyeyGreenstein(cosine, anisotropy);
                normalization += phase;
                meanCosine += cosine * phase;
            }
            double measure = 4.0 * PI / steps;
            assertEquals(1.0, normalization * measure, 2.0e-8);
            assertEquals(anisotropy, meanCosine * measure, 2.0e-8);
        }
    }

    @Test
    void labPbrClosuresHaveMatchingEvaluationAndSamplingEntrypoints() throws IOException {
        Path shaderRoot = Path.of(System.getProperty("user.dir"), "shaders");
        String diffuse = Files.readString(shaderRoot.resolve("bsdf_diffuse.glsl"));
        String microfacet = Files.readString(shaderRoot.resolve("bsdf_microfacet.glsl"));
        String subsurface = Files.readString(shaderRoot.resolve("bsdf_subsurface.glsl"));
        String emission = Files.readString(shaderRoot.resolve("bsdf_emission.glsl"));
        String composition = Files.readString(shaderRoot.resolve("bsdf.glsl"));
        String defaults = Files.readString(shaderRoot.resolve("default_material.glsl"));

        assertClosurePair(diffuse, "DiffuseReflection");
        assertClosurePair(diffuse, "DiffuseTransmission");
        assertClosurePair(diffuse, "ThinSubsurface");
        assertClosurePair(microfacet, "GgxDielectricReflection");
        assertClosurePair(microfacet, "GgxConductorF0");
        assertClosurePair(microfacet, "GgxConductorComplex");
        assertClosurePair(microfacet, "GgxDielectricInterface");
        assertTrue(microfacet.contains("primeSampleThinDielectric"));
        assertTrue(subsurface.contains("primeEvaluateHenyeyGreenstein"));
        assertTrue(subsurface.contains("primeSampleHenyeyGreenstein"));
        assertTrue(emission.contains("primeEvaluateDiffuseEmission"));
        assertTrue(emission.contains("primeSampleDiffuseEmission"));
        assertTrue(microfacet.contains("alpha=(1-smoothness)^2"));
        assertTrue(microfacet.contains("btdf /= etaPath * etaPath"));
        assertTrue(defaults.contains("PRIME_DEFAULT_DIELECTRIC_F0 = 0.04"));
        assertTrue(defaults.contains("PRIME_REC2020_LUMINANCE = vec3(0.2627, 0.6780, 0.0593)"));
        assertTrue(composition.contains("primeDefaultReflectiveDirectionalEnergyFit"));
        assertTrue(composition.contains("primeEvaluateDefaultBsdf"));
        assertTrue(composition.contains("primeSampleDefaultBsdf"));
        assertTrue(composition.contains("BsdfEvaluation combined = primeEvaluateDefaultBsdf"));
    }

    private static void assertClosurePair(String source, String suffix) {
        assertTrue(source.contains("primeEvaluate" + suffix), suffix + " evaluate");
        assertTrue(source.contains("primeSample" + suffix), suffix + " sample");
    }

    private static double fresnelDielectric(
            double cosineIncident, double incidentIor, double transmittedIor) {
        double cosine = Math.clamp(cosineIncident, -1.0, 1.0);
        double etaI = incidentIor;
        double etaT = transmittedIor;
        if (cosine < 0.0) {
            cosine = -cosine;
            double swap = etaI;
            etaI = etaT;
            etaT = swap;
        }
        double sineIncident = Math.sqrt(Math.max(0.0, 1.0 - cosine * cosine));
        double sineTransmitted = etaI * sineIncident / etaT;
        if (sineTransmitted >= 1.0) {
            return 1.0;
        }
        double cosineTransmitted = Math.sqrt(Math.max(0.0, 1.0 - sineTransmitted * sineTransmitted));
        double parallel = (etaT * cosine - etaI * cosineTransmitted)
                / (etaT * cosine + etaI * cosineTransmitted);
        double perpendicular = (etaI * cosine - etaT * cosineTransmitted)
                / (etaI * cosine + etaT * cosineTransmitted);
        return 0.5 * (parallel * parallel + perpendicular * perpendicular);
    }

    private static double fresnelConductor(double cosineIncident, double eta, double k) {
        double cosine = Math.clamp(Math.abs(cosineIncident), 0.0, 1.0);
        double cosine2 = cosine * cosine;
        double sine2 = 1.0 - cosine2;
        double eta2 = eta * eta;
        double k2 = k * k;
        double t0 = eta2 - k2 - sine2;
        double a2PlusB2 = Math.sqrt(t0 * t0 + 4.0 * eta2 * k2);
        double a = Math.sqrt(0.5 * (a2PlusB2 + t0));
        double t1 = a2PlusB2 + cosine2;
        double t2 = 2.0 * cosine * a;
        double rs = (t1 - t2) / (t1 + t2);
        double t3 = cosine2 * a2PlusB2 + sine2 * sine2;
        double t4 = t2 * sine2;
        double rp = rs * (t3 - t4) / (t3 + t4);
        return 0.5 * (rs + rp);
    }

    private static double ggxD(double alpha, double cosine) {
        double alpha2 = alpha * alpha;
        double denominator = cosine * cosine * (alpha2 - 1.0) + 1.0;
        return alpha2 / (PI * denominator * denominator);
    }

    private static double ggxG1(double alpha, double cosine) {
        double tangent2 = Math.max(0.0, 1.0 - cosine * cosine) / (cosine * cosine);
        double lambda = 0.5 * (Math.sqrt(1.0 + alpha * alpha * tangent2) - 1.0);
        return 1.0 / (1.0 + lambda);
    }

    private static double defaultLinearRoughness(double luminance) {
        double unit = Math.clamp((luminance - 0.08) / (0.90 - 0.08), 0.0, 1.0);
        double brightness = unit * unit * (3.0 - 2.0 * unit);
        return 0.90 + brightness * (0.70 - 0.90);
    }

    private static double henyeyGreenstein(double cosine, double anisotropy) {
        double denominator = 1.0 + anisotropy * anisotropy - 2.0 * anisotropy * cosine;
        return (1.0 - anisotropy * anisotropy)
                / (4.0 * PI * denominator * Math.sqrt(denominator));
    }

    private static double square(double value) {
        return value * value;
    }
}
