package dev.prime.render.replay;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Versioned container for one complete Prime-owned raw-to-NRD replay observation. */
public final class RenderReplayCaptureCodec {
    private static final int MAGIC = 0x3152_5250;
    private static final int VERSION = 3;
    private static final int CHUNK_COUNT = 7;
    private static final int MAX_BYTES = 512 * 1024 * 1024;

    private RenderReplayCaptureCodec() {
    }

    public static byte[] encode(RenderReplayCapture capture) {
        Objects.requireNonNull(capture, "capture");
        byte[][] chunks = {
            capture.platform().canonicalBytes(),
            capture.binary().canonicalBytes(),
            RayTraceReplayInputCodec.encode(capture.frame()),
            NrdPreparationReplayInputCodec.encode(capture.nrdPreparation()),
            capture.rawWavefront().encode(),
            capture.preparedNrd().encode(),
            capture.postNrd().encode()
        };
        long size = 2L * Integer.BYTES;
        for (byte[] chunk : chunks) {
            size = Math.addExact(
                    size, Math.addExact(Integer.BYTES, chunk.length));
        }
        if (size > MAX_BYTES) {
            throw new IllegalArgumentException(
                    "Render replay exceeds the file-size limit");
        }
        ByteBuffer output =
                ByteBuffer.allocate((int) size).order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(MAGIC);
        output.putInt(VERSION);
        for (byte[] chunk : chunks) {
            output.putInt(chunk.length);
            output.put(chunk);
        }
        return output.array();
    }

    public static RenderReplayCapture decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length > MAX_BYTES
                || encoded.length < 2 * Integer.BYTES
                        + CHUNK_COUNT * Integer.BYTES) {
            throw new IllegalArgumentException(
                    "Render replay has an invalid byte size");
        }
        ByteBuffer input =
                ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        if (input.getInt() != MAGIC || input.getInt() != VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported render-replay header");
        }
        byte[][] chunks = new byte[CHUNK_COUNT][];
        for (int index = 0; index < chunks.length; index++) {
            if (input.remaining() < Integer.BYTES) {
                throw new IllegalArgumentException(
                        "Render replay is truncated before chunk " + index);
            }
            int length = input.getInt();
            if (length < 0 || length > input.remaining()) {
                throw new IllegalArgumentException(
                        "Render replay chunk " + index + " is truncated");
            }
            chunks[index] = new byte[length];
            input.get(chunks[index]);
        }
        if (input.hasRemaining()) {
            throw new IllegalArgumentException(
                    "Render replay contains trailing data");
        }
        return new RenderReplayCapture(
                RenderPlatformFingerprint.decode(chunks[0]),
                RenderBinaryFingerprint.decode(chunks[1]),
                RayTraceReplayInputCodec.decode(chunks[2]),
                NrdPreparationReplayInputCodec.decode(chunks[3]),
                CapturedRenderStage.decode(chunks[4]),
                CapturedRenderStage.decode(chunks[5]),
                CapturedRenderStage.decode(chunks[6]));
    }

    public static String sha256(RenderReplayCapture capture) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(encode(capture)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(
                    "Required SHA-256 algorithm is unavailable", exception);
        }
    }
}
