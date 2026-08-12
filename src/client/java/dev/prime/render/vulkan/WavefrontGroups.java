package dev.prime.render.vulkan;

/** Shared ray-generation group order for both wavefront integrators. */
final class WavefrontGroups {
    static final int HEAD = 0;
    static final int RESOLVE = 5;
    static final int GROUP_COUNT = 6;
    static final int MODULE_COUNT = 4;
    static final int[] MODULES = {0, 1, 1, 2, 2, 3};

    private WavefrontGroups() {
    }

    static int step(int queue) {
        return queued(queue, 1, 2);
    }

    static int area(int queue) {
        return queued(queue, 3, 4);
    }

    private static int queued(int queue, int first, int second) {
        return switch (queue) {
            case 0 -> first;
            case 1 -> second;
            default -> throw new IllegalArgumentException("Invalid wavefront queue");
        };
    }
}
