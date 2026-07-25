package dev.prime.render.scene.vanilla;

import com.mojang.blaze3d.vertex.MeshData;
import dev.prime.render.terrain.CpuSectionGeometry;
import dev.prime.render.terrain.LabPbrMaterialSet;
import dev.prime.render.terrain.SectionMeshAccumulator;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadAtlas;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder;
import net.fabricmc.fabric.api.client.rendering.v1.BlockColorRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.BlockTintsFactory;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.block.dispatch.WeightedVariants;
import net.minecraft.client.renderer.block.dispatch.multipart.MultiPartModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.joml.Vector3fc;

/**
 * Thread-confined side channel for one invocation of Minecraft's {@link SectionCompiler}.
 *
 * <p>Minecraft's mesh and vertex interfaces remain untouched. Small Mixins observe only geometry
 * that vanilla has already accepted and forward it here while the owning compile scope is active.
 * The inactive path is a single {@link ThreadLocal#get()} and does not allocate.
 */
public final class VanillaSectionCapture implements AutoCloseable {
    private static final ThreadLocal<VanillaSectionCapture> ACTIVE = new ThreadLocal<>();
    private static final int UNCACHED_TINT = Integer.MIN_VALUE;
    private static final float COPLANAR_OVERLAY_OFFSET = 1.0F / 4096.0F;

    private final RenderSectionRegion region;
    private final BlockStateModelSet blockModels;
    private final BlockColors blockColors;
    private final SpriteFinder blockSpriteFinder;
    private final boolean cutoutLeaves;
    private final VanillaGeometryPolicy geometryPolicy;
    private final SectionMeshAccumulator mesh;
    private final SectionMeshAccumulator.Quad blockQuad = new SectionMeshAccumulator.Quad();
    private final SectionMeshAccumulator.Surface blockSurface = new SectionMeshAccumulator.Surface();
    private final ArrayDeque<FluidCapture> fluidStack = new ArrayDeque<>();
    private long tintPosition = Long.MIN_VALUE;
    private int tintIndex = -1;
    private int tintValue = -1;
    private long blockPosition = Long.MIN_VALUE;
    private BlockState blockState;
    private boolean blockForceOpaque;
    private boolean blockFoliage;
    private boolean blockMergeable;
    private boolean blockCollisionKnown;
    private boolean blockCollisionEmpty;
    private final int[] fabricBaseColors = new int[4];
    private int[] fabricTintCache = new int[4];
    private final IntArrayList fabricDynamicTints = new IntArrayList();
    private BlockAndTintGetter fabricLevel;
    private BlockPos fabricPosition;
    private BlockState fabricState;
    private boolean fabricMergeable;
    private List<BlockTintSource> fabricTintSources = List.of();
    private BlockTintsFactory fabricTintFactory;
    private boolean fabricDynamicTintsLoaded;
    private boolean fabricQuadPending;
    private int sourceQuadCount;
    private boolean finished;

    private VanillaSectionCapture(
            RenderSectionRegion region,
            BlockStateModelSet blockModels,
            BlockColors blockColors,
            SpriteFinder blockSpriteFinder,
            LabPbrMaterialSet labPbrMaterials,
            VanillaGeometryPolicy geometryPolicy,
            boolean cutoutLeaves,
            boolean buildOpacityMicromap,
            int segmentTriangleTarget) {
        this.region = region;
        this.blockModels = blockModels;
        this.blockColors = blockColors;
        this.blockSpriteFinder = blockSpriteFinder;
        this.geometryPolicy = geometryPolicy;
        this.cutoutLeaves = cutoutLeaves;
        this.mesh = new SectionMeshAccumulator(
                labPbrMaterials, buildOpacityMicromap, segmentTriangleTarget);
    }

    public static VanillaSectionCapture open(
            RenderSectionRegion region,
            BlockStateModelSet blockModels,
            BlockColors blockColors,
            SpriteFinder blockSpriteFinder,
            LabPbrMaterialSet labPbrMaterials,
            VanillaGeometryPolicy geometryPolicy,
            boolean cutoutLeaves,
            boolean buildOpacityMicromap,
            int segmentTriangleTarget) {
        if (ACTIVE.get() != null) {
            throw new IllegalStateException("Nested vanilla Section capture is not supported");
        }
        VanillaSectionCapture capture = new VanillaSectionCapture(
                region,
                blockModels,
                blockColors,
                blockSpriteFinder,
                labPbrMaterials,
                geometryPolicy,
                cutoutLeaves,
                buildOpacityMicromap,
                segmentTriangleTarget);
        ACTIVE.set(capture);
        return capture;
    }

    public static void recordBlockQuad(
            float x,
            float y,
            float z,
            BlockAndTintGetter level,
            BlockState state,
            BlockPos position,
            BakedQuad bakedQuad) {
        VanillaSectionCapture capture = ACTIVE.get();
        if (capture != null) {
            capture.addBlockQuad(x, y, z, level, state, position, bakedQuad);
        }
    }

    public static void recordBlockTint(BlockPos position, int tintIndex, int tint) {
        VanillaSectionCapture capture = ACTIVE.get();
        if (capture != null) {
            capture.tintPosition = position.asLong();
            capture.tintIndex = tintIndex;
            capture.tintValue = tint;
        }
    }

    public static void beginFabricBlock(
            BlockAndTintGetter level,
            BlockPos position,
            BlockState state,
            BlockStateModel model) {
        VanillaSectionCapture capture = ACTIVE.get();
        if (capture != null) {
            capture.openFabricBlock(level, position, state, model);
        }
    }

    public static void endFabricBlock() {
        VanillaSectionCapture capture = ACTIVE.get();
        if (capture != null) {
            capture.closeFabricBlock();
        }
    }

    public static void beginFabricQuad(MutableQuadView quad) {
        VanillaSectionCapture capture = ACTIVE.get();
        if (capture != null && capture.fabricLevel != null) {
            capture.openFabricQuad(quad);
        }
    }

    public static void finishFabricQuad(MutableQuadView quad, boolean accepted) {
        VanillaSectionCapture capture = ACTIVE.get();
        if (capture != null && capture.fabricLevel != null) {
            capture.closeFabricQuad(quad, accepted);
        }
    }

    public static void beginFluid(
            BlockAndTintGetter level,
            BlockPos position,
            BlockState blockState,
            FluidState fluidState,
            FluidModel model) {
        VanillaSectionCapture capture = ACTIVE.get();
        if (capture != null) {
            // Fabric's default FluidRenderHandler deliberately calls FluidRenderer.tesselate a
            // second time under a ScopedValue guard. Keep both scopes: the outer scope covers
            // vertices emitted directly by a custom handler, while the inner scope owns vanilla's
            // vertices. A stack preserves the exact material context without depending on Fabric
            // internals or mistaking this supported delegation for recursive corruption.
            capture.fluidStack.push(
                    new FluidCapture(capture, level, position, blockState, fluidState, model));
        }
    }

    public static void recordFluidVertex(
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
        VanillaSectionCapture capture = ACTIVE.get();
        if (capture != null && !capture.fluidStack.isEmpty()) {
            capture.fluidStack.peek().addVertex(x, y, z, u, v);
        }
    }

    public static void endFluid() {
        VanillaSectionCapture capture = ACTIVE.get();
        if (capture != null) {
            if (capture.fluidStack.isEmpty()) {
                throw new IllegalStateException("Fluid capture scope was not opened");
            }
            capture.fluidStack.pop().finish();
        }
    }

    public CpuSectionGeometry finish(SectionCompiler.Results results) {
        if (this.finished) {
            throw new IllegalStateException("Vanilla Section capture was already finished");
        }
        if (!this.fluidStack.isEmpty()) {
            throw new IllegalStateException("Fluid tessellation did not leave its capture scope");
        }
        if (this.fabricLevel != null || this.fabricQuadPending) {
            throw new IllegalStateException("Fabric tessellation did not leave its capture scope");
        }
        int vanillaQuadCount = 0;
        for (MeshData layer : results.renderedLayers.values()) {
            int vertexCount = layer.drawState().vertexCount();
            if ((vertexCount & 3) != 0) {
                throw new IllegalStateException("Vanilla Section layer is not quad aligned");
            }
            vanillaQuadCount += vertexCount / 4;
        }
        if (vanillaQuadCount != this.sourceQuadCount) {
            throw new IllegalStateException(
                    "Prime captured " + this.sourceQuadCount
                            + " of " + vanillaQuadCount + " vanilla Section quads");
        }
        this.finished = true;
        return this.mesh.build();
    }

    private void addBlockQuad(
            float x,
            float y,
            float z,
            BlockAndTintGetter level,
            BlockState state,
            BlockPos position,
            BakedQuad bakedQuad) {
        if (level != this.region) {
            throw new IllegalStateException("Captured block quad belongs to a different region");
        }
        this.sourceQuadCount++;
        long packedPosition = position.asLong();
        if (this.blockPosition != packedPosition || this.blockState != state) {
            this.blockPosition = packedPosition;
            this.blockState = state;
            this.blockForceOpaque = ModelBlockRenderer.forceOpaque(this.cutoutLeaves, state);
            this.blockFoliage = !this.blockForceOpaque
                    && (state.is(BlockTags.LEAVES)
                            || state.getBlock() == Blocks.SHORT_GRASS
                            || state.getBlock() == Blocks.TALL_GRASS);
            // The selected quad, including random orientation, is compared exactly downstream.
            // Admit every built-in model shape but keep arbitrary renderer models conservative.
            this.blockMergeable = mergeableModel(this.blockModels.get(state));
            this.blockCollisionKnown = false;
        }
        ChunkSectionLayer layer = this.blockForceOpaque
                ? ChunkSectionLayer.SOLID
                : bakedQuad.materialInfo().layer();
        boolean foliage = this.blockFoliage;
        SurfaceLayer surfaceLayer = classifySurfaceLayer(
                layer, foliage, requiresAlphaCut(state));
        boolean cutout = surfaceLayer.cutout();
        boolean transmissive = surfaceLayer.transmissive();
        if (transmissive && !this.blockCollisionKnown) {
            this.blockCollisionEmpty = state.getCollisionShape(this.region, position).isEmpty();
            this.blockCollisionKnown = true;
        }
        boolean thinWalled = transmissive && this.blockCollisionEmpty;
        int requestedTint = bakedQuad.materialInfo().tintIndex();
        int tint = -1;
        if (requestedTint >= 0) {
            if (this.tintPosition != position.asLong() || this.tintIndex != requestedTint) {
                throw new IllegalStateException("Vanilla block tint was not captured before its quad");
            }
            tint = this.tintValue;
        }
        TextureAtlasSprite sprite = bakedQuad.materialInfo().sprite();
        SectionMeshAccumulator.Quad quad = this.blockQuad;
        Direction direction = bakedQuad.direction();
        quad.normalX = direction.getStepX();
        quad.normalY = direction.getStepY();
        quad.normalZ = direction.getStepZ();
        for (int index = 0; index < 4; index++) {
            Vector3fc vertex = bakedQuad.position(index);
            quad.x[index] = vertex.x() + x;
            quad.y[index] = vertex.y() + y;
            quad.z[index] = vertex.z() + z;
            long packedUv = bakedQuad.packedUV(index);
            quad.u[index] = net.minecraft.client.model.geom.builders.UVPair.unpackU(packedUv);
            quad.v[index] = net.minecraft.client.model.geom.builders.UVPair.unpackV(packedUv);
        }
        offsetRasterOverlay(
                quad,
                isRasterOverlay(
                        state.getBlock() == Blocks.GRASS_BLOCK,
                        state.getBlock() == Blocks.REDSTONE_WIRE,
                        requestedTint,
                        quad.normalY));
        this.mesh.addQuad(quad, this.blockSurface.set(
                tint,
                cutout,
                sprite.contents().isAnimated(),
                transmissive,
                thinWalled || foliage,
                false,
                foliage,
                this.blockMergeable,
                Math.max(state.getLightEmission(), bakedQuad.materialInfo().lightEmission()),
                sprite));
    }

    private void openFabricBlock(
            BlockAndTintGetter level,
            BlockPos position,
            BlockState state,
            BlockStateModel model) {
        if (level != this.region) {
            throw new IllegalStateException("Captured Fabric block belongs to a different region");
        }
        if (this.fabricLevel != null || this.fabricQuadPending) {
            throw new IllegalStateException("Nested Fabric block tessellation during Section capture");
        }
        this.fabricLevel = level;
        this.fabricPosition = position;
        this.fabricState = state;
        // Indigo also routes ordinary vanilla models through this path. Preserve the same
        // built-in-model gate as direct capture instead of treating every Fabric-rendered quad as
        // custom geometry.
        this.fabricMergeable = mergeableModel(model);
        this.fabricTintSources = this.blockColors.getTintSources(state);
        this.fabricTintFactory = this.fabricTintSources.isEmpty()
                ? BlockColorRegistry.getFactory(state)
                : null;
        this.fabricDynamicTints.clear();
        this.fabricDynamicTintsLoaded = false;
        Arrays.fill(this.fabricTintCache, UNCACHED_TINT);
    }

    private void closeFabricBlock() {
        if (this.fabricLevel == null) {
            throw new IllegalStateException("Fabric block capture scope was not opened");
        }
        if (this.fabricQuadPending) {
            throw new IllegalStateException("Fabric block capture ended with an unfinished quad");
        }
        this.fabricLevel = null;
        this.fabricPosition = null;
        this.fabricState = null;
        this.fabricMergeable = false;
        this.fabricTintSources = List.of();
        this.fabricTintFactory = null;
        this.fabricDynamicTints.clear();
        this.fabricDynamicTintsLoaded = false;
    }

    private void openFabricQuad(MutableQuadView quad) {
        if (this.fabricQuadPending) {
            throw new IllegalStateException("Nested Fabric quad transformation during Section capture");
        }
        for (int index = 0; index < 4; index++) {
            this.fabricBaseColors[index] = quad.color(index);
        }
        this.fabricQuadPending = true;
    }

    private void closeFabricQuad(MutableQuadView source, boolean accepted) {
        if (!this.fabricQuadPending) {
            throw new IllegalStateException("Fabric quad capture was not opened");
        }
        this.fabricQuadPending = false;
        if (!accepted) {
            return;
        }
        if (source.atlas() != QuadAtlas.BLOCK) {
            throw new IllegalStateException("A world block emitted geometry from a non-block atlas");
        }

        this.sourceQuadCount++;
        BlockState state = this.fabricState;
        BlockPos position = this.fabricPosition;
        boolean forceOpaque = ModelBlockRenderer.forceOpaque(this.cutoutLeaves, state);
        boolean foliage = !forceOpaque
                && (state.is(BlockTags.LEAVES)
                        || state.getBlock() == Blocks.SHORT_GRASS
                        || state.getBlock() == Blocks.TALL_GRASS);
        ChunkSectionLayer layer = forceOpaque ? ChunkSectionLayer.SOLID : source.chunkLayer();
        SurfaceLayer surfaceLayer = classifySurfaceLayer(
                layer, foliage, requiresAlphaCut(state));
        boolean cutout = surfaceLayer.cutout();
        boolean transmissive = surfaceLayer.transmissive();
        boolean thinWalled = transmissive
                && state.getCollisionShape(this.region, position).isEmpty();
        int tint = this.averageFabricColor(source.tintIndex());
        TextureAtlasSprite sprite = this.blockSpriteFinder.find(source);

        SectionMeshAccumulator.Quad quad = this.blockQuad;
        Vector3fc faceNormal = source.faceNormal();
        quad.normalX = faceNormal.x();
        quad.normalY = faceNormal.y();
        quad.normalZ = faceNormal.z();
        for (int index = 0; index < 4; index++) {
            quad.x[index] = source.x(index);
            quad.y[index] = source.y(index);
            quad.z[index] = source.z(index);
            quad.u[index] = source.u(index);
            quad.v[index] = source.v(index);
        }
        offsetRasterOverlay(
                quad,
                isRasterOverlay(
                        state.getBlock() == Blocks.GRASS_BLOCK,
                        state.getBlock() == Blocks.REDSTONE_WIRE,
                        source.tintIndex(),
                        quad.normalY));
        this.mesh.addQuad(quad, this.blockSurface.set(
                tint,
                cutout,
                source.animated() || sprite.contents().isAnimated(),
                transmissive,
                thinWalled || foliage,
                false,
                foliage,
                this.fabricMergeable,
                Math.max(state.getLightEmission(), source.emissive() ? 15 : 0),
                sprite));
    }

    static void offsetRasterOverlay(
            SectionMeshAccumulator.Quad quad, boolean rasterOverlay) {
        if (!rasterOverlay) {
            return;
        }
        // Vulkan traversal has no raster draw order for coincident faces. Keep Minecraft's
        // compositing layer just outside the base; Section-local coordinates make this offset
        // representable without a visible silhouette change.
        for (int index = 0; index < 4; index++) {
            quad.x[index] += quad.normalX * COPLANAR_OVERLAY_OFFSET;
            quad.y[index] += quad.normalY * COPLANAR_OVERLAY_OFFSET;
            quad.z[index] += quad.normalZ * COPLANAR_OVERLAY_OFFSET;
        }
    }

    static boolean isRasterOverlay(
            boolean grassBlock, boolean redstoneWire, int tintIndex, float normalY) {
        return (grassBlock && tintIndex >= 0 && Math.abs(normalY) < 0.5F)
                || (redstoneWire && tintIndex < 0);
    }

    static SurfaceLayer classifySurfaceLayer(
            ChunkSectionLayer layer, boolean foliage, boolean alphaCutOverride) {
        // Minecraft's force_translucent may request alpha blending without describing a
        // dielectric medium. Known binary-coverage models translate that raster hint to cutout.
        return new SurfaceLayer(
                layer == ChunkSectionLayer.CUTOUT || foliage || alphaCutOverride,
                layer == ChunkSectionLayer.TRANSLUCENT && !alphaCutOverride);
    }

    static boolean requiresAlphaCut(BlockState state) {
        // Some packs replace these models without carrying Minecraft's raster layer metadata.
        // Their geometry still relies on binary texture coverage: treating the head planes as
        // solid turns transparent texels into an opaque box and also expands the emitter support.
        return state.getBlock() == Blocks.REDSTONE_WIRE
                || state.getBlock() == Blocks.REDSTONE_TORCH
                || state.getBlock() == Blocks.REDSTONE_WALL_TORCH;
    }

    record SurfaceLayer(boolean cutout, boolean transmissive) {
    }

    private int averageFabricColor(int tintIndex) {
        int tint = tintIndex < 0 ? -1 : this.resolveFabricTint(tintIndex);
        int alpha = 0;
        int red = 0;
        int green = 0;
        int blue = 0;
        for (int index = 0; index < 4; index++) {
            int color = tintIndex < 0
                    ? this.fabricBaseColors[index]
                    : ARGB.multiply(this.fabricBaseColors[index], tint);
            alpha += color >>> 24;
            red += color >>> 16 & 0xff;
            green += color >>> 8 & 0xff;
            blue += color & 0xff;
        }
        return (alpha + 2) / 4 << 24
                | (red + 2) / 4 << 16
                | (green + 2) / 4 << 8
                | (blue + 2) / 4;
    }

    private int resolveFabricTint(int tintIndex) {
        if (tintIndex >= this.fabricTintCache.length) {
            int oldLength = this.fabricTintCache.length;
            this.fabricTintCache = Arrays.copyOf(
                    this.fabricTintCache,
                    Math.max(tintIndex + 1, oldLength * 2));
            Arrays.fill(this.fabricTintCache, oldLength, this.fabricTintCache.length, UNCACHED_TINT);
        }
        int cached = this.fabricTintCache[tintIndex];
        if (cached != UNCACHED_TINT) {
            return cached;
        }
        int value = -1;
        if (tintIndex < this.fabricTintSources.size()) {
            value = this.fabricTintSources.get(tintIndex)
                    .colorInWorld(this.fabricState, this.fabricLevel, this.fabricPosition);
        } else if (this.fabricTintSources.isEmpty() && this.fabricTintFactory != null) {
            if (!this.fabricDynamicTintsLoaded) {
                this.fabricTintFactory.collect(
                        this.fabricState,
                        this.fabricLevel,
                        this.fabricPosition,
                        this.fabricDynamicTints);
                this.fabricDynamicTintsLoaded = true;
            }
            if (tintIndex < this.fabricDynamicTints.size()) {
                value = this.fabricDynamicTints.getInt(tintIndex);
            }
        }
        this.fabricTintCache[tintIndex] = value;
        return value;
    }

    static boolean mergeableModel(BlockStateModel model) {
        return model instanceof SingleVariant
                || model instanceof WeightedVariants
                || model instanceof MultiPartModel;
    }

    @Override
    public void close() {
        if (ACTIVE.get() != this) {
            throw new IllegalStateException("Vanilla Section capture closed on the wrong thread");
        }
        ACTIVE.remove();
        this.fluidStack.clear();
        this.fabricLevel = null;
        this.fabricPosition = null;
        this.fabricState = null;
        this.fabricMergeable = false;
        this.fabricQuadPending = false;
    }

    /**
     * Collects the exact vertices emitted by FluidRenderer, then removes raster-only reverse faces.
     * A Vulkan ray-tracing triangle is already two-sided; retaining both windings would create two
     * coincident dielectric interfaces and corrupt the volume stack. This is a representation
     * translation, not an alteration of Minecraft's mesh contract.
     */
    private static final class FluidCapture {
        private final VanillaSectionCapture owner;
        private final BlockAndTintGetter level;
        private final int localX;
        private final int localY;
        private final int localZ;
        private final int worldX;
        private final int worldY;
        private final int worldZ;
        private final int tint;
        private final int lightEmission;
        private final boolean transmissive;
        private final boolean water;
        private final boolean fullCeiling;
        private final TextureAtlasSprite stillSprite;
        private final TextureAtlasSprite flowingSprite;
        private final TextureAtlasSprite overlaySprite;
        private final SectionMeshAccumulator.Quad quad = new SectionMeshAccumulator.Quad();
        private final SectionMeshAccumulator.Surface surface = new SectionMeshAccumulator.Surface();
        private final BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
        private int vertexCount;

        private FluidCapture(
                VanillaSectionCapture owner,
                BlockAndTintGetter level,
                BlockPos position,
                BlockState blockState,
                FluidState fluidState,
                FluidModel model) {
            if (level != owner.region) {
                throw new IllegalStateException("Captured fluid belongs to a different region");
            }
            this.owner = owner;
            this.level = level;
            this.localX = SectionPos.sectionRelative(position.getX());
            this.localY = SectionPos.sectionRelative(position.getY());
            this.localZ = SectionPos.sectionRelative(position.getZ());
            this.worldX = position.getX();
            this.worldY = position.getY();
            this.worldZ = position.getZ();
            this.water = fluidState.is(FluidTags.WATER);
            this.transmissive = !fluidState.is(FluidTags.LAVA);
            this.tint = model.tintSource() == null
                    ? -1
                    : model.tintSource().colorInWorld(blockState, level, position);
            this.lightEmission = blockState.getLightEmission();
            this.stillSprite = model.stillMaterial().sprite();
            this.flowingSprite = model.flowingMaterial().sprite();
            this.overlaySprite = model.overlayMaterial() == null
                    ? null
                    : model.overlayMaterial().sprite();
            this.neighbor.set(this.worldX, this.worldY + 1, this.worldZ);
            this.fullCeiling = level.getBlockState(this.neighbor)
                    .isCollisionShapeFullBlock(level, this.neighbor);
        }

        private void addVertex(float x, float y, float z, float u, float v) {
            int index = this.vertexCount;
            this.quad.x[index] = x;
            this.quad.y[index] = this.owner.geometryPolicy.closeCoveredFluidGap()
                            && this.fullCeiling
                            && y > this.localY + 0.5F
                    ? this.localY + 1.0F
                    : y;
            this.quad.z[index] = z;
            this.quad.u[index] = u;
            this.quad.v[index] = v;
            this.vertexCount++;
            if (this.vertexCount == 4) {
                this.owner.sourceQuadCount++;
                this.emitQuad();
                this.vertexCount = 0;
            }
        }

        private void finish() {
            if (this.vertexCount != 0) {
                throw new IllegalStateException("Fluid renderer emitted an incomplete quad");
            }
        }

        private void emitQuad() {
            float edgeOneX = this.quad.x[1] - this.quad.x[0];
            float edgeOneY = this.quad.y[1] - this.quad.y[0];
            float edgeOneZ = this.quad.z[1] - this.quad.z[0];
            float edgeTwoX = this.quad.x[2] - this.quad.x[0];
            float edgeTwoY = this.quad.y[2] - this.quad.y[0];
            float edgeTwoZ = this.quad.z[2] - this.quad.z[0];
            float normalX = edgeOneY * edgeTwoZ - edgeOneZ * edgeTwoY;
            float normalY = edgeOneZ * edgeTwoX - edgeOneX * edgeTwoZ;
            float normalZ = edgeOneX * edgeTwoY - edgeOneY * edgeTwoX;
            float centerX = 0.25F * (this.quad.x[0] + this.quad.x[1] + this.quad.x[2] + this.quad.x[3]);
            float centerY = 0.25F * (this.quad.y[0] + this.quad.y[1] + this.quad.y[2] + this.quad.y[3]);
            float centerZ = 0.25F * (this.quad.z[0] + this.quad.z[1] + this.quad.z[2] + this.quad.z[3]);
            float outward = normalX * (centerX - this.localX - 0.5F)
                    + normalY * (centerY - this.localY - 0.5F)
                    + normalZ * (centerZ - this.localZ - 0.5F);
            if (!(outward > 1.0e-7F)) {
                return;
            }

            float squaredNormalLength = normalX * normalX + normalY * normalY + normalZ * normalZ;
            if (!(squaredNormalLength > 1.0e-20F)) {
                return;
            }
            float inverseNormalLength = 1.0F / (float) Math.sqrt(squaredNormalLength);
            this.quad.normalX = normalX * inverseNormalLength;
            this.quad.normalY = normalY * inverseNormalLength;
            this.quad.normalZ = normalZ * inverseNormalLength;

            Direction face = Direction.getApproximateNearest(normalX, normalY, normalZ);
            this.neighbor.set(
                    this.worldX + face.getStepX(),
                    this.worldY + face.getStepY(),
                    this.worldZ + face.getStepZ());
            if (this.owner.geometryPolicy.suppressFluidFaceAgainstFullCollision()
                    && this.level.getBlockState(this.neighbor)
                            .isCollisionShapeFullBlock(this.level, this.neighbor)) {
                return;
            }

            TextureAtlasSprite sprite = this.selectSprite();
            boolean animated = this.stillSprite.contents().isAnimated()
                    || this.flowingSprite.contents().isAnimated()
                    || this.overlaySprite != null && this.overlaySprite.contents().isAnimated();
            this.owner.mesh.addQuad(this.quad, this.surface.set(
                    this.tint,
                    false,
                    animated,
                    this.transmissive,
                    false,
                    this.water,
                    false,
                    false,
                    this.lightEmission,
                    sprite));
        }

        private TextureAtlasSprite selectSprite() {
            float u = 0.25F * (this.quad.u[0] + this.quad.u[1] + this.quad.u[2] + this.quad.u[3]);
            float v = 0.25F * (this.quad.v[0] + this.quad.v[1] + this.quad.v[2] + this.quad.v[3]);
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
    }
}
