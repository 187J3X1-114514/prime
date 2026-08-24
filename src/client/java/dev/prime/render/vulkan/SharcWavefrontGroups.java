package dev.prime.render.vulkan;

/** Independent SHARC training and cache-only query topologies. */
final class SharcWavefrontGroups {
    static final int HEAD = 0;
    static final int PRIMARY_DIRECT = 1;
    static final int PRIMARY = 2;
    static final int PRIMARY_CHAIN = 3;
    static final int PRIMARY_LANDING_CLASSIFY = 4;
    static final int PRIMARY_LANDING_PRIMARY = 5;
    static final int PRIMARY_LANDING_SECONDARY = 6;
    static final int PRIMARY_LANDING_ADVANCE = 7;
    static final int BRIDGE_TRACE = 8;
    static final int BRIDGE_QUERY = 9;
    static final int TRANSPARENT_RESOLVE = 10;
    static final int RESOLVE = 11;
    static final int QUERY_GROUP_COUNT = 12;
    static final int QUERY_MODULE_COUNT = 12;

    static final int TRAIN_LANDING = 1;
    static final int TRAIN_WALK = 2;
    static final int TRAIN_CLASSIFY = 4;
    static final int TRAIN_NONE = 5;
    static final int TRAIN_SUN = 6;
    static final int TRAIN_AREA = 7;
    static final int TRAIN_REDUCE = 8;
    static final int TRAIN_GROUP_COUNT = 9;
    static final int TRAIN_MODULE_COUNT = 8;

    private SharcWavefrontGroups() {}

    static RaygenSchedule querySchedule(String suffix) {
        return GeneratedShaderPrograms.schedule("sharc.query." + mode(suffix));
    }

    static RaygenSchedule trainingSchedule(String suffix) {
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
