package dev.prime.render.terrain;

import dev.prime.PrimeClient;
import dev.prime.render.ResourceCleanup;
import dev.prime.render.scene.vanilla.VanillaGeometryPolicy;
import dev.prime.render.scene.vanilla.VanillaAssetSnapshot;
import dev.prime.render.scene.vanilla.VanillaSceneInterpreter;
import dev.prime.render.scene.vanilla.VanillaSectionCompileInput;
import dev.prime.render.scene.vanilla.VanillaSectionSnapshot;
import dev.prime.render.vulkan.StagingArena;
import dev.prime.render.vulkan.VulkanContext;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.SectionPos;
import net.minecraft.data.AtlasIds;
import net.minecraft.util.Util;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.FabricTextureAtlas;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder;

/**
 * Owns the terrain portion of Prime's scene independently from vanilla's raster renderer.
 *
 * <p>This is the sole authority for which virtual clusters Prime wants, when they become dirty,
 * which generation is current, and how long uploaded geometry remains resident. One aligned
 * 4x4x4 logical cluster replaces its 64 Sections atomically. Its ordinary geometry owns one BLAS;
 * optional experimental detail meshes add reusable BLASes and per-face TLAS instances. Its CPU
 * build input may use any number of storage segments. In
 * particular, this scheduler must never depend on {@code LevelRenderer.visibleSections()}, the
 * occlusion graph, or vanilla's raster compilation queue: those are presentation decisions and can
 * omit geometry that still contributes to ray-traced visibility and global illumination.
 *
 * <p>Independence ends at mesh semantics. Once this class has selected the stable 6x6x6 snapshot
 * neighborhood around a cluster, each of its 64 inner Sections is delegated to
 * {@link VanillaSceneInterpreter}. Prime does not maintain a second block/fluid mesher and does
 * not merge geometry captured from vanilla's raster tasks.
 */
public final class TerrainStreamer implements AutoCloseable {
    private static final long[] EMPTY_EVICTIONS = new long[0];
    // A normal frame targets one reusable staging page. An oversized atomic replacement obtains a
    // correspondingly sized transient page instead of being rejected by a content policy.
    private static final long TARGET_UPLOAD_BYTES_PER_FRAME = StagingArena.PAGE_SIZE;
    private static final int MAX_UNLOADED_PROBES_PER_FRAME = 64;
    private static final int MAX_EXTERNAL_DIRTY_SECTIONS = 16_384;
    private final TerrainScene scene;
    private final VanillaSceneInterpreter sceneInterpreter;
    private final boolean opacityMicromapSupported;
    private final int maxOpacityMicromapSubdivisionLevel;
    private final int segmentTriangleTarget;
    private final Executor workers;
    private final int maximumInFlight;
    // Workers publish one immutable result per accepted job. The render-thread-owned count is
    // never reset across worlds, so the fixed queue remains a proof-backed bound during churn.
    private final ArrayBlockingQueue<CompletedCluster> completed;
    private final BoundedDirtySections externalDirty =
            new BoundedDirtySections(MAX_EXTERNAL_DIRTY_SECTIONS);
    private final LongOpenHashSet desired = new LongOpenHashSet();
    private final LongOpenHashSet empty = new LongOpenHashSet();
    private final LongOpenHashSet pendingEvictions = new LongOpenHashSet();
    private final LongOpenHashSet dirtyClusters = new LongOpenHashSet();
    private final ClusterGenerationTracker generations = new ClusterGenerationTracker();
    private final ClusterPipelineState pipelineState = new ClusterPipelineState();
    private final PriorityQueue<ClusterRequest> requests = new PriorityQueue<>(Comparator
            .comparingInt(ClusterRequest::priority)
            .thenComparingLong(ClusterRequest::distanceSquared)
            .thenComparingLong(ClusterRequest::key));
    private final ArrayDeque<CompletedCluster> readyForUpload = new ArrayDeque<>();
    private final ArrayList<CompiledCluster> uploadBatch = new ArrayList<>();
    private final ArrayList<ClusterRequest> unloadedRequests =
            new ArrayList<>(MAX_UNLOADED_PROBES_PER_FRAME);
    private final ArrayList<ClusterRequest> blockedRequests =
            new ArrayList<>(MAX_UNLOADED_PROBES_PER_FRAME);

    private ClientLevel world;
    private int centerSectionX = Integer.MIN_VALUE;
    private int centerSectionY = Integer.MIN_VALUE;
    private int centerSectionZ = Integer.MIN_VALUE;
    private int renderDistance = -1;
    private int minimumSectionY;
    private int maximumSectionY;
    private LabPbrMaterialSet labPbrMaterials = LabPbrMaterialSet.EMPTY;
    private boolean voxelTextureSurfaces;
    private int workerJobs;

    public TerrainStreamer(VulkanContext context, StagingArena stagingArena) {
        this.scene = new TerrainScene(context, stagingArena);
        this.opacityMicromapSupported = context.capabilities().opacityMicromapSupported();
        this.maxOpacityMicromapSubdivisionLevel =
                context.capabilities().maxOpacityMicromapSubdivisionLevel();
        this.segmentTriangleTarget = TerrainMemoryBudget.segmentTriangleTarget(
                context.capabilities().maxAccelerationStructurePrimitiveCount());
        this.sceneInterpreter = new VanillaSceneInterpreter();
        // Match vanilla section compilation: use Minecraft's shared work-stealing pool and its
        // configured CPU limit instead of imposing a second, Prime-specific four-thread ceiling.
        this.workers = Util.backgroundExecutor();
        this.maximumInFlight = TerrainMemoryBudget.maximumInFlight(
                Math.max(1, Util.maxAllowedExecutorThreads()),
                Runtime.getRuntime().maxMemory());
        this.completed = new ArrayBlockingQueue<>(this.maximumInFlight);
    }

    public void update(Minecraft minecraft, double cameraX, double cameraY, double cameraZ) {
        ClientLevel currentWorld = minecraft.level;
        if (currentWorld == null || minecraft.player == null) {
            if (this.world != null) {
                this.clearWorld(cameraX, cameraY, cameraZ);
            }
            return;
        }
        if (this.world != currentWorld) {
            this.clearWorld(cameraX, cameraY, cameraZ);
            this.world = currentWorld;
            this.externalDirty.invalidateAll();
        }

        int playerSectionX = (int) Math.floor(cameraX) >> 4;
        int playerSectionY = (int) Math.floor(cameraY) >> 4;
        int playerSectionZ = (int) Math.floor(cameraZ) >> 4;
        int previousCenterX = this.centerSectionX;
        int previousCenterY = this.centerSectionY;
        int previousCenterZ = this.centerSectionZ;
        int viewDistance = minecraft.options.getEffectiveRenderDistance();
        int minSectionY = currentWorld.getMinY() >> 4;
        int maxSectionY = currentWorld.getMinY() + currentWorld.getHeight() - 1 >> 4;
        if (playerSectionX != this.centerSectionX
                || playerSectionY != this.centerSectionY
                || playerSectionZ != this.centerSectionZ
                || viewDistance != this.renderDistance
                || minSectionY != this.minimumSectionY
                || maxSectionY != this.maximumSectionY) {
            this.synchronizeWindow(
                    playerSectionX,
                    playerSectionY,
                    playerSectionZ,
                    viewDistance,
                    minSectionY,
                    maxSectionY);
            if (this.voxelTextureSurfaces
                    && previousCenterX != Integer.MIN_VALUE
                    && (previousCenterX != playerSectionX
                            || previousCenterY != playerSectionY
                            || previousCenterZ != playerSectionZ)) {
                this.invalidateVoxelSurfaceWindow(
                        previousCenterX,
                        previousCenterY,
                        previousCenterZ,
                        playerSectionX,
                        playerSectionY,
                        playerSectionZ);
            }
        }

        this.drainInvalidations();
        this.drainCompleted();
        this.uploadReady(cameraX, cameraY, cameraZ);
        this.dispatchSnapshots(minecraft, currentWorld);
    }

    public TerrainScene.ResidentSceneView residentScene() {
        return this.scene.residentView();
    }

    public void setLabPbrMaterials(LabPbrMaterialSet materials) {
        if (!this.labPbrMaterials.equals(materials)) {
            this.labPbrMaterials = materials;
            this.invalidateAll();
        }
    }

    public void setVoxelTextureSurfaces(boolean enabled) {
        if (this.voxelTextureSurfaces != enabled) {
            this.voxelTextureSurfaces = enabled;
            this.invalidateAll();
        }
    }

    public boolean isNearCameraReady() {
        if (this.world == null || this.centerSectionX == Integer.MIN_VALUE) {
            return false;
        }
        for (int z = this.centerSectionZ - 1; z <= this.centerSectionZ + 1; z++) {
            for (int y = Math.max(this.minimumSectionY, this.centerSectionY - 1);
                    y <= Math.min(this.maximumSectionY, this.centerSectionY + 1);
                    y++) {
                for (int x = this.centerSectionX - 1; x <= this.centerSectionX + 1; x++) {
                    long key = SectionCluster.keyForSection(x, y, z);
                    if (!this.scene.contains(key) && !this.empty.contains(key)) {
                        return false;
                    }
                }
            }
        }
        return this.scene.residentView() != null;
    }

    public void invalidateBlocks(
            int minimumX,
            int minimumY,
            int minimumZ,
            int maximumX,
            int maximumY,
            int maximumZ) {
        this.externalDirty.addExpandedBlockRange(
                minimumX, minimumY, minimumZ, maximumX, maximumY, maximumZ);
    }

    public void invalidateAll() {
        this.externalDirty.invalidateAll();
    }

    @Override
    public void close() {
        // The executor belongs to Minecraft and is shut down by Minecraft. World epochs and the
        // interpreter's closed flag make late results harmless without taking ownership here.
        RuntimeException failure = ResourceCleanup.close(this.sceneInterpreter, null);
        failure = ResourceCleanup.close(this.scene, failure);
        this.completed.clear();
        this.externalDirty.clear();
        this.readyForUpload.clear();
        this.pipelineState.clear();
        ResourceCleanup.throwIfFailed(failure);
    }

    private void synchronizeWindow(
            int centerX,
            int centerY,
            int centerZ,
            int distance,
            int minSectionY,
            int maxSectionY) {
        this.centerSectionX = centerX;
        this.centerSectionY = centerY;
        this.centerSectionZ = centerZ;
        this.renderDistance = distance;
        this.minimumSectionY = minSectionY;
        this.maximumSectionY = maxSectionY;

        LongOpenHashSet replacement = new LongOpenHashSet();
        int diameter = distance * 2 + 1;
        int verticalCount = maxSectionY - minSectionY + 1;
        replacement.ensureCapacity(diameter * diameter * verticalCount);
        for (int z = centerZ - distance; z <= centerZ + distance; z++) {
            for (int x = centerX - distance; x <= centerX + distance; x++) {
                int deltaX = x - centerX;
                int deltaZ = z - centerZ;
                if (deltaX * deltaX + deltaZ * deltaZ > distance * distance) {
                    continue;
                }
                for (int y = minSectionY; y <= maxSectionY; y++) {
                    replacement.add(SectionCluster.keyForSection(x, y, z));
                }
            }
        }

        for (long key : this.scene.residentKeys()) {
            if (!replacement.contains(key)) {
                this.pendingEvictions.add(key);
            }
        }
        this.pendingEvictions.removeIf(replacement::contains);
        this.empty.removeIf(key -> !replacement.contains(key));
        this.desired.clear();
        this.desired.addAll(replacement);
        this.rebuildRequestQueue(1);
    }

    private void drainInvalidations() {
        BoundedDirtySections.Batch batch = this.externalDirty.drain();
        if (batch.fullInvalidation()) {
            this.empty.clear();
            this.rebuildRequestQueue(0);
            return;
        }
        this.dirtyClusters.clear();
        for (long sectionKey : batch.keys()) {
            long clusterKey = SectionCluster.keyForSection(sectionKey);
            if (this.desired.contains(clusterKey)) {
                this.dirtyClusters.add(clusterKey);
            }
        }
        for (long clusterKey : this.dirtyClusters) {
            if (!this.desired.contains(clusterKey)) {
                continue;
            }
            this.empty.remove(clusterKey);
            long nextGeneration = this.generations.advance(clusterKey);
            this.enqueue(clusterKey, 0, nextGeneration);
        }
        this.dirtyClusters.clear();
    }

    private void rebuildRequestQueue(int priority) {
        this.requests.clear();
        this.pipelineState.clearQueued();
        for (long key : this.desired) {
            if (!this.scene.contains(key) || priority == 0) {
                long nextGeneration = priority == 0
                        ? this.generations.advance(key)
                        : this.generations.current(key);
                this.enqueue(key, priority, nextGeneration);
            }
        }
    }

    private void enqueue(long clusterKey, int priority, long token) {
        if (!this.pipelineState.enqueue(clusterKey, token)) {
            return;
        }
        int x = SectionPos.x(clusterKey) + SectionCluster.SECTION_SIZE / 2;
        int y = SectionPos.y(clusterKey) + SectionCluster.SECTION_SIZE / 2;
        int z = SectionPos.z(clusterKey) + SectionCluster.SECTION_SIZE / 2;
        long dx = x - this.centerSectionX;
        long dy = y - this.centerSectionY;
        long dz = z - this.centerSectionZ;
        long distanceSquared = ((dx * dx + dz * dz) << 8) | Math.min(255L, Math.abs(dy));
        this.requests.add(new ClusterRequest(clusterKey, token, priority, distanceSquared));
        this.compactRequestQueueIfNeeded();
    }

    private void dispatchSnapshots(Minecraft minecraft, ClientLevel level) {
        // Count every stage after snapshot capture so a temporarily busy GPU cannot turn the
        // shared executor into an unbounded producer of completed cluster payloads.
        int outstanding = this.workerJobs + this.readyForUpload.size();
        int dispatchBudget = Math.max(0, this.maximumInFlight - outstanding);
        if (dispatchBudget == 0 || this.requests.isEmpty()) {
            return;
        }
        RenderRegionCache regionCache = new RenderRegionCache();
        BlockStateModelSet models = minecraft.getModelManager().getBlockStateModelSet();
        FluidStateModelSet fluidModels = minecraft.getModelManager().getFluidStateModelSet();
        BlockColors blockColors = minecraft.getBlockColors();
        TextureAtlas blockAtlas = minecraft.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
        SpriteFinder blockSpriteFinder = ((FabricTextureAtlas) (Object) blockAtlas).spriteFinder();
        boolean cutoutLeaves = minecraft.options.cutoutLeaves().get();
        VanillaAssetSnapshot assetSnapshot = new VanillaAssetSnapshot(
                models,
                fluidModels,
                blockColors,
                blockSpriteFinder,
                this.labPbrMaterials,
                VanillaGeometryPolicy.VANILLA_PARITY,
                cutoutLeaves,
                this.opacityMicromapSupported,
                this.segmentTriangleTarget);
        this.unloadedRequests.clear();
        this.blockedRequests.clear();
        int examined = 0;
        int accepted = 0;
        while (accepted < dispatchBudget
                && examined < MAX_UNLOADED_PROBES_PER_FRAME
                && !this.requests.isEmpty()) {
            ClusterRequest request = this.requests.poll();
            examined++;
            if (!this.pipelineState.isQueued(request.key(), request.generation())) {
                continue;
            }
            if (!this.desired.contains(request.key())
                    || !this.generations.isCurrent(request.key(), request.generation())) {
                this.pipelineState.cancelQueued(request.key(), request.generation());
                continue;
            }
            if (this.pipelineState.hasInFlight(request.key())) {
                this.blockedRequests.add(request);
                continue;
            }
            this.pipelineState.cancelQueued(request.key(), request.generation());
            int clusterX = SectionPos.x(request.key());
            int clusterY = SectionPos.y(request.key());
            int clusterZ = SectionPos.z(request.key());
            boolean voxelSurfaces = this.voxelTextureSurfaces;
            float detailCenterWorldX = (this.centerSectionX << 4) + 8.0F;
            float detailCenterWorldY = (this.centerSectionY << 4) + 8.0F;
            float detailCenterWorldZ = (this.centerSectionZ << 4) + 8.0F;
            if (!hasCompleteClusterNeighborhood(level, clusterX, clusterZ)) {
                // A 4x4x4 virtual chunk needs one Section of source data around every face.
                // Minecraft loads vertical Sections as part of the same chunk column, so checking
                // the 6x6 horizontal chunk columns establishes the complete 6x6x6 snapshot.
                this.unloadedRequests.add(request);
                continue;
            }

            ArrayList<VanillaSectionSnapshot> snapshots = new ArrayList<>(
                    SectionCluster.SECTION_COUNT);
            for (int sectionZ = clusterZ;
                    sectionZ < clusterZ + SectionCluster.SECTION_SIZE;
                    sectionZ++) {
                for (int sectionY = clusterY;
                        sectionY < clusterY + SectionCluster.SECTION_SIZE;
                        sectionY++) {
                    if (sectionY < this.minimumSectionY || sectionY > this.maximumSectionY) {
                        continue;
                    }
                    for (int sectionX = clusterX;
                            sectionX < clusterX + SectionCluster.SECTION_SIZE;
                            sectionX++) {
                        LevelChunk chunk = level.getChunkSource().getChunk(
                                sectionX, sectionZ, ChunkStatus.FULL, false);
                        if (chunk == null) {
                            throw new IllegalStateException(
                                    "Complete cluster neighborhood lost a loaded chunk");
                        }
                        if (chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY))
                                .hasOnlyAir()) {
                            continue;
                        }
                        long sectionKey = SectionPos.asLong(sectionX, sectionY, sectionZ);
                        snapshots.add(new VanillaSectionSnapshot(
                                sectionX,
                                sectionY,
                                sectionZ,
                                regionCache.createRegion(level, sectionKey)));
                    }
                }
            }

            if (snapshots.isEmpty()) {
                if (this.scene.contains(request.key())) {
                    CompletedCluster result = new CompletedCluster(
                            this.generations.worldEpoch(),
                            request.key(),
                            request.generation(),
                            clusterX,
                            clusterY,
                            clusterZ,
                            CpuClusterMesh.empty(),
                            null);
                    if (this.pipelineState.completeToReady(
                            result.key(), result.generation())) {
                        this.readyForUpload.addLast(result);
                    }
                } else {
                    this.empty.add(request.key());
                }
                accepted++;
                continue;
            }
            this.pipelineState.beginInFlight(request.key(), request.generation());
            long worldEpoch = this.generations.worldEpoch();
            try {
                this.workers.execute(() -> {
                    CpuClusterMesh mesh = CpuClusterMesh.empty();
                    Throwable failure = null;
                    try {
                        SectionClusterMeshBuilder cluster = new SectionClusterMeshBuilder(
                                clusterX,
                                clusterY,
                                clusterZ,
                                this.segmentTriangleTarget,
                                TerrainStreamer.this.maxOpacityMicromapSubdivisionLevel,
                                voxelSurfaces,
                                detailCenterWorldX,
                                detailCenterWorldY,
                                detailCenterWorldZ);
                        for (VanillaSectionSnapshot snapshot : snapshots) {
                            CpuSectionGeometry sectionGeometry =
                                    TerrainStreamer.this.sceneInterpreter
                                    .compileSection(
                                            new VanillaSectionCompileInput(
                                                    snapshot,
                                                    assetSnapshot));
                            cluster.add(
                                    snapshot.sectionX(),
                                    snapshot.sectionY(),
                                    snapshot.sectionZ(),
                                    sectionGeometry);
                        }
                        mesh = cluster.build();
                    } catch (Throwable throwable) {
                        failure = throwable;
                    }
                    CompletedCluster completedCluster = new CompletedCluster(
                            worldEpoch,
                            request.key(),
                            request.generation(),
                            clusterX,
                            clusterY,
                            clusterZ,
                            mesh,
                            failure);
                    TerrainStreamer.this.completed.add(completedCluster);
                });
                this.workerJobs++;
            } catch (RejectedExecutionException ignored) {
                this.pipelineState.cancelInFlight(request.key(), request.generation());
                this.enqueue(request.key(), request.priority(), request.generation());
                PrimeClient.LOGGER.debug("Terrain executor is temporarily saturated");
                break;
            }
            accepted++;
        }
        this.requests.addAll(this.blockedRequests);
        for (ClusterRequest request : this.unloadedRequests) {
            this.enqueue(request.key(), request.priority(), request.generation());
        }
        this.blockedRequests.clear();
        this.unloadedRequests.clear();
    }

    private static boolean hasCompleteClusterNeighborhood(
            ClientLevel level,
            int clusterX,
            int clusterZ) {
        int minimumChunkX = clusterX - SectionCluster.SNAPSHOT_HALO;
        int minimumChunkZ = clusterZ - SectionCluster.SNAPSHOT_HALO;
        int maximumChunkX = clusterX + SectionCluster.SECTION_SIZE;
        int maximumChunkZ = clusterZ + SectionCluster.SECTION_SIZE;
        for (int chunkZ = minimumChunkZ; chunkZ <= maximumChunkZ; chunkZ++) {
            for (int chunkX = minimumChunkX; chunkX <= maximumChunkX; chunkX++) {
                if (level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private void drainCompleted() {
        CompletedCluster result;
        while ((result = this.completed.poll()) != null) {
            this.workerJobs--;
            if (result.worldEpoch() != this.generations.worldEpoch()) {
                continue;
            }
            this.pipelineState.cancelInFlight(result.key(), result.generation());
            if (!this.desired.contains(result.key())
                    || !this.generations.isCurrent(result.key(), result.generation())) {
                continue;
            }
            if (result.failure() != null) {
                // A completed job is immutable and retrying the same generation cannot repair a
                // deterministic compiler/capture failure. Immediate requeue previously left Prime
                // in STREAMING forever and could emit hundreds of megabytes of identical stacks.
                // Escalate once on the render thread so the runtime performs its defined FAILED ->
                // vanilla fallback and presents the actionable reason to the user.
                String message = result.failure() instanceof OutOfMemoryError
                        ? "Terrain resources exhausted while extracting virtual cluster "
                                + result.key()
                        : "Terrain extraction failed for virtual cluster " + result.key();
                throw new IllegalStateException(message, result.failure());
            }
            if (this.pipelineState.completeToReady(result.key(), result.generation())) {
                this.readyForUpload.addLast(result);
            }
        }
    }

    private void uploadReady(double cameraX, double cameraY, double cameraZ) {
        List<CompiledCluster> uploads = this.uploadBatch;
        uploads.clear();
        long uploadBytes = 0L;
        while (!this.readyForUpload.isEmpty()) {
            CompletedCluster next = this.readyForUpload.peekFirst();
            if (!this.generations.isCurrent(next.key(), next.generation()) || !this.desired.contains(next.key())) {
                this.readyForUpload.removeFirst();
                this.pipelineState.consumeReady(next.key(), next.generation());
                continue;
            }
            long nextEndOffset = stagingEndOffset(
                    uploadBytes, next.mesh(), this.opacityMicromapSupported);
            if (!uploads.isEmpty() && nextEndOffset > TARGET_UPLOAD_BYTES_PER_FRAME) {
                break;
            }
            this.readyForUpload.removeFirst();
            this.pipelineState.consumeReady(next.key(), next.generation());
            uploadBytes = nextEndOffset;
            uploads.add(new CompiledCluster(
                    next.key(), next.clusterX(), next.clusterY(), next.clusterZ(), next.mesh()));
        }
        long[] evictions = this.pendingEvictions.isEmpty()
                ? EMPTY_EVICTIONS
                : this.pendingEvictions.toLongArray();
        boolean updated = this.scene.update(uploads, evictions, cameraX, cameraY, cameraZ);
        if (!updated) {
            for (int index = uploads.size() - 1; index >= 0; index--) {
                CompiledCluster upload = uploads.get(index);
                CompletedCluster result = new CompletedCluster(
                        this.generations.worldEpoch(),
                        upload.key(),
                        this.generations.current(upload.key()),
                        upload.clusterX(),
                        upload.clusterY(),
                        upload.clusterZ(),
                        upload.mesh(),
                        null);
                if (this.pipelineState.completeToReady(
                        result.key(), result.generation())) {
                    this.readyForUpload.addFirst(result);
                }
            }
            return;
        }
        this.pendingEvictions.clear();
        for (long key : evictions) {
            this.empty.remove(key);
        }
        for (CompiledCluster upload : uploads) {
            if (upload.isEmpty()) {
                this.empty.add(upload.key());
            } else {
                this.empty.remove(upload.key());
            }
        }
    }

    static long stagingEndOffset(
            long cursor, CpuClusterMesh mesh, boolean includeOpacityMicromap) {
        if (mesh.isEmpty()) {
            return cursor;
        }
        long result = cursor;
        for (CpuClusterMesh.Segment segment : mesh.segments()) {
            result = segmentStagingEndOffset(
                    result, segment.opaqueTriangleCount());
            result = segmentStagingEndOffset(
                    result, segment.cutoutTriangleCount());
            result = segmentStagingEndOffset(
                    result, segment.transmissiveTriangleCount());
        }
        result = opacityStagingEndOffset(
                result, mesh.opacityMicromap(), includeOpacityMicromap);
        if (!mesh.lights().isEmpty()) {
            result = StagingArena.requiredEndOffset(
                    result, mesh.lights().byteSize(), 16L);
        }
        for (CpuVoxelMesh voxelMesh : mesh.voxelMeshes()) {
            result = StagingArena.requiredEndOffset(
                    result, voxelMesh.positionBytes(), Float.BYTES);
            result = StagingArena.requiredEndOffset(
                    result, voxelMesh.primitiveBytes(), Integer.BYTES);
            result = opacityStagingEndOffset(
                    result,
                    voxelMesh.opacityMicromap(),
                    includeOpacityMicromap);
        }
        return result;
    }

    private static long opacityStagingEndOffset(
            long cursor,
            OpacityMicromapData opacityMicromap,
            boolean includeOpacityMicromap) {
        return stagingEndOffset(
                cursor,
                0L,
                0L,
                0L,
                includeOpacityMicromap
                        ? (long) opacityMicromap.triangleCount() * Integer.BYTES
                        : 0L,
                includeOpacityMicromap ? opacityMicromap.blockStorageBytes() : 0L,
                includeOpacityMicromap
                        ? (long) opacityMicromap.blockCount()
                                * org.lwjgl.vulkan.VkMicromapTriangleEXT.SIZEOF
                        : 0L);
    }

    private static long segmentStagingEndOffset(long cursor, int triangleCount) {
        if (triangleCount == 0) {
            return cursor;
        }
        long result = StagingArena.requiredEndOffset(
                cursor, (long) triangleCount * 9L * Float.BYTES, Float.BYTES);
        return StagingArena.requiredEndOffset(
                result,
                (long) triangleCount * CpuSectionMesh.PRIMITIVE_WORDS * Integer.BYTES,
                Integer.BYTES);
    }

    static long stagingEndOffset(
            long cursor,
            long positionBytes,
            long primitiveBytes,
            long lightBytes,
            long opacityIndexBytes,
            long opacityDataBytes,
            long opacityTriangleBytes) {
        long endOffset = StagingArena.requiredEndOffset(cursor, positionBytes, Float.BYTES);
        endOffset = StagingArena.requiredEndOffset(endOffset, primitiveBytes, Integer.BYTES);
        if (lightBytes != 0L) {
            endOffset = StagingArena.requiredEndOffset(endOffset, lightBytes, 16L);
        }
        if (opacityIndexBytes != 0L) {
            endOffset = StagingArena.requiredEndOffset(
                    endOffset, opacityIndexBytes, Integer.BYTES);
        }
        if (opacityDataBytes != 0L) {
            endOffset = StagingArena.requiredEndOffset(endOffset, opacityDataBytes, 16L);
        }
        return opacityTriangleBytes == 0L
                ? endOffset
                : StagingArena.requiredEndOffset(
                        endOffset, opacityTriangleBytes, Integer.BYTES);
    }

    private void clearWorld(double cameraX, double cameraY, double cameraZ) {
        this.scene.beginUnrelatedWorld();
        this.scene.update(List.of(), this.scene.residentKeys(), cameraX, cameraY, cameraZ);
        this.world = null;
        this.desired.clear();
        this.empty.clear();
        this.pendingEvictions.clear();
        this.dirtyClusters.clear();
        this.generations.resetWorld();
        this.pipelineState.clear();
        this.requests.clear();
        this.externalDirty.clear();
        this.readyForUpload.clear();
        this.centerSectionX = Integer.MIN_VALUE;
        this.centerSectionY = Integer.MIN_VALUE;
        this.centerSectionZ = Integer.MIN_VALUE;
        this.renderDistance = -1;
    }

    private void invalidateVoxelSurfaceWindow(
            int oldSectionX,
            int oldSectionY,
            int oldSectionZ,
            int newSectionX,
            int newSectionY,
            int newSectionZ) {
        float oldX = (oldSectionX << 4) + 8.0F;
        float oldY = (oldSectionY << 4) + 8.0F;
        float oldZ = (oldSectionZ << 4) + 8.0F;
        float newX = (newSectionX << 4) + 8.0F;
        float newY = (newSectionY << 4) + 8.0F;
        float newZ = (newSectionZ << 4) + 8.0F;
        for (long key : this.desired) {
            int clusterX = SectionPos.x(key) << 4;
            int clusterY = SectionPos.y(key) << 4;
            int clusterZ = SectionPos.z(key) << 4;
            if (clusterIntersectsDetailSphere(
                            clusterX, clusterY, clusterZ, oldX, oldY, oldZ)
                    || clusterIntersectsDetailSphere(
                            clusterX, clusterY, clusterZ, newX, newY, newZ)) {
                this.externalDirty.add(key);
            }
        }
    }

    static boolean clusterIntersectsDetailSphere(
            float clusterX,
            float clusterY,
            float clusterZ,
            float centerX,
            float centerY,
            float centerZ) {
        float edge = SectionCluster.SECTION_SIZE * 16.0F;
        float dx = intervalDistance(centerX, clusterX, clusterX + edge);
        float dy = intervalDistance(centerY, clusterY, clusterY + edge);
        float dz = intervalDistance(centerZ, clusterZ, clusterZ + edge);
        float radius = MergedFaceMeshBuilder.VOXEL_SURFACE_RADIUS;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    private static float intervalDistance(float value, float minimum, float maximum) {
        return value < minimum
                ? minimum - value
                : (value > maximum ? value - maximum : 0.0F);
    }

    private void compactRequestQueueIfNeeded() {
        long desiredLimit = Math.max(1024L, (long) this.desired.size() * 2L);
        if (this.requests.size() <= desiredLimit) {
            return;
        }
        this.requests.removeIf(request ->
                !this.desired.contains(request.key())
                        || !this.generations.isCurrent(request.key(), request.generation())
                        || !this.pipelineState.isQueued(
                                request.key(), request.generation()));
    }

    private record ClusterRequest(long key, long generation, int priority, long distanceSquared) {
    }

    private record CompletedCluster(
            long worldEpoch,
            long key,
            long generation,
            int clusterX,
            int clusterY,
            int clusterZ,
            CpuClusterMesh mesh,
            Throwable failure) {
    }
}
