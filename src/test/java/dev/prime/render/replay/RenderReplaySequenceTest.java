package dev.prime.render.replay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RenderReplaySequenceTest {
    @Test
    void sequenceRoundTripsCanonically() {
        RenderReplayCapture frame =
                NrdInputSemanticValidatorTest.capture(false, false);
        RenderReplaySequence sequence =
                new RenderReplaySequence(List.of(frame, frame));
        byte[] encoded = RenderReplaySequenceCodec.encode(sequence);

        RenderReplaySequence decoded =
                RenderReplaySequenceCodec.decode(encoded);

        assertArrayEquals(
                encoded, RenderReplaySequenceCodec.encode(decoded));
        assertEquals(
                RenderReplaySequenceCodec.sha256(sequence),
                RenderReplaySequenceCodec.sha256(decoded));
    }

    @Test
    void truncatedSequenceIsRejected() {
        RenderReplayCapture frame =
                NrdInputSemanticValidatorTest.capture(false, false);
        byte[] encoded = RenderReplaySequenceCodec.encode(
                new RenderReplaySequence(List.of(frame)));

        assertThrows(
                IllegalArgumentException.class,
                () -> RenderReplaySequenceCodec.decode(
                        Arrays.copyOf(encoded, encoded.length - 1)));
    }

    @Test
    void comparatorLocatesFirstStageWordDifference() {
        RenderReplayCapture frame =
                NrdInputSemanticValidatorTest.capture(false, false);
        int[] changedWords = frame.rawWavefront().words();
        changedWords[0] ^= 1;
        RenderReplayCapture changed = new RenderReplayCapture(
                frame.platform(),
                frame.binary(),
                frame.frame(),
                frame.nrdPreparation(),
                new CapturedRenderStage(
                        frame.rawWavefront().schema(),
                        frame.rawWavefront().width(),
                        frame.rawWavefront().height(),
                        changedWords),
                frame.preparedNrd());

        RenderReplayComparator.Report same =
                RenderReplayComparator.compare(
                        new RenderReplaySequence(List.of(frame)),
                        new RenderReplaySequence(List.of(frame)));
        RenderReplayComparator.Report different =
                RenderReplayComparator.compare(
                        new RenderReplaySequence(List.of(frame)),
                        new RenderReplaySequence(List.of(changed)));

        assertTrue(same.identical());
        assertFalse(different.identical());
        assertEquals(
                RenderStageSchema.RAW_WAVEFRONT,
                different.firstMismatch().stage());
        assertEquals(
                "primary.view_z",
                different.firstMismatch().signal());
        assertTrue(RenderReplayVerification.compare(
                        new RenderReplaySequence(List.of(frame)),
                        new RenderReplaySequence(List.of(frame)))
                .valid());
        assertFalse(RenderReplayVerification.compare(
                        new RenderReplaySequence(List.of(frame)),
                        new RenderReplaySequence(List.of(changed)))
                .valid());
    }
}
