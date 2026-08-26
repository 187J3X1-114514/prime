package dev.prime.render;

/** Fixed no-RR wavefront rounds, including the landing primary-surface round. */
public final class WavefrontPrefixSettings {
    public static final int MINIMUM_ROUNDS = 2;
    public static final int MAXIMUM_ROUNDS = 4;
    public static final int DEFAULT_ROUNDS = 2;

    private WavefrontPrefixSettings() {
    }

    public static int validateRounds(int rounds) {
        if (rounds < MINIMUM_ROUNDS || rounds > MAXIMUM_ROUNDS) {
            throw new IllegalArgumentException(
                    "Wavefront prefix rounds must be between 2 and 4");
        }
        return rounds;
    }
}
