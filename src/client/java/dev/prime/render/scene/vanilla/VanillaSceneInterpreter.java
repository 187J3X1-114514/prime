package dev.prime.render.scene.vanilla;

import dev.prime.render.terrain.CpuSectionMesh;
import dev.prime.render.terrain.LabPbrMaterialSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder;

/**
 * Single geometry authority between Minecraft's rendering front end and Prime's renderer-owned
 * world scene.
 *
 * <p>{@code TerrainStreamer} owns coverage, invalidation, scheduling and lifetime. This module owns
 * only the translation of one renderer-owned {@link RenderSectionRegion} through Minecraft's real
 * Section compiler into an immutable Prime payload. It neither consumes completed raster meshes
 * nor observes raster visibility, so there is no multi-source reconciliation policy.
 *
 * <p>Only this module interprets vanilla mesh production. Vulkan code consumes immutable scene
 * payloads and never observes Mixins, block states, model objects, or vanilla vertex interfaces.
 */
public final class VanillaSceneInterpreter implements AutoCloseable {
    private final ConcurrentLinkedQueue<SectionBufferBuilderPack> availableSectionBuffers =
            new ConcurrentLinkedQueue<>();
    private volatile VanillaGeometryPolicy geometryPolicy = VanillaGeometryPolicy.VANILLA_PARITY;
    private final boolean buildOpacityMicromap;
    private volatile boolean closed;

    public VanillaSceneInterpreter(boolean buildOpacityMicromap) {
        this.buildOpacityMicromap = buildOpacityMicromap;
    }

    public CpuSectionMesh compileSection(
            RenderSectionRegion region,
            BlockStateModelSet blockModels,
            FluidStateModelSet fluidModels,
            BlockColors blockColors,
            SpriteFinder blockSpriteFinder,
            LabPbrMaterialSet labPbrMaterials,
            boolean cutoutLeaves,
            int sectionX,
            int sectionY,
            int sectionZ) {
        VanillaGeometryPolicy policy = this.geometryPolicy;
        if (this.closed) {
            throw new IllegalStateException("Vanilla scene interpreter is closed");
        }
        SectionBufferBuilderPack buffers = this.availableSectionBuffers.poll();
        if (buffers == null) {
            buffers = new SectionBufferBuilderPack();
        }
        try {
            return VanillaSectionMesher.mesh(
                    region,
                    blockModels,
                    fluidModels,
                    blockColors,
                    blockSpriteFinder,
                    labPbrMaterials,
                    policy,
                    cutoutLeaves,
                    this.buildOpacityMicromap,
                    sectionX,
                    sectionY,
                    sectionZ,
                    buffers);
        } finally {
            if (this.closed) {
                buffers.close();
            } else {
                this.availableSectionBuffers.offer(buffers);
            }
        }
    }

    public VanillaGeometryPolicy geometryPolicy() {
        return this.geometryPolicy;
    }

    public void setGeometryPolicy(VanillaGeometryPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("Vanilla geometry policy must not be null");
        }
        this.geometryPolicy = policy;
    }

    @Override
    public void close() {
        this.closed = true;
        SectionBufferBuilderPack buffers;
        while ((buffers = this.availableSectionBuffers.poll()) != null) {
            buffers.close();
        }
    }
}
