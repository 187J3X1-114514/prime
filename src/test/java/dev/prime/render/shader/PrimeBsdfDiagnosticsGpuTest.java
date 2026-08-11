package dev.prime.render.shader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("gpu-shader")
final class PrimeBsdfDiagnosticsGpuTest {
    private static final long SEED = 0x4253_4446_4449_4147L;
    private static final int INPUT_WORDS = 1;
    private static final int WITNESS_WORDS = 2;
    private static final int NONNEGATIVE_FIELD = 2;
    private static final int DIRECTION_FIELD = 5;

    private static ShaderComputeRunner runner;

    @BeforeAll
    static void openRunner() throws ShaderComputeRunner.UnavailableException {
        try {
            runner = ShaderComputeRunner.open();
        } catch (ShaderComputeRunner.UnavailableException | LinkageError exception) {
            if (Boolean.getBoolean("prime.shaderTests.required")) {
                throw new AssertionError(
                        "A Vulkan compute device is required for shader tests", exception);
            }
            Assumptions.assumeTrue(
                    false, "Vulkan shader tests unavailable: " + exception.getMessage());
        }
    }

    @AfterAll
    static void closeRunner() {
        if (runner != null) runner.close();
    }

    @Test
    void rejectedAdapterSamplesPreserveTheirFirstRawDiagnostic() throws IOException {
        int[][] cases = {
            {0, 1, 0, 0},
            {1, 0, 1, NONNEGATIVE_FIELD},
            {2, 0, 8, NONNEGATIVE_FIELD},
            {3, 0, 256, DIRECTION_FIELD},
            {4, 0, 2, NONNEGATIVE_FIELD},
            {5, 0, 0, -1},
            // The later sample sanitizer rejects invalid relativeEta; the raw observer must see it.
            {6, 1, 2, NONNEGATIVE_FIELD}
        };
        ByteBuffer input = ShaderTestBuffer.inputs(cases.length, INPUT_WORDS);
        for (int caseIndex = 0; caseIndex < cases.length; caseIndex++) {
            for (int component = 0; component < 4; component++) {
                ShaderTestBuffer.putInt(
                        input,
                        caseIndex,
                        INPUT_WORDS,
                        0,
                        component,
                        cases[caseIndex][component]);
            }
        }
        Path shader = Path.of(
                System.getProperty("prime.test.slangShaderDirectory"),
                "prime_bsdf_diagnostics.comp.spv");
        ShaderPropertyBatch.assertProperties(
                runner,
                shader,
                input,
                cases.length,
                INPUT_WORDS,
                WITNESS_WORDS,
                SEED);
    }
}
