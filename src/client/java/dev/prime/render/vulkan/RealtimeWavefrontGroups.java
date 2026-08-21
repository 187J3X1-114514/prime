package dev.prime.render.vulkan;

import dev.prime.render.shader.ShaderAbi;
import java.util.List;

/** Independent standard two-stage and legacy SHARC ray-generation schedules. */
final class RealtimeWavefrontGroups {
    static final int HEAD = 0;
    static final int PRIMARY_DIRECT = 1;
    static final int PRIMARY = 2;
    static final int PRIMARY_CHAIN = 3;
    static final int PRIMARY_LANDING_CLASSIFY = 4;
    static final int PRIMARY_LANDING_PRIMARY = 5;
    static final int PRIMARY_LANDING_SECONDARY = 6;
    static final int CLASSIFY = 7;
    static final int NONE = 8;
    static final int SUN = 9;
    static final int AREA = 10;
    static final int TRANSPARENT_RESOLVE = 11;
    static final int RESOLVE = 12;
    static final int GROUP_COUNT = 13;
    static final int MODULE_COUNT = 13;

    static final int LEGACY_HEAD = 0;
    static final int LEGACY_PRIMARY_DIRECT = 7;
    static final int LEGACY_PRIMARY = 8;
    static final int LEGACY_RESOLVE = 9;
    static final int LEGACY_TRANSPARENT_RESOLVE = 10;
    static final int LEGACY_GROUP_COUNT = 11;
    static final int LEGACY_MODULE_COUNT = 8;

    private static final int[] MODULES = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
    private static final int[] CONTROLS = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    private static final int[] LEGACY_MODULES = {0, 1, 1, 4, 4, 5, 5, 2, 3, 6, 7};
    private static final int[] LEGACY_CONTROLS = {0, 0, 256, 768, 512, 768, 512, 0, 0, 3, 5};

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
                prefix + "sharc_bounce" + suffix,
                prefix + "primary_direct" + suffix,
                prefix + "primary" + suffix,
                prefix + "transparent_bounce" + suffix,
                prefix + "transparent_area" + suffix,
                prefix + "resolve" + suffix,
                prefix + "transparent_resolve" + suffix),
                LEGACY_MODULES,
                LEGACY_CONTROLS);
    }

    static int module(int group) {
        return MODULES[group];
    }

    static int control(int group) {
        return CONTROLS[group];
    }

    static int legacyBounce(int queue) {
        return queued(queue, 1, 2);
    }

    static int legacyTransparentBounce(int queue) {
        return switch (queue) {
            case ShaderAbi.WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0 -> 3;
            case ShaderAbi.WAVEFRONT_TRANSPARENT_TRACE_QUEUE_1 -> 4;
            default -> throw new IllegalArgumentException("Invalid transparent wavefront queue");
        };
    }

    static int legacyTransparentArea(int queue) {
        return switch (queue) {
            case ShaderAbi.WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0 -> 5;
            case ShaderAbi.WAVEFRONT_TRANSPARENT_TRACE_QUEUE_1 -> 6;
            default -> throw new IllegalArgumentException("Invalid transparent wavefront queue");
        };
    }

    private static int queued(int queue, int first, int second) {
        return switch (queue) {
            case ShaderAbi.WAVEFRONT_TRACE_QUEUE_0 -> first;
            case ShaderAbi.WAVEFRONT_TRACE_QUEUE_1 -> second;
            default -> throw new IllegalArgumentException("Invalid wavefront queue");
        };
    }
}
