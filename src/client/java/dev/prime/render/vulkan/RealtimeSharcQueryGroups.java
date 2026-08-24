package dev.prime.render.vulkan;

/** SHARC cache-query topology following the shared realtime primary prefix. */
final class RealtimeSharcQueryGroups {
    static final int BRIDGE_TRACE = 8;
    static final int BRIDGE_QUERY = 9;
    static final int BRANCH_RESOLVE = 10;
    static final int NOISY_OUTPUT_RESOLVE = 11;
    static final int GROUP_COUNT = 12;
    static final int MODULE_COUNT = 12;

    private RealtimeSharcQueryGroups() {}

    static RaygenSchedule schedule(String suffix) {
        return GeneratedShaderPrograms.schedule("sharc.query." + mode(suffix));
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
