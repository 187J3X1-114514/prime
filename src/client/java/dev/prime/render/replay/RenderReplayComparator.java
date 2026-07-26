package dev.prime.render.replay;

import java.util.Arrays;
import java.util.Objects;

/** Finds the first same-platform bitwise replay divergence. */
public final class RenderReplayComparator {
    private RenderReplayComparator() {
    }

    public static Report compare(
            RenderReplaySequence expected, RenderReplaySequence actual) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(actual, "actual");
        if (!expected.platform().isStrictlyCompatibleWith(actual.platform())) {
            return new Report(new Mismatch(
                    -1,
                    "platform",
                    null,
                    null,
                    -1,
                    -1,
                    -1,
                    -1,
                    -1));
        }
        if (!expected.frames()
                .getFirst()
                .binary()
                .isStrictlyCompatibleWith(
                        actual.frames().getFirst().binary())) {
            return new Report(new Mismatch(
                    -1,
                    "binary",
                    null,
                    null,
                    -1,
                    -1,
                    -1,
                    -1,
                    -1));
        }
        if (expected.frames().size() != actual.frames().size()) {
            return new Report(new Mismatch(
                    -1,
                    "frame_count",
                    null,
                    null,
                    -1,
                    -1,
                    -1,
                    expected.frames().size(),
                    actual.frames().size()));
        }
        for (int index = 0; index < expected.frames().size(); index++) {
            RenderReplayCapture expectedFrame = expected.frames().get(index);
            RenderReplayCapture actualFrame = actual.frames().get(index);
            Mismatch mismatch = compareBytes(
                    index,
                    "ray_trace_input",
                    RayTraceReplayInputCodec.encode(expectedFrame.frame()),
                    RayTraceReplayInputCodec.encode(actualFrame.frame()));
            if (mismatch != null) {
                return new Report(mismatch);
            }
            mismatch = compareBytes(
                    index,
                    "nrd_preparation_input",
                    NrdPreparationReplayInputCodec.encode(
                            expectedFrame.nrdPreparation()),
                    NrdPreparationReplayInputCodec.encode(
                            actualFrame.nrdPreparation()));
            if (mismatch != null) {
                return new Report(mismatch);
            }
            mismatch = compareStage(
                    index,
                    expectedFrame.rawWavefront(),
                    actualFrame.rawWavefront());
            if (mismatch != null) {
                return new Report(mismatch);
            }
            mismatch = compareStage(
                    index,
                    expectedFrame.preparedNrd(),
                    actualFrame.preparedNrd());
            if (mismatch != null) {
                return new Report(mismatch);
            }
        }
        return new Report(null);
    }

    private static Mismatch compareBytes(
            int frame, String component, byte[] expected, byte[] actual) {
        int common = Math.min(expected.length, actual.length);
        for (int offset = 0; offset < common; offset++) {
            if (expected[offset] != actual[offset]) {
                return new Mismatch(
                        frame,
                        component,
                        null,
                        null,
                        offset,
                        -1,
                        -1,
                        Byte.toUnsignedInt(expected[offset]),
                        Byte.toUnsignedInt(actual[offset]));
            }
        }
        if (expected.length != actual.length) {
            return new Mismatch(
                    frame,
                    component + "_length",
                    null,
                    null,
                    common,
                    -1,
                    -1,
                    expected.length,
                    actual.length);
        }
        return null;
    }

    private static Mismatch compareStage(
            int frame,
            CapturedRenderStage expected,
            CapturedRenderStage actual) {
        if (expected.schema() != actual.schema()
                || expected.width() != actual.width()
                || expected.height() != actual.height()) {
            return new Mismatch(
                    frame,
                    "stage_shape",
                    expected.schema(),
                    null,
                    -1,
                    -1,
                    -1,
                    Arrays.hashCode(new int[] {
                        expected.schema().ordinal(),
                        expected.width(),
                        expected.height()
                    }),
                    Arrays.hashCode(new int[] {
                        actual.schema().ordinal(),
                        actual.width(),
                        actual.height()
                    }));
        }
        for (String signal : expected.schema().signals()) {
            for (int y = 0; y < expected.height(); y++) {
                for (int x = 0; x < expected.width(); x++) {
                    for (int channel = 0; channel < 4; channel++) {
                        int expectedBits =
                                expected.rawWord(signal, x, y, channel);
                        int actualBits =
                                actual.rawWord(signal, x, y, channel);
                        if (expectedBits != actualBits) {
                            return new Mismatch(
                                    frame,
                                    "stage",
                                    expected.schema(),
                                    signal,
                                    -1,
                                    x,
                                    y,
                                    expectedBits,
                                    actualBits,
                                    channel);
                        }
                    }
                }
            }
        }
        return null;
    }

    public record Report(Mismatch firstMismatch) {
        public boolean identical() {
            return this.firstMismatch == null;
        }

        public void requireIdentical() {
            if (!identical()) {
                throw new IllegalStateException(
                        "Render replay diverged at " + this.firstMismatch);
            }
        }
    }

    public record Mismatch(
            int frame,
            String component,
            RenderStageSchema stage,
            String signal,
            int byteOffset,
            int x,
            int y,
            int expectedBits,
            int actualBits,
            int channel) {
        private Mismatch(
                int frame,
                String component,
                RenderStageSchema stage,
                String signal,
                int byteOffset,
                int x,
                int y,
                int expectedBits,
                int actualBits) {
            this(
                    frame,
                    component,
                    stage,
                    signal,
                    byteOffset,
                    x,
                    y,
                    expectedBits,
                    actualBits,
                    -1);
        }
    }
}
