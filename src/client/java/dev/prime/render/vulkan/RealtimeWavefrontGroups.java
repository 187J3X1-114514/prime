package dev.prime.render.vulkan;

import java.util.List;

/** Shared two-stage group topology with execution-mode-specific steady modules. */
final class RealtimeWavefrontGroups {
    static final int HEAD = 0;
    static final int PRIMARY_DIRECT = 1;
    static final int PRIMARY = 2;
    static final int PRIMARY_CHAIN = 3;
    static final int PRIMARY_LANDING_CLASSIFY = 4;
    static final int PRIMARY_LANDING_PRIMARY = 5;
    static final int PRIMARY_LANDING_SECONDARY = 6;
    static final int PRIMARY_LANDING_ADVANCE = 7;
    static final int CLASSIFY = 8;
    static final int NONE = 9;
    static final int SUN = 10;
    static final int AREA = 11;
    static final int TRANSPARENT_RESOLVE = 12;
    static final int RESOLVE = 13;
    static final int GROUP_COUNT = 14;
    static final int MODULE_COUNT = 14;

    private static final int[] MODULES = {
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13
    };
    private static final int[] CONTROLS = {
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
    };
    private RealtimeWavefrontGroups() {}

    static RaygenSchedule standardSchedule(String suffix) {
        String prefix = "/prime/shaders/realtime_wavefront_";
        return RaygenSchedule.of(List.of(
                prefix + "head" + suffix,
                prefix + "primary_direct" + suffix,
                prefix + "two_stage_primary" + suffix,
                prefix + "primary_chain" + suffix,
                prefix + "primary_landing" + suffix,
                prefix + "primary_landing_primary" + suffix,
                prefix + "primary_landing_secondary" + suffix,
                prefix + "primary_landing_advance" + suffix,
                prefix + "steady_classify" + suffix,
                prefix + "steady_none" + suffix,
                prefix + "steady_sun" + suffix,
                prefix + "steady_area" + suffix,
                prefix + "two_stage_transparent_resolve" + suffix,
                prefix + "resolve" + suffix), MODULES, CONTROLS);
    }

    static RaygenSchedule sharcSchedule(String suffix) {
        String prefix = "/prime/shaders/realtime_wavefront_";
        return RaygenSchedule.of(List.of(
                prefix + "head" + suffix,
                prefix + "primary_direct" + suffix,
                prefix + "two_stage_primary" + suffix,
                prefix + "primary_chain" + suffix,
                prefix + "primary_landing" + suffix,
                prefix + "primary_landing_primary" + suffix,
                prefix + "primary_landing_secondary" + suffix,
                prefix + "primary_landing_advance" + suffix,
                prefix + "sharc_steady_classify" + suffix,
                prefix + "sharc_steady_none" + suffix,
                prefix + "sharc_steady_sun" + suffix,
                prefix + "sharc_steady_area" + suffix,
                prefix + "two_stage_transparent_resolve" + suffix,
                prefix + "resolve" + suffix),
                MODULES,
                CONTROLS);
    }

    static int module(int group) {
        return MODULES[group];
    }

    static int control(int group) {
        return CONTROLS[group];
    }

}
