package dev.prime.render;

/** Realtime delta-chain length before the first non-delta surface. */
public final class SpecularBounceSettings {
    public static final int MINIMUM_COUNT = 1;
    public static final int MAXIMUM_COUNT = 64;
    public static final int DEFAULT_COUNT = 8;

    private SpecularBounceSettings() {
    }

    public static int validateCount(int count) {
        if (count < MINIMUM_COUNT || count > MAXIMUM_COUNT) {
            throw new IllegalArgumentException(
                    "Additional specular bounce count must be between 1 and 64");
        }
        return count;
    }
}
