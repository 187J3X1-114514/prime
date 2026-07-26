package dev.prime.render.scene.vanilla;

import dev.prime.render.ResourceCleanup;
import dev.prime.render.terrain.CpuSectionGeometry;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;

/**
 * Single geometry authority between Minecraft's rendering front end and Prime's renderer-owned
 * world scene.
 *
 * <p>{@code TerrainStreamer} owns coverage, invalidation, scheduling and lifetime. This module owns
 * only the translation of one captured {@link RenderSectionRegion} through Minecraft's real
 * Section compiler into an immutable Prime payload. The region's block states are copied by
 * Minecraft; remaining live tint/light/entity services are an explicit capture-boundary gap, not
 * interpreter-owned hidden state. It neither consumes completed raster meshes nor observes raster
 * visibility, so there is no multi-source reconciliation policy.
 *
 * <p>Only this module interprets vanilla mesh production. Vulkan code consumes immutable scene
 * payloads and never observes Mixins, block states, model objects, or vanilla vertex interfaces.
 */
public final class VanillaSceneInterpreter implements AutoCloseable {
    private final ConcurrentLinkedQueue<SectionBufferBuilderPack> availableSectionBuffers =
            new ConcurrentLinkedQueue<>();
    // close() runs on the client thread while shared-executor compilers may still return buffers.
    private volatile boolean closed;

    public VanillaSceneInterpreter() {
    }

    public CpuSectionGeometry compileSection(VanillaSectionCompileInput input) {
        if (this.closed) {
            throw new IllegalStateException("Vanilla scene interpreter is closed");
        }
        SectionBufferBuilderPack buffers = this.availableSectionBuffers.poll();
        if (buffers == null) {
            buffers = new SectionBufferBuilderPack();
        }
        try {
            return VanillaSectionMesher.compile(input, buffers);
        } finally {
            if (this.closed) {
                buffers.close();
            } else {
                this.availableSectionBuffers.offer(buffers);
                // close() can win between the first check and offer(). Removing after publication
                // transfers ownership to exactly one side without adding a lock domain.
                if (this.closed && this.availableSectionBuffers.remove(buffers)) {
                    buffers.close();
                }
            }
        }
    }

    @Override
    public void close() {
        this.closed = true;
        RuntimeException failure = null;
        SectionBufferBuilderPack buffers;
        while ((buffers = this.availableSectionBuffers.poll()) != null) {
            SectionBufferBuilderPack retired = buffers;
            failure = ResourceCleanup.run(retired::close, failure);
        }
        ResourceCleanup.throwIfFailed(failure);
    }
}
