package dev.prime.render;

/** Shared execution-round policy for realtime and offline wavefront transport. */
public final class WavefrontSettings {
    public static final int MINIMUM_ROUNDS = 1;
    public static final int MAXIMUM_ROUNDS = 64;
    public static final int DEFAULT_ROUNDS = 16;

    private WavefrontSettings() {
    }

    public static int validateRounds(int rounds) {
        if (rounds < MINIMUM_ROUNDS || rounds > MAXIMUM_ROUNDS) {
            throw new IllegalArgumentException("Wavefront rounds must be between 1 and 64");
        }
        return rounds;
    }
}
