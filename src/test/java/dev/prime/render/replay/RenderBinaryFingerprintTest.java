package dev.prime.render.replay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.RealtimeIntegratorMode;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class RenderBinaryFingerprintTest {
    @Test
    void activeBinaryAndResourcesRoundTripCanonically() {
        RenderBinaryFingerprint fingerprint =
                RenderBinaryFingerprint.capture(false);
        byte[] encoded = fingerprint.canonicalBytes();
        RenderBinaryFingerprint decoded =
                RenderBinaryFingerprint.decode(encoded);

        assertArrayEquals(encoded, decoded.canonicalBytes());
        assertTrue(fingerprint.isStrictlyCompatibleWith(decoded));
        assertFalse(fingerprint.resources().isEmpty());
    }

    @Test
    void truncatedIdentityIsRejected() {
        byte[] encoded = NrdInputSemanticValidatorTest.binary()
                .canonicalBytes();

        assertThrows(
                IllegalArgumentException.class,
                () -> RenderBinaryFingerprint.decode(
                        Arrays.copyOf(encoded, encoded.length - 1)));
    }

    @Test
    void nestedJarFallbackHashesEveryDeclaredExecutionResource() {
        String first = RenderBinaryFingerprint.digestExecutionClasses();
        String second = RenderBinaryFingerprint.digestExecutionClasses();

        assertEquals(64, first.length());
        assertEquals(first, second);
    }

    @Test
    void integratorModesFingerprintTheirOwnRaygenModules() {
        RenderBinaryFingerprint wavefront = RenderBinaryFingerprint.capture(
                false, RealtimeIntegratorMode.WAVEFRONT);
        RenderBinaryFingerprint lightweight = RenderBinaryFingerprint.capture(
                false, RealtimeIntegratorMode.LIGHTWEIGHT);

        assertFalse(wavefront.isStrictlyCompatibleWith(lightweight));
        assertTrue(wavefront.resources().stream()
                .anyMatch(resource -> resource.name().contains("realtime_wavefront_area")));
        assertTrue(lightweight.resources().stream()
                .anyMatch(resource -> resource.name().contains("lightweight_wavefront_step")));
    }
}
