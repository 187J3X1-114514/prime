package dev.prime.render.replay;

import java.io.IOException;
import java.nio.file.Path;

/** Offline validator and strict same-platform comparator for captured replay fixtures. */
public final class RenderReplayFixtureTool {
    private RenderReplayFixtureTool() {
    }

    public static void main(String[] arguments) throws IOException {
        if (arguments.length == 2 && arguments[0].equals("validate")) {
            RenderReplaySequence sequence =
                    RenderReplayFixtureStore.load(Path.of(arguments[1]));
            System.out.println(summary("valid", sequence));
            return;
        }
        if (arguments.length == 3 && arguments[0].equals("compare")) {
            RenderReplaySequence expected =
                    RenderReplayFixtureStore.load(Path.of(arguments[1]));
            RenderReplaySequence actual =
                    RenderReplayFixtureStore.load(Path.of(arguments[2]));
            RenderReplayVerification verification =
                    RenderReplayVerification.compare(expected, actual);
            verification.requireValid();
            System.out.println(summary("identical", actual));
            return;
        }
        throw new IllegalArgumentException(
                "Usage: validate <fixture> | compare <expected> <actual>");
    }

    private static String summary(
            String verdict, RenderReplaySequence sequence) {
        return verdict
                + " sha256="
                + RenderReplaySequenceCodec.sha256(sequence)
                + " frames="
                + sequence.frames().size()
                + " extent="
                + sequence.width()
                + "x"
                + sequence.height()
                + " platform="
                + sequence.platform().sha256();
    }
}
