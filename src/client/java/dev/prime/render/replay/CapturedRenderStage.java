package dev.prime.render.replay;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Canonical float32 shader-visible values captured signal-major from one GPU stage. */
public final class CapturedRenderStage {
    private static final int MAGIC = 0x3153_5250;
    private static final int VERSION = 1;
    private static final int HEADER_BYTES = 6 * Integer.BYTES;
    private static final int CHANNELS = 4;

    private final RenderStageSchema schema;
    private final int width;
    private final int height;
    private final int[] words;

    public CapturedRenderStage(
            RenderStageSchema schema, int width, int height, int[] words) {
        this.schema = Objects.requireNonNull(schema, "schema");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Captured render-stage extent must be positive");
        }
        Objects.requireNonNull(words, "words");
        int expected = Math.multiplyExact(
                Math.multiplyExact(
                        Math.multiplyExact(width, height),
                        schema.signalCount()),
                CHANNELS);
        if (words.length != expected) {
            throw new IllegalArgumentException(
                    "Captured render-stage payload has the wrong size");
        }
        this.width = width;
        this.height = height;
        this.words = words.clone();
    }

    public RenderStageSchema schema() {
        return this.schema;
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    public int rawWord(String signal, int x, int y, int channel) {
        return this.words[index(this.schema.signalIndex(signal), x, y, channel)];
    }

    public float value(String signal, int x, int y, int channel) {
        return Float.intBitsToFloat(rawWord(signal, x, y, channel));
    }

    public int[] words() {
        return this.words.clone();
    }

    public byte[] encode() {
        ByteBuffer output = ByteBuffer.allocate(
                        Math.addExact(
                                HEADER_BYTES,
                                Math.multiplyExact(this.words.length, Integer.BYTES)))
                .order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(MAGIC);
        output.putInt(VERSION);
        output.putInt(this.schema.ordinal());
        output.putInt(this.width);
        output.putInt(this.height);
        output.putInt(this.words.length);
        for (int word : this.words) {
            output.putInt(word);
        }
        return output.array();
    }

    public static CapturedRenderStage decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length < HEADER_BYTES) {
            throw new IllegalArgumentException(
                    "Captured render stage is truncated");
        }
        ByteBuffer input =
                ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        if (input.getInt() != MAGIC || input.getInt() != VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported captured render-stage header");
        }
        int schemaIndex = input.getInt();
        RenderStageSchema[] schemas = RenderStageSchema.values();
        if (schemaIndex < 0 || schemaIndex >= schemas.length) {
            throw new IllegalArgumentException(
                    "Captured render stage has an unknown schema");
        }
        int width = input.getInt();
        int height = input.getInt();
        int count = input.getInt();
        if (count < 0
                || input.remaining() != (long) count * Integer.BYTES) {
            throw new IllegalArgumentException(
                    "Captured render-stage payload length is invalid");
        }
        int[] words = new int[count];
        for (int index = 0; index < count; index++) {
            words[index] = input.getInt();
        }
        try {
            return new CapturedRenderStage(
                    schemas[schemaIndex], width, height, words);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "Captured render-stage extent overflows its payload", exception);
        }
    }

    public String sha256() {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(encode()));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(
                    "Required SHA-256 algorithm is unavailable", exception);
        }
    }

    private int index(int signal, int x, int y, int channel) {
        if (x < 0 || x >= this.width
                || y < 0 || y >= this.height
                || channel < 0 || channel >= CHANNELS) {
            throw new IndexOutOfBoundsException(
                    "Captured render-stage coordinate is outside its payload");
        }
        int pixelCount = Math.multiplyExact(this.width, this.height);
        return Math.addExact(
                Math.multiplyExact(
                        Math.addExact(
                                Math.multiplyExact(signal, pixelCount),
                                Math.addExact(
                                        Math.multiplyExact(y, this.width), x)),
                        CHANNELS),
                channel);
    }
}
