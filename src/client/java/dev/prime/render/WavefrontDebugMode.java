package dev.prime.render;

/** Session-only wavefront cost-isolation modes for profiler captures. */
public enum WavefrontDebugMode {
    BASELINE("baseline", false),
    NO_PRIMARY_TRANSPARENT_REFLECTION(
            "no_primary_transparent_reflection", true);

    private final String id;
    private final boolean suppressPrimaryTransparentReflection;

    WavefrontDebugMode(
            String id,
            boolean suppressPrimaryTransparentReflection) {
        this.id = id;
        this.suppressPrimaryTransparentReflection = suppressPrimaryTransparentReflection;
    }

    public String id() {
        return this.id;
    }

    public boolean suppressPrimaryTransparentReflection() {
        return this.suppressPrimaryTransparentReflection;
    }

    public static WavefrontDebugMode fromId(String id) {
        if ("no_secondary_area_nee".equals(id)) {
            return BASELINE;
        }
        if ("no_primary_transparent_reflection_or_secondary_area_nee".equals(id)) {
            return NO_PRIMARY_TRANSPARENT_REFLECTION;
        }
        for (WavefrontDebugMode mode : values()) {
            if (mode.id.equals(id)) {
                return mode;
            }
        }
        return BASELINE;
    }

    public static WavefrontDebugMode of(
            boolean suppressPrimaryTransparentReflection,
            boolean ignoredSuppressSecondaryAreaNee) {
        // The second flag is accepted only to normalize v3 replay files recorded before
        // secondary block-light NEE became the fixed estimator policy.
        return suppressPrimaryTransparentReflection
                ? NO_PRIMARY_TRANSPARENT_REFLECTION
                : BASELINE;
    }
}
