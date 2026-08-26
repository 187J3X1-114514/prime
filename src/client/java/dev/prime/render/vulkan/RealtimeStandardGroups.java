package dev.prime.render.vulkan;

/** Realtime transport topology. */
final class RealtimeStandardGroups {
    static final int TRACE_CLASSIFY = 8;
    static final int NO_LIGHT_ADVANCE = 9;
    static final int DUAL_LIGHT_ADVANCE = 10;
    static final int TAIL_ADMISSION = 11;
    static final int TAIL = 12;
    static final int BRANCH_RESOLVE = 13;
    static final int NOISY_OUTPUT_RESOLVE = 14;
    static final int GROUP_COUNT = 15;
    static final int MODULE_COUNT = 15;

    private static final int[] MODULES = {
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14
    };
    private static final int[] CONTROLS = {
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
    };
    private RealtimeStandardGroups() {}

    static RaygenSchedule standardSchedule(String suffix) {
        return GeneratedShaderPrograms.schedule("realtime.standard." + mode(suffix));
    }

    private static String mode(String suffix) {
        return switch (suffix) {
            case ".rgen.spv" -> "scalar";
            case "_ser.rgen.spv" -> "ser";
            default -> throw new IllegalArgumentException("Unknown wavefront shader suffix: " + suffix);
        };
    }

    static int module(int group) {
        return MODULES[group];
    }

    static int control(int group) {
        return CONTROLS[group];
    }

}
