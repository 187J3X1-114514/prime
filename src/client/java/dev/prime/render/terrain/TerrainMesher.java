package dev.prime.render.terrain;

import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.prime.PrimeClient;
import java.util.Arrays;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.joml.Vector3fc;

public final class TerrainMesher {
    private static final int[] FIRST_TRIANGLE = new int[] {0, 1, 2};
    private static final int[] SECOND_TRIANGLE = new int[] {0, 2, 3};

    private TerrainMesher() {
    }

    public static CpuSectionMesh mesh(
            RenderSectionRegion region,
            BlockStateModelSet models,
            FluidStateModelSet fluidModels,
            TintSnapshot tints,
            int sectionX,
            int sectionY,
            int sectionZ) {
        MeshBuilder opaque = new MeshBuilder();
        // Alpha-tested and transmissive triangles share the non-opaque BLAS geometry. Their
        // primitive flags keep the two material semantics distinct in any-hit and closest-hit.
        MeshBuilder nonOpaque = new MeshBuilder();
        CpuSectionLights.Builder lights = new CpuSectionLights.Builder();
        QuadCapture capture = new QuadCapture(opaque, nonOpaque, lights, tints);
        FluidCapture fluidCapture = new FluidCapture(opaque, nonOpaque, lights, region);
        ModelBlockRenderer renderer = new ModelBlockRenderer(false, true, new BlockColors());
        FluidRenderer fluidRenderer = new FluidRenderer(fluidModels);
        MutableBlockPos position = new MutableBlockPos();
        int originX = sectionX << 4;
        int originY = sectionY << 4;
        int originZ = sectionZ << 4;
        for (int localY = 0; localY < 16; localY++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int localX = 0; localX < 16; localX++) {
                    position.set(originX + localX, originY + localY, originZ + localZ);
                    BlockState state = region.getBlockState(position);

                    // Fluids are independent of RenderShape and include waterlogged blocks, so
                    // they must be extracted before the block-model branch.
                    FluidState fluid = state.getFluidState();
                    if (!fluid.isEmpty()) {
                        try {
                            FluidModel fluidModel = fluidModels.get(fluid);
                            fluidCapture.setBlock(
                                    localX,
                                    localY,
                                    localZ,
                                    position,
                                    state,
                                    fluid,
                                    fluidModel);
                            fluidRenderer.tesselate(region, position, fluidCapture, state, fluid);
                        } catch (RuntimeException exception) {
                            PrimeClient.LOGGER.debug(
                                    "Skipping fluid model that failed to tessellate at {}",
                                    position,
                                    exception);
                        }
                    }

                    if (state.getRenderShape() != RenderShape.MODEL) {
                        continue;
                    }
                    capture.setBlock(
                            localX,
                            localY,
                            localZ,
                            state.getLightEmission(),
                            state.getCollisionShape(region, position).isEmpty(),
                            state.is(BlockTags.LEAVES)
                                    || state.getBlock() == Blocks.SHORT_GRASS
                                    || state.getBlock() == Blocks.TALL_GRASS);
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
        float[] positions = concatenate(opaque.positions.toArray(), nonOpaque.positions.toArray());
        int[] primitives = concatenate(opaque.primitives.toArray(), nonOpaque.primitives.toArray());
        return new CpuSectionMesh(
                positions,
                primitives,
                opaque.triangleCount,
                nonOpaque.triangleCount,
                lights.build());
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

    private static void emitTriangle(
            MeshBuilder destination,
            CpuSectionLights.Builder lights,
            CapturedQuad quad,
            int[] indices,
            int tint,
            boolean cutout,
            boolean animated,
            boolean transmissive,
            boolean thinWalled,
            boolean water,
            boolean foliage,
            int lightEmission,
            TextureAtlasSprite sprite) {
        int firstIndex = indices[0];
        int secondIndex = indices[1];
        int thirdIndex = indices[2];
        float firstX = quad.x[firstIndex];
        float firstY = quad.y[firstIndex];
        float firstZ = quad.z[firstIndex];
        float secondX = quad.x[secondIndex];
        float secondY = quad.y[secondIndex];
        float secondZ = quad.z[secondIndex];
        float thirdX = quad.x[thirdIndex];
        float thirdY = quad.y[thirdIndex];
        float thirdZ = quad.z[thirdIndex];
        destination.positions.add(firstX);
        destination.positions.add(firstY);
        destination.positions.add(firstZ);
        destination.positions.add(secondX);
        destination.positions.add(secondY);
        destination.positions.add(secondZ);
        destination.positions.add(thirdX);
        destination.positions.add(thirdY);
        destination.positions.add(thirdZ);

        float uv0U = quad.u[firstIndex];
        float uv0V = quad.v[firstIndex];
        float uv1U = quad.u[secondIndex];
        float uv1V = quad.v[secondIndex];
        float uv2U = quad.u[thirdIndex];
        float uv2V = quad.v[thirdIndex];
        int packedUv0 = PrimitivePacking.packHalf2(uv0U, uv0V);
        int packedUv1 = PrimitivePacking.packHalf2(uv1U, uv1V);
        int packedUv2 = PrimitivePacking.packHalf2(uv2U, uv2V);
        int packedTint = PrimitivePacking.packTint(tint);
        destination.primitives.add(packedUv0);
        destination.primitives.add(packedUv1);
        destination.primitives.add(packedUv2);
        destination.primitives.add(packedTint);

        float edge1X = secondX - firstX;
        float edge1Y = secondY - firstY;
        float edge1Z = secondZ - firstZ;
        float edge2X = thirdX - firstX;
        float edge2Y = thirdY - firstY;
        float edge2Z = thirdZ - firstZ;
        int packedUvDensity = PrimitivePacking.packUvDensity(
                edge1X,
                edge1Y,
                edge1Z,
                edge2X,
                edge2Y,
                edge2Z,
                uv1U - uv0U,
                uv1V - uv0V,
                uv2U - uv0U,
                uv2V - uv0V);
        destination.primitives.add(PrimitivePacking.packTriangleNormal(
                edge1X,
                edge1Y,
                edge1Z,
                edge2X,
                edge2Y,
                edge2Z,
                quad.normalX,
                quad.normalY,
                quad.normalZ));
        destination.primitives.add(PrimitivePacking.packFlags(
                cutout, animated, transmissive, thinWalled, water, foliage));
        destination.primitives.add(lights.addTriangle(
                firstX,
                firstY,
                firstZ,
                secondX,
                secondY,
                secondZ,
                thirdX,
                thirdY,
                thirdZ,
                packedUv0,
                packedUv1,
                packedUv2,
                tint,
                packedTint,
                cutout,
                lightEmission,
                sprite));
        destination.primitives.add(packedUvDensity);
        destination.triangleCount++;
    }

    private static final class QuadCapture implements BlockQuadOutput {
        private final MeshBuilder opaque;
        private final MeshBuilder nonOpaque;
        private final CpuSectionLights.Builder lights;
        private final TintSnapshot tints;
        private final CapturedQuad captured = new CapturedQuad();
        private int localX;
        private int localY;
        private int localZ;
        private int blockLightEmission;
        private boolean thinWalled;
        private boolean foliage;

        private QuadCapture(
                MeshBuilder opaque,
                MeshBuilder nonOpaque,
                CpuSectionLights.Builder lights,
                TintSnapshot tints) {
            this.opaque = opaque;
            this.nonOpaque = nonOpaque;
            this.lights = lights;
            this.tints = tints;
        }

        private void setBlock(
                int x,
                int y,
                int z,
                int lightEmission,
                boolean thinWalled,
                boolean foliage) {
            this.localX = x;
            this.localY = y;
            this.localZ = z;
            this.blockLightEmission = lightEmission;
            this.thinWalled = thinWalled;
            this.foliage = foliage;
        }

        @Override
        public void put(float x, float y, float z, BakedQuad quad, QuadInstance instance) {
            ChunkSectionLayer layer = quad.materialInfo().layer();
            // Leaves can become SOLID under vanilla's fast-leaves setting, but Prime's foliage
            // material always needs alpha-tested coverage before its thin-wall BSDF is evaluated.
            boolean foliage = this.foliage;
            boolean cutout = layer == ChunkSectionLayer.CUTOUT || foliage;
            boolean transmissive = layer == ChunkSectionLayer.TRANSLUCENT;
            // A pane has a narrow but real collision volume, so it must enter and later leave the
            // medium stack. Reserve the thin-wall closure for truly zero-volume model geometry.
            boolean thinWalled = transmissive && this.thinWalled;
            MeshBuilder destination = layer == ChunkSectionLayer.SOLID && !foliage
                    ? this.opaque
                    : this.nonOpaque;
            int tint = quad.materialInfo().tintIndex() < 0
                    ? -1
                    : this.tints.color(this.localX, this.localY, this.localZ, quad.materialInfo().tintIndex());
            TextureAtlasSprite sprite = quad.materialInfo().sprite();
            Direction direction = quad.direction();
            this.captured.normalX = direction.getStepX();
            this.captured.normalY = direction.getStepY();
            this.captured.normalZ = direction.getStepZ();
            for (int index = 0; index < 4; index++) {
                Vector3fc position = quad.position(index);
                this.captured.x[index] = position.x() + x;
                this.captured.y[index] = position.y() + y;
                this.captured.z[index] = position.z() + z;
                long packedUv = quad.packedUV(index);
                this.captured.u[index] = UVPair.unpackU(packedUv);
                this.captured.v[index] = UVPair.unpackV(packedUv);
            }
            int lightEmission = Math.max(this.blockLightEmission, quad.materialInfo().lightEmission());
            boolean animated = sprite.contents().isAnimated();
            emitTriangle(
                    destination,
                    this.lights,
                    this.captured,
                    FIRST_TRIANGLE,
                    tint,
                    cutout,
                    animated,
                    transmissive,
                    thinWalled || foliage,
                    false,
                    foliage,
                    lightEmission,
                    sprite);
            emitTriangle(
                    destination,
                    this.lights,
                    this.captured,
                    SECOND_TRIANGLE,
                    tint,
                    cutout,
                    animated,
                    transmissive,
                    thinWalled || foliage,
                    false,
                    foliage,
                    lightEmission,
                    sprite);
        }
    }

    /**
     * Captures vanilla fluid quads while removing raster-only duplicate back faces.
     *
     * <p>A path-traced triangle is intrinsically two-sided. Keeping the reverse-wound copy emitted
     * for raster culling would create two coincident dielectric boundaries and corrupt the per-path
     * volume stack. Faces against full collision shapes are also discarded: glass and ice opt out
     * of raster occlusion, but their real full-block boundary must replace the adjacent water face
     * rather than leave a fictitious water-to-air interface at the same location.
     */
    private static final class FluidCapture implements VertexConsumer, FluidRenderer.Output {
        private final MeshBuilder opaque;
        private final MeshBuilder nonOpaque;
        private final CpuSectionLights.Builder lights;
        private final RenderSectionRegion region;
        private final CapturedQuad captured = new CapturedQuad();
        private final MutableBlockPos neighborPosition = new MutableBlockPos();
        private int vertexCount;
        private int localX;
        private int localY;
        private int localZ;
        private int worldX;
        private int worldY;
        private int worldZ;
        private int tint;
        private int lightEmission;
        private boolean transmissive;
        private boolean water;
        private boolean fullCeiling;
        private TextureAtlasSprite stillSprite;
        private TextureAtlasSprite flowingSprite;
        private TextureAtlasSprite overlaySprite;

        private FluidCapture(
                MeshBuilder opaque,
                MeshBuilder nonOpaque,
                CpuSectionLights.Builder lights,
                RenderSectionRegion region) {
            this.opaque = opaque;
            this.nonOpaque = nonOpaque;
            this.lights = lights;
            this.region = region;
        }

        private void setBlock(
                int localX,
                int localY,
                int localZ,
                BlockPos position,
                BlockState blockState,
                FluidState fluidState,
                FluidModel model) {
            this.vertexCount = 0;
            this.localX = localX;
            this.localY = localY;
            this.localZ = localZ;
            this.worldX = position.getX();
            this.worldY = position.getY();
            this.worldZ = position.getZ();
            this.water = fluidState.is(FluidTags.WATER);
            this.transmissive = !fluidState.is(FluidTags.LAVA);
            // FluidRenderer's emitted vertex color already contains vanilla's cardinal face
            // shading. Prime must retain only the semantic biome/material tint so the BSDF, not a
            // raster-era directional multiplier, owns illumination.
            this.tint = model.tintSource() == null
                    ? -1
                    : model.tintSource().colorInWorld(blockState, this.region, position);
            this.lightEmission = blockState.getLightEmission();
            this.stillSprite = model.stillMaterial().sprite();
            this.flowingSprite = model.flowingMaterial().sprite();
            this.overlaySprite = model.overlayMaterial() == null
                    ? null
                    : model.overlayMaterial().sprite();
            this.neighborPosition.set(this.worldX, this.worldY + 1, this.worldZ);
            this.fullCeiling = this.region.getBlockState(this.neighborPosition)
                    .isCollisionShapeFullBlock(this.region, this.neighborPosition);
        }

        @Override
        public VertexConsumer getBuilder(ChunkSectionLayer layer) {
            return this;
        }

        @Override
        public void addVertex(
                float x,
                float y,
                float z,
                int color,
                float u,
                float v,
                int overlay,
                int light,
                float normalX,
                float normalY,
                float normalZ) {
            int index = this.vertexCount;
            this.captured.x[index] = x;
            // Vanilla leaves a covered source at 8/9 height because its raster top is invisible.
            // A real dielectric volume must meet a full ceiling without an artificial air slit.
            this.captured.y[index] = this.fullCeiling && y > this.localY + 0.5F
                    ? this.localY + 1.0F
                    : y;
            this.captured.z[index] = z;
            this.captured.u[index] = u;
            this.captured.v[index] = v;
            this.vertexCount++;
            if (this.vertexCount == 4) {
                this.emitQuad();
                this.vertexCount = 0;
            }
        }

        private void emitQuad() {
            float edgeOneX = this.captured.x[1] - this.captured.x[0];
            float edgeOneY = this.captured.y[1] - this.captured.y[0];
            float edgeOneZ = this.captured.z[1] - this.captured.z[0];
            float edgeTwoX = this.captured.x[2] - this.captured.x[0];
            float edgeTwoY = this.captured.y[2] - this.captured.y[0];
            float edgeTwoZ = this.captured.z[2] - this.captured.z[0];
            float normalX = edgeOneY * edgeTwoZ - edgeOneZ * edgeTwoY;
            float normalY = edgeOneZ * edgeTwoX - edgeOneX * edgeTwoZ;
            float normalZ = edgeOneX * edgeTwoY - edgeOneY * edgeTwoX;
            float centerX = 0.25F * (this.captured.x[0]
                    + this.captured.x[1] + this.captured.x[2] + this.captured.x[3]);
            float centerY = 0.25F * (this.captured.y[0]
                    + this.captured.y[1] + this.captured.y[2] + this.captured.y[3]);
            float centerZ = 0.25F * (this.captured.z[0]
                    + this.captured.z[1] + this.captured.z[2] + this.captured.z[3]);
            float outward = normalX * (centerX - this.localX - 0.5F)
                    + normalY * (centerY - this.localY - 0.5F)
                    + normalZ * (centerZ - this.localZ - 0.5F);
            if (!(outward > 1.0e-7F)) {
                return;
            }

            float inverseNormalLength = 1.0F / (float) Math.sqrt(
                    normalX * normalX + normalY * normalY + normalZ * normalZ);
            this.captured.normalX = normalX * inverseNormalLength;
            this.captured.normalY = normalY * inverseNormalLength;
            this.captured.normalZ = normalZ * inverseNormalLength;

            Direction face = Direction.getApproximateNearest(normalX, normalY, normalZ);
            this.neighborPosition.set(
                    this.worldX + face.getStepX(),
                    this.worldY + face.getStepY(),
                    this.worldZ + face.getStepZ());
            if (this.region.getBlockState(this.neighborPosition)
                    .isCollisionShapeFullBlock(this.region, this.neighborPosition)) {
                return;
            }

            TextureAtlasSprite sprite = this.selectSprite();
            boolean animated = this.stillSprite.contents().isAnimated()
                    || this.flowingSprite.contents().isAnimated();
            MeshBuilder destination = this.transmissive ? this.nonOpaque : this.opaque;
            emitTriangle(
                    destination,
                    this.lights,
                    this.captured,
                    FIRST_TRIANGLE,
                    this.tint,
                    false,
                    animated,
                    this.transmissive,
                    false,
                    this.water,
                    false,
                    this.lightEmission,
                    sprite);
            emitTriangle(
                    destination,
                    this.lights,
                    this.captured,
                    SECOND_TRIANGLE,
                    this.tint,
                    false,
                    animated,
                    this.transmissive,
                    false,
                    this.water,
                    false,
                    this.lightEmission,
                    sprite);
        }

        private TextureAtlasSprite selectSprite() {
            float u = 0.25F * (this.captured.u[0]
                    + this.captured.u[1] + this.captured.u[2] + this.captured.u[3]);
            float v = 0.25F * (this.captured.v[0]
                    + this.captured.v[1] + this.captured.v[2] + this.captured.v[3]);
            if (contains(this.stillSprite, u, v)) {
                return this.stillSprite;
            }
            if (this.overlaySprite != null && contains(this.overlaySprite, u, v)) {
                return this.overlaySprite;
            }
            return this.flowingSprite;
        }

        private static boolean contains(TextureAtlasSprite sprite, float u, float v) {
            return u >= Math.min(sprite.getU0(), sprite.getU1())
                    && u <= Math.max(sprite.getU0(), sprite.getU1())
                    && v >= Math.min(sprite.getV0(), sprite.getV1())
                    && v <= Math.max(sprite.getV0(), sprite.getV1());
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    }

    private static final class CapturedQuad {
        private final float[] x = new float[4];
        private final float[] y = new float[4];
        private final float[] z = new float[4];
        private final float[] u = new float[4];
        private final float[] v = new float[4];
        private float normalX;
        private float normalY;
        private float normalZ;
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
