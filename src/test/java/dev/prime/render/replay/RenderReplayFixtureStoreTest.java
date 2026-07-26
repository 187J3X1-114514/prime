package dev.prime.render.replay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RenderReplayFixtureStoreTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void validFixtureRoundTripsAndReplacesAtomically() throws IOException {
        RenderReplaySequence first = validSequence();
        RenderReplaySequence second =
                new RenderReplaySequence(List.of(
                        first.frames().getFirst(),
                        first.frames().getFirst()));
        Path fixture = this.temporaryDirectory.resolve("nested/reference.prseq");

        RenderReplayFixtureStore.save(fixture, first);
        RenderReplayFixtureStore.save(fixture, second);
        RenderReplaySequence loaded =
                RenderReplayFixtureStore.load(fixture);

        assertArrayEquals(
                RenderReplaySequenceCodec.encode(second),
                RenderReplaySequenceCodec.encode(loaded));
        try (var children = Files.list(fixture.getParent())) {
            assertArrayEquals(
                    new String[] {"reference.prseq"},
                    children.map(path -> path.getFileName().toString())
                            .sorted()
                            .toArray(String[]::new));
        }
    }

    @Test
    void truncatedFixtureIsRejected() throws IOException {
        byte[] encoded =
                RenderReplayFixtureCodec.encode(validSequence());
        Path fixture = this.temporaryDirectory.resolve("truncated.prseq");
        Files.write(
                fixture,
                Arrays.copyOf(encoded, encoded.length - 1),
                StandardOpenOption.CREATE_NEW);

        assertThrows(
                IOException.class,
                () -> RenderReplayFixtureStore.load(fixture));
    }

    @Test
    void payloadBitFlipIsRejectedByIntegrityCheck() throws IOException {
        byte[] encoded =
                RenderReplayFixtureCodec.encode(validSequence());
        encoded[encoded.length - 1] ^= 1;
        Path fixture = this.temporaryDirectory.resolve("corrupt.prseq");
        Files.write(
                fixture, encoded, StandardOpenOption.CREATE_NEW);

        assertThrows(
                IOException.class,
                () -> RenderReplayFixtureStore.load(fixture));
    }

    @Test
    void oversizedFixtureIsRejectedBeforeDecode() throws IOException {
        Path fixture = this.temporaryDirectory.resolve("oversized.prseq");
        try (var output = java.nio.channels.FileChannel.open(
                fixture,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            output.position(RenderReplayFixtureStore.MAX_FILE_BYTES);
            output.write(java.nio.ByteBuffer.wrap(new byte[] {0}));
        }

        assertThrows(
                IOException.class,
                () -> RenderReplayFixtureStore.load(fixture));
    }

    @Test
    void semanticallyInvalidFixtureIsRejected() throws IOException {
        RenderReplayCapture invalid =
                NrdInputSemanticValidatorTest.capture(true, false);
        Path fixture = this.temporaryDirectory.resolve("invalid.prseq");
        Files.write(
                fixture,
                RenderReplayFixtureCodec.encode(
                        new RenderReplaySequence(List.of(invalid))),
                StandardOpenOption.CREATE_NEW);

        assertThrows(
                IOException.class,
                () -> RenderReplayFixtureStore.load(fixture));
    }

    private static RenderReplaySequence validSequence() {
        return new RenderReplaySequence(List.of(
                NrdInputSemanticValidatorTest.capture(false, false)));
    }
}
