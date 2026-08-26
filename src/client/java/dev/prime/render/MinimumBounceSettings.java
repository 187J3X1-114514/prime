package dev.prime.render;

/** Fixed no-roulette realtime prefix length, including the primary-surface round. */
public final class MinimumBounceSettings {
    public static final int MINIMUM_COUNT = 1;
    public static final int MAXIMUM_COUNT = 8;
    public static final int DEFAULT_COUNT = 2;

    private MinimumBounceSettings() {
    }

    public static int validateCount(int count) {
        if (count < MINIMUM_COUNT || count > MAXIMUM_COUNT) {
            throw new IllegalArgumentException(
                    "Minimum bounce count must be between 1 and 8");
        }
        return count;
    }
}
