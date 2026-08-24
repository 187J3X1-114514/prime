package dev.prime.render.vulkan;

/** Sparse SHARC training topology, independent from visible cache queries. */
final class RealtimeSharcTrainingGroups {
    static final int CAMERA_DELTA_WALK = 0;
    static final int LANDING_ADVANCE = 1;
    static final int PATH_WALK = 2;
    static final int LIGHT_CLASSIFY = 4;
    static final int LIGHT_NONE = 5;
    static final int LIGHT_SUN = 6;
    static final int LIGHT_AREA = 7;
    static final int ANCHOR_REDUCE = 8;
    static final int GROUP_COUNT = 9;
    static final int MODULE_COUNT = 8;

    private RealtimeSharcTrainingGroups() {}

    static RaygenSchedule schedule(String suffix) {
        return GeneratedShaderPrograms.schedule("sharc.training." + mode(suffix));
    }

    private static String mode(String suffix) {
        return switch (suffix) {
            case ".rgen.spv" -> "scalar";
            case "_ser.rgen.spv" -> "ser";
            default -> throw new IllegalArgumentException(
                    "Unknown wavefront shader suffix: " + suffix);
        };
    }
}
