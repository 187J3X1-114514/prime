package dev.prime.render.replay;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Objects;

/** Versioned container for an ordered production-render replay sequence. */
public final class RenderReplaySequenceCodec {
    private static final int MAGIC = 0x3151_5250;
    private static final int VERSION = 1;
    private static final int MAX_FRAMES = 64;
    private static final int MAX_BYTES = 512 * 1024 * 1024;

    private RenderReplaySequenceCodec() {
    }

    public static byte[] encode(RenderReplaySequence sequence) {
        Objects.requireNonNull(sequence, "sequence");
        ArrayList<byte[]> frames = new ArrayList<>(sequence.frames().size());
        long size = 3L * Integer.BYTES;
        for (RenderReplayCapture frame : sequence.frames()) {
            byte[] encoded = RenderReplayCaptureCodec.encode(frame);
            frames.add(encoded);
            size = Math.addExact(
                    size, Math.addExact(Integer.BYTES, encoded.length));
        }
        if (frames.size() > MAX_FRAMES || size > MAX_BYTES) {
            throw new IllegalArgumentException(
                    "Render replay sequence exceeds its format limits");
        }
        ByteBuffer output =
                ByteBuffer.allocate((int) size).order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(MAGIC);
        output.putInt(VERSION);
        output.putInt(frames.size());
        for (byte[] frame : frames) {
            output.putInt(frame.length);
            output.put(frame);
        }
        return output.array();
    }

    public static RenderReplaySequence decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length < 3 * Integer.BYTES
                || encoded.length > MAX_BYTES) {
            throw new IllegalArgumentException(
                    "Render replay sequence has an invalid byte size");
        }
        ByteBuffer input =
                ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        if (input.getInt() != MAGIC || input.getInt() != VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported render-replay-sequence header");
        }
        int count = input.getInt();
        if (count <= 0 || count > MAX_FRAMES) {
            throw new IllegalArgumentException(
                    "Render replay sequence has an invalid frame count");
        }
        ArrayList<RenderReplayCapture> frames = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            if (input.remaining() < Integer.BYTES) {
                throw new IllegalArgumentException(
                        "Render replay sequence is truncated before frame "
                                + index);
            }
            int length = input.getInt();
            if (length < 0 || length > input.remaining()) {
                throw new IllegalArgumentException(
                        "Render replay sequence frame " + index
                                + " is truncated");
            }
            byte[] frame = new byte[length];
            input.get(frame);
            frames.add(RenderReplayCaptureCodec.decode(frame));
        }
        if (input.hasRemaining()) {
            throw new IllegalArgumentException(
                    "Render replay sequence contains trailing data");
        }
        return new RenderReplaySequence(frames);
    }

    public static String sha256(RenderReplaySequence sequence) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(encode(sequence)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(
                    "Required SHA-256 algorithm is unavailable", exception);
        }
    }
}
