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
 * Boundary between Minecraft's rendering front end and Prime's renderer-owned world scene.
 *
 * <p>Only this module interprets vanilla mesh production. Vulkan code consumes immutable scene
 * payloads and never observes Mixins, block states, model objects, or vanilla vertex interfaces.
 */
public final class VanillaSceneInterpreter implements AutoCloseable {
    private final ConcurrentLinkedQueue<SectionBufferBuilderPack> availableSectionBuffers =
            new ConcurrentLinkedQueue<>();
    private volatile VanillaGeometryPolicy geometryPolicy = VanillaGeometryPolicy.VANILLA_PARITY;
    private volatile boolean closed;

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
