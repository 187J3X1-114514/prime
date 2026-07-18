package dev.prime.render.scene.vanilla;

/**
 * Explicit changes Prime may apply while translating vanilla raster geometry to a world scene.
 *
 * <p>The default preserves Minecraft's generated surface positions and adjacency bugs. Mandatory
 * representation conversions, such as collapsing a raster-only reverse winding into one two-sided
 * ray-tracing surface, are invariants and therefore are not presented as optional fixes.
 */
public record VanillaGeometryPolicy(
        boolean closeCoveredFluidGap,
        boolean suppressFluidFaceAgainstFullCollision) {
    public static final VanillaGeometryPolicy VANILLA_PARITY = new VanillaGeometryPolicy(false, false);
}
