package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

final class StarmapResourceTest {
    @Test
    void importanceTableIsACompleteFiniteAliasDistribution() throws IOException {
        byte[] bytes;
        try (InputStream encoded = StarmapResourceTest.class.getResourceAsStream(
                        "/prime/starmap/starmap_2020_8k.alias.gz")) {
            assertNotNull(encoded);
            try (GZIPInputStream decoded = new GZIPInputStream(encoded)) {
                bytes = decoded.readAllBytes();
            }
        }

        int count = StarmapTexture.IMPORTANCE_WIDTH * StarmapTexture.IMPORTANCE_HEIGHT;
        assertEquals(count * StarmapTexture.IMPORTANCE_RECORD_BYTES, bytes.length);
        ByteBuffer records = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        double massSum = 0.0;
        int positiveMasses = 0;
        for (int index = 0; index < count; index++) {
            float threshold = records.getFloat();
            int alias = records.getInt();
            float mass = records.getFloat();
            assertTrue(Float.isFinite(threshold) && threshold >= 0.0F && threshold <= 1.0F);
            assertTrue(alias >= 0 && alias < count);
            assertTrue(Float.isFinite(mass) && mass >= 0.0F);
            massSum += mass;
            if (mass > 0.0F) positiveMasses++;
        }
        assertEquals(1.0, massSum, 1.0e-6);
        assertTrue(positiveMasses > count / 2);
    }
}
