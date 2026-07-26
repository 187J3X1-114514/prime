package dev.prime.render.replay;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Bounded filesystem boundary for versioned low-resolution replay fixtures.
 *
 * <p>Encoding and semantic validation stay pure. Only this class owns replay-fixture I/O.
 */
public final class RenderReplayFixtureStore {
    public static final long MAX_FILE_BYTES =
            RenderReplayFixtureCodec.MAX_ENCODED_BYTES;

    private RenderReplayFixtureStore() {
    }

    /**
     * Validates and durably replaces one fixture.
     *
     * <p>The temporary file is forced before publication. Atomic replacement is used where the
     * filesystem supports it; the fallback still never exposes a partially written target.
     */
    public static void save(Path target, RenderReplaySequence sequence)
            throws IOException {
        Objects.requireNonNull(target, "target");
        requireValid(sequence);
        byte[] encoded = RenderReplayFixtureCodec.encode(sequence);
        if (encoded.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException(
                    "Replay fixture exceeds the filesystem boundary limit");
        }

        Path absolute = target.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        Path name = absolute.getFileName();
        if (parent == null || name == null) {
            throw new IllegalArgumentException(
                    "Replay fixture target must name a file");
        }
        Files.createDirectories(parent);
        Path temporary =
                Files.createTempFile(parent, "." + name + ".", ".tmp");
        try {
            try (FileChannel output = FileChannel.open(
                    temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer bytes = ByteBuffer.wrap(encoded);
                while (bytes.hasRemaining()) {
                    output.write(bytes);
                }
                output.force(true);
            }
            replace(temporary, absolute);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Loads one bounded fixture and rejects malformed containers or invalid NRD semantics.
     */
    public static RenderReplaySequence load(Path source) throws IOException {
        Objects.requireNonNull(source, "source");
        byte[] encoded = readBounded(source.toAbsolutePath().normalize());
        RenderReplaySequence sequence;
        try {
            sequence = RenderReplayFixtureCodec.decode(encoded);
            requireValid(sequence);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new IOException(
                    "Invalid render replay fixture " + source, exception);
        }
        return sequence;
    }

    private static void requireValid(RenderReplaySequence sequence) {
        Objects.requireNonNull(sequence, "sequence");
        NrdInputSemanticValidator.validate(sequence).requireValid();
    }

    private static byte[] readBounded(Path source) throws IOException {
        try (FileChannel input =
                FileChannel.open(source, StandardOpenOption.READ)) {
            long size = input.size();
            if (size < 0L || size > MAX_FILE_BYTES) {
                throw new IOException(
                        "Replay fixture has an invalid byte size: " + size);
            }
            byte[] encoded = new byte[Math.toIntExact(size)];
            ByteBuffer destination = ByteBuffer.wrap(encoded);
            while (destination.hasRemaining()) {
                if (input.read(destination) < 0) {
                    throw new EOFException(
                            "Replay fixture changed or ended while loading");
                }
            }
            ByteBuffer extra = ByteBuffer.allocate(1);
            if (input.read(extra) >= 0) {
                throw new IOException(
                        "Replay fixture changed or grew while loading");
            }
            return encoded;
        }
    }

    private static void replace(Path temporary, Path target)
            throws IOException {
        try {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
