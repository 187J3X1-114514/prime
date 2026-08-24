package dev.prime.render;

/** Realtime first-stage limit for exact delta transitions before steady transport. */
public final class DeltaWalkSettings {
    public static final int MINIMUM_LIMIT = 1;
    public static final int MAXIMUM_LIMIT = 64;
    public static final int DEFAULT_LIMIT = 8;

    private DeltaWalkSettings() {
    }

    public static int validateLimit(int limit) {
        if (limit < MINIMUM_LIMIT || limit > MAXIMUM_LIMIT) {
            throw new IllegalArgumentException(
                    "Delta-walk limit must be between 1 and 64");
        }
        return limit;
    }
}
