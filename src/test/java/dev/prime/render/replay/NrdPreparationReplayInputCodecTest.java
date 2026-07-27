package dev.prime.render.replay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.FrameCamera;
import dev.prime.render.SunDirection;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

final class NrdPreparationReplayInputCodecTest {
    private static final int HEADER_BYTES = 2 * Integer.BYTES;
    private static final int CAMERAS_BYTES =
            2 * FrameCameraSnapshot.ENCODED_BYTES;
    private static final int REVISIONS_BYTES = 3 * Long.BYTES;
    private static final int CURRENT_JITTER_X =
            HEADER_BYTES + CAMERAS_BYTES + REVISIONS_BYTES;
    private static final int SUN_X =
            CURRENT_JITTER_X + 8 * Integer.BYTES;
    private static final int DIAGNOSTIC_MODE =
            SUN_X + 3 * Integer.BYTES;

    @Test
    void completeCausalAndDerivedPlanRoundTripsCanonically() {
        FrameCamera camera = new FrameCamera(
                new Matrix4f(),
                new Matrix4f(),
                new Matrix4f(),
                1.0,
                2.0,
                3.0,
                1.0,
                2.0,
                3.0);
        NrdPreparationReplayInput input = NrdPreparationReplayInput.capture(
                camera,
                camera,
                123_456L,
                7L,
                11L,
                0.25F,
                -0.5F,
                0.125F,
                -0.25F,
                9,
                false,
                true,
                16.5F,
                new SunDirection(0.0F, 1.0F, 0.0F),
                2,
                false);

        byte[] encoded = NrdPreparationReplayInputCodec.encode(input);
        NrdPreparationReplayInput decoded =
                NrdPreparationReplayInputCodec.decode(encoded);

        assertArrayEquals(
                encoded, NrdPreparationReplayInputCodec.encode(decoded));
        assertEquals(123_456L, decoded.frameTimeNanos());
        assertEquals(7L, decoded.sceneRevision());
        assertEquals(11L, decoded.textureRevision());
        assertEquals(9, decoded.frameIndex());
        assertFalse(decoded.forceRestart());
        assertTrue(decoded.restart());
        assertEquals(16.5F, decoded.deltaMilliseconds());
        assertEquals(2, decoded.diagnosticMode());
        assertFalse(decoded.nativeValidation());
        assertEquals(
                Double.doubleToRawLongBits(camera.x()),
                Double.doubleToRawLongBits(
                        decoded.currentCamera().materialize().x()));
    }

    @Test
    void decodeRejectsInvalidCameraJitterAndSunContracts() {
        byte[] canonical = canonicalEncoding();

        assertThrows(
                IllegalArgumentException.class,
                () -> NrdPreparationReplayInputCodec.decode(
                        withFloat(canonical, HEADER_BYTES, Float.NaN)));
        assertThrows(
                IllegalArgumentException.class,
                () -> NrdPreparationReplayInputCodec.decode(
                        withFloat(canonical, CURRENT_JITTER_X, 0.5001F)));
        assertThrows(
                IllegalArgumentException.class,
                () -> NrdPreparationReplayInputCodec.decode(
                        withFloat(canonical, SUN_X, 1.0F)));
        assertThrows(
                IllegalArgumentException.class,
                () -> NrdPreparationReplayInputCodec.decode(
                        withInt(canonical, DIAGNOSTIC_MODE, 7)));
    }

    private static byte[] canonicalEncoding() {
        FrameCamera camera = new FrameCamera(
                new Matrix4f(),
                new Matrix4f(),
                new Matrix4f(),
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0);
        return NrdPreparationReplayInputCodec.encode(
                NrdPreparationReplayInput.capture(
                        camera,
                        camera,
                        0L,
                        0L,
                        0L,
                        0.0F,
                        0.0F,
                        0.0F,
                        0.0F,
                        0,
                        true,
                        true,
                        0.0F,
                        new SunDirection(0.0F, 1.0F, 0.0F),
                        0,
                        false));
    }

    private static byte[] withFloat(
            byte[] source, int offset, float value) {
        byte[] result = source.clone();
        ByteBuffer.wrap(result)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putFloat(offset, value);
        return result;
    }

    private static byte[] withInt(
            byte[] source, int offset, int value) {
        byte[] result = source.clone();
        ByteBuffer.wrap(result)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(offset, value);
        return result;
    }
}
