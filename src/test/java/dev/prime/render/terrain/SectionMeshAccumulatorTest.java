package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

final class SectionMeshAccumulatorTest {
    @Test
    void buildTransfersOwnershipExactlyOnce() {
        SectionMeshAccumulator accumulator = new SectionMeshAccumulator(
                LabPbrMaterialSet.EMPTY, false);
        CpuSectionGeometry geometry = accumulator.build();
        assertTrue(geometry.meshes().isEmpty());
        assertTrue(geometry.mergeFaces().isEmpty());
        assertThrows(IllegalStateException.class, accumulator::build);
    }

    @Test
    void retainsOnlyCompleteAxisAlignedUnitFacesForClusterMerging() {
        try (TestSprite sprite = new TestSprite()) {
            SectionMeshAccumulator accumulator = new SectionMeshAccumulator(
                    LabPbrMaterialSet.EMPTY, false);
            accumulator.addQuad(
                    horizontalQuad(3.0F, 5.0F, 7.0F, 1.0F),
                    opaqueSurface(sprite));
            accumulator.addQuad(
                    horizontalQuad(4.0F, 5.0F, 7.0F, 0.5F),
                    opaqueSurface(sprite));
            accumulator.addQuad(
                    horizontalQuad(5.0F, 5.0F, 7.0F, 1.0F),
                    new SectionMeshAccumulator.Surface().set(
                            -1,
                            false,
                            false,
                            false,
                            false,
                            false,
                            false,
                            false,
                            0,
                            sprite));

            CpuSectionGeometry geometry = accumulator.build();

            assertEquals(1, geometry.mergeFaces().size());
            assertEquals(1, geometry.meshes().size());
            assertEquals(4, geometry.meshes().getFirst().opaqueTriangleCount());
            assertTrue(Float.intBitsToFloat(
                    geometry.mergeFaces().getFirst().primitive()[6]) < 0.0F);
        }
    }

    @Test
    void admitsNonFluidTransmissionButKeepsWaterOutOfMerging() {
        try (TestSprite sprite = new TestSprite()) {
            SectionMeshAccumulator accumulator = new SectionMeshAccumulator(
                    LabPbrMaterialSet.EMPTY, false);
            accumulator.addQuad(
                    horizontalQuad(0.0F, 0.0F, 2.0F, 1.0F),
                    transmissiveSurface(sprite, false));
            accumulator.addQuad(
                    horizontalQuad(1.0F, 0.0F, 2.0F, 1.0F),
                    transmissiveSurface(sprite, true));

            CpuSectionGeometry geometry = accumulator.build();

            assertEquals(1, geometry.mergeFaces().size());
            assertTrue(geometry.mergeFaces().getFirst().transmissive());
            assertEquals(1, geometry.meshes().size());
            assertEquals(2, geometry.meshes().getFirst().transmissiveTriangleCount());
        }
    }

    static SectionMeshAccumulator.Quad horizontalQuad(
            float x, float y, float z, float width) {
        SectionMeshAccumulator.Quad quad = new SectionMeshAccumulator.Quad();
        quad.x[0] = x;
        quad.y[0] = y;
        quad.z[0] = z;
        quad.x[1] = x + width;
        quad.y[1] = y;
        quad.z[1] = z;
        quad.x[2] = x + width;
        quad.y[2] = y + 1.0F;
        quad.z[2] = z;
        quad.x[3] = x;
        quad.y[3] = y + 1.0F;
        quad.z[3] = z;
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

    static SectionMeshAccumulator.Surface opaqueSurface(TextureAtlasSprite sprite) {
        return new SectionMeshAccumulator.Surface().set(
                -1, false, false, false, false, false, false, true, 0, sprite);
    }

    static SectionMeshAccumulator.Surface cutoutSurface(TextureAtlasSprite sprite) {
        return new SectionMeshAccumulator.Surface().set(
                -1, true, false, false, false, false, false, true, 0, sprite);
    }

    static SectionMeshAccumulator.Surface transmissiveSurface(
            TextureAtlasSprite sprite, boolean water) {
        return new SectionMeshAccumulator.Surface().set(
                -1, false, false, true, false, water, false, true, 0, sprite);
    }

    static final class TestSprite extends TextureAtlasSprite {
        TestSprite() {
            super(
                    Identifier.fromNamespaceAndPath("prime", "merge_test"),
                    new SpriteContents(
                            Identifier.fromNamespaceAndPath("prime", "merge_test"),
                            new FrameSize(16, 16),
                            new NativeImage(16, 16, true)),
                    16,
                    16,
                    0,
                    0,
                    0);
        }
    }
}
