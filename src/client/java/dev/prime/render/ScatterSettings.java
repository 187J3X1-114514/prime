package dev.prime.render;

/** Shared path-scatter limit for every transport backend. */
public final class ScatterSettings {
    public static final int MINIMUM_COUNT = 1;
    public static final int MAXIMUM_COUNT = 64;
    public static final int DEFAULT_COUNT = 12;

    private ScatterSettings() {
    }

    public static int validateCount(int count) {
        if (count < MINIMUM_COUNT || count > MAXIMUM_COUNT) {
            throw new IllegalArgumentException("Scatter count must be between 1 and 64");
        }
        return count;
    }
}
