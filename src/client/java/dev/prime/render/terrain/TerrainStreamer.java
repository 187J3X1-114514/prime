package dev.prime.render.terrain;

import dev.prime.PrimeClient;
import dev.prime.render.scene.vanilla.VanillaSceneInterpreter;
import dev.prime.render.vulkan.StagingArena;
import dev.prime.render.vulkan.VulkanContext;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.SectionPos;
import net.minecraft.data.AtlasIds;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.FabricTextureAtlas;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder;

/**
 * Owns the terrain portion of Prime's scene independently from vanilla's raster renderer.
 *
 * <p>This is the sole authority for which virtual clusters Prime wants, when they become dirty,
 * which generation is current, and how long uploaded geometry remains resident. One aligned
 * 4x4x4 cluster replaces its 64 Sections as the update, light-tree, BLAS, TLAS-instance and
 * eviction unit. In particular, this
 * scheduler must never depend on {@code LevelRenderer.visibleSections()}, the occlusion graph, or
 * vanilla's raster compilation queue: those are presentation decisions and can omit geometry that
 * still contributes to ray-traced visibility and global illumination.
 *
 * <p>Independence ends at mesh semantics. Once this class has selected the stable 6x6x6 snapshot
 * neighborhood around a cluster, each of its 64 inner Sections is delegated to
 * {@link VanillaSceneInterpreter}. Prime does not maintain a second block/fluid mesher and does
 * not merge geometry captured from vanilla's raster tasks.
 */
public final class TerrainStreamer implements AutoCloseable {
    private static final long[] EMPTY_EVICTIONS = new long[0];
    private static final int MAX_SNAPSHOTS_PER_FRAME = 1;
    private static final int MAX_UPLOADS_PER_FRAME = 1;
    // A normal frame retains the old 16 MiB budget, but a single atomic cluster is allowed to
    // exceed it and obtains a correspondingly sized staging page.
    private static final long MAX_UPLOAD_BYTES_PER_FRAME = StagingArena.PAGE_SIZE;
    private static final int MAX_UNLOADED_PROBES_PER_FRAME = 64;
    private static final int MAX_READY_FOR_UPLOAD = 16;
    private static final int MAX_EXTERNAL_DIRTY_SECTIONS = 16_384;
    private static final CpuSectionMesh EMPTY_MESH = new CpuSectionMesh(
            new float[0], new int[0], 0, 0, CpuSectionLights.EMPTY);

    private final TerrainScene scene;
    private final VanillaSceneInterpreter sceneInterpreter = new VanillaSceneInterpreter();
    private final ThreadPoolExecutor workers;
    private final int maximumInFlight;
    private final ArrayBlockingQueue<CompletedCluster> completed;
    private final AtomicBoolean completionOverflow = new AtomicBoolean();
    private final BoundedDirtySections externalDirty =
            new BoundedDirtySections(MAX_EXTERNAL_DIRTY_SECTIONS);
    private final LongOpenHashSet desired = new LongOpenHashSet();
    private final LongOpenHashSet empty = new LongOpenHashSet();
    private final LongOpenHashSet pendingEvictions = new LongOpenHashSet();
    private final LongOpenHashSet dirtyClusters = new LongOpenHashSet();
    private final ClusterGenerationTracker generations = new ClusterGenerationTracker();
    private final Long2LongOpenHashMap queuedGeneration = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap inFlightGeneration = new Long2LongOpenHashMap();
    private final PriorityQueue<ClusterRequest> requests = new PriorityQueue<>(Comparator
            .comparingInt(ClusterRequest::priority)
            .thenComparingLong(ClusterRequest::distanceSquared)
            .thenComparingLong(ClusterRequest::key));
    private final ArrayDeque<CompletedCluster> readyForUpload = new ArrayDeque<>();
    private final ArrayList<ClusterUpload> uploadBatch = new ArrayList<>(MAX_UPLOADS_PER_FRAME);
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

    public TerrainStreamer(VulkanContext context, StagingArena stagingArena) {
        this.scene = new TerrainScene(context, stagingArena);
        int threadCount = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 2));
        this.maximumInFlight = threadCount * 2;
        this.completed = new ArrayBlockingQueue<>(this.maximumInFlight * 2);
        AtomicInteger threadNumber = new AtomicInteger();
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, "Prime terrain worker " + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, failure) ->
                    PrimeClient.LOGGER.error("Uncaught Prime terrain worker failure", failure));
            return thread;
        };
        this.workers = new ThreadPoolExecutor(
                threadCount,
                threadCount,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(this.maximumInFlight - threadCount),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
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
        }

        this.drainInvalidations();
        this.drainCompleted();
        this.uploadReady(cameraX, cameraY, cameraZ);
        this.dispatchSnapshots(minecraft, currentWorld);
    }

    public TerrainScene.SceneView sceneView() {
        return this.scene.view();
    }

    public void setLabPbrMaterials(LabPbrMaterialSet materials) {
        if (!this.labPbrMaterials.equals(materials)) {
            this.labPbrMaterials = materials;
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
        return this.scene.view() != null;
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
        this.workers.shutdownNow();
        try {
            if (!this.workers.awaitTermination(5L, TimeUnit.SECONDS)) {
                PrimeClient.LOGGER.warn("Prime terrain workers did not stop within five seconds");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        this.sceneInterpreter.close();
        this.scene.close();
        this.completed.clear();
        this.externalDirty.clear();
        this.readyForUpload.clear();
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
        this.queuedGeneration.clear();
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
        if (this.queuedGeneration.getOrDefault(clusterKey, Long.MIN_VALUE) == token) {
            return;
        }
        int x = SectionPos.x(clusterKey) + SectionCluster.SECTION_SIZE / 2;
        int y = SectionPos.y(clusterKey) + SectionCluster.SECTION_SIZE / 2;
        int z = SectionPos.z(clusterKey) + SectionCluster.SECTION_SIZE / 2;
        long dx = x - this.centerSectionX;
        long dy = y - this.centerSectionY;
        long dz = z - this.centerSectionZ;
        long distanceSquared = ((dx * dx + dz * dz) << 8) | Math.min(255L, Math.abs(dy));
        this.queuedGeneration.put(clusterKey, token);
        this.requests.add(new ClusterRequest(clusterKey, token, priority, distanceSquared));
        this.compactRequestQueueIfNeeded();
    }

    private void dispatchSnapshots(Minecraft minecraft, ClientLevel level) {
        int slots = this.maximumInFlight - this.inFlightGeneration.size();
        int dispatchBudget = Math.min(MAX_SNAPSHOTS_PER_FRAME, Math.max(0, slots));
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
        this.unloadedRequests.clear();
        this.blockedRequests.clear();
        int examined = 0;
        int dispatched = 0;
        while (dispatched < dispatchBudget
                && examined < MAX_UNLOADED_PROBES_PER_FRAME
                && !this.requests.isEmpty()) {
            ClusterRequest request = this.requests.poll();
            examined++;
            if (this.queuedGeneration.getOrDefault(request.key(), Long.MIN_VALUE) != request.generation()) {
                continue;
            }
            if (!this.desired.contains(request.key())
                    || !this.generations.isCurrent(request.key(), request.generation())) {
                this.queuedGeneration.remove(request.key());
                continue;
            }
            if (this.inFlightGeneration.containsKey(request.key())) {
                this.blockedRequests.add(request);
                continue;
            }
            this.queuedGeneration.remove(request.key());
            int clusterX = SectionPos.x(request.key());
            int clusterY = SectionPos.y(request.key());
            int clusterZ = SectionPos.z(request.key());
            if (!hasCompleteClusterNeighborhood(level, clusterX, clusterZ)) {
                // A 4x4x4 virtual chunk needs one Section of source data around every face.
                // Minecraft loads vertical Sections as part of the same chunk column, so checking
                // the 6x6 horizontal chunk columns establishes the complete 6x6x6 snapshot.
                this.unloadedRequests.add(request);
                continue;
            }

            ArrayList<ClusterSectionSnapshot> snapshots = new ArrayList<>(
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
                        snapshots.add(new ClusterSectionSnapshot(
                                sectionX,
                                sectionY,
                                sectionZ,
                                regionCache.createRegion(level, sectionKey)));
                    }
                }
            }

            if (snapshots.isEmpty()) {
                if (this.scene.contains(request.key())) {
                    if (this.readyForUpload.size() >= MAX_READY_FOR_UPLOAD) {
                        this.enqueue(request.key(), request.priority(), request.generation());
                    } else {
                        this.readyForUpload.addLast(new CompletedCluster(
                                this.generations.worldEpoch(),
                                request.key(),
                                request.generation(),
                                clusterX,
                                clusterY,
                                clusterZ,
                                EMPTY_MESH,
                                null));
                    }
                } else {
                    this.empty.add(request.key());
                }
                continue;
            }
            LabPbrMaterialSet materialSnapshot = this.labPbrMaterials;
            this.inFlightGeneration.put(request.key(), request.generation());
            long worldEpoch = this.generations.worldEpoch();
            try {
                this.workers.execute(() -> {
                    CpuSectionMesh mesh = EMPTY_MESH;
                    Throwable failure = null;
                    try {
                        SectionClusterMeshBuilder cluster = new SectionClusterMeshBuilder(
                                clusterX, clusterY, clusterZ);
                        for (ClusterSectionSnapshot snapshot : snapshots) {
                            CpuSectionMesh sectionMesh = TerrainStreamer.this.sceneInterpreter
                                    .compileSection(
                                            snapshot.region(),
                                            models,
                                            fluidModels,
                                            blockColors,
                                            blockSpriteFinder,
                                            materialSnapshot,
                                            cutoutLeaves,
                                            snapshot.sectionX(),
                                            snapshot.sectionY(),
                                            snapshot.sectionZ());
                            cluster.add(
                                    snapshot.sectionX(),
                                    snapshot.sectionY(),
                                    snapshot.sectionZ(),
                                    sectionMesh);
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
                    if (!TerrainStreamer.this.completed.offer(completedCluster)) {
                        TerrainStreamer.this.completionOverflow.set(true);
                    }
                });
            } catch (RejectedExecutionException ignored) {
                this.inFlightGeneration.remove(request.key());
                this.enqueue(request.key(), request.priority(), request.generation());
                PrimeClient.LOGGER.debug("Terrain executor is temporarily saturated");
                break;
            }
            dispatched++;
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
        if (this.completionOverflow.getAndSet(false)) {
            this.completed.clear();
            this.readyForUpload.clear();
            this.inFlightGeneration.clear();
            this.rebuildRequestQueue(0);
            PrimeClient.LOGGER.warn("Terrain completion queue overflowed; rebuilding the bounded work set");
            return;
        }
        CompletedCluster result;
        while ((result = this.completed.poll()) != null) {
            if (result.worldEpoch() != this.generations.worldEpoch()) {
                continue;
            }
            if (this.inFlightGeneration.getOrDefault(result.key(), Long.MIN_VALUE) == result.generation()) {
                this.inFlightGeneration.remove(result.key());
            }
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
                throw new IllegalStateException(
                        "Terrain extraction failed for virtual cluster " + result.key(),
                        result.failure());
            }
            if (this.readyForUpload.size() >= MAX_READY_FOR_UPLOAD) {
                this.enqueue(result.key(), 0, result.generation());
                continue;
            }
            this.readyForUpload.addLast(result);
        }
    }

    private void uploadReady(double cameraX, double cameraY, double cameraZ) {
        List<ClusterUpload> uploads = this.uploadBatch;
        uploads.clear();
        long uploadBytes = 0L;
        while (uploads.size() < MAX_UPLOADS_PER_FRAME && !this.readyForUpload.isEmpty()) {
            CompletedCluster next = this.readyForUpload.peekFirst();
            if (!this.generations.isCurrent(next.key(), next.generation()) || !this.desired.contains(next.key())) {
                this.readyForUpload.removeFirst();
                continue;
            }
            long nextEndOffset = stagingEndOffset(uploadBytes, next.mesh());
            if (!uploads.isEmpty() && nextEndOffset > MAX_UPLOAD_BYTES_PER_FRAME) {
                break;
            }
            this.readyForUpload.removeFirst();
            uploadBytes = nextEndOffset;
            uploads.add(new ClusterUpload(
                    next.key(), next.clusterX(), next.clusterY(), next.clusterZ(), next.mesh()));
        }
        long[] evictions = this.pendingEvictions.isEmpty()
                ? EMPTY_EVICTIONS
                : this.pendingEvictions.toLongArray();
        boolean updated = this.scene.update(uploads, evictions, cameraX, cameraY, cameraZ);
        if (!updated) {
            for (int index = uploads.size() - 1; index >= 0; index--) {
                ClusterUpload upload = uploads.get(index);
                this.readyForUpload.addFirst(new CompletedCluster(
                        this.generations.worldEpoch(),
                        upload.key(),
                        this.generations.current(upload.key()),
                        upload.clusterX(),
                        upload.clusterY(),
                        upload.clusterZ(),
                        upload.mesh(),
                        null));
            }
            return;
        }
        this.pendingEvictions.clear();
        for (long key : evictions) {
            this.empty.remove(key);
        }
        for (ClusterUpload upload : uploads) {
            if (upload.mesh().isEmpty()) {
                this.empty.add(upload.key());
            } else {
                this.empty.remove(upload.key());
            }
        }
    }

    static long stagingEndOffset(long cursor, CpuSectionMesh mesh) {
        if (mesh.isEmpty()) {
            return cursor;
        }
        return stagingEndOffset(
                cursor,
                (long) mesh.positions().length * Float.BYTES,
                (long) mesh.primitiveRecords().length * Integer.BYTES,
                mesh.lights().byteSize());
    }

    static long stagingEndOffset(
            long cursor,
            long positionBytes,
            long primitiveBytes,
            long lightBytes) {
        long endOffset = StagingArena.requiredEndOffset(cursor, positionBytes, Float.BYTES);
        endOffset = StagingArena.requiredEndOffset(endOffset, primitiveBytes, Integer.BYTES);
        return lightBytes == 0L
                ? endOffset
                : StagingArena.requiredEndOffset(endOffset, lightBytes, 16L);
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
        this.queuedGeneration.clear();
        this.inFlightGeneration.clear();
        this.requests.clear();
        this.externalDirty.clear();
        this.completionOverflow.set(false);
        this.readyForUpload.clear();
        this.centerSectionX = Integer.MIN_VALUE;
        this.centerSectionY = Integer.MIN_VALUE;
        this.centerSectionZ = Integer.MIN_VALUE;
        this.renderDistance = -1;
    }

    private void compactRequestQueueIfNeeded() {
        long desiredLimit = Math.max(1024L, (long) this.desired.size() * 2L);
        if (this.requests.size() <= desiredLimit) {
            return;
        }
        this.requests.removeIf(request ->
                !this.desired.contains(request.key())
                        || !this.generations.isCurrent(request.key(), request.generation())
                        || this.queuedGeneration.getOrDefault(request.key(), Long.MIN_VALUE)
                                != request.generation());
    }

    private record ClusterRequest(long key, long generation, int priority, long distanceSquared) {
    }

    private record ClusterSectionSnapshot(
            int sectionX,
            int sectionY,
            int sectionZ,
            RenderSectionRegion region) {
    }

    private record CompletedCluster(
            long worldEpoch,
            long key,
            long generation,
            int clusterX,
            int clusterY,
            int clusterZ,
            CpuSectionMesh mesh,
            Throwable failure) {
    }
}
