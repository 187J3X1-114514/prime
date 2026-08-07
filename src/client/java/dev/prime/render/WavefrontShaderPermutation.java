package dev.prime.render;

public final class WavefrontShaderPermutation {
    private WavefrontShaderPermutation() {}

    public static String suffix(
            boolean subgroupSupported, boolean invocationReorderSupported) {
        if (subgroupSupported && invocationReorderSupported) {
            return "_ser.rgen.spv";
        }
        return subgroupSupported ? "_subgroup.rgen.spv" : ".rgen.spv";
    }
}
