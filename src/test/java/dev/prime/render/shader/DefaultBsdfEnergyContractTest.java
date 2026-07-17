package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class DefaultBsdfEnergyContractTest {
    private static final Pattern ENERGY_ENTRY = Pattern.compile(
            "vec2\\((\\d+\\.\\d+), (\\d+\\.\\d+)\\),?");

    @Test
    void roughDielectricLayerKeepsItsDirectionalEnergyPartitionAtGrazingAngles()
            throws IOException {
        String shader = Files.readString(Path.of(
                System.getProperty("user.dir"), "shaders", "bsdf.glsl"));
        String integrator = Files.readString(Path.of(
                System.getProperty("user.dir"), "shaders", "integrator.glsl"));
        int tableStart = shader.indexOf("PRIME_DEFAULT_GGX_DIRECTIONAL_ENERGY[32]");
        int tableEnd = shader.indexOf(");", tableStart);
        assertTrue(tableStart >= 0 && tableEnd > tableStart);

        Matcher matcher = ENERGY_ENTRY.matcher(shader.substring(tableStart, tableEnd));
        List<double[]> energy = new ArrayList<>();
        while (matcher.find()) {
            energy.add(new double[] {
                Double.parseDouble(matcher.group(1)),
                Double.parseDouble(matcher.group(2))
            });
        }
        assertEquals(32, energy.size());

        double grazingTotal = energy.getFirst()[0] + energy.getFirst()[1];
        double grazingTransmission = energy.getFirst()[1] / grazingTotal;
        double normalTotal = energy.getLast()[0] + energy.getLast()[1];
        double normalReflection = energy.getLast()[0] / normalTotal;
        assertTrue(grazingTransmission > 0.5);
        assertTrue(normalReflection > 0.02 && normalReflection < 0.05);
        assertTrue(shader.contains("context.diffuseEnergyScale = directionalEnergy.y / context.resolvedEnergy"));
        assertTrue(shader.contains("components.diffuse.value *= context.diffuseEnergyScale"));
        assertTrue(shader.contains("components.specular.value /= context.resolvedEnergy"));
        assertEquals(
                2,
                Pattern.compile("defaultContext = primeMakeDefaultBsdfContext")
                        .matcher(integrator)
                        .results()
                        .count());
        assertTrue(integrator.contains("primeSampleDefaultBsdfWithContext"));
        assertTrue(integrator.contains("primeEvaluateDefaultBsdfComponentsWithContext"));
        assertFalse(shader.contains("(1.0 - fresnelIn) * (1.0 - fresnelOut)"));
    }
}
