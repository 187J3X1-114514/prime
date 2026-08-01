package dev.prime.render;

/** Session-only wavefront cost-isolation modes for profiler captures. */
public enum WavefrontDebugMode {
    BASELINE("baseline", false, false),
    NO_PRIMARY_TRANSPARENT_REFLECTION(
            "no_primary_transparent_reflection", true, false),
    NO_SECONDARY_AREA_NEE("no_secondary_area_nee", false, true),
    NO_PRIMARY_TRANSPARENT_REFLECTION_OR_SECONDARY_AREA_NEE(
            "no_primary_transparent_reflection_or_secondary_area_nee", true, true);

    private final String id;
    private final boolean suppressPrimaryTransparentReflection;
    private final boolean suppressSecondaryAreaNee;

    WavefrontDebugMode(
            String id,
            boolean suppressPrimaryTransparentReflection,
            boolean suppressSecondaryAreaNee) {
        this.id = id;
        this.suppressPrimaryTransparentReflection = suppressPrimaryTransparentReflection;
        this.suppressSecondaryAreaNee = suppressSecondaryAreaNee;
    }

    public String id() {
        return this.id;
    }

    public boolean suppressPrimaryTransparentReflection() {
        return this.suppressPrimaryTransparentReflection;
    }

    public boolean suppressSecondaryAreaNee() {
        return this.suppressSecondaryAreaNee;
    }

    public static WavefrontDebugMode fromId(String id) {
        for (WavefrontDebugMode mode : values()) {
            if (mode.id.equals(id)) {
                return mode;
            }
        }
        return BASELINE;
    }

    public static WavefrontDebugMode of(
            boolean suppressPrimaryTransparentReflection,
            boolean suppressSecondaryAreaNee) {
        if (suppressPrimaryTransparentReflection) {
            return suppressSecondaryAreaNee
                    ? NO_PRIMARY_TRANSPARENT_REFLECTION_OR_SECONDARY_AREA_NEE
                    : NO_PRIMARY_TRANSPARENT_REFLECTION;
        }
        return suppressSecondaryAreaNee ? NO_SECONDARY_AREA_NEE : BASELINE;
    }
}
