package dev.prime.render.replay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.FrameCamera;
import dev.prime.render.SunDirection;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

final class NrdPreparationReplayInputCodecTest {
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
}
