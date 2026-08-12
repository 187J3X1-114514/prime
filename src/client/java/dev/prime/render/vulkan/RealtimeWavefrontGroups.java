package dev.prime.render.vulkan;

import dev.prime.render.shader.ShaderAbi;

/** Ray-generation group order for execution-mode queues. */
final class RealtimeWavefrontGroups {
    static final int HEAD = 0;
    static final int PRIMARY = 3;
    static final int RESOLVE = 8;
    static final int GROUP_COUNT = 9;
    static final int MODULE_COUNT = 7;
    static final int[] MODULES = {0, 1, 1, 2, 3, 4, 5, 5, 6};
    static final int[] CONTROLS = {0, 1, 257, 0, 4, 6, 2, 258, 3};

    private RealtimeWavefrontGroups() {}

    static int step(int queue) {
        return queued(queue, 1, 2);
    }

    static int area() {
        return 4;
    }

    static int sun() {
        return 5;
    }

    static int shade(int queue) {
        return queued(queue, 6, 7);
    }

    private static int queued(int queue, int first, int second) {
        return switch (queue) {
            case ShaderAbi.WAVEFRONT_TRACE_QUEUE_0 -> first;
            case ShaderAbi.WAVEFRONT_TRACE_QUEUE_1 -> second;
            default -> throw new IllegalArgumentException("Invalid wavefront queue");
        };
    }
}
