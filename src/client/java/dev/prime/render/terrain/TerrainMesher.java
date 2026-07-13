package dev.prime.render.terrain;

import com.mojang.blaze3d.vertex.QuadInstance;
import dev.prime.PrimeClient;
import java.util.Arrays;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3fc;

public final class TerrainMesher {
    private TerrainMesher() {
    }

    public static CpuSectionMesh mesh(
            RenderSectionRegion region,
            BlockStateModelSet models,
            TintSnapshot tints,
            int sectionX,
            int sectionY,
            int sectionZ) {
        MeshBuilder opaque = new MeshBuilder();
        MeshBuilder cutout = new MeshBuilder();
        QuadCapture capture = new QuadCapture(opaque, cutout, tints);
        ModelBlockRenderer renderer = new ModelBlockRenderer(false, true, new BlockColors());
        MutableBlockPos position = new MutableBlockPos();
        int originX = sectionX << 4;
        int originY = sectionY << 4;
        int originZ = sectionZ << 4;
        for (int localY = 0; localY < 16; localY++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int localX = 0; localX < 16; localX++) {
                    position.set(originX + localX, originY + localY, originZ + localZ);
                    BlockState state = region.getBlockState(position);
                    if (state.getRenderShape() != RenderShape.MODEL) {
                        continue;
                    }
                    capture.setBlock(localX, localY, localZ);
                    try {
                        renderer.tesselateBlock(
                                capture,
                                localX,
                                localY,
                                localZ,
                                region,
                                position,
                                state,
                                models.get(state),
                                state.getSeed(position));
                    } catch (RuntimeException exception) {
                        PrimeClient.LOGGER.debug("Skipping block model that failed to tessellate at {}", position, exception);
                    }
                }
            }
        }
        float[] positions = concatenate(opaque.positions.toArray(), cutout.positions.toArray());
        int[] primitives = concatenate(opaque.primitives.toArray(), cutout.primitives.toArray());
        return new CpuSectionMesh(positions, primitives, opaque.triangleCount, cutout.triangleCount);
    }

    private static float[] concatenate(float[] first, float[] second) {
        float[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static int[] concatenate(int[] first, int[] second) {
        int[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static final class QuadCapture implements BlockQuadOutput {
        private static final int[] FIRST_TRIANGLE = new int[] {0, 1, 2};
        private static final int[] SECOND_TRIANGLE = new int[] {0, 2, 3};

        private final MeshBuilder opaque;
        private final MeshBuilder cutout;
        private final TintSnapshot tints;
        private int localX;
        private int localY;
        private int localZ;

        private QuadCapture(MeshBuilder opaque, MeshBuilder cutout, TintSnapshot tints) {
            this.opaque = opaque;
            this.cutout = cutout;
            this.tints = tints;
        }

        private void setBlock(int x, int y, int z) {
            this.localX = x;
            this.localY = y;
            this.localZ = z;
        }

        @Override
        public void put(float x, float y, float z, BakedQuad quad, QuadInstance instance) {
            ChunkSectionLayer layer = quad.materialInfo().layer();
            if (layer == ChunkSectionLayer.TRANSLUCENT) {
                return;
            }
            MeshBuilder destination = layer == ChunkSectionLayer.SOLID ? this.opaque : this.cutout;
            int tint = quad.materialInfo().tintIndex() < 0
                    ? -1
                    : this.tints.color(this.localX, this.localY, this.localZ, quad.materialInfo().tintIndex());
            emitTriangle(destination, x, y, z, quad, FIRST_TRIANGLE, tint, layer == ChunkSectionLayer.CUTOUT);
            emitTriangle(destination, x, y, z, quad, SECOND_TRIANGLE, tint, layer == ChunkSectionLayer.CUTOUT);
        }

        private static void emitTriangle(
                MeshBuilder destination,
                float x,
                float y,
                float z,
                BakedQuad quad,
                int[] indices,
                int tint,
                boolean cutout) {
            Vector3fc first = quad.position(indices[0]);
            Vector3fc second = quad.position(indices[1]);
            Vector3fc third = quad.position(indices[2]);
            destination.positions.add(first.x() + x);
            destination.positions.add(first.y() + y);
            destination.positions.add(first.z() + z);
            destination.positions.add(second.x() + x);
            destination.positions.add(second.y() + y);
            destination.positions.add(second.z() + z);
            destination.positions.add(third.x() + x);
            destination.positions.add(third.y() + y);
            destination.positions.add(third.z() + z);

            destination.primitives.add(packUv(quad.packedUV(indices[0])));
            destination.primitives.add(packUv(quad.packedUV(indices[1])));
            destination.primitives.add(packUv(quad.packedUV(indices[2])));
            destination.primitives.add(PrimitivePacking.packTint(tint));

            float edge1X = second.x() - first.x();
            float edge1Y = second.y() - first.y();
            float edge1Z = second.z() - first.z();
            float edge2X = third.x() - first.x();
            float edge2Y = third.y() - first.y();
            float edge2Z = third.z() - first.z();
            float normalX = edge1Y * edge2Z - edge1Z * edge2Y;
            float normalY = edge1Z * edge2X - edge1X * edge2Z;
            float normalZ = edge1X * edge2Y - edge1Y * edge2X;
            float inverseLength = 1.0F / Math.max(
                    1.0e-20F,
                    (float) Math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ));
            destination.primitives.add(PrimitivePacking.packOctahedralNormal(
                    normalX * inverseLength,
                    normalY * inverseLength,
                    normalZ * inverseLength));
            destination.primitives.add(cutout ? 1 : 0);
            destination.primitives.add(0);
            destination.primitives.add(0);
            destination.triangleCount++;
        }

        private static int packUv(long packedUv) {
            return PrimitivePacking.packHalf2(UVPair.unpackU(packedUv), UVPair.unpackV(packedUv));
        }
    }

    private static final class MeshBuilder {
        private final FloatArrayBuilder positions = new FloatArrayBuilder();
        private final IntArrayBuilder primitives = new IntArrayBuilder();
        private int triangleCount;
    }

    private static final class FloatArrayBuilder {
        private float[] values = new float[1024];
        private int size;

        private void add(float value) {
            if (this.size == this.values.length) {
                this.values = Arrays.copyOf(this.values, this.values.length * 2);
            }
            this.values[this.size++] = value;
        }

        private float[] toArray() {
            return Arrays.copyOf(this.values, this.size);
        }
    }

    private static final class IntArrayBuilder {
        private int[] values = new int[1024];
        private int size;

        private void add(int value) {
            if (this.size == this.values.length) {
                this.values = Arrays.copyOf(this.values, this.values.length * 2);
            }
            this.values[this.size++] = value;
        }

        private int[] toArray() {
            return Arrays.copyOf(this.values, this.size);
        }
    }
}
