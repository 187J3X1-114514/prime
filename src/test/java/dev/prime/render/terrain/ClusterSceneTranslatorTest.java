package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.scene.CapturedSectionGeometry;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

final class ClusterSceneTranslatorTest {
    @Test
    void alphaCutOverrideIsResolvedOnlyInsideClusterTranslation() {
        try (SectionMeshAccumulatorTest.TestSprite sprite =
                new SectionMeshAccumulatorTest.TestSprite("alpha_cut_override")) {
            CapturedSectionGeometry.Surface translucent =
                    CapturedSectionGeometry.Surface.uniform(
                            -1,
                            CapturedSectionGeometry.Layer.TRANSLUCENT,
                            false,
                            false,
                            false,
                            false,
                            false,
                            true,
                            false,
                            0,
                            sprite);
            CapturedSectionGeometry.Surface alphaCut =
                    CapturedSectionGeometry.Surface.uniform(
                            -1,
                            CapturedSectionGeometry.Layer.TRANSLUCENT,
                            true,
                            false,
                            false,
                            false,
                            false,
                            true,
                            false,
                            0,
                            sprite);

            assertFalse(ClusterSceneTranslator.isCutout(translucent));
            assertTrue(ClusterSceneTranslator.isTransmissive(translucent));
            assertTrue(ClusterSceneTranslator.isCutout(alphaCut));
            assertFalse(ClusterSceneTranslator.isTransmissive(alphaCut));
        }
    }

    @Test
    void vanillaGrassSideOverlayIsCompositedWithoutMovingItsCapturedPlane() {
        try (SectionMeshAccumulatorTest.TestSprite base =
                        new SectionMeshAccumulatorTest.TestSprite("captured_grass_side");
                SectionMeshAccumulatorTest.TestSprite overlay =
                        new SectionMeshAccumulatorTest.TestSprite(
                                "captured_grass_side_overlay")) {
            base.fill(0xff70_5030);
            overlay.fill(0);
            overlay.setPixel(0, 0, 0xff80_c060);
            int tint = 0xff70_d050;
            CapturedSectionGeometry section = capturedLayeredFace(
                    base,
                    overlay,
                    3.0F,
                    new int[] {tint, tint, tint, tint});

            CpuClusterMesh cluster = translate(section);

            assertEquals(1, cluster.voxelMeshes().size());
            assertEquals(1, cluster.voxelInstances().count());
            assertEquals(3.0F, cluster.voxelInstances().translationZ(0), 0.0F);
            assertEquals(
                    PrimitivePacking.packTint(tint) & 0x00ff_ffff,
                    cluster.voxelInstances().packedTint(0));
            assertEquals(0L, cluster.opaqueTriangleCount());
            assertEquals(0L, cluster.cutoutTriangleCount());

            CompiledCluster compiled =
                    new CompiledCluster(0L, 0, 0, 0, cluster);
            CompiledCluster decoded =
                    CompiledClusterCodec.decode(
                            CompiledClusterCodec.encode(compiled));
            assertEquals(
                    CompiledClusterFingerprint.sha256Hex(compiled),
                    CompiledClusterFingerprint.sha256Hex(decoded));
        }
    }

    @Test
    void fabricVertexColorsAreAveragedOnlyInsideClusterTranslation() {
        try (SectionMeshAccumulatorTest.TestSprite base =
                        new SectionMeshAccumulatorTest.TestSprite("fabric_grass_side");
                SectionMeshAccumulatorTest.TestSprite overlay =
                        new SectionMeshAccumulatorTest.TestSprite(
                                "fabric_grass_side_overlay")) {
            base.fill(0xff60_4020);
            overlay.fill(0xff40_a040);
            int[] colors = {
                0xff20_8040,
                0xff40_a060,
                0xff60_c080,
                0xff80_e0a0
            };
            CapturedSectionGeometry section =
                    capturedLayeredFace(base, overlay, 5.0F, colors);

            CpuClusterMesh first = translate(section);
            CpuClusterMesh second = translate(section);
            int expected = ClusterSceneTranslator.averageColor(
                    section.quads().get(1).surface());

            assertEquals(1, first.voxelInstances().count());
            assertEquals(5.0F, first.voxelInstances().translationZ(0), 0.0F);
            assertEquals(
                    PrimitivePacking.packTint(expected) & 0x00ff_ffff,
                    first.voxelInstances().packedTint(0));
            assertArrayEquals(
                    first.voxelInstances().packedTints(),
                    second.voxelInstances().packedTints());
            assertArrayEquals(
                    first.voxelInstances().translations(),
                    second.voxelInstances().translations());
            assertArrayEquals(
                    first.voxelMeshes().getFirst().positions(),
                    second.voxelMeshes().getFirst().positions());
            assertArrayEquals(
                    first.voxelMeshes().getFirst().primitiveRecords(),
                    second.voxelMeshes().getFirst().primitiveRecords());
        }
    }

    @Test
    void coincidentCutoutWithoutRasterOverlayRoleIsNotBakedIntoOpaqueBase() {
        try (SectionMeshAccumulatorTest.TestSprite base =
                        new SectionMeshAccumulatorTest.TestSprite("ordinary_base");
                SectionMeshAccumulatorTest.TestSprite cutout =
                        new SectionMeshAccumulatorTest.TestSprite("ordinary_cutout")) {
            base.fill(0xff70_5030);
            cutout.fill(0xff80_c060);
            CapturedSectionGeometry.MutableQuad quad = face(1.0F);
            CapturedSectionGeometry.Builder section = new CapturedSectionGeometry.Builder();
            section.add(quad, surface(base, -1, false, false));
            section.add(quad, surface(cutout, -1, true, false));

            CpuClusterMesh cluster = translate(section.build());

            assertEquals(2, cluster.voxelInstances().count());
        }
    }

    @Test
    void rawFluidWindingsAndCollisionFactsAreResolvedAtClusterScope() {
        try (SectionMeshAccumulatorTest.TestSprite water =
                new SectionMeshAccumulatorTest.TestSprite("captured_water")) {
            water.fill(0xff40_80c0);
            CapturedSectionGeometry.MutableQuad outward = face(1.0F);
            CapturedSectionGeometry.MutableQuad inward = reversed(outward);
            CapturedSectionGeometry.Builder section =
                    new CapturedSectionGeometry.Builder();
            int collisionMask = 1 << Direction.SOUTH.ordinal();
            section.add(outward, fluidSurface(water, collisionMask));
            section.add(inward, fluidSurface(water, collisionMask));
            CapturedSectionGeometry captured = section.build();

            CpuClusterMesh translated = translate(captured, false);
            CpuClusterMesh suppressed = translate(captured, true);

            assertEquals(2L, translated.transmissiveTriangleCount());
            assertEquals(0L, suppressed.transmissiveTriangleCount());
        }
    }

    @Test
    void fixedSectionSlotsMakeCaptureCompletionOrderIrrelevant() {
        try (SectionMeshAccumulatorTest.TestSprite base =
                        new SectionMeshAccumulatorTest.TestSprite("ordered_base");
                SectionMeshAccumulatorTest.TestSprite overlay =
                        new SectionMeshAccumulatorTest.TestSprite(
                                "ordered_overlay")) {
            base.fill(0xff60_4020);
            overlay.fill(0xff40_a040);
            CapturedSectionGeometry section = capturedLayeredFace(
                    base,
                    overlay,
                    2.0F,
                    new int[] {-1, -1, -1, -1});
            CapturedCluster.Builder forward = new CapturedCluster.Builder(0, 0, 0);
            forward.add(0, 0, 0, section);
            forward.add(1, 0, 0, section);
            CapturedCluster.Builder reverse = new CapturedCluster.Builder(0, 0, 0);
            reverse.add(1, 0, 0, section);
            reverse.add(0, 0, 0, section);

            CpuClusterMesh first = translate(forward.build(), false);
            CpuClusterMesh second = translate(reverse.build(), false);

            assertArrayEquals(
                    first.voxelInstances().meshIndices(),
                    second.voxelInstances().meshIndices());
            assertArrayEquals(
                    first.voxelInstances().packedTints(),
                    second.voxelInstances().packedTints());
            assertArrayEquals(
                    first.voxelInstances().translations(),
                    second.voxelInstances().translations());
            assertArrayEquals(
                    first.voxelMeshes().getFirst().primitiveRecords(),
                    second.voxelMeshes().getFirst().primitiveRecords());
        }
    }

    private static CapturedSectionGeometry capturedLayeredFace(
            SectionMeshAccumulatorTest.TestSprite base,
            SectionMeshAccumulatorTest.TestSprite overlay,
            float plane,
            int[] overlayColors) {
        CapturedSectionGeometry.MutableQuad scratch = face(plane);
        CapturedSectionGeometry.Builder section = new CapturedSectionGeometry.Builder();
        section.add(scratch, surface(base, -1, false, false));
        section.add(scratch, new CapturedSectionGeometry.Surface(
                overlayColors[0],
                overlayColors[1],
                overlayColors[2],
                overlayColors[3],
                CapturedSectionGeometry.Layer.CUTOUT,
                false,
                false,
                false,
                false,
                false,
                true,
                true,
                0,
                overlay,
                null));
        for (int vertex = 0; vertex < 4; vertex++) {
            scratch.z[vertex] = plane + 7.0F;
        }
        return section.build();
    }

    private static CapturedSectionGeometry.Surface surface(
            SectionMeshAccumulatorTest.TestSprite sprite,
            int color,
            boolean cutout,
            boolean rasterOverlay) {
        return CapturedSectionGeometry.Surface.uniform(
                color,
                cutout
                        ? CapturedSectionGeometry.Layer.CUTOUT
                        : CapturedSectionGeometry.Layer.OPAQUE,
                false,
                false,
                false,
                false,
                false,
                true,
                rasterOverlay,
                0,
                sprite);
    }

    private static CapturedSectionGeometry.MutableQuad face(float plane) {
        CapturedSectionGeometry.MutableQuad quad =
                new CapturedSectionGeometry.MutableQuad();
        quad.x[0] = 0.0F;
        quad.y[0] = 0.0F;
        quad.x[1] = 1.0F;
        quad.y[1] = 0.0F;
        quad.x[2] = 1.0F;
        quad.y[2] = 1.0F;
        quad.x[3] = 0.0F;
        quad.y[3] = 1.0F;
        for (int vertex = 0; vertex < 4; vertex++) {
            quad.z[vertex] = plane;
        }
        quad.u[0] = 0.0F;
        quad.v[0] = 0.0F;
        quad.u[1] = 1.0F;
        quad.v[1] = 0.0F;
        quad.u[2] = 1.0F;
        quad.v[2] = 1.0F;
        quad.u[3] = 0.0F;
        quad.v[3] = 1.0F;
        quad.normalZ = 1.0F;
        return quad;
    }

    private static CapturedSectionGeometry.MutableQuad reversed(
            CapturedSectionGeometry.MutableQuad source) {
        CapturedSectionGeometry.MutableQuad reversed =
                new CapturedSectionGeometry.MutableQuad();
        int[] order = {0, 3, 2, 1};
        for (int vertex = 0; vertex < 4; vertex++) {
            int sourceVertex = order[vertex];
            reversed.x[vertex] = source.x[sourceVertex];
            reversed.y[vertex] = source.y[sourceVertex];
            reversed.z[vertex] = source.z[sourceVertex];
            reversed.u[vertex] = source.u[sourceVertex];
            reversed.v[vertex] = source.v[sourceVertex];
        }
        reversed.normalZ = -1.0F;
        return reversed;
    }

    private static CapturedSectionGeometry.Surface fluidSurface(
            SectionMeshAccumulatorTest.TestSprite sprite,
            int collisionMask) {
        return new CapturedSectionGeometry.Surface(
                -1,
                -1,
                -1,
                -1,
                CapturedSectionGeometry.Layer.TRANSLUCENT,
                false,
                false,
                false,
                true,
                false,
                false,
                false,
                0,
                sprite,
                new CapturedSectionGeometry.FluidFacts(
                        0, 0, 0, false, collisionMask));
    }

    private static CpuClusterMesh translate(CapturedSectionGeometry section) {
        return translate(section, false);
    }

    private static CpuClusterMesh translate(
            CapturedSectionGeometry section, boolean suppressFluidFace) {
        CapturedCluster.Builder captured = new CapturedCluster.Builder(0, 0, 0);
        captured.add(0, 0, 0, section);
        return translate(captured.build(), suppressFluidFace);
    }

    private static CpuClusterMesh translate(
            CapturedCluster captured, boolean suppressFluidFace) {
        return ClusterSceneTranslator.translate(
                captured,
                LabPbrMaterialSet.EMPTY,
                new ClusterTranslationSettings(
                        false,
                        TerrainMemoryBudget.TARGET_SEGMENT_TRIANGLES,
                        OpacityMicromapData.SUBDIVISION_LEVEL + 2,
                        true,
                        VoxelSurfaceSettings.BASE_HEIGHT,
                        false,
                        suppressFluidFace));
    }
}
