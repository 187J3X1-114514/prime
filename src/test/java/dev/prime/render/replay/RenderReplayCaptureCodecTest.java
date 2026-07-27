package dev.prime.render.replay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class RenderReplayCaptureCodecTest {
    @Test
    void completeCaptureRoundTripsCanonically() {
        RenderReplayCapture capture =
                NrdInputSemanticValidatorTest.capture(false, false);
        byte[] encoded = RenderReplayCaptureCodec.encode(capture);

        RenderReplayCapture decoded =
                RenderReplayCaptureCodec.decode(encoded);

        assertArrayEquals(
                encoded, RenderReplayCaptureCodec.encode(decoded));
        assertEquals(
                capture.platform().sha256(), decoded.platform().sha256());
        assertEquals(
                capture.rawWavefront().sha256(),
                decoded.rawWavefront().sha256());
        assertEquals(
                capture.preparedNrd().sha256(),
                decoded.preparedNrd().sha256());
        assertEquals(
                capture.postNrd().sha256(),
                decoded.postNrd().sha256());
        assertEquals(
                RenderReplayCaptureCodec.sha256(capture),
                RenderReplayCaptureCodec.sha256(decoded));
    }

    @Test
    void truncatedCaptureIsRejected() {
        byte[] encoded = RenderReplayCaptureCodec.encode(
                NrdInputSemanticValidatorTest.capture(false, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> RenderReplayCaptureCodec.decode(
                        java.util.Arrays.copyOf(
                                encoded, encoded.length - 1)));
    }
}
