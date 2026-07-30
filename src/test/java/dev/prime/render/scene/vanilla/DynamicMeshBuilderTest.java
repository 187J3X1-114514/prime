package dev.prime.render.scene.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.PrimitiveTopology;
import dev.prime.render.terrain.CpuClusterMesh;
import dev.prime.render.terrain.PrimitivePacking;
import java.util.List;
import net.minecraft.util.LightCoordsUtil;
import org.junit.jupiter.api.Test;

final class DynamicMeshBuilderTest {
    @Test
    void capturesQuadForGiWithoutRegisteringVisibleEmissionAsALight() {
        DynamicMeshBuilder builder = new DynamicMeshBuilder(10.0, 20.0, 30.0);
        DynamicMeshBuilder.VertexSink sink = builder.open(
                VanillaSceneBoundary.Element.BLOCK_ENTITY,
                PrimitiveTopology.QUADS,
                7,
                LightCoordsUtil.FULL_BRIGHT);
        vertex(sink, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
        vertex(sink, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F);
        vertex(sink, 1.0F, 1.0F, 0.0F, 1.0F, 1.0F);
        vertex(sink, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F);
        sink.finish();

        DynamicSceneFrame frame = builder.build(0, 0, 0, List.of());
        CpuClusterMesh mesh = frame.mesh();

        assertEquals(2L, mesh.triangleCount());
        assertEquals(2L, mesh.cutoutTriangleCount());
        assertEquals(2, frame.blockEntityTriangles());
        assertEquals(0, frame.entityTriangles());
        assertEquals(0, frame.particleTriangles());
        assertTrue(mesh.lights().isEmpty());
        assertFalse(mesh.opacityMicromap().isEmpty());

        CpuClusterMesh.Segment segment = mesh.segments().getFirst();
        assertEquals(10.0F, segment.positions()[0]);
        assertEquals(20.0F, segment.positions()[1]);
        assertEquals(30.0F, segment.positions()[2]);
        int[] records = segment.primitiveRecords();
        for (int triangle = 0; triangle < 2; triangle++) {
            int flagsTexture = records[triangle * 8 + 5];
            assertEquals(7, PrimitivePacking.unpackDynamicTextureIndex(flagsTexture));
            assertTrue(PrimitivePacking.hasVisibleEmission(flagsTexture));
            assertEquals(
                    PrimitivePacking.NO_EMITTER_INDEX,
                    PrimitivePacking.unpackEmitterIndex(flagsTexture));
        }
    }

    @Test
    void capturesParticleTriangleWithoutInventingEmission() {
        DynamicMeshBuilder builder = new DynamicMeshBuilder(0.0, 0.0, 0.0);
        DynamicMeshBuilder.VertexSink sink = builder.open(
                VanillaSceneBoundary.Element.PARTICLE,
                PrimitiveTopology.TRIANGLES,
                3,
                0);
        vertex(sink, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
        vertex(sink, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F);
        vertex(sink, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F);
        sink.finish();

        DynamicSceneFrame frame = builder.build(0, 0, 0, List.of());
        int flagsTexture =
                frame.mesh().segments().getFirst().primitiveRecords()[5];

        assertEquals(1, frame.particleTriangles());
        assertEquals(3, PrimitivePacking.unpackDynamicTextureIndex(flagsTexture));
        assertFalse(PrimitivePacking.hasVisibleEmission(flagsTexture));
        assertTrue(frame.mesh().lights().isEmpty());
    }

    private static void vertex(
            DynamicMeshBuilder.VertexSink sink,
            float x,
            float y,
            float z,
            float u,
            float v) {
        sink.addVertex(x, y, z)
                .setColor(-1)
                .setUv(u, v)
                .setNormal(0.0F, 0.0F, 1.0F);
    }
}
