package dev.prime.render.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RenderPlatformFingerprintTest {
    @Test
    void canonicalIdentityIsStableAndSensitiveToExecutionFeatures() {
        RenderPlatformFingerprint first = fingerprint(false);
        RenderPlatformFingerprint same = fingerprint(false);
        RenderPlatformFingerprint ser = fingerprint(true);

        assertEquals(first.sha256(), same.sha256());
        assertTrue(first.isStrictlyCompatibleWith(same));
        assertFalse(first.isStrictlyCompatibleWith(ser));
        assertEquals(
                first.sha256(),
                RenderPlatformFingerprint.decode(
                                first.canonicalBytes())
                        .sha256());
    }

    @Test
    void malformedPipelineCacheIdentityIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RenderPlatformFingerprint(
                        "GPU",
                        1,
                        2,
                        3,
                        4,
                        5,
                        "ABC",
                        32,
                        32,
                        64,
                        64,
                        1 << 20,
                        1,
                        1L,
                        1L,
                        256,
                        true,
                        false,
                        true,
                        4,
                        4,
                        true));
    }

    @Test
    void truncatedIdentityIsRejectedAsInputError() {
        byte[] encoded = fingerprint(false).canonicalBytes();
        for (int length : List.of(0, 4, 8, encoded.length - 1)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> RenderPlatformFingerprint.decode(
                            Arrays.copyOf(encoded, length)));
        }
    }

    @Test
    void legacySingleLimitIdentityDecodesBothOpacityFormats() {
        byte[] current = fingerprint(false).canonicalBytes();
        int prefixBytes = current.length - 2 * Integer.BYTES - 1;
        byte[] legacy = new byte[current.length - Integer.BYTES];
        System.arraycopy(current, 0, legacy, 0, prefixBytes + Integer.BYTES);
        legacy[legacy.length - 1] = current[current.length - 1];
        ByteBuffer.wrap(legacy).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 1);

        RenderPlatformFingerprint decoded = RenderPlatformFingerprint.decode(legacy);

        assertEquals(12, decoded.maxOpacity2StateSubdivisionLevel());
        assertEquals(12, decoded.maxOpacity4StateSubdivisionLevel());
    }

    private static RenderPlatformFingerprint fingerprint(boolean ser) {
        return new RenderPlatformFingerprint(
                "Test GPU",
                0x10de,
                0x2684,
                1,
                123,
                456,
                "00112233445566778899aabbccddeeff",
                32,
                32,
                64,
                64,
                1 << 20,
                1,
                1L << 28,
                1L << 24,
                256,
                true,
                ser,
                true,
                12,
                10,
                true);
    }
}
