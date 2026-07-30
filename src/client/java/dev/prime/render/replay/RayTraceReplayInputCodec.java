package dev.prime.render.replay;

import dev.prime.render.AstronomySettings;
import dev.prime.render.AstronomyState;
import dev.prime.render.LightingSettings;
import dev.prime.render.MaterialSettings;
import dev.prime.render.SunDirection;
import dev.prime.render.post.PostProcessingMode;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** Versioned fixed-width encoding of {@link RayTraceReplayInput}. */
public final class RayTraceReplayInputCodec {
    private static final int MAGIC = 0x3146_5250;
    private static final int VERSION = 2;
    private static final int ENCODED_BYTES = 384;
    private static final int FLAG_CAMERA_IN_WATER = 1;
    private static final int FLAG_SH_INPUT = 1 << 1;
    private static final int FLAG_RAW_NUMERICAL = 1 << 2;
    private static final int FLAG_TRIANGLE_DEBUG = 1 << 3;
    private static final int VALID_FLAGS = FLAG_CAMERA_IN_WATER
            | FLAG_SH_INPUT
            | FLAG_RAW_NUMERICAL
            | FLAG_TRIANGLE_DEBUG;

    private RayTraceReplayInputCodec() {
    }

    public static byte[] encode(RayTraceReplayInput input) {
        Objects.requireNonNull(input, "input");
        ByteBuffer output =
                ByteBuffer.allocate(ENCODED_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(MAGIC);
        output.putInt(VERSION);
        output.putInt(input.scene().originX());
        output.putInt(input.scene().originY());
        output.putInt(input.scene().originZ());
        output.putLong(input.scene().revision());
        output.putLong(input.scene().resetRevision());
        output.putLong(input.scene().temporalRevision());
        input.camera().encode(output);
        output.putInt(input.width());
        output.putInt(input.height());
        output.putInt(Float.floatToRawIntBits(input.sunDirection().x()));
        output.putInt(Float.floatToRawIntBits(input.sunDirection().y()));
        output.putInt(Float.floatToRawIntBits(input.sunDirection().z()));
        output.putInt(input.astronomy().latitudeDegrees());
        output.putInt(input.astronomy().solarLongitudeDegrees());
        output.putInt(input.packedRayCone());
        output.putInt(input.sampleIndex());
        output.putInt(input.sampleEpoch());
        output.putInt(input.jitterPhase());
        output.putInt(flags(input));
        output.putInt(mode(input.postProcessingMode()));
        output.putInt(input.lighting().sunQuarterSteps());
        output.putInt(input.lighting().starQuarterSteps());
        output.putInt(input.lighting().blockLightQuarterSteps());
        output.putInt(Float.floatToRawIntBits(input.lighting().sunMultiplier()));
        output.putInt(Float.floatToRawIntBits(input.lighting().starMultiplier()));
        output.putInt(
                Float.floatToRawIntBits(input.lighting().blockLightMultiplier()));
        output.putLong(input.lighting().revision());
        output.putInt(input.material().roughnessSteps());
        output.putInt(
                Float.floatToRawIntBits(input.material().linearRoughness()));
        output.putLong(input.material().revision());
        if (output.hasRemaining()) {
            throw new AssertionError("Ray-trace replay size calculation is incomplete");
        }
        return output.array();
    }

    public static RayTraceReplayInput decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length != ENCODED_BYTES) {
            throw new IllegalArgumentException(
                    "Ray-trace replay has an unsupported byte size");
        }
        ByteBuffer input =
                ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        try {
            if (input.getInt() != MAGIC || input.getInt() != VERSION) {
                throw new IllegalArgumentException(
                        "Unsupported ray-trace replay header");
            }
            RayTraceReplayInput.SceneIdentity scene =
                    new RayTraceReplayInput.SceneIdentity(
                            input.getInt(),
                            input.getInt(),
                            input.getInt(),
                            input.getLong(),
                            input.getLong(),
                            input.getLong());
            FrameCameraSnapshot camera = FrameCameraSnapshot.decode(input);
            int width = input.getInt();
            int height = input.getInt();
            SunDirection sun = new SunDirection(
                    Float.intBitsToFloat(input.getInt()),
                    Float.intBitsToFloat(input.getInt()),
                    Float.intBitsToFloat(input.getInt()));
            AstronomyState astronomy = new AstronomyState(
                    new AstronomySettings(input.getInt(), input.getInt()),
                    sun);
            int packedRayCone = input.getInt();
            int sampleIndex = input.getInt();
            int sampleEpoch = input.getInt();
            int jitterPhase = input.getInt();
            int flags = input.getInt();
            if ((flags & ~VALID_FLAGS) != 0) {
                throw new IllegalArgumentException(
                        "Ray-trace replay contains unknown flags");
            }
            PostProcessingMode mode = mode(input.getInt());
            int sunQuarterSteps = input.getInt();
            int starQuarterSteps = input.getInt();
            int blockLightQuarterSteps = input.getInt();
            int sunMultiplierBits = input.getInt();
            int starMultiplierBits = input.getInt();
            int blockLightMultiplierBits = input.getInt();
            LightingSettings.Snapshot lighting =
                    new LightingSettings.Snapshot(
                            sunQuarterSteps,
                            starQuarterSteps,
                            blockLightQuarterSteps,
                            input.getLong());
            requireDerivedValue(
                    sunMultiplierBits,
                    lighting.sunMultiplier(),
                    "sun multiplier");
            requireDerivedValue(
                    starMultiplierBits,
                    lighting.starMultiplier(),
                    "star multiplier");
            requireDerivedValue(
                    blockLightMultiplierBits,
                    lighting.blockLightMultiplier(),
                    "block-light multiplier");
            int roughnessSteps = input.getInt();
            int linearRoughnessBits = input.getInt();
            MaterialSettings.Snapshot material =
                    new MaterialSettings.Snapshot(
                            roughnessSteps,
                            input.getLong());
            requireDerivedValue(
                    linearRoughnessBits,
                    material.linearRoughness(),
                    "material roughness");
            if (input.hasRemaining()) {
                throw new IllegalArgumentException(
                        "Ray-trace replay contains trailing data");
            }
            return new RayTraceReplayInput(
                    camera,
                    scene,
                    width,
                    height,
                    astronomy,
                    packedRayCone,
                    sampleIndex,
                    sampleEpoch,
                    jitterPhase,
                    (flags & FLAG_CAMERA_IN_WATER) != 0,
                    mode,
                    lighting,
                    material,
                    (flags & FLAG_SH_INPUT) != 0,
                    (flags & FLAG_RAW_NUMERICAL) != 0,
                    (flags & FLAG_TRIANGLE_DEBUG) != 0);
        } catch (BufferUnderflowException exception) {
            throw new IllegalArgumentException(
                    "Ray-trace replay is truncated", exception);
        }
    }

    private static int flags(RayTraceReplayInput input) {
        int result = 0;
        if (input.cameraInWater()) {
            result |= FLAG_CAMERA_IN_WATER;
        }
        if (input.shInput()) {
            result |= FLAG_SH_INPUT;
        }
        if (input.rawNumericalDiagnostic()) {
            result |= FLAG_RAW_NUMERICAL;
        }
        if (input.triangleDebug()) {
            result |= FLAG_TRIANGLE_DEBUG;
        }
        return result;
    }

    private static void requireDerivedValue(
            int encodedBits, float expected, String label) {
        if (encodedBits != Float.floatToRawIntBits(expected)) {
            throw new IllegalArgumentException(
                    "Ray-trace replay contains an inconsistent " + label);
        }
    }

    private static int mode(PostProcessingMode mode) {
        return switch (mode) {
            case NRD_FSR -> 0;
            case DLSS_RR -> 1;
            case DISABLED -> 2;
        };
    }

    private static PostProcessingMode mode(int encoded) {
        return switch (encoded) {
            case 0 -> PostProcessingMode.NRD_FSR;
            case 1 -> PostProcessingMode.DLSS_RR;
            case 2 -> PostProcessingMode.DISABLED;
            default -> throw new IllegalArgumentException(
                    "Ray-trace replay contains an unknown post-processing mode");
        };
    }
}
