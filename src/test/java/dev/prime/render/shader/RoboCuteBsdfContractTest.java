package dev.prime.render.shader;

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
    private static final int GGX_LUT_RESOLUTION = 32;
    private static final int GGX_LUT_CHANNELS = 4;
    private static final String GGX_LUT_SHA256 =
            "2d655ae640d7f57da3ad0609d92fde55df1d9de18e9fbc19fa9aa2adbb0d8df4";

    @Test
    void transmissionGgxEnergyTableIsExactAndFinite()
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
        assertEquals(
                GGX_LUT_RESOLUTION * GGX_LUT_RESOLUTION * GGX_LUT_RESOLUTION
                        * GGX_LUT_CHANNELS * Float.BYTES,
                decoded.length);
        assertEquals(
                GGX_LUT_SHA256,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(decoded)));

        ByteBuffer values = ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN);
        while (values.hasRemaining()) {
            float value = values.getFloat();
            assertTrue(Float.isFinite(value));
            assertTrue(value >= 0.0F && value <= 1.0F);
        }
    }

}
