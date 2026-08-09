package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.scene.CapturedSectionGeometry;
import org.junit.jupiter.api.Test;

final class TransparentBoundaryResolverTest {
    @Test
    void equalSolidMediaRemoveTheSharedFace() {
        try (SectionMeshAccumulatorTest.TestSprite glass =
                new SectionMeshAccumulatorTest.TestSprite("equal_contact_glass")) {
            CapturedSectionGeometry.Builder section = new CapturedSectionGeometry.Builder();
            section.add(
                    xFace(1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    surface(glass, 0xff80_c0e0, false, false, 0, 0, 0));
            section.add(
                    xFace(-1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    surface(glass, 0xff80_c0e0, false, false, 1, 0, 0));

            CpuClusterMesh mesh = translate(0, section.build());

            assertEquals(0L, mesh.triangleCount());
            assertEquals(0L, mesh.surfaceRelationBytes());
        }
    }

    @Test
    void differentSolidMediaBecomeOneBilateralBoundary() {
        try (SectionMeshAccumulatorTest.TestSprite glass =
                        new SectionMeshAccumulatorTest.TestSprite("contact_glass");
                SectionMeshAccumulatorTest.TestSprite ice =
                        new SectionMeshAccumulatorTest.TestSprite("contact_ice")) {
            CapturedSectionGeometry.Builder section = new CapturedSectionGeometry.Builder();
            section.add(
                    xFace(1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    surface(glass, 0xff40_80c0, false, false, 0, 0, 0));
            section.add(
                    xFace(-1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    surface(ice, 0xffc0_e8ff, false, false, 1, 0, 0));

            CpuClusterMesh mesh = translate(0, section.build());

            assertEquals(2L, mesh.transmissiveTriangleCount());
            assertEquals(0L, mesh.opaqueTriangleCount());
            assertEquals(32L, mesh.surfaceRelationBytes());
            int[] records = mesh.segments().getFirst().surfaceRelationRecords();
            assertEquals(8, records.length);
            assertEquals(2, records[0]);
            assertEquals(5, records[1]);
            assertEquals(
                    CpuSectionMesh.SURFACE_RELATION_BOUNDARY,
                    records[2] & CpuSectionMesh.SURFACE_RELATION_KIND_MASK);
            assertEquals(
                    CpuSectionMesh.SURFACE_RELATION_BOUNDARY,
                    records[5] & CpuSectionMesh.SURFACE_RELATION_KIND_MASK);
            CompiledCluster decoded = CompiledClusterCodec.decode(
                    CompiledClusterCodec.encode(
                            new CompiledCluster(0L, 0, 0, 0, mesh)));
            assertEquals(32L, decoded.mesh().surfaceRelationBytes());
            assertArrayEquals(
                    records,
                    decoded.mesh().segments().getFirst().surfaceRelationRecords());
        }
    }

    @Test
    void solidMediumOwnsThePartialPaneContact() {
        try (SectionMeshAccumulatorTest.TestSprite pane =
                        new SectionMeshAccumulatorTest.TestSprite("contact_pane");
                SectionMeshAccumulatorTest.TestSprite glass =
                        new SectionMeshAccumulatorTest.TestSprite("contact_block")) {
            CapturedSectionGeometry.Builder section = new CapturedSectionGeometry.Builder();
            section.add(
                    xFace(1.0F, 0.25F, 0.75F, 0.375F, 0.625F),
                    surface(pane, 0xffa0_d0f0, false, false, 0, 0, 0, 1));
            section.add(
                    xFace(-1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    surface(glass, 0xffa0_d0f0, false, false, 1, 0, 0, 1));

            CpuClusterMesh mesh = translate(0, section.build());

            assertEquals(0.875F, projectedArea(mesh), 1.0E-6F);
            assertEquals(0L, mesh.surfaceRelationBytes());
        }
    }

    @Test
    void opaqueSurfaceWinsOverTheCoincidentGlassFace() {
        try (SectionMeshAccumulatorTest.TestSprite glass =
                        new SectionMeshAccumulatorTest.TestSprite("contact_transparent");
                SectionMeshAccumulatorTest.TestSprite stone =
                        new SectionMeshAccumulatorTest.TestSprite("contact_opaque")) {
            CapturedSectionGeometry.Builder section = new CapturedSectionGeometry.Builder();
            section.add(
                    xFace(1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    surface(glass, -1, false, false, 0, 0, 0));
            section.add(
                    xFace(-1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    surface(stone, -1, false, true, 1, 0, 0));

            CpuClusterMesh mesh = translate(0, section.build());

            assertEquals(2L, mesh.opaqueTriangleCount());
            assertEquals(0L, mesh.transmissiveTriangleCount());
        }
    }

    @Test
    void layeredOpaqueSurfaceWinsWithoutDroppingItsOverlay() {
        try (SectionMeshAccumulatorTest.TestSprite glass =
                        new SectionMeshAccumulatorTest.TestSprite("layered_transparent");
                SectionMeshAccumulatorTest.TestSprite stone =
                        new SectionMeshAccumulatorTest.TestSprite("layered_opaque");
                SectionMeshAccumulatorTest.TestSprite overlay =
                        new SectionMeshAccumulatorTest.TestSprite("layered_overlay")) {
            CapturedSectionGeometry.Builder section = new CapturedSectionGeometry.Builder();
            section.add(
                    xFace(1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    surface(glass, -1, false, false, 0, 0, 0));
            section.add(
                    xFace(-1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    surface(stone, -1, false, true, 1, 0, 0));
            section.add(
                    xFace(-1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    cutoutSurface(overlay, 1, 0, 0));

            CpuClusterMesh mesh = translate(0, section.build());

            assertEquals(2L, mesh.opaqueTriangleCount());
            assertEquals(2L, mesh.cutoutTriangleCount());
            assertEquals(0L, mesh.transmissiveTriangleCount());
        }
    }

    @Test
    void subpixelWallFireBecomesAPositiveSideOverlay() {
        try (SectionMeshAccumulatorTest.TestSprite stone =
                        new SectionMeshAccumulatorTest.TestSprite("wall_fire_stone");
                SectionMeshAccumulatorTest.TestSprite fire =
                        new SectionMeshAccumulatorTest.TestSprite("wall_fire_layer")) {
            stone.fill(0xff70_7070);
            fire.fill(0xffff_8020);
            CapturedSectionGeometry.Builder section =
                    new CapturedSectionGeometry.Builder();
            section.add(
                    xFaceAt(1.0F, 1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    surface(stone, -1, false, true, 0, 0, 0));
            section.add(
                    xFaceAt(1.000625F, -1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    emissiveOverlay(fire, 1, 0, 0));

            CpuClusterMesh mesh = translate(0, section.build());

            assertEquals(2L, mesh.opaqueTriangleCount());
            assertEquals(0L, mesh.cutoutTriangleCount());
            CpuClusterMesh.Segment segment = mesh.segments().getFirst();
            int[] relation = SurfaceRelationTable.record(
                    segment.surfaceRelationRecords(),
                    segment.opaquePrimitiveCount(),
                    0);
            assertEquals(
                    CpuSectionMesh.SURFACE_RELATION_OVERLAY,
                    relation[0] & CpuSectionMesh.SURFACE_RELATION_KIND_MASK);
            assertTrue((relation[0]
                    & CpuSectionMesh.SURFACE_RELATION_POSITIVE_ONLY) != 0);
            assertTrue((relation[0] >> 8
                    & PrimitivePacking.CONTROL_ALPHA_CUTOUT) != 0);
            assertEquals(1.0F, segment.positions()[0], 0.0F);
        }
    }

    @Test
    void equalFluidMediaUseTheSameContactResolver() {
        try (SectionMeshAccumulatorTest.TestSprite water =
                new SectionMeshAccumulatorTest.TestSprite("fluid_contact_water")) {
            CapturedSectionGeometry.Builder section =
                    new CapturedSectionGeometry.Builder();
            section.add(
                    xFace(1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    fluidSurface(water, 0, 0, 0));
            section.add(
                    xFace(-1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    fluidSurface(water, 1, 0, 0));

            CpuClusterMesh mesh = translate(0, section.build());

            assertEquals(0L, mesh.triangleCount());
            assertEquals(0L, mesh.surfaceRelationBytes());
        }
    }

    @Test
    void clusterHaloUsesTheLowerBlockAsTheOnlyOwner() {
        try (SectionMeshAccumulatorTest.TestSprite glass =
                        new SectionMeshAccumulatorTest.TestSprite("halo_glass");
                SectionMeshAccumulatorTest.TestSprite ice =
                        new SectionMeshAccumulatorTest.TestSprite("halo_ice")) {
            CapturedSectionGeometry.Builder lowerSection =
                    new CapturedSectionGeometry.Builder();
            lowerSection.add(
                    xFaceAt(16.0F, 1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    surface(glass, -1, false, false, 63, 0, 0));
            lowerSection.addPeer(
                    xFaceAt(16.0F, -1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    surface(ice, -1, false, false, 64, 0, 0));
            CapturedCluster.Builder lower = new CapturedCluster.Builder(0, 0, 0);
            lower.add(3, 0, 0, lowerSection.build());

            CapturedSectionGeometry.Builder upperSection =
                    new CapturedSectionGeometry.Builder();
            upperSection.add(
                    xFaceAt(0.0F, -1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    surface(ice, -1, false, false, 64, 0, 0));
            upperSection.addPeer(
                    xFaceAt(0.0F, 1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    surface(glass, -1, false, false, 63, 0, 0));
            CapturedCluster.Builder upper = new CapturedCluster.Builder(4, 0, 0);
            upper.add(4, 0, 0, upperSection.build());

            CpuClusterMesh lowerMesh = translate(lower.build());
            CpuClusterMesh upperMesh = translate(upper.build());

            assertEquals(2L, lowerMesh.transmissiveTriangleCount());
            assertEquals(32L, lowerMesh.surfaceRelationBytes());
            assertEquals(0L, upperMesh.triangleCount());
        }
    }

    private static CapturedSectionGeometry.Surface surface(
            SectionMeshAccumulatorTest.TestSprite sprite,
            int color,
            boolean collisionEmpty,
            boolean opaque,
            int x,
            int y,
            int z) {
        return surface(sprite, color, collisionEmpty, opaque, x, y, z, 0);
    }

    private static CapturedSectionGeometry.Surface surface(
            SectionMeshAccumulatorTest.TestSprite sprite,
            int color,
            boolean collisionEmpty,
            boolean opaque,
            int x,
            int y,
            int z,
            int mediumFamily) {
        return CapturedSectionGeometry.Surface.uniform(
                color,
                opaque
                        ? CapturedSectionGeometry.Layer.OPAQUE
                        : CapturedSectionGeometry.Layer.TRANSLUCENT,
                false,
                collisionEmpty,
                false,
                false,
                false,
                true,
                false,
                0,
                sprite.sprite(),
                new CapturedSectionGeometry.BlockFacts(x, y, z, mediumFamily));
    }

    private static CapturedSectionGeometry.Surface cutoutSurface(
            SectionMeshAccumulatorTest.TestSprite sprite, int x, int y, int z) {
        return CapturedSectionGeometry.Surface.uniform(
                -1,
                CapturedSectionGeometry.Layer.CUTOUT,
                false,
                false,
                false,
                false,
                false,
                true,
                false,
                0,
                sprite.sprite(),
                new CapturedSectionGeometry.BlockFacts(x, y, z));
    }

    private static CapturedSectionGeometry.Surface emissiveOverlay(
            SectionMeshAccumulatorTest.TestSprite sprite, int x, int y, int z) {
        return CapturedSectionGeometry.Surface.uniform(
                -1,
                CapturedSectionGeometry.Layer.CUTOUT,
                false,
                false,
                true,
                false,
                false,
                true,
                false,
                15,
                sprite.sprite(),
                new CapturedSectionGeometry.BlockFacts(x, y, z));
    }

    private static CapturedSectionGeometry.Surface fluidSurface(
            SectionMeshAccumulatorTest.TestSprite sprite, int x, int y, int z) {
        return new CapturedSectionGeometry.Surface(
                -1,
                -1,
                -1,
                -1,
                CapturedSectionGeometry.Layer.TRANSLUCENT,
                false,
                false,
                true,
                true,
                false,
                false,
                false,
                0,
                sprite.sprite(),
                new CapturedSectionGeometry.FluidFacts(x, y, z, false, 0),
                new CapturedSectionGeometry.BlockFacts(x, y, z));
    }

    private static float projectedArea(CpuClusterMesh mesh) {
        float area = 0.0F;
        for (CpuClusterMesh.Segment segment : mesh.segments()) {
            float[] positions = segment.positions();
            int first = 9 * (segment.opaqueTriangleCount() + segment.cutoutTriangleCount());
            for (int offset = first; offset < positions.length; offset += 9) {
                float edgeOneY = positions[offset + 4] - positions[offset + 1];
                float edgeOneZ = positions[offset + 5] - positions[offset + 2];
                float edgeTwoY = positions[offset + 7] - positions[offset + 1];
                float edgeTwoZ = positions[offset + 8] - positions[offset + 2];
                area += 0.5F * Math.abs(
                        edgeOneY * edgeTwoZ - edgeOneZ * edgeTwoY);
            }
        }
        return area;
    }

    private static CapturedSectionGeometry.MutableQuad xFace(
            float normal,
            float minimumY,
            float maximumY,
            float minimumZ,
            float maximumZ) {
        return xFaceAt(
                1.0F,
                normal,
                minimumY,
                maximumY,
                minimumZ,
                maximumZ);
    }

    private static CapturedSectionGeometry.MutableQuad xFaceAt(
            float plane,
            float normal,
            float minimumY,
            float maximumY,
            float minimumZ,
            float maximumZ) {
        CapturedSectionGeometry.MutableQuad quad =
                new CapturedSectionGeometry.MutableQuad();
        float[] y = normal > 0.0F
                ? new float[] {minimumY, maximumY, maximumY, minimumY}
                : new float[] {minimumY, minimumY, maximumY, maximumY};
        float[] z = normal > 0.0F
                ? new float[] {minimumZ, minimumZ, maximumZ, maximumZ}
                : new float[] {minimumZ, maximumZ, maximumZ, minimumZ};
        for (int vertex = 0; vertex < 4; vertex++) {
            quad.x[vertex] = plane;
            quad.y[vertex] = y[vertex];
            quad.z[vertex] = z[vertex];
            quad.u[vertex] = (vertex == 1 || vertex == 2) ? 1.0F : 0.0F;
            quad.v[vertex] = vertex >= 2 ? 1.0F : 0.0F;
        }
        quad.normalX = normal;
        return quad;
    }

    private static CpuClusterMesh translate(
            int sectionX, CapturedSectionGeometry section) {
        CapturedCluster.Builder captured = new CapturedCluster.Builder(0, 0, 0);
        captured.add(sectionX, 0, 0, section);
        return translate(captured.build());
    }

    private static CpuClusterMesh translate(CapturedCluster captured) {
        return ClusterSceneTranslator.translate(
                captured,
                LabPbrMaterialSet.EMPTY,
                new ClusterTranslationSettings(
                        false,
                        TerrainMemoryBudget.TARGET_SEGMENT_TRIANGLES,
                        OpacityMicromapData.SUBDIVISION_LEVEL + 2,
                        false,
                        VoxelSurfaceSettings.BASE_HEIGHT,
                        false,
                        false));
    }
}
