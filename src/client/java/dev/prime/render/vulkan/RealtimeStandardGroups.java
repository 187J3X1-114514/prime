package dev.prime.render.vulkan;

/** Realtime transport topology. */
final class RealtimeStandardGroups {
    static final int BRIDGE_TRACE_0 = 7;
    static final int LIGHT_SELECT_0 = 8;
    static final int DIRECT_0 = 9;
    static final int SCATTER_0 = 10;
    static final int BRIDGE_TRACE_1 = 11;
    static final int LIGHT_SELECT_1 = 12;
    static final int DIRECT_1 = 13;
    static final int SCATTER_1 = 14;
    static final int TAIL_ADMISSION_0 = 15;
    static final int TAIL_ADMISSION_1 = 16;
    static final int TAIL = 17;
    static final int BRANCH_RESOLVE = 18;
    static final int NOISY_OUTPUT_RESOLVE = 19;
    static final int GROUP_COUNT = 20;
    static final int MODULE_COUNT = 15;

    private static final int[] MODULES = {
        0, 1, 2, 3, 4, 5, 6,
        7, 8, 9, 10, 7, 8, 9, 10,
        11, 11, 12, 13, 14
    };
    private static final int[] CONTROLS = {
        0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 1, 1, 1, 1,
        0, 1, 0, 0, 0
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

    static int bridgeTrace(boolean sourceOne) {
        return sourceOne ? BRIDGE_TRACE_1 : BRIDGE_TRACE_0;
    }

    static int lightSelect(boolean sourceOne) {
        return sourceOne ? LIGHT_SELECT_1 : LIGHT_SELECT_0;
    }

    static int direct(boolean sourceOne) {
        return sourceOne ? DIRECT_1 : DIRECT_0;
    }

    static int scatter(boolean sourceOne) {
        return sourceOne ? SCATTER_1 : SCATTER_0;
    }

    static int tailAdmission(boolean sourceOne) {
        return sourceOne ? TAIL_ADMISSION_1 : TAIL_ADMISSION_0;
    }

}
