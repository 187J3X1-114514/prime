package dev.prime.render.vulkan;

/** Offline camera/path/area/output group order. */
final class OfflineGroups {
    static final int CAMERA_SURFACE_STEP = 0;
    static final int SAMPLE_RESOLVE = 5;
    static final int GROUP_COUNT = 6;
    static final int MODULE_COUNT = 4;
    private static final int[] MODULES = {0, 1, 1, 2, 2, 3};
    private static final int[] CONTROLS = {0, 1, 257, 2, 258, 4};

    private OfflineGroups() {
    }

    static RaygenSchedule schedule(String suffix) {
        String mode = switch (suffix) {
            case ".rgen.spv" -> "scalar";
            case "_ser.rgen.spv" -> "ser";
            default -> throw new IllegalArgumentException("Unknown wavefront shader suffix: " + suffix);
        };
        return GeneratedShaderPrograms.schedule("offline." + mode);
    }

    static int module(int group) {
        return MODULES[group];
    }

    static int control(int group) {
        return CONTROLS[group];
    }

    static int pathSurfaceStep(int queue) {
        return queued(queue, 1, 2);
    }

    static int areaShadow(int queue) {
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
