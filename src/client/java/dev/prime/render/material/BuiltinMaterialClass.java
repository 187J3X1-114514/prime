package dev.prime.render.material;

/** Stable four-bit ABI identifiers for conservative built-in Minecraft PBR presets. */
public enum BuiltinMaterialClass {
    DEFAULT(0, Float.NaN, 0),
    ROUGH_STONE(1, 0.82F, 0),
    POLISHED_STONE(2, 0.48F, 0),
    EARTH(3, 0.96F, 0),
    WOOD(4, 0.72F, 0),
    FIBER(5, 0.98F, 0),
    CERAMIC(6, 0.62F, 0),
    GLAZED_CERAMIC(7, 0.28F, 0),
    ORGANIC(8, 0.90F, 0),
    IRON(9, 0.38F, 231),
    GOLD(10, 0.30F, 232),
    COPPER(11, 0.34F, 235),
    AGED_COPPER(12, 0.78F, 0);

    private final int id;
    private final float roughness;
    private final int fresnelCode;

    BuiltinMaterialClass(int id, float roughness, int fresnelCode) {
        this.id = id;
        this.roughness = roughness;
        this.fresnelCode = fresnelCode;
    }

    public int id() {
        return this.id;
    }

    public float roughness() {
        return this.roughness;
    }

    public int fresnelCode() {
        return this.fresnelCode;
    }

    public static BuiltinMaterialClass fromId(int id) {
        for (BuiltinMaterialClass value : values()) {
            if (value.id == id) {
                return value;
            }
        }
        throw new IllegalArgumentException("Invalid built-in material class: " + id);
    }
}
