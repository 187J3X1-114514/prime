package dev.prime.render;

/** Runtime controls for Minecraft material translation. */
public final class MaterialSettings {
    public static final int STEPS_PER_UNIT = 100;
    public static final int MINIMUM_ROUGHNESS_STEPS = 0;
    public static final int MAXIMUM_ROUGHNESS_STEPS = STEPS_PER_UNIT;
    public static final int DEFAULT_ROUGHNESS_STEPS = 90;
    public static final boolean DEFAULT_SEAMLESS_GLASS = true;
    public static final boolean DEFAULT_AIR_GAP = true;

    private MaterialSettings() {
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

    public record Snapshot(
            int roughnessSteps,
            boolean seamlessGlass,
            boolean airGap,
            long revision) {
        public Snapshot(int roughnessSteps, long revision) {
            this(
                    roughnessSteps,
                    DEFAULT_SEAMLESS_GLASS,
                    DEFAULT_AIR_GAP,
                    revision);
        }

        public Snapshot(int roughnessSteps, boolean seamlessGlass, long revision) {
            this(roughnessSteps, seamlessGlass, DEFAULT_AIR_GAP, revision);
        }

        public Snapshot {
            requireValid(roughnessSteps);
            if (revision < 0L) {
                throw new IllegalArgumentException(
                        "Material revision must not be negative");
            }
        }

        public float linearRoughness() {
            return MaterialSettings.linearRoughness(this.roughnessSteps);
        }
    }
}
