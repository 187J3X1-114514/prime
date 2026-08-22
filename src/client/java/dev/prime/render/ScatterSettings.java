package dev.prime.render;

/** Steady realtime bounce limit and total offline scatter limit. */
public final class ScatterSettings {
    public static final int MINIMUM_COUNT = 1;
    public static final int MAXIMUM_COUNT = 64;
    public static final int DEFAULT_COUNT = 8;

    private ScatterSettings() {
    }

    public static int validateCount(int count) {
        if (count < MINIMUM_COUNT || count > MAXIMUM_COUNT) {
            throw new IllegalArgumentException("Scatter count must be between 1 and 64");
        }
        return count;
    }
}
