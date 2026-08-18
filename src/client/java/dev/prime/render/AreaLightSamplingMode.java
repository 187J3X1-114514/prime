package dev.prime.render;

import dev.prime.render.shader.ShaderAbi;

/** Non-persistent realtime primary area-light sampling strategies for direct A/B comparison. */
public enum AreaLightSamplingMode {
    PURE_LIGHT_TREE(
            "pure_light_tree",
            ShaderAbi.PATH_AREA_LIGHT_SAMPLING_MODE_PURE_LIGHT_TREE),
    WIDE_RIS_4X4(
            "wide_ris_4x4",
            ShaderAbi.PATH_AREA_LIGHT_SAMPLING_MODE_WIDE_RIS_4X4);

    public static final AreaLightSamplingMode DEFAULT = PURE_LIGHT_TREE;

    private final String id;
    private final int abiValue;

    AreaLightSamplingMode(String id, int abiValue) {
        this.id = id;
        this.abiValue = abiValue;
    }

    public String id() {
        return this.id;
    }

    public int abiValue() {
        return this.abiValue;
    }

    public static AreaLightSamplingMode fromId(String id) {
        for (AreaLightSamplingMode value : values()) {
            if (value.id.equals(id)) {
                return value;
            }
        }
        return DEFAULT;
    }
}
