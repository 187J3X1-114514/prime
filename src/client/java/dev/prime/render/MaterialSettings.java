package dev.prime.render;

/** Runtime controls for Minecraft materials that have no authored PBR roughness. */
public final class MaterialSettings {
    public static final int STEPS_PER_UNIT = 100;
    public static final int MINIMUM_ROUGHNESS_STEPS = 0;
    public static final int MAXIMUM_ROUGHNESS_STEPS = STEPS_PER_UNIT;
    public static final int DEFAULT_ROUGHNESS_STEPS = 80;

    private static volatile State state = new State(DEFAULT_ROUGHNESS_STEPS, 0L);

    private MaterialSettings() {
    }

    public static Snapshot snapshot() {
        State current = state;
        return new Snapshot(
                current.roughnessSteps,
                linearRoughness(current.roughnessSteps),
                current.revision);
    }

    public static int roughnessSteps() {
        return state.roughnessSteps;
    }

    public static synchronized void setRoughnessSteps(int roughnessSteps) {
        requireValid(roughnessSteps);
        State current = state;
        if (roughnessSteps != current.roughnessSteps) {
            state = new State(roughnessSteps, current.revision + 1L);
        }
    }

    public static float linearRoughness(int roughnessSteps) {
        requireValid(roughnessSteps);
        return roughnessSteps / (float) STEPS_PER_UNIT;
    }

    private static void requireValid(int roughnessSteps) {
        if (roughnessSteps < MINIMUM_ROUGHNESS_STEPS
                || roughnessSteps > MAXIMUM_ROUGHNESS_STEPS) {
            throw new IllegalArgumentException("Material roughness must be between 0 and 1");
        }
    }

    public record Snapshot(int roughnessSteps, float linearRoughness, long revision) {
    }

    private record State(int roughnessSteps, long revision) {
    }
}
