package dev.prime.render.scene.vanilla;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.block.dispatch.WeightedVariants;
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
