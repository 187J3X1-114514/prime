package dev.prime.render;

/**
 * Runtime lighting controls expressed as photographic exposure-value offsets.
 *
 * <p>The zero-EV defaults preserve Prime's calibrated ten-unit extraterrestrial sun and the
 * level-squared Minecraft emitter model. A quarter-EV step is converted only at the rendering
 * boundary as {@code 2^(quarterSteps / 4)}; the stored values never masquerade as linear light.
 */
public final class LightingSettings {
    public static final int QUARTER_STEPS_PER_EV = 4;
    public static final int MINIMUM_QUARTER_STEPS = -16;
    public static final int MAXIMUM_QUARTER_STEPS = 16;
    public static final int DEFAULT_SUN_QUARTER_STEPS = 0;
    public static final int DEFAULT_BLOCK_LIGHT_QUARTER_STEPS = 0;

    private static volatile State state = new State(
            DEFAULT_SUN_QUARTER_STEPS,
            DEFAULT_BLOCK_LIGHT_QUARTER_STEPS,
            0L);

    private LightingSettings() {
    }

    public static Snapshot snapshot() {
        State current = state;
        return new Snapshot(
                current.sunQuarterSteps,
                current.blockLightQuarterSteps,
                linearMultiplier(current.sunQuarterSteps),
                linearMultiplier(current.blockLightQuarterSteps),
                current.revision);
    }

    public static int sunQuarterSteps() {
        return state.sunQuarterSteps;
    }

    public static int blockLightQuarterSteps() {
        return state.blockLightQuarterSteps;
    }

    public static synchronized void setSunQuarterSteps(int quarterSteps) {
        requireValid(quarterSteps);
        State current = state;
        if (quarterSteps != current.sunQuarterSteps) {
            state = new State(
                    quarterSteps,
                    current.blockLightQuarterSteps,
                    current.revision + 1L);
        }
    }

    public static synchronized void setBlockLightQuarterSteps(int quarterSteps) {
        requireValid(quarterSteps);
        State current = state;
        if (quarterSteps != current.blockLightQuarterSteps) {
            state = new State(
                    current.sunQuarterSteps,
                    quarterSteps,
                    current.revision + 1L);
        }
    }

    public static float linearMultiplier(int quarterSteps) {
        requireValid(quarterSteps);
        return (float) Math.pow(2.0, quarterSteps / (double) QUARTER_STEPS_PER_EV);
    }

    public static float exposureValue(int quarterSteps) {
        requireValid(quarterSteps);
        return quarterSteps / (float) QUARTER_STEPS_PER_EV;
    }

    private static void requireValid(int quarterSteps) {
        if (quarterSteps < MINIMUM_QUARTER_STEPS
                || quarterSteps > MAXIMUM_QUARTER_STEPS) {
            throw new IllegalArgumentException(
                    "Lighting EV must be between "
                            + exposureValueUnchecked(MINIMUM_QUARTER_STEPS)
                            + " and "
                            + exposureValueUnchecked(MAXIMUM_QUARTER_STEPS));
        }
    }

    private static float exposureValueUnchecked(int quarterSteps) {
        return quarterSteps / (float) QUARTER_STEPS_PER_EV;
    }

    public record Snapshot(
            int sunQuarterSteps,
            int blockLightQuarterSteps,
            float sunMultiplier,
            float blockLightMultiplier,
            long revision) {
    }

    private record State(int sunQuarterSteps, int blockLightQuarterSteps, long revision) {
    }
}
