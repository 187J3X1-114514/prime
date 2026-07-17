package dev.prime.render;

/** Runtime controls that affect only Prime's final display-referred transform. */
public final class DisplaySettings {
    public static final int STEPS_PER_UNIT = 32;
    public static final int MINIMUM_OVEREXPOSURE_STEPS = STEPS_PER_UNIT;
    public static final int MAXIMUM_OVEREXPOSURE_STEPS = 2 * STEPS_PER_UNIT;
    public static final int DEFAULT_OVEREXPOSURE_STEPS = 37;

    private static volatile int overexposureSteps = DEFAULT_OVEREXPOSURE_STEPS;

    private DisplaySettings() {
    }

    public static int overexposureSteps() {
        return overexposureSteps;
    }

    public static float overexposure() {
        return overexposure(overexposureSteps);
    }

    public static synchronized void setOverexposureSteps(int steps) {
        requireValid(steps);
        overexposureSteps = steps;
    }

    public static float overexposure(int steps) {
        requireValid(steps);
        return steps / (float) STEPS_PER_UNIT;
    }

    private static void requireValid(int steps) {
        if (steps < MINIMUM_OVEREXPOSURE_STEPS || steps > MAXIMUM_OVEREXPOSURE_STEPS) {
            throw new IllegalArgumentException("Oklab DRT overexposure must be between 1.0 and 2.0");
        }
    }
}
