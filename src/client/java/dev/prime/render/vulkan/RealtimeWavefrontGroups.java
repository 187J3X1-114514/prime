package dev.prime.render.vulkan;

import dev.prime.render.shader.ShaderAbi;

/** Ray-generation group order for execution-mode queues. */
final class RealtimeWavefrontGroups {
    static final int HEAD = 0;
    static final int PRIMARY = 3;
    static final int PRIMARY_AREA = 4;
    static final int PRIMARY_SUN = 5;
    static final int RESOLVE = 12;
    static final int TRANSPARENT_RESOLVE = 13;
    static final int GROUP_COUNT = 14;
    static final int MODULE_COUNT = 11;
    static final int[] MODULES = {0, 1, 1, 2, 3, 4, 5, 6, 7, 7, 8, 8, 9, 10};
    static final int[] CONTROLS = {0, 1, 257, 0, 0, 0, 4, 6, 2, 258, 2, 258, 3, 5};

    private RealtimeWavefrontGroups() {}

    static int step(int queue) {
        return queued(queue, 1, 2);
    }

    static int area() {
        return 6;
    }

    static int sun() {
        return 7;
    }

    static int shade(int queue) {
        return queued(queue, 8, 9);
    }

    static int transparentShade(int queue) {
        return queued(queue, 10, 11);
    }

    private static int queued(int queue, int first, int second) {
        return switch (queue) {
            case ShaderAbi.WAVEFRONT_TRACE_QUEUE_0 -> first;
            case ShaderAbi.WAVEFRONT_TRACE_QUEUE_1 -> second;
            default -> throw new IllegalArgumentException("Invalid wavefront queue");
        };
    }
}
