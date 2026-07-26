package dev.prime.render.replay;

import dev.prime.render.SunDirection;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** Versioned fixed-width encoding of {@link NrdPreparationReplayInput}. */
public final class NrdPreparationReplayInputCodec {
    private static final int MAGIC = 0x314E_5250;
    private static final int VERSION = 2;
    private static final int ENCODED_BYTES =
            2 * Integer.BYTES
                    + 2 * FrameCameraSnapshot.ENCODED_BYTES
                    + 3 * Long.BYTES
                    + 4 * Integer.BYTES
                    + 3 * Integer.BYTES
                    + 4 * Integer.BYTES
                    + 2 * Integer.BYTES;

    private NrdPreparationReplayInputCodec() {
    }

    public static byte[] encode(NrdPreparationReplayInput input) {
        Objects.requireNonNull(input, "input");
        ByteBuffer output =
                ByteBuffer.allocate(ENCODED_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(MAGIC);
        output.putInt(VERSION);
        input.currentCamera().encode(output);
        input.historyCamera().encode(output);
        output.putLong(input.frameTimeNanos());
        output.putLong(input.sceneRevision());
        output.putLong(input.textureRevision());
        output.putInt(Float.floatToRawIntBits(input.currentJitterX()));
        output.putInt(Float.floatToRawIntBits(input.currentJitterY()));
        output.putInt(Float.floatToRawIntBits(input.historyJitterX()));
        output.putInt(Float.floatToRawIntBits(input.historyJitterY()));
        output.putInt(input.frameIndex());
        output.putInt(input.forceRestart() ? 1 : 0);
        output.putInt(input.restart() ? 1 : 0);
        output.putInt(Float.floatToRawIntBits(input.deltaMilliseconds()));
        output.putInt(Float.floatToRawIntBits(input.sunDirection().x()));
        output.putInt(Float.floatToRawIntBits(input.sunDirection().y()));
        output.putInt(Float.floatToRawIntBits(input.sunDirection().z()));
        output.putInt(input.diagnosticMode());
        output.putInt(input.nativeValidation() ? 1 : 0);
        if (output.hasRemaining()) {
            throw new AssertionError(
                    "NRD preparation replay size calculation is incomplete");
        }
        return output.array();
    }

    public static NrdPreparationReplayInput decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length != ENCODED_BYTES) {
            throw new IllegalArgumentException(
                    "NRD preparation replay has an unsupported byte size");
        }
        ByteBuffer input =
                ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        if (input.getInt() != MAGIC || input.getInt() != VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported NRD preparation replay header");
        }
        FrameCameraSnapshot current = FrameCameraSnapshot.decode(input);
        FrameCameraSnapshot history = FrameCameraSnapshot.decode(input);
        long frameTimeNanos = input.getLong();
        long sceneRevision = input.getLong();
        long textureRevision = input.getLong();
        float currentJitterX = Float.intBitsToFloat(input.getInt());
        float currentJitterY = Float.intBitsToFloat(input.getInt());
        float historyJitterX = Float.intBitsToFloat(input.getInt());
        float historyJitterY = Float.intBitsToFloat(input.getInt());
        int frameIndex = input.getInt();
        int forceRestart = input.getInt();
        if (forceRestart != 0 && forceRestart != 1) {
            throw new IllegalArgumentException(
                    "NRD preparation replay force-restart flag is invalid");
        }
        int restart = input.getInt();
        if (restart != 0 && restart != 1) {
            throw new IllegalArgumentException(
                    "NRD preparation replay restart flag is invalid");
        }
        float deltaMilliseconds = Float.intBitsToFloat(input.getInt());
        SunDirection sun = new SunDirection(
                Float.intBitsToFloat(input.getInt()),
                Float.intBitsToFloat(input.getInt()),
                Float.intBitsToFloat(input.getInt()));
        int diagnosticMode = input.getInt();
        int nativeValidation = input.getInt();
        if (nativeValidation != 0 && nativeValidation != 1) {
            throw new IllegalArgumentException(
                    "NRD preparation replay native-validation flag is invalid");
        }
        return new NrdPreparationReplayInput(
                current,
                history,
                frameTimeNanos,
                sceneRevision,
                textureRevision,
                currentJitterX,
                currentJitterY,
                historyJitterX,
                historyJitterY,
                frameIndex,
                forceRestart != 0,
                restart != 0,
                deltaMilliseconds,
                sun,
                diagnosticMode,
                nativeValidation != 0);
    }
}
