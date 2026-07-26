package dev.prime.render.replay;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/** Versioned, integrity-checked envelope for one replay sequence fixture. */
public final class RenderReplayFixtureCodec {
    public static final int MAX_ENCODED_BYTES = 256 * 1024 * 1024;

    private static final int MAGIC = 0x3158_4650;
    private static final int VERSION = 1;
    private static final int DIGEST_BYTES = 32;
    private static final int HEADER_BYTES =
            3 * Integer.BYTES + DIGEST_BYTES;

    private RenderReplayFixtureCodec() {
    }

    public static byte[] encode(RenderReplaySequence sequence) {
        Objects.requireNonNull(sequence, "sequence");
        byte[] payload = RenderReplaySequenceCodec.encode(sequence);
        long size = Math.addExact((long) HEADER_BYTES, payload.length);
        if (size > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException(
                    "Render replay fixture exceeds its format limit");
        }
        ByteBuffer output =
                ByteBuffer.allocate((int) size).order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(MAGIC);
        output.putInt(VERSION);
        output.putInt(payload.length);
        output.put(digest(payload));
        output.put(payload);
        return output.array();
    }

    public static RenderReplaySequence decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length < HEADER_BYTES
                || encoded.length > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException(
                    "Render replay fixture has an invalid byte size");
        }
        ByteBuffer input =
                ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        if (input.getInt() != MAGIC || input.getInt() != VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported render-replay-fixture header");
        }
        int payloadLength = input.getInt();
        byte[] expectedDigest = new byte[DIGEST_BYTES];
        input.get(expectedDigest);
        if (payloadLength < 0 || payloadLength != input.remaining()) {
            throw new IllegalArgumentException(
                    "Render replay fixture has an invalid payload length");
        }
        byte[] payload = new byte[payloadLength];
        input.get(payload);
        if (!MessageDigest.isEqual(expectedDigest, digest(payload))) {
            throw new IllegalArgumentException(
                    "Render replay fixture failed its integrity check");
        }
        return RenderReplaySequenceCodec.decode(payload);
    }

    private static byte[] digest(byte[] payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(
                    "Required SHA-256 algorithm is unavailable", exception);
        }
    }
}
