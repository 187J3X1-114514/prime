package dev.prime.render;

/** Primary-inclusive non-delta transport limit; realtime fixed-prefix work takes precedence. */
public final class MaximumBounceSettings {
    public static final int MINIMUM_COUNT = 1;
    public static final int MAXIMUM_COUNT = 64;
    public static final int DEFAULT_COUNT = 8;

    private MaximumBounceSettings() {
    }

    public static int validateCount(int count) {
        if (count < MINIMUM_COUNT || count > MAXIMUM_COUNT) {
            throw new IllegalArgumentException(
                    "Maximum bounce count must be between 1 and 64");
        }
        return count;
    }
}
