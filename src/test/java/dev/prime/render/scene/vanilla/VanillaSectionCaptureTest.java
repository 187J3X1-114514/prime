package dev.prime.render.scene.vanilla;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.terrain.SectionMeshAccumulator;
import java.util.List;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.block.dispatch.WeightedVariants;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.WeightedList;
import org.junit.jupiter.api.Test;

final class VanillaSectionCaptureTest {
    @Test
    void indigoAndDirectCaptureAdmitSelectedVanillaVariantsButNotCustomModels() {
        BlockStateModel deterministic = new SingleVariant(new EmptyPart());
        BlockStateModel randomized =
                new WeightedVariants(WeightedList.of(deterministic));
        BlockStateModel custom = new EmptyModel();

        assertTrue(VanillaSectionCapture.mergeableModel(deterministic));
        assertTrue(VanillaSectionCapture.mergeableModel(randomized));
        assertFalse(VanillaSectionCapture.mergeableModel(custom));
    }

    @Test
    void onlyVanillaCoplanarOverlayRolesReceiveRasterOrderDepth() {
        assertTrue(VanillaSectionCapture.isRasterOverlay(
                true, false, 0, 0.0F));
        assertFalse(VanillaSectionCapture.isRasterOverlay(
                true, false, -1, 0.0F));
        assertFalse(VanillaSectionCapture.isRasterOverlay(
                true, false, 0, 1.0F));
        assertTrue(VanillaSectionCapture.isRasterOverlay(
                false, true, -1, 1.0F));

        SectionMeshAccumulator.Quad quad = new SectionMeshAccumulator.Quad();
        quad.x[0] = 1.0F;
        quad.x[1] = 1.0F;
        quad.x[2] = 1.0F;
        quad.x[3] = 1.0F;
        quad.normalX = 1.0F;
        VanillaSectionCapture.offsetRasterOverlay(quad, true);
        for (float x : quad.x) {
            assertTrue(x > 1.0F);
        }
    }

    @Test
    void redstoneRasterTranslucencyMapsToAlphaCutInsteadOfPhysicalTransmission() {
        VanillaSectionCapture.SurfaceLayer ordinary =
                VanillaSectionCapture.classifySurfaceLayer(
                        ChunkSectionLayer.TRANSLUCENT, false, false);
        VanillaSectionCapture.SurfaceLayer redstone =
                VanillaSectionCapture.classifySurfaceLayer(
                        ChunkSectionLayer.TRANSLUCENT, false, true);

        assertFalse(ordinary.cutout());
        assertTrue(ordinary.transmissive());
        assertTrue(redstone.cutout());
        assertFalse(redstone.transmissive());
    }

    private static final class EmptyModel implements BlockStateModel {
        @Override
        public void collectParts(RandomSource random, List<BlockStateModelPart> output) {
        }

        @Override
        public Material.Baked particleMaterial() {
            return null;
        }

        @Override
        public int materialFlags() {
            return 0;
        }
    }

    private static final class EmptyPart implements BlockStateModelPart {
        @Override
        public List<BakedQuad> getQuads(Direction direction) {
            return List.of();
        }

        @Override
        public boolean useAmbientOcclusion() {
            return false;
        }

        @Override
        public Material.Baked particleMaterial() {
            return null;
        }

        @Override
        public int materialFlags() {
            return 0;
        }
    }
}
