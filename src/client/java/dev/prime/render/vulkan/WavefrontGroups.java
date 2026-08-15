package dev.prime.render.vulkan;

import java.util.List;

/** Shared ray-generation group order for both wavefront integrators. */
final class WavefrontGroups {
    static final int HEAD = 0;
    static final int RESOLVE = 5;
    static final int GROUP_COUNT = 6;
    static final int MODULE_COUNT = 4;
    private static final int[] MODULES = {0, 1, 1, 2, 2, 3};
    private static final int[] CONTROLS = {0, 1, 257, 2, 258, 4};

    private WavefrontGroups() {
    }

    static RaygenSchedule schedule(String suffix) {
        String prefix = "/prime/shaders/offline_wavefront_";
        return RaygenSchedule.of(List.of(
                prefix + "head" + suffix,
                prefix + "step" + suffix,
                prefix + "area" + suffix,
                prefix + "resolve" + suffix), MODULES, CONTROLS);
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
