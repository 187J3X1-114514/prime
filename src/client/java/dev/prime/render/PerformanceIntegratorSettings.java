package dev.prime.render;

/** User-adjustable transport limits for the performance realtime integrator. */
public final class PerformanceIntegratorSettings {
    public static final int MINIMUM_SCATTERS = 1;
    public static final int MAXIMUM_SCATTERS = 12;
    public static final int DEFAULT_SCATTERS = 6;

    private PerformanceIntegratorSettings() {
    }

    public static int validateScatters(int value) {
        if (value < MINIMUM_SCATTERS || value > MAXIMUM_SCATTERS) {
            throw new IllegalArgumentException(
                    "Performance surface-scatter limit must be in [1, 12]");
        }
        return value;
    }
}
