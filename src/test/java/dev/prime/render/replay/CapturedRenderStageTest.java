package dev.prime.render.replay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class CapturedRenderStageTest {
    @Test
    void signalMajorPayloadRoundTripsWithoutChangingFloatBits() {
        RenderStageSchema schema = RenderStageSchema.PREPARED_NRD;
        int[] words = new int[schema.signalCount() * 2 * 4];
        for (int index = 0; index < words.length; index++) {
            words[index] = 0x3f00_0000 + index;
        }
        CapturedRenderStage captured =
                new CapturedRenderStage(schema, 2, 1, words);

        CapturedRenderStage decoded =
                CapturedRenderStage.decode(captured.encode());

        assertEquals(schema, decoded.schema());
        assertEquals(
                words[7 * 2 * 4 + 1 * 4 + 3],
                decoded.rawWord("reflection.motion", 1, 0, 3));
        assertArrayEquals(words, decoded.words());
        assertEquals(captured.sha256(), decoded.sha256());
    }

    @Test
    void malformedPayloadIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CapturedRenderStage(
                        RenderStageSchema.RAW_WAVEFRONT,
                        1,
                        1,
                        new int[1]));
    }
}
