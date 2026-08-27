package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

final class BsdfLookupTableDataTest {
    @Test
    void ggxLtcTablesMatchThePinnedSupplementalDataConversion() throws IOException {
        byte[] matrix = decode("/prime/light/ggx_ltc_matrix.bytes.gz.b64");
        byte[] amplitude = decode("/prime/light/ggx_ltc_amplitude.bytes.gz.b64");

        assertEquals(BsdfLookupTable.LTC_MATRIX_BYTE_SIZE, matrix.length);
        assertEquals(BsdfLookupTable.LTC_AMPLITUDE_BYTE_SIZE, amplitude.length);
        assertEquals(
                "e2fc71977a4c35c32c5c26621875959e9504431e330f2993204ef4013b770fea",
                sha256(matrix));
        assertEquals(
                "c720db27d3cffee3eba898cce6009b9c9ce6a3d9337cd488c5c9c672b4b8b140",
                sha256(amplitude));
    }

    private static byte[] decode(String resource) throws IOException {
        InputStream encoded = BsdfLookupTableDataTest.class.getResourceAsStream(resource);
        assertNotNull(encoded, resource);
        try (encoded;
                InputStream decoded = Base64.getMimeDecoder().wrap(encoded);
                GZIPInputStream decompressed = new GZIPInputStream(decoded)) {
            return decompressed.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
