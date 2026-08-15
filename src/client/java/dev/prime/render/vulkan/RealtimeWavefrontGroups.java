package dev.prime.render.vulkan;

import dev.prime.render.shader.ShaderAbi;
import java.util.List;

/** Ray-generation group order for execution-mode queues. */
final class RealtimeWavefrontGroups {
    static final int HEAD = 0;
    static final int PRIMARY = 3;
    static final int PRIMARY_AREA = 4;
    static final int PRIMARY_SUN = 5;
    static final int RESOLVE = 11;
    static final int TRANSPARENT_RESOLVE = 12;
    static final int GROUP_COUNT = 13;
    static final int MODULE_COUNT = 10;
    private static final int[] MODULES = {0, 1, 1, 2, 3, 4, 5, 6, 6, 7, 7, 8, 9};
    private static final int[] CONTROLS = {0, 1, 257, 0, 0, 0, 4, 2, 258, 2, 258, 3, 5};

    private RealtimeWavefrontGroups() {}

    static RaygenSchedule standardSchedule(String suffix) {
        String prefix = "/prime/shaders/realtime_wavefront_";
        return schedule(
                suffix,
                prefix + "light" + suffix,
                prefix + "shade" + suffix);
    }

    static RaygenSchedule sharcSchedule(String suffix) {
        return schedule(
                suffix,
                "/prime/shaders/realtime_wavefront_sharc_light" + suffix,
                "/prime/shaders/realtime_wavefront_sharc_shade" + suffix);
    }

    private static RaygenSchedule schedule(String suffix, String light, String shade) {
        String prefix = "/prime/shaders/realtime_wavefront_";
        return RaygenSchedule.of(List.of(
                prefix + "head" + suffix,
                prefix + "step" + suffix,
                prefix + "primary" + suffix,
                prefix + "primary_area" + suffix,
                prefix + "primary_sun" + suffix,
                light,
                shade,
                prefix + "transparent_shade" + suffix,
                prefix + "resolve" + suffix,
                prefix + "transparent_resolve" + suffix), MODULES, CONTROLS);
    }

    static int module(int group) {
        return MODULES[group];
    }

    static int control(int group) {
        return CONTROLS[group];
    }

    static int step(int queue) {
        return queued(queue, 1, 2);
    }

    static int light() {
        return 6;
    }

    static int shade(int queue) {
        return queued(queue, 7, 8);
    }

    static int transparentShade(int queue) {
        return queued(queue, 9, 10);
    }

    private static int queued(int queue, int first, int second) {
        return switch (queue) {
            case ShaderAbi.WAVEFRONT_TRACE_QUEUE_0 -> first;
            case ShaderAbi.WAVEFRONT_TRACE_QUEUE_1 -> second;
            default -> throw new IllegalArgumentException("Invalid wavefront queue");
        };
    }
}
