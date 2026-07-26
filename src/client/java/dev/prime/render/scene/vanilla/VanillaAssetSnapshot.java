package dev.prime.render.scene.vanilla;

import dev.prime.render.terrain.LabPbrMaterialSet;
import java.util.Objects;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;

/**
 * Resource-pack and geometry-policy view captured once before Section jobs are dispatched.
 *
 * <p>The referenced Minecraft registries are treated as immutable for one reload epoch. Reload
 * invalidation replaces this whole value before another generation can become resident.
 */
public record VanillaAssetSnapshot(
        BlockStateModelSet blockModels,
        FluidStateModelSet fluidModels,
        BlockColors blockColors,
        SpriteFinder blockSpriteFinder,
        LabPbrMaterialSet labPbrMaterials,
        VanillaGeometryPolicy geometryPolicy,
        boolean cutoutLeaves,
        boolean buildOpacityMicromap,
        int segmentTriangleTarget) {
    public VanillaAssetSnapshot {
        Objects.requireNonNull(blockModels, "blockModels");
        Objects.requireNonNull(fluidModels, "fluidModels");
        Objects.requireNonNull(blockColors, "blockColors");
        Objects.requireNonNull(blockSpriteFinder, "blockSpriteFinder");
        Objects.requireNonNull(labPbrMaterials, "labPbrMaterials");
        Objects.requireNonNull(geometryPolicy, "geometryPolicy");
        if (segmentTriangleTarget < 2 || (segmentTriangleTarget & 1) != 0) {
            throw new IllegalArgumentException(
                    "Section mesh segment capacity must contain whole quads");
        }
    }
}
