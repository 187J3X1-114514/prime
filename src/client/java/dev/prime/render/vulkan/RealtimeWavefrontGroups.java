package dev.prime.render.vulkan;

import dev.prime.render.shader.ShaderAbi;
import java.util.List;

/** Ray-generation group order for execution-mode queues. */
final class RealtimeWavefrontGroups {
    static final int HEAD = 0;
    static final int PRIMARY_DIRECT = 5;
    static final int PRIMARY = 6;
    static final int RESOLVE = 7;
    static final int TRANSPARENT_RESOLVE = 8;
    static final int GROUP_COUNT = 9;
    static final int MODULE_COUNT = 7;
    private static final int[] MODULES = {0, 1, 1, 4, 4, 2, 3, 5, 6};
    private static final int[] CONTROLS = {0, 0, 256, 768, 512, 0, 0, 3, 5};

    private RealtimeWavefrontGroups() {}

    static RaygenSchedule standardSchedule(String suffix) {
        String prefix = "/prime/shaders/realtime_wavefront_";
        return schedule(
                suffix,
                prefix + "bounce" + suffix);
    }

    static RaygenSchedule sharcSchedule(String suffix) {
        String prefix = "/prime/shaders/realtime_wavefront_";
        return schedule(
                suffix,
                prefix + "sharc_bounce" + suffix);
    }

    private static RaygenSchedule schedule(
            String suffix,
            String bounce) {
        String prefix = "/prime/shaders/realtime_wavefront_";
        return RaygenSchedule.of(List.of(
                prefix + "head" + suffix,
                bounce,
                prefix + "primary_direct" + suffix,
                prefix + "primary" + suffix,
                prefix + "transparent_bounce" + suffix,
                prefix + "resolve" + suffix,
                prefix + "transparent_resolve" + suffix), MODULES, CONTROLS);
    }

    static int module(int group) {
        return MODULES[group];
    }

    static int control(int group) {
        return CONTROLS[group];
    }

    static int bounce(int queue) {
        return queued(queue, 1, 2);
    }

    static int transparentBounce(int queue) {
        return switch (queue) {
            case ShaderAbi.WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0 -> 3;
            case ShaderAbi.WAVEFRONT_TRANSPARENT_TRACE_QUEUE_1 -> 4;
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
