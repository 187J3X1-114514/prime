package dev.prime.render.vulkan;

/** Ray-generation group order for trace, independent Area NEE and shade/scatter stages. */
final class RealtimeWavefrontGroups {
    static final int HEAD = 0;
    static final int RESOLVE = 7;
    static final int GROUP_COUNT = 8;
    static final int MODULE_COUNT = 5;
    static final int[] MODULES = {0, 1, 1, 2, 2, 3, 3, 4};

    private RealtimeWavefrontGroups() {}

    static int step(int queue) {
        return queued(queue, 1, 2);
    }

    static int area(int queue) {
        return queued(queue, 3, 4);
    }

    static int shade(int queue) {
        return queued(queue, 5, 6);
    }

    private static int queued(int queue, int first, int second) {
        return switch (queue) {
            case 0 -> first;
            case 1 -> second;
            default -> throw new IllegalArgumentException("Invalid wavefront queue");
        };
    }
}
