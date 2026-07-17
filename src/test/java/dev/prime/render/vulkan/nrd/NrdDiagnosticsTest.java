package dev.prime.render.vulkan.nrd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class NrdDiagnosticsTest {
    @Test
    void modesSelectExactlyOneFinalValidationSource() {
        assertArrayEquals(
                new int[] {0, 1, 2, 3},
                Arrays.stream(NrdDiagnostics.Mode.values())
                        .mapToInt(NrdDiagnostics.Mode::outputSelector)
                        .toArray());
        for (NrdDiagnostics.Mode selected : NrdDiagnostics.Mode.values()) {
            for (NrdDiagnostics.Mode denoiser : NrdDiagnostics.Mode.values()) {
                boolean expected = selected != NrdDiagnostics.Mode.OFF && selected == denoiser;
                assertEquals(expected, selected.enablesValidationFor(denoiser));
            }
        }
        assertFalse(NrdDiagnostics.Mode.OFF.enablesValidationFor(NrdDiagnostics.Mode.OPAQUE));
        assertTrue(NrdDiagnostics.Mode.TRANSMISSION.enablesValidationFor(
                NrdDiagnostics.Mode.TRANSMISSION));
    }

    @Test
    void oldDevelopmentViewsMigrateToOpaqueValidation() {
        assertEquals(
                NrdDiagnostics.Mode.OPAQUE,
                NrdDiagnostics.Mode.fromId("nrd_validation"));
        assertEquals(
                NrdDiagnostics.Mode.OPAQUE,
                NrdDiagnostics.Mode.fromId("reprojection_error"));
        assertEquals(NrdDiagnostics.Mode.OPAQUE, NrdDiagnostics.Mode.fromId("motion"));
    }

    @Test
    void everyPermanentModeHasEnglishAndChineseUserText() throws IOException {
        Path languageRoot = Path.of("src/client/resources/assets/prime/lang");
        String english = Files.readString(languageRoot.resolve("en_us.json"));
        String chinese = Files.readString(languageRoot.resolve("zh_cn.json"));
        for (NrdDiagnostics.Mode mode : NrdDiagnostics.Mode.values()) {
            String key = "\"prime.options.nrd.debug_view." + mode.id() + "\"";
            assertTrue(english.contains(key));
            assertTrue(chinese.contains(key));
        }
        for (String key : new String[] {
            "prime.options.fsr.quality.tooltip",
            "prime.options.lighting.sun_ev.tooltip",
            "prime.options.lighting.block_light_ev.tooltip",
            "prime.options.display.oklab_overexposure.tooltip",
            "prime.options.nrd.debug_view.tooltip",
            "prime.options.fsr.debug_view.tooltip"
        }) {
            assertTrue(english.contains("\"" + key + "\""));
            assertTrue(chinese.contains("\"" + key + "\""));
        }
    }
}
