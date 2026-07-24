package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

final class RoboCuteBsdfContractTest {
    private static final int GGX_LUT_WIDTH = 44;
    private static final int GGX_LUT_HEIGHT = 32;
    private static final int GGX_LUT_DEPTH = 159;
    private static final int GGX_LUT_CHANNELS = 4;
    private static final String GGX_LUT_SHA256 =
            "605c9160fb9348a1d033321c40cf9930226ce74c03f2624033f5b73aacfa67df";

    @Test
    void transmissionGgxEnergyTableMatchesAuthoritativeHalfDataAndIsFinite()
            throws IOException, NoSuchAlgorithmException {
        Path encodedPath = Path.of(
                System.getProperty("user.dir"),
                "src", "client", "resources", "prime", "bsdf",
                "trans_ggx.bytes.gz.b64");
        byte[] compressed = Base64.getMimeDecoder().decode(Files.readAllBytes(encodedPath));
        byte[] decoded;
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(compressed));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.transferTo(output);
            decoded = output.toByteArray();
        }
        byte[] authoritative = Files.readAllBytes(Path.of(
                System.getProperty("user.dir"),
                "third_party", "robocute", "author-bsdf-hotfix-2026-07-24",
                "trans_ggx.bytes"));
        assertArrayEquals(authoritative, decoded);
        assertEquals(
                GGX_LUT_WIDTH * GGX_LUT_HEIGHT * GGX_LUT_DEPTH
                        * GGX_LUT_CHANNELS * Short.BYTES,
                decoded.length);
        assertEquals(
                GGX_LUT_SHA256,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(decoded)));

        ByteBuffer values = ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN);
        while (values.hasRemaining()) {
            float value = Float.float16ToFloat(values.getShort());
            assertTrue(Float.isFinite(value));
        }
    }

}
