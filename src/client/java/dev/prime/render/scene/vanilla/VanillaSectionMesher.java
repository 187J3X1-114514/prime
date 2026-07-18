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

/** Runs Minecraft's real Section compiler with a renderer-owned semantic side channel. */
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
