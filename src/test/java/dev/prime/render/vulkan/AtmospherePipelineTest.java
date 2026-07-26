package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

final class AtmospherePipelineTest {
    @Test
    void measuredFourSpeciesPhaseTableIsExact() throws Exception {
        byte[] bytes;
        try (InputStream encoded = AtmospherePipelineTest.class.getResourceAsStream(
                        "/prime/atmosphere/phase_lut.bin.gz.b64");
                InputStream decoded = Base64.getMimeDecoder().wrap(encoded);
                GZIPInputStream decompressed = new GZIPInputStream(decoded)) {
            bytes = decompressed.readAllBytes();
        }
        assertEquals(131_072, bytes.length);
        assertEquals(
                "43a6b9b1be8c690d6ab58f247a61ae753009f7f8fa99c51962ac2c59aec1d37b",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    @Test
    void minecraftBuildRangeUsesOneToOneAtmosphereScale() {
        assertEquals(0.0F, AtmospherePipeline.worldAltitudeKm(-128.0));
        assertEquals(0.064F, AtmospherePipeline.worldAltitudeKm(-64.0));
        assertEquals(0.448F, AtmospherePipeline.worldAltitudeKm(320.0));
        // The one-metre radius offset keeps ray/sphere tests numerically outside the ground while
        // the conceptual virtual-ground altitude remains exactly zero.
        assertEquals(6_360.001F, AtmospherePipeline.eyeRadiusKm(-128.0));
        assertEquals(6_360.064F, AtmospherePipeline.eyeRadiusKm(-64.0));
        assertEquals(6_360.448F, AtmospherePipeline.eyeRadiusKm(320.0));
    }

    @Test
    void atmosphereRadiusNeverLeavesTheLutShell() {
        assertEquals(6_360.001F, AtmospherePipeline.eyeRadiusKm(-1.0e9));
        assertEquals(6_459.999F, AtmospherePipeline.eyeRadiusKm(1.0e9));
    }
}
