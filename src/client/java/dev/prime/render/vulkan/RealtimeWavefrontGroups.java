package dev.prime.render.vulkan;

import dev.prime.render.shader.ShaderAbi;
import java.util.List;

/** Ray-generation group order for execution-mode queues. */
final class RealtimeWavefrontGroups {
    static final int HEAD = 0;
    static final int PRIMARY_DIRECT = 3;
    static final int PRIMARY = 5;
    static final int RESOLVE = 10;
    static final int TRANSPARENT_RESOLVE = 11;
    static final int GROUP_COUNT = 12;
    static final int MODULE_COUNT = 9;
    private static final int[] MODULES = {0, 1, 1, 2, 3, 4, 5, 5, 6, 6, 7, 8};
    private static final int[] CONTROLS = {0, 1, 257, 0, 4, 0, 2, 258, 2, 258, 3, 5};

    private RealtimeWavefrontGroups() {}

    static RaygenSchedule standardSchedule(String suffix) {
        String prefix = "/prime/shaders/realtime_wavefront_";
        return schedule(
                suffix,
                prefix + "light" + suffix,
                prefix + "shade" + suffix);
    }

    static RaygenSchedule sharcSchedule(String suffix) {
        String prefix = "/prime/shaders/realtime_wavefront_";
        return schedule(
                suffix,
                prefix + "sharc_light" + suffix,
                prefix + "sharc_shade" + suffix);
    }

    private static RaygenSchedule schedule(
            String suffix,
            String light,
            String shade) {
        String prefix = "/prime/shaders/realtime_wavefront_";
        return RaygenSchedule.of(List.of(
                prefix + "head" + suffix,
                prefix + "step" + suffix,
                prefix + "primary_direct" + suffix,
                light,
                prefix + "primary" + suffix,
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
        return 4;
    }

    static int shade(int queue) {
        return queued(queue, 6, 7);
    }

    static int transparentShade(int queue) {
        return queued(queue, 8, 9);
    }

    private static int queued(int queue, int first, int second) {
        return switch (queue) {
            case ShaderAbi.WAVEFRONT_TRACE_QUEUE_0 -> first;
            case ShaderAbi.WAVEFRONT_TRACE_QUEUE_1 -> second;
            default -> throw new IllegalArgumentException("Invalid wavefront queue");
        };
    }
}
