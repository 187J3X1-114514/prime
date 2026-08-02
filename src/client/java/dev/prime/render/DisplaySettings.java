package dev.prime.render;

/** Runtime controls that affect only Prime's final display-referred transform. */
public final class DisplaySettings {
    public static final int QUARTER_STEPS_PER_EV = 4;
    public static final int MINIMUM_FINAL_EXPOSURE_QUARTER_STEPS =
            -8 * QUARTER_STEPS_PER_EV;
    public static final int MAXIMUM_FINAL_EXPOSURE_QUARTER_STEPS =
            8 * QUARTER_STEPS_PER_EV;
    public static final int DEFAULT_FINAL_EXPOSURE_QUARTER_STEPS = 0;
    public static final int STEPS_PER_UNIT = 32;
    public static final int MINIMUM_OVEREXPOSURE_STEPS = STEPS_PER_UNIT;
    public static final int MAXIMUM_OVEREXPOSURE_STEPS = 2 * STEPS_PER_UNIT;
    public static final int DEFAULT_OVEREXPOSURE_STEPS = 32;

    private DisplaySettings() {
    }

    public static float finalExposureMultiplier(int quarterSteps) {
        requireValidFinalExposure(quarterSteps);
        return Math.scalb(1.0F, Math.floorDiv(quarterSteps, QUARTER_STEPS_PER_EV))
                * (float) Math.pow(
                        2.0,
                        Math.floorMod(quarterSteps, QUARTER_STEPS_PER_EV)
                                / (double) QUARTER_STEPS_PER_EV);
    }

    public static float overexposure(int steps) {
        requireValidOverexposure(steps);
        return steps / (float) STEPS_PER_UNIT;
    }

    private static void requireValidFinalExposure(int quarterSteps) {
        if (quarterSteps < MINIMUM_FINAL_EXPOSURE_QUARTER_STEPS
                || quarterSteps > MAXIMUM_FINAL_EXPOSURE_QUARTER_STEPS) {
            throw new IllegalArgumentException("Final exposure must be between -8 EV and +8 EV");
        }
    }

    private static void requireValidOverexposure(int steps) {
        if (steps < MINIMUM_OVEREXPOSURE_STEPS || steps > MAXIMUM_OVEREXPOSURE_STEPS) {
            throw new IllegalArgumentException("Oklab DRT overexposure must be between 1.0 and 2.0");
        }
    }

    public record Snapshot(
            int finalExposureQuarterSteps,
            int oklabOverexposureSteps) {
        public Snapshot {
            DisplaySettings.finalExposureMultiplier(finalExposureQuarterSteps);
            DisplaySettings.overexposure(oklabOverexposureSteps);
        }

        public float finalExposureMultiplier() {
            return DisplaySettings.finalExposureMultiplier(this.finalExposureQuarterSteps);
        }

        public float oklabOverexposure() {
            return DisplaySettings.overexposure(this.oklabOverexposureSteps);
        }
    }
}
