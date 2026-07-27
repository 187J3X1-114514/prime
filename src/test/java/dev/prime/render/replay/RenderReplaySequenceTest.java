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
        changedWords[
                RenderStageSchema.RAW_WAVEFRONT.signalIndex(
                        "display.position")
                        * 4] ^= 1;
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
                frame.preparedNrd(),
                frame.postNrd());

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
                "display.position",
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

    @Test
    void comparatorIgnoresUndefinedInactiveRawBranchFields() {
        RenderReplayCapture frame =
                NrdInputSemanticValidatorTest.capture(false, false);
        int[] changedWords = frame.rawWavefront().words();
        changedWords[
                RenderStageSchema.RAW_WAVEFRONT.signalIndex(
                        "reflection.position")
                        * 4] ^= 0x7F00_0001;
        RenderReplayCapture changed = new RenderReplayCapture(
                frame.platform(),
                frame.binary(),
                frame.frame(),
                frame.nrdPreparation(),
                new CapturedRenderStage(
                        RenderStageSchema.RAW_WAVEFRONT,
                        frame.rawWavefront().width(),
                        frame.rawWavefront().height(),
                        changedWords),
                frame.preparedNrd(),
                frame.postNrd());

        RenderReplayComparator.Report comparison =
                RenderReplayComparator.compare(
                        new RenderReplaySequence(List.of(frame)),
                        new RenderReplaySequence(List.of(changed)));

        assertTrue(comparison.identical());
    }

    @Test
    void comparatorIncludesPostNrdOutput() {
        RenderReplayCapture frame =
                NrdInputSemanticValidatorTest.capture(false, false);
        int[] changedWords = frame.postNrd().words();
        changedWords[0] ^= 1;
        RenderReplayCapture changed = new RenderReplayCapture(
                frame.platform(),
                frame.binary(),
                frame.frame(),
                frame.nrdPreparation(),
                frame.rawWavefront(),
                frame.preparedNrd(),
                new CapturedRenderStage(
                        RenderStageSchema.POST_NRD,
                        frame.postNrd().width(),
                        frame.postNrd().height(),
                        changedWords));

        RenderReplayComparator.Report different =
                RenderReplayComparator.compare(
                        new RenderReplaySequence(List.of(frame)),
                        new RenderReplaySequence(List.of(changed)));

        assertFalse(different.identical());
        assertEquals(
                RenderStageSchema.POST_NRD,
                different.firstMismatch().stage());
        assertEquals(
                "composite.color",
                different.firstMismatch().signal());
    }
}
