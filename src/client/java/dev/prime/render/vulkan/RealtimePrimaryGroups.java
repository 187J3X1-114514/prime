package dev.prime.render.vulkan;

/** Group order shared by the standard realtime and SHARC-query primary prefix. */
final class RealtimePrimaryGroups {
    static final int CAMERA_TRACE = 0;
    static final int VISIBLE_DIRECT = 1;
    static final int SURFACE_SPLIT = 2;
    static final int DELTA_WALK = 3;
    static final int LANDING_LIGHT_CLASSIFY = 4;
    static final int LANDING_GUIDE_DUAL_LIGHT = 5;
    static final int LANDING_SAMPLED_LIGHT_ADVANCE = 6;
    static final int LANDING_GUIDE_ADVANCE = 7;

    private RealtimePrimaryGroups() {}
}
