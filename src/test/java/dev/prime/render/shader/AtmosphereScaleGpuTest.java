package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("gpu-shader")
final class AtmosphereScaleGpuTest {
    private static final int CASE_FLOATS = 4;

    @Test
    void coordinateScalePreservesAtmosphereAndMultipliesWorldOpticalDepth()
            throws IOException {
        ShaderComputeRunner opened;
        try {
            opened = ShaderComputeRunner.open();
        } catch (ShaderComputeRunner.UnavailableException | LinkageError exception) {
            if (Boolean.getBoolean("prime.shaderTests.required")) {
                throw new AssertionError(
                        "A Vulkan compute device is required for shader tests",
                        exception);
            }
            Assumptions.assumeTrue(
                    false,
                    "Vulkan shader tests unavailable: "
                            + exception.getMessage());
            return;
        }

        float[][] cases = {
            {0.0F, 0.0F, 1.0F, 1.0F},
            {0.5F, 1.0F, 0.2F, 16.0F},
            {1.5F, 0.0F, 0.0F, 64.0F},
            {10.0F, 1.0F, -0.2F, 256.0F},
            {50.0F, 0.0F, 0.7F, 1_024.0F},
            {99.0F, 1.0F, -0.7F, 2_048.0F}
        };
        ByteBuffer input = ByteBuffer.allocateDirect(
                        cases.length * CASE_FLOATS * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float[] value : cases) {
            for (float component : value) {
                input.putFloat(component);
            }
        }
        input.flip();
        ByteBuffer push = ByteBuffer.allocateDirect(Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(cases.length)
                .flip();
        Path shader = Path.of(
                System.getProperty("prime.test.shaderDirectory"),
                "atmosphere_scale_properties.comp.spv");

        try (ShaderComputeRunner runner = opened) {
            ByteBuffer output = runner.dispatch(
                    shader,
                    input,
                    cases.length * CASE_FLOATS * Float.BYTES,
                    new ShaderComputeRunner.Workgroups(1, 1, 1),
                    push);
            for (int caseIndex = 0;
                    caseIndex < cases.length;
                    ++caseIndex) {
                int base = caseIndex * CASE_FLOATS * Float.BYTES;
                for (int component = 0;
                        component < CASE_FLOATS;
                        ++component) {
                    float error = output.getFloat(
                            base + component * Float.BYTES);
                    assertTrue(
                            error <= 5.0e-4F,
                            "coordinate-scale case " + caseIndex
                                    + " component " + component
                                    + ": " + error);
                }
            }
        }
    }
}
