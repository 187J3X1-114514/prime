package dev.prime.render.scene.vanilla;

import com.mojang.blaze3d.vertex.VertexSorting;
import dev.prime.render.terrain.CpuSectionMesh;
import dev.prime.render.terrain.LabPbrMaterialSet;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder;

/**
 * Runs Minecraft's real Section compiler with a renderer-owned semantic side channel.
 *
 * <p>The compiler invocation is Prime's one mesh-production entry point. It deliberately reuses
 * vanilla model selection, face culling, randomization, tint lookup, fluid tessellation, render
 * layers, and Fabric renderer integration, but it is scheduled from Prime's scene rather than from
 * vanilla's visible raster Sections.
 *
 * <p>The remaining differences are explicit representation boundaries: raster AO and light-map
 * vertex illumination are excluded from a path-traced material, translucent index sorting is
 * irrelevant to ray-tracing geometry, and coincident reverse fluid faces are collapsed because a
 * Vulkan triangle is already two-sided. Any future compatibility fix belongs in this translation
 * boundary, not in a parallel block or fluid mesher.
 */
public final class VanillaSectionMesher {
    private VanillaSectionMesher() {
    }

    public static CpuSectionMesh mesh(
            RenderSectionRegion region,
            BlockStateModelSet blockModels,
            FluidStateModelSet fluidModels,
            BlockColors blockColors,
            SpriteFinder blockSpriteFinder,
            LabPbrMaterialSet labPbrMaterials,
            VanillaGeometryPolicy geometryPolicy,
            boolean cutoutLeaves,
            int sectionX,
            int sectionY,
            int sectionZ,
            SectionBufferBuilderPack builders) {
        // AO and the light map affect only raster vertex illumination. Disabling AO here avoids
        // doing expensive work that the side channel deliberately does not consume; geometry,
        // model selection, culling, UVs, fluid surfaces and render layers still come from vanilla.
        SectionCompiler compiler = new SectionCompiler(
                false, cutoutLeaves, blockModels, fluidModels, blockColors);
        SectionPos section = SectionPos.of(sectionX, sectionY, sectionZ);
        boolean completed = false;
        try (VanillaSectionCapture capture = VanillaSectionCapture.open(
                region,
                blockColors,
                blockSpriteFinder,
                labPbrMaterials,
                geometryPolicy,
                cutoutLeaves)) {
            SectionCompiler.Results results = compiler.compile(
                    section, region, VertexSorting.byDistance(0.0F, 0.0F, 0.0F), builders);
            try {
                CpuSectionMesh mesh = capture.finish(results);
                completed = true;
                return mesh;
            } finally {
                results.release();
            }
        } finally {
            if (completed) {
                builders.clearAll();
            } else {
                builders.discardAll();
            }
        }
    }
}
