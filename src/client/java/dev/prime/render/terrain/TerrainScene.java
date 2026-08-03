package dev.prime.render.terrain;

import dev.prime.render.ResourceCleanup;
import dev.prime.render.vulkan.PreparedBlas;
import dev.prime.render.vulkan.StagingArena;
import dev.prime.render.vulkan.TopLevelAccelerationStructure;
import dev.prime.render.vulkan.VulkanBuffer;
import dev.prime.render.vulkan.VulkanContext;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.SectionPos;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.EXTOpacityMicromap;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;

public final class TerrainScene implements AutoCloseable {
    private static final int TLAS_SLOT_COUNT = 3;
    private static final int REBASE_DISTANCE = 256;

    private final VulkanContext context;
    private final StagingArena stagingArena;
    private final BlasCompactionScheduler compactionScheduler =
            new BlasCompactionScheduler();
    private Long2ObjectOpenHashMap<GpuCluster> resident = new Long2ObjectOpenHashMap<>();
    private final List<TopLevelAccelerationStructure> tlasSlots = new ArrayList<>(TLAS_SLOT_COUNT);
    private TopLevelAccelerationStructure currentTlas;
    private VulkanBuffer currentWorldLights;
    private CpuWorldLightTree.Result currentWorldLightTree =
            CpuWorldLightTree.Result.empty(0);
    private ResidentSceneView currentView;
    private int originX;
    private int originY;
    private int originZ;
    private long revision;
    private long resetRevision;
    private long temporalRevision;
    private long occluderRevision;

    public TerrainScene(VulkanContext context, StagingArena stagingArena) {
        this.context = context;
        this.stagingArena = stagingArena;
    }

    public boolean update(
            List<CompiledCluster> uploads,
            long[] evictions,
            double cameraX,
            double cameraY,
            double cameraZ) {
        boolean contentChanged = this.hasActualContentChange(uploads, evictions);
        boolean staticContentChanged =
                this.hasActualStaticContentChange(uploads, evictions);
        LongOpenHashSet removedKeys = removedKeys(uploads, evictions);
        List<TerrainOccluderChange> occluderChanges = staticContentChanged
                ? this.occluderChanges(uploads, evictions)
                : List.of();
        boolean needsRebase = this.currentTlas == null
                ? contentChanged
                : RenderOrigin.needsRebase(
                        cameraX,
                        cameraY,
                        cameraZ,
                        this.originX,
                        this.originY,
                        this.originZ,
                        REBASE_DISTANCE);
        boolean invalidateTemporalHistory = invalidatesTemporalHistory(needsRebase);
        if (!contentChanged && !needsRebase) {
            return true;
        }
        int finalClusterCount = this.estimateFinalClusterCount(uploads, removedKeys);
        int finalInstanceCount = this.estimateFinalInstanceCount(uploads, removedKeys);
        TopLevelAccelerationStructure replacementTlas = null;
        if (finalClusterCount > 0) {
            replacementTlas = this.acquireTlas(finalInstanceCount);
            if (replacementTlas == null) {
                return false;
            }
        }

        int nonEmptyUploadCount = 0;
        for (CompiledCluster upload : uploads) {
            if (!upload.isEmpty()) {
                nonEmptyUploadCount++;
            }
        }
        boolean hasPotentialLights = false;
        for (GpuCluster cluster : this.resident.values()) {
            if (!cluster.lights().isEmpty()) {
                hasPotentialLights = true;
                break;
            }
        }
        if (!hasPotentialLights) {
            for (CompiledCluster upload : uploads) {
                if (!upload.mesh().lights().isEmpty()) {
                    hasPotentialLights = true;
                    break;
                }
            }
        }
        boolean needsClusterStaging = nonEmptyUploadCount > 0;
        /*
         * A semantic change already replaces and uploads the complete packed tree. Stable-slot
         * refit would therefore save only CPU construction while retaining inactive reserve nodes
         * and accumulated SAH degradation on the GPU. Rebuild to exactly 2L-1 nodes; BLAS
         * compaction changes only addresses and deliberately reuses the committed tree.
         */
        /*
         * The reserved dynamic instance sorts after every terrain cluster and cannot carry
         * emitters. Replacing it cannot change static cluster indices or light-tree topology.
         */
        boolean rebuildWorldLights = staticContentChanged || needsRebase;
        boolean needsWorldStaging = rebuildWorldLights && finalClusterCount > 0 && hasPotentialLights;
        long clusterStagingBytes = 0L;
        for (CompiledCluster upload : uploads) {
            clusterStagingBytes = TerrainStreamer.stagingEndOffset(
                    clusterStagingBytes,
                    upload.mesh(),
                    this.context.capabilities().opacityMicromapSupported());
        }
        StagingArena.Batch clusterStagingBatch = needsClusterStaging
                ? this.stagingArena.tryBeginBatch(clusterStagingBytes)
                : null;
        if (needsClusterStaging && clusterStagingBatch == null) {
            if (replacementTlas != null) {
                replacementTlas.release();
            }
            return false;
        }
        StagingArena.Batch worldStagingBatch = needsWorldStaging
                ? this.stagingArena.tryBeginBatch()
                : null;
        if (needsWorldStaging && worldStagingBatch == null) {
            if (clusterStagingBatch != null) {
                clusterStagingBatch.close();
            }
            if (replacementTlas != null) {
                replacementTlas.release();
            }
            return false;
        }

        List<GpuCluster> replacements = new ArrayList<>(nonEmptyUploadCount);
        VulkanBuffer replacementWorldLights = null;
        VkCommandBuffer commandBuffer = null;
        boolean submitted = false;
        boolean ownershipTransferred = false;
        boolean cleanupHandled = false;
        try {
            if (nonEmptyUploadCount > 0 || replacementTlas != null) {
                commandBuffer = this.context.commandEncoder().allocateAndBeginTransientCommandBuffer();
                this.context.device().instance().debug().beginDebugGroup(commandBuffer, () -> "Prime terrain scene update");
            }

            if (clusterStagingBatch != null) {
                for (CompiledCluster upload : uploads) {
                    if (!upload.isEmpty()) {
                        replacements.add(this.prepareCluster(
                                upload, clusterStagingBatch, commandBuffer));
                    }
                }
            }

            List<GpuCluster> finalClusters = this.buildFinalClusterList(
                    removedKeys, replacements, finalClusterCount);
            int nextOriginX = needsRebase ? RenderOrigin.alignToSection(cameraX) : this.originX;
            int nextOriginY = needsRebase ? RenderOrigin.alignToSection(cameraY) : this.originY;
            int nextOriginZ = needsRebase ? RenderOrigin.alignToSection(cameraZ) : this.originZ;
            CpuWorldLightTree.Result worldLightTree = rebuildWorldLights
                    ? CpuWorldLightTree.build(
                            WorldLightTreeInput.capture(
                                    finalClusters,
                                    nextOriginX,
                                    nextOriginY,
                                    nextOriginZ))
                    : this.currentWorldLightTree;
            if (requiresWorldLightUpload(rebuildWorldLights, worldLightTree)) {
                if (worldStagingBatch == null || commandBuffer == null) {
                    throw new IllegalStateException("World light tree requires an upload batch");
                }
                int[] packedWorldLights = worldLightTree.pack();
                replacementWorldLights = this.context.createBuffer(
                        (long) packedWorldLights.length * Integer.BYTES,
                        VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                        false,
                        "Prime world light tree");
                StagingArena.Slice worldLightSlice = worldStagingBatch.write(
                        packedWorldLights, 16L);
                copyBuffer(commandBuffer, worldLightSlice, replacementWorldLights);
            }

            if (!replacements.isEmpty() || replacementWorldLights != null) {
                boolean hasOpacityMicromapBuild = replacements.stream()
                        .anyMatch(GpuCluster::hasOpacityMicromapBuild);
                memoryBarrier(
                        commandBuffer,
                        VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                        KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR
                                | (hasOpacityMicromapBuild
                                        ? EXTOpacityMicromap.VK_PIPELINE_STAGE_2_MICROMAP_BUILD_BIT_EXT
                                        : 0L)
                                | KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                        KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR
                                | (hasOpacityMicromapBuild
                                        ? EXTOpacityMicromap.VK_ACCESS_2_MICROMAP_READ_BIT_EXT
                                        : 0L)
                                | VK12.VK_ACCESS_SHADER_READ_BIT);
                if (hasOpacityMicromapBuild) {
                    for (GpuCluster cluster : replacements) {
                        cluster.recordOpacityMicromapBuild(commandBuffer);
                    }
                    // EXT micromap construction and BLAS construction are distinct device
                    // operations. The BLAS is allowed to consume the micromap only after its
                    // implementation-owned data is visible; this dependency must remain even
                    // though both commands currently share one transient command buffer.
                    memoryBarrier(
                            commandBuffer,
                            EXTOpacityMicromap.VK_PIPELINE_STAGE_2_MICROMAP_BUILD_BIT_EXT,
                            EXTOpacityMicromap.VK_ACCESS_2_MICROMAP_WRITE_BIT_EXT,
                            KHRAccelerationStructure
                                    .VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                            EXTOpacityMicromap.VK_ACCESS_2_MICROMAP_READ_BIT_EXT);
                }
                for (GpuCluster cluster : replacements) {
                    cluster.recordBuild(commandBuffer);
                }
                if (!replacements.isEmpty()) {
                    memoryBarrier(
                            commandBuffer,
                            KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                            KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                            KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                            KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
                }
            }

            if (replacementTlas != null) {
                VulkanBuffer effectiveWorldLights = rebuildWorldLights
                        ? replacementWorldLights
                        : this.currentWorldLights;
                populateTlas(
                        replacementTlas,
                        finalInstanceCount,
                        finalClusters,
                        Map.of(),
                        effectiveWorldLights,
                        worldLightTree,
                        nextOriginX,
                        nextOriginY,
                        nextOriginZ);
                memoryBarrier(
                        commandBuffer,
                        VK12.VK_PIPELINE_STAGE_HOST_BIT,
                        VK12.VK_ACCESS_HOST_WRITE_BIT,
                        KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR
                                | KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                        KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR
                                | VK12.VK_ACCESS_SHADER_READ_BIT);
                replacementTlas.recordBuild(commandBuffer);
                memoryBarrier(
                        commandBuffer,
                        KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                        KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                        KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                        KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
            }

            PreparedUpdate preparedUpdate = this.prepareUpdate(
                    finalClusters,
                    removedKeys,
                    replacementTlas,
                    replacementWorldLights,
                    worldLightTree,
                    rebuildWorldLights,
                    invalidateTemporalHistory,
                    occluderChanges,
                    nextOriginX,
                    nextOriginY,
                    nextOriginZ);
            if (commandBuffer != null) {
                this.context.device().instance().debug().endDebugGroup(commandBuffer);
                VulkanContext.check(VK12.vkEndCommandBuffer(commandBuffer), "end Prime terrain command buffer");
                if (clusterStagingBatch != null) {
                    clusterStagingBatch.prepareForSubmission();
                }
                if (worldStagingBatch != null) {
                    worldStagingBatch.prepareForSubmission();
                }
                this.context.commandEncoder().execute(commandBuffer);
                submitted = true;
                RuntimeException stagingFailure = null;
                if (clusterStagingBatch != null) {
                    stagingFailure = ResourceCleanup.run(
                            clusterStagingBatch::submitted, null);
                }
                if (worldStagingBatch != null) {
                    stagingFailure = ResourceCleanup.run(
                            worldStagingBatch::submitted, stagingFailure);
                }
                ResourceCleanup.throwIfFailed(stagingFailure);
            }
            this.publish(preparedUpdate);
            ownershipTransferred = true;
            replacementTlas = null;
            replacementWorldLights = null;
            RuntimeException retirementFailure = null;
            for (GpuCluster replacement : replacements) {
                retirementFailure = ResourceCleanup.run(
                        replacement::submitted, retirementFailure);
                retirementFailure = ResourceCleanup.run(
                        () -> this.compactionScheduler.register(replacement),
                        retirementFailure);
            }
            for (GpuCluster retired : preparedUpdate.retired()) {
                this.compactionScheduler.unregister(retired);
            }
            retirementFailure = this.retire(preparedUpdate, retirementFailure);
            ResourceCleanup.throwIfFailed(retirementFailure);
            return true;
        } catch (RuntimeException exception) {
            RuntimeException failure = exception;
            if (!ownershipTransferred) {
                for (GpuCluster replacement : replacements) {
                    if (submitted) {
                        failure = ResourceCleanup.run(
                                () -> this.context.defer(replacement::destroyAllResources),
                                failure);
                    } else {
                        failure = ResourceCleanup.run(
                                replacement::destroyAllResources, failure);
                    }
                }
                if (replacementTlas != null) {
                    if (submitted) {
                        TopLevelAccelerationStructure failedTlas = replacementTlas;
                        failure = ResourceCleanup.run(
                                () -> this.context.defer(failedTlas::release), failure);
                    } else {
                        failure = ResourceCleanup.run(replacementTlas::release, failure);
                    }
                }
                if (replacementWorldLights != null) {
                    if (submitted) {
                        VulkanBuffer failedWorldLights = replacementWorldLights;
                        failure = ResourceCleanup.run(
                                () -> this.context.defer(failedWorldLights), failure);
                    } else {
                        failure = ResourceCleanup.destroy(replacementWorldLights, failure);
                    }
                }
            }
            failure = ResourceCleanup.close(clusterStagingBatch, failure);
            failure = ResourceCleanup.close(worldStagingBatch, failure);
            cleanupHandled = true;
            throw failure;
        } finally {
            if (!cleanupHandled) {
                RuntimeException failure = null;
                failure = ResourceCleanup.close(clusterStagingBatch, failure);
                failure = ResourceCleanup.close(worldStagingBatch, failure);
                ResourceCleanup.throwIfFailed(failure);
            }
        }
    }

    /** Advances ready static BLAS compactions after uploads have had first access to frame resources. */
    void advanceCompactions() {
        if (this.currentTlas == null
                || this.resident.isEmpty()
                || !this.compactionScheduler.hasReadyWork()) {
            return;
        }

        int instanceCount = 0;
        for (GpuCluster cluster : this.resident.values()) {
            instanceCount = Math.addExact(instanceCount, cluster.tlasInstanceCount());
        }
        TopLevelAccelerationStructure replacementTlas =
                this.acquireCompactionTlas(instanceCount);
        if (replacementTlas == null) {
            return;
        }

        BlasCompactionScheduler.Batch batch = null;
        boolean submitted = false;
        boolean ownershipTransferred = false;
        try {
            batch = this.compactionScheduler.prepareBatch();
            if (batch.isEmpty()) {
                replacementTlas.release();
                replacementTlas = null;
                return;
            }

            LongOpenHashSet noRemovedKeys = new LongOpenHashSet();
            List<GpuCluster> finalClusters = this.buildFinalClusterList(
                    noRemovedKeys, List.of(), this.resident.size());
            IdentityHashMap<PreparedBlas, PreparedBlas.Compaction> replacements =
                    new IdentityHashMap<>(batch.compactions().size());
            for (PreparedBlas.Compaction compaction : batch.compactions()) {
                if (replacements.put(compaction.owner(), compaction) != null) {
                    throw new IllegalStateException(
                            "A BLAS was selected for compaction more than once");
                }
            }

            VkCommandBuffer commandBuffer =
                    this.context.commandEncoder().allocateAndBeginTransientCommandBuffer();
            this.context.device().instance().debug().beginDebugGroup(
                    commandBuffer, () -> "Prime BLAS compaction");
            for (PreparedBlas.Compaction compaction : batch.compactions()) {
                compaction.recordCopy(commandBuffer);
            }
            memoryBarrier(
                    commandBuffer,
                    KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                    KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
            populateTlas(
                    replacementTlas,
                    instanceCount,
                    finalClusters,
                    replacements,
                    this.currentWorldLights,
                    this.currentWorldLightTree,
                    this.originX,
                    this.originY,
                    this.originZ);
            memoryBarrier(
                    commandBuffer,
                    VK12.VK_PIPELINE_STAGE_HOST_BIT,
                    VK12.VK_ACCESS_HOST_WRITE_BIT,
                    KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR
                            | KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR
                            | VK12.VK_ACCESS_SHADER_READ_BIT);
            replacementTlas.recordBuild(commandBuffer);
            memoryBarrier(
                    commandBuffer,
                    KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);

            // Address-only publication reuses lighting and preserves temporal/occluder identity.
            PreparedUpdate preparedUpdate = this.prepareUpdate(
                    finalClusters,
                    noRemovedKeys,
                    replacementTlas,
                    null,
                    this.currentWorldLightTree,
                    false,
                    false,
                    List.of(),
                    this.originX,
                    this.originY,
                    this.originZ);
            for (PreparedBlas.Compaction compaction : batch.compactions()) {
                compaction.requirePublishable();
            }
            this.context.device().instance().debug().endDebugGroup(commandBuffer);
            VulkanContext.check(
                    VK12.vkEndCommandBuffer(commandBuffer),
                    "end Prime BLAS compaction command buffer");
            this.context.commandEncoder().execute(commandBuffer);
            submitted = true;

            for (PreparedBlas.Compaction compaction : batch.compactions()) {
                compaction.publish();
            }
            this.publish(preparedUpdate);
            batch.commitPublished();
            ownershipTransferred = true;
            replacementTlas = null;

            RuntimeException retirementFailure = null;
            for (PreparedBlas.Compaction compaction : batch.compactions()) {
                retirementFailure = ResourceCleanup.run(
                        compaction::retireSource, retirementFailure);
            }
            retirementFailure = this.retire(preparedUpdate, retirementFailure);
            ResourceCleanup.throwIfFailed(retirementFailure);
        } catch (RuntimeException exception) {
            RuntimeException failure = exception;
            if (!ownershipTransferred) {
                if (batch != null) {
                    if (submitted) {
                        failure = ResourceCleanup.run(
                                batch::abandonAfterSubmission, failure);
                    } else {
                        failure = ResourceCleanup.close(batch, failure);
                    }
                }
                if (replacementTlas != null) {
                    if (submitted) {
                        TopLevelAccelerationStructure failedTlas = replacementTlas;
                        failure = ResourceCleanup.run(
                                () -> this.context.defer(failedTlas::release), failure);
                    } else {
                        failure = ResourceCleanup.run(replacementTlas::release, failure);
                    }
                }
            }
            throw failure;
        } finally {
            if (ownershipTransferred) {
                ResourceCleanup.close(batch, null);
            }
        }
    }

    public ResidentSceneView residentView() {
        return this.currentView;
    }

    public CompactionStats compactionStats() {
        BlasCompactionScheduler.Snapshot snapshot =
                this.compactionScheduler.snapshot();
        return new CompactionStats(
                snapshot.waiting(),
                snapshot.ready(),
                snapshot.retiring(),
                snapshot.reservedTargetBytes(),
                snapshot.highWaterTargetBytes(),
                snapshot.reclaimedBytes(),
                snapshot.completedCount());
    }

    static boolean requiresWorldLightUpload(
            boolean rebuildWorldLights, CpuWorldLightTree.Result worldLightTree) {
        return rebuildWorldLights && !worldLightTree.isEmpty();
    }

    public boolean contains(long key) {
        return this.resident.containsKey(key);
    }

    /** Marks every temporal consumer as unrelated to its previous world. */
    void beginUnrelatedWorld() {
        this.resetRevision++;
        this.temporalRevision++;
    }

    public int residentCount() {
        return this.resident.size();
    }

    public long[] residentKeys() {
        return this.resident.keySet().toLongArray();
    }

    @Override
    public void close() {
        RuntimeException failure = ResourceCleanup.close(this.compactionScheduler, null);
        for (GpuCluster cluster : this.resident.values()) {
            failure = ResourceCleanup.run(cluster::destroy, failure);
        }
        this.resident.clear();
        for (TopLevelAccelerationStructure slot : this.tlasSlots) {
            failure = ResourceCleanup.run(slot::destroy, failure);
        }
        this.tlasSlots.clear();
        this.currentTlas = null;
        this.currentView = null;
        if (this.currentWorldLights != null) {
            failure = ResourceCleanup.destroy(this.currentWorldLights, failure);
            this.currentWorldLights = null;
        }
        this.currentWorldLightTree = CpuWorldLightTree.Result.empty(0);
        ResourceCleanup.throwIfFailed(failure);
    }

    private static LongOpenHashSet removedKeys(
            List<CompiledCluster> uploads, long[] evictions) {
        LongOpenHashSet result = new LongOpenHashSet(evictions);
        LongOpenHashSet uploadKeys = new LongOpenHashSet();
        for (CompiledCluster upload : uploads) {
            if (!uploadKeys.add(upload.key())) {
                throw new IllegalArgumentException(
                        "A logical cluster was replaced more than once in one update");
            }
            result.add(upload.key());
        }
        return result;
    }

    private int estimateFinalClusterCount(
            List<CompiledCluster> uploads, LongOpenHashSet removedKeys) {
        int count = this.resident.size();
        for (long key : removedKeys) {
            if (this.resident.containsKey(key)) {
                count--;
            }
        }
        for (CompiledCluster upload : uploads) {
            if (!upload.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private int estimateFinalInstanceCount(
            List<CompiledCluster> uploads, LongOpenHashSet removedKeys) {
        int count = 0;
        for (GpuCluster cluster : this.resident.values()) {
            if (!removedKeys.contains(cluster.key())) {
                count = Math.addExact(count, cluster.tlasInstanceCount());
            }
        }
        for (CompiledCluster upload : uploads) {
            if (!upload.isEmpty()) {
                count = Math.addExact(
                        count,
                        Math.addExact(1, upload.mesh().voxelInstances().count()));
            }
        }
        return count;
    }

    private boolean hasActualContentChange(List<CompiledCluster> uploads, long[] evictions) {
        for (long key : evictions) {
            if (this.resident.containsKey(key)) {
                return true;
            }
        }
        for (CompiledCluster upload : uploads) {
            if (!upload.isEmpty() || this.resident.containsKey(upload.key())) {
                return true;
            }
        }
        return false;
    }

    private List<TerrainOccluderChange> occluderChanges(
            List<CompiledCluster> uploads, long[] evictions) {
        LongOpenHashSet changedKeys = new LongOpenHashSet();
        for (long key : evictions) {
            if (key != CompiledCluster.DYNAMIC_KEY
                    && this.resident.containsKey(key)) {
                changedKeys.add(key);
            }
        }
        for (CompiledCluster upload : uploads) {
            if (!upload.dynamic()
                    && (!upload.isEmpty()
                            || this.resident.containsKey(upload.key()))) {
                changedKeys.add(upload.key());
            }
        }
        List<TerrainOccluderChange> changes = new ArrayList<>(changedKeys.size());
        for (long key : changedKeys) {
            int minimumX = SectionPos.x(key) << 4;
            int minimumY = SectionPos.y(key) << 4;
            int minimumZ = SectionPos.z(key) << 4;
            int clusterBlockSize = SectionCluster.SECTION_SIZE << 4;
            changes.add(new TerrainOccluderChange(
                    minimumX,
                    minimumY,
                    minimumZ,
                    Math.addExact(minimumX, clusterBlockSize),
                    Math.addExact(minimumY, clusterBlockSize),
                    Math.addExact(minimumZ, clusterBlockSize)));
        }
        return List.copyOf(changes);
    }

    private boolean hasActualStaticContentChange(
            List<CompiledCluster> uploads, long[] evictions) {
        for (long key : evictions) {
            if (key != CompiledCluster.DYNAMIC_KEY && this.resident.containsKey(key)) {
                return true;
            }
        }
        for (CompiledCluster upload : uploads) {
            if (!upload.dynamic()
                    && (!upload.isEmpty() || this.resident.containsKey(upload.key()))) {
                return true;
            }
        }
        return false;
    }

    private List<GpuCluster> buildFinalClusterList(
            LongOpenHashSet removedKeys,
            List<GpuCluster> replacements,
            int finalClusterCount) {
        List<GpuCluster> result = new ArrayList<>(finalClusterCount);
        for (GpuCluster cluster : this.resident.values()) {
            if (!removedKeys.contains(cluster.key())) {
                result.add(cluster);
            }
        }
        result.addAll(replacements);
        result.sort(Comparator.comparingLong(GpuCluster::key));
        return result;
    }

    private static void populateTlas(
            TopLevelAccelerationStructure tlas,
            int instanceCount,
            List<GpuCluster> clusters,
            Map<PreparedBlas, PreparedBlas.Compaction> compactions,
            VulkanBuffer worldLights,
            CpuWorldLightTree.Result worldLightTree,
            int originX,
            int originY,
            int originZ) {
        long worldLightAddress = worldLights == null
                ? 0L
                : worldLights.deviceAddress();
        long worldLightForwardAddress = worldLights == null
                ? 0L
                : worldLights.deviceAddress() + worldLightTree.forwardByteOffset();
        int worldLightNodeCount = worldLights == null
                ? 0
                : worldLightTree.nodeCount();
        tlas.populate(instanceCount, writer -> {
            for (int clusterIndex = 0; clusterIndex < clusters.size(); clusterIndex++) {
                GpuCluster cluster = clusters.get(clusterIndex);
                PreparedBlas base = cluster.baseBlas();
                float sectionX = (cluster.clusterX() << 4) - originX;
                float sectionY = (cluster.clusterY() << 4) - originY;
                float sectionZ = (cluster.clusterZ() << 4) - originZ;
                writer.writeInstanced(
                        compactionAddress(base, compactions),
                        base.primitives().deviceAddress(),
                        cluster.lightAddress(),
                        worldLightAddress,
                        worldLightForwardAddress,
                        base.cutoutPrimitiveBase(),
                        base.transmissivePrimitiveBase(),
                        base.opaqueMacroTriangleBase(),
                        base.cutoutMacroTriangleBase(),
                        base.transmissiveMacroTriangleBase(),
                        cluster.dynamic()
                                ? CpuLightTree.NO_INDEX
                                : worldLightTree.leafNode(clusterIndex),
                        cluster.lights().emitterCount(),
                        worldLightNodeCount,
                        cluster.blas() == null ? 0 : 0xff,
                        0,
                        sectionX,
                        sectionY,
                        sectionZ,
                        sectionX,
                        sectionY,
                        sectionZ);
            }
            for (int clusterIndex = 0; clusterIndex < clusters.size(); clusterIndex++) {
                GpuCluster cluster = clusters.get(clusterIndex);
                float sectionX = (cluster.clusterX() << 4) - originX;
                float sectionY = (cluster.clusterY() << 4) - originY;
                float sectionZ = (cluster.clusterZ() << 4) - originZ;
                CpuVoxelInstances instances = cluster.voxelInstances();
                for (int index = 0; index < instances.count(); index++) {
                    PreparedBlas voxel =
                            cluster.voxelBlases().get(instances.meshIndex(index));
                    writer.writeInstanced(
                            compactionAddress(voxel, compactions),
                            voxel.primitives().deviceAddress(),
                            cluster.lightAddress(),
                            worldLightAddress,
                            worldLightForwardAddress,
                            voxel.cutoutPrimitiveBase(),
                            voxel.transmissivePrimitiveBase(),
                            voxel.opaqueMacroTriangleBase(),
                            voxel.cutoutMacroTriangleBase(),
                            voxel.transmissiveMacroTriangleBase(),
                            cluster.dynamic()
                                    ? CpuLightTree.NO_INDEX
                                    : worldLightTree.leafNode(clusterIndex),
                            cluster.lights().emitterCount(),
                            worldLightNodeCount,
                            0xff,
                            0x8000_0000 | instances.packedTint(index),
                            sectionX + instances.translationX(index),
                            sectionY + instances.translationY(index),
                            sectionZ + instances.translationZ(index),
                            sectionX,
                            sectionY,
                            sectionZ);
                }
            }
        });
    }

    private static long compactionAddress(
            PreparedBlas blas,
            Map<PreparedBlas, PreparedBlas.Compaction> compactions) {
        PreparedBlas.Compaction compaction = compactions.get(blas);
        return compaction == null
                ? blas.accelerationStructure().deviceAddress()
                : compaction.targetDeviceAddress();
    }

    private PreparedUpdate prepareUpdate(
            List<GpuCluster> finalClusters,
            LongOpenHashSet removedKeys,
            TopLevelAccelerationStructure replacementTlas,
            VulkanBuffer replacementWorldLights,
            CpuWorldLightTree.Result replacementWorldLightTree,
            boolean replaceWorldLights,
            boolean invalidateTemporalHistory,
            List<TerrainOccluderChange> occluderChanges,
            int nextOriginX,
            int nextOriginY,
            int nextOriginZ) {
        List<GpuCluster> retired = new ArrayList<>();
        Long2ObjectOpenHashMap<GpuCluster> nextResident =
                new Long2ObjectOpenHashMap<>(finalClusters.size());
        for (var entry : this.resident.long2ObjectEntrySet()) {
            if (removedKeys.contains(entry.getLongKey())) {
                retired.add(entry.getValue());
            }
        }
        for (GpuCluster cluster : finalClusters) {
            if (nextResident.put(cluster.key(), cluster) != null) {
                throw new IllegalStateException(
                        "Prepared terrain scene contains a duplicate logical cluster");
            }
        }

        TopLevelAccelerationStructure previousTlas = this.currentTlas;
        VulkanBuffer previousWorldLights = replaceWorldLights ? this.currentWorldLights : null;
        long nextRevision = this.revision + 1L;
        long nextTemporalRevision = invalidateTemporalHistory
                ? this.temporalRevision + 1L
                : this.temporalRevision;
        long nextOccluderRevision = occluderChanges.isEmpty()
                ? this.occluderRevision
                : this.occluderRevision + 1L;
        ResidentSceneView nextView = replacementTlas == null || nextResident.isEmpty()
                ? null
                : new ResidentSceneView(
                        replacementTlas.handle(),
                        replacementTlas.sectionTableAddress(),
                        nextOriginX,
                        nextOriginY,
                        nextOriginZ,
                        nextRevision,
                        this.resetRevision,
                        nextTemporalRevision,
                        nextOccluderRevision,
                        occluderChanges);

        return new PreparedUpdate(
                nextResident,
                replacementTlas,
                replacementWorldLights,
                replacementWorldLightTree,
                replaceWorldLights,
                nextOriginX,
                nextOriginY,
                nextOriginZ,
                nextRevision,
                nextTemporalRevision,
                nextOccluderRevision,
                nextView,
                retired,
                previousTlas,
                previousWorldLights);
    }

    /** Publishes a fully allocated scene state; this path must remain allocation- and I/O-free. */
    private void publish(PreparedUpdate update) {
        this.resident = update.resident();
        this.currentTlas = update.tlas();
        if (update.replaceWorldLights()) {
            this.currentWorldLights = update.worldLights();
            this.currentWorldLightTree = update.worldLightTree();
        }
        this.originX = update.originX();
        this.originY = update.originY();
        this.originZ = update.originZ();
        this.revision = update.revision();
        this.temporalRevision = update.temporalRevision();
        this.occluderRevision = update.occluderRevision();
        this.currentView = update.view();
    }

    private RuntimeException retire(
            PreparedUpdate update, RuntimeException retirementFailure) {
        for (GpuCluster removed : update.retired()) {
            retirementFailure = ResourceCleanup.run(
                    () -> this.context.defer(removed::destroy), retirementFailure);
        }
        if (update.previousTlas() != null) {
            retirementFailure = ResourceCleanup.run(
                    () -> this.context.defer(update.previousTlas()::release),
                    retirementFailure);
        }
        if (update.previousWorldLights() != null) {
            retirementFailure = ResourceCleanup.run(
                    () -> this.context.defer(update.previousWorldLights()),
                    retirementFailure);
        }
        return retirementFailure;
    }

    private TopLevelAccelerationStructure acquireTlas(int capacity) {
        for (int index = 0; index < this.tlasSlots.size(); index++) {
            TopLevelAccelerationStructure slot = this.tlasSlots.get(index);
            if (slot == this.currentTlas || !slot.tryAcquire()) {
                continue;
            }
            if (slot.hasCapacity(capacity)) {
                return slot;
            }
            slot.destroy();
            TopLevelAccelerationStructure replacement = TopLevelAccelerationStructure.create(
                    this.context, capacity, "Prime TLAS slot " + index);
            if (!replacement.tryAcquire()) {
                throw new IllegalStateException("New TLAS slot was unexpectedly busy");
            }
            this.tlasSlots.set(index, replacement);
            return replacement;
        }
        if (this.tlasSlots.size() >= TLAS_SLOT_COUNT) {
            return null;
        }
        int index = this.tlasSlots.size();
        TopLevelAccelerationStructure slot = TopLevelAccelerationStructure.create(
                this.context, capacity, "Prime TLAS slot " + index);
        if (!slot.tryAcquire()) {
            throw new IllegalStateException("New TLAS slot was unexpectedly busy");
        }
        this.tlasSlots.add(slot);
        return slot;
    }

    private TopLevelAccelerationStructure acquireCompactionTlas(int capacity) {
        TopLevelAccelerationStructure replacement = this.acquireTlas(capacity);
        if (replacement == null) {
            return null;
        }
        /*
         * Dynamic capture occurs later in the frame and has the same immediate-publication
         * priority as terrain uploads. Prove that one additional TLAS slot is available before
         * compact targets consume memory; no other render-thread work can claim it in between.
         */
        TopLevelAccelerationStructure dynamicReserve;
        try {
            dynamicReserve = this.acquireTlas(capacity);
        } catch (RuntimeException exception) {
            replacement.release();
            throw exception;
        }
        if (dynamicReserve == null) {
            replacement.release();
            return null;
        }
        dynamicReserve.release();
        return replacement;
    }

    private GpuCluster prepareCluster(
            CompiledCluster upload,
            StagingArena.Batch stagingBatch,
            VkCommandBuffer commandBuffer) {
        CpuClusterMesh mesh = upload.mesh();
        PreparedBlas.CompactionPolicy compactionPolicy =
                compactionPolicy(upload.dynamic());
        VulkanBuffer positions = null;
        VulkanBuffer primitives = null;
        VulkanBuffer lights = null;
        PreparedBlas blas = null;
        ArrayList<PreparedBlas> voxelBlases =
                new ArrayList<>(mesh.voxelMeshes().size());
        try {
            if (mesh.triangleCount() != 0L) {
                positions = this.context.createBuffer(
                        mesh.positionBytes(),
                        VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                                | KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                        false,
                        "Prime cluster " + upload.key() + " positions");
                primitives = this.context.createBuffer(
                        mesh.primitiveBytes(),
                        VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                                | VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                        false,
                        "Prime cluster " + upload.key() + " primitives");
                copyMeshSegments(
                        commandBuffer, stagingBatch, mesh, positions, primitives);
                blas = PreparedBlas.create(
                        this.context,
                        positions,
                        primitives,
                        mesh.opacityMicromap(),
                        stagingBatch,
                        commandBuffer,
                        mesh.opaqueTriangleCount(),
                        mesh.cutoutTriangleCount(),
                        mesh.transmissiveTriangleCount(),
                        mesh.opaqueMacroTriangleCount(),
                        mesh.cutoutMacroTriangleCount(),
                        mesh.transmissiveMacroTriangleCount(),
                        compactionPolicy,
                        "Prime cluster " + upload.key() + " BLAS");
            }
            if (!mesh.lights().isEmpty()) {
                lights = this.context.createBuffer(
                        mesh.lights().byteSize(),
                        VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                        false,
                        "Prime cluster " + upload.key() + " lights");
            }
            if (lights != null) {
                copyBuffer(
                        commandBuffer,
                        stagingBatch.write(mesh.lights().relocate(lights.deviceAddress()), 16L),
                        lights);
            }
            CompiledClusterLights.Summary lightSummary = mesh.lights().summary();
            for (int index = 0; index < mesh.voxelMeshes().size(); index++) {
                voxelBlases.add(this.prepareVoxelMesh(
                        mesh.voxelMeshes().get(index),
                        stagingBatch,
                        commandBuffer,
                        compactionPolicy,
                        "Prime cluster " + upload.key()
                                + " voxel mesh " + index));
            }
            return new GpuCluster(
                    upload.key(),
                    upload.clusterX(),
                    upload.clusterY(),
                    upload.clusterZ(),
                    blas,
                    voxelBlases,
                    mesh.voxelInstances(),
                    lights,
                    lightSummary,
                    upload.dynamic());
        } catch (RuntimeException exception) {
            RuntimeException failure = exception;
            if (blas != null) {
                failure = ResourceCleanup.run(blas::destroyAllResources, failure);
            } else {
                failure = ResourceCleanup.destroy(positions, failure);
                failure = ResourceCleanup.destroy(primitives, failure);
            }
            for (PreparedBlas voxelBlas : voxelBlases) {
                failure = ResourceCleanup.run(
                        voxelBlas::destroyAllResources, failure);
            }
            failure = ResourceCleanup.destroy(lights, failure);
            throw failure;
        }
    }

    private PreparedBlas prepareVoxelMesh(
            CpuVoxelMesh mesh,
            StagingArena.Batch stagingBatch,
            VkCommandBuffer commandBuffer,
            PreparedBlas.CompactionPolicy compactionPolicy,
            String label) {
        VulkanBuffer positions = null;
        VulkanBuffer primitives = null;
        PreparedBlas blas = null;
        try {
            positions = this.context.createBuffer(
                    mesh.positionBytes(),
                    VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                            | KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                    false,
                    label + " positions");
            primitives = this.context.createBuffer(
                    mesh.primitiveBytes(),
                    VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                            | VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    false,
                    label + " primitives");
            copyBuffer(
                    commandBuffer,
                    stagingBatch.write(mesh.positions(), Float.BYTES),
                    positions);
            copyBuffer(
                    commandBuffer,
                    stagingBatch.write(mesh.primitiveRecords(), Integer.BYTES),
                    primitives);
            blas = PreparedBlas.create(
                    this.context,
                    positions,
                    primitives,
                    mesh.opacityMicromap(),
                    stagingBatch,
                    commandBuffer,
                    mesh.opaqueTriangleCount(),
                    mesh.cutoutTriangleCount(),
                    mesh.transmissiveTriangleCount(),
                    compactionPolicy,
                    label + " BLAS");
            return blas;
        } catch (RuntimeException exception) {
            RuntimeException failure = exception;
            if (blas != null) {
                failure = ResourceCleanup.run(
                        blas::destroyAllResources, failure);
            } else {
                failure = ResourceCleanup.destroy(positions, failure);
                failure = ResourceCleanup.destroy(primitives, failure);
            }
            throw failure;
        }
    }

    private static void copyMeshSegments(
            VkCommandBuffer commandBuffer,
            StagingArena.Batch staging,
            CpuClusterMesh mesh,
            VulkanBuffer positions,
            VulkanBuffer primitives) {
        long[] positionCursors = new long[] {
            0L,
            Math.multiplyExact(mesh.opaqueTriangleCount(), 9L * Float.BYTES),
            Math.multiplyExact(
                    Math.addExact(mesh.opaqueTriangleCount(), mesh.cutoutTriangleCount()),
                    9L * Float.BYTES)
        };
        long[] primitiveCursors = new long[] {
            0L,
            Math.multiplyExact(
                    mesh.opaquePrimitiveCount(),
                    (long) CpuSectionMesh.PRIMITIVE_WORDS * Integer.BYTES),
            Math.multiplyExact(
                    Math.addExact(mesh.opaquePrimitiveCount(), mesh.cutoutPrimitiveCount()),
                    (long) CpuSectionMesh.PRIMITIVE_WORDS * Integer.BYTES)
        };
        for (CpuClusterMesh.Segment segment : mesh.segments()) {
            int sourcePosition = 0;
            int sourcePrimitive = 0;
            for (int category = 0; category < 3; category++) {
                int triangleCount = switch (category) {
                    case 0 -> segment.opaqueTriangleCount();
                    case 1 -> segment.cutoutTriangleCount();
                    default -> segment.transmissiveTriangleCount();
                };
                int primitiveCount = switch (category) {
                    case 0 -> segment.opaquePrimitiveCount();
                    case 1 -> segment.cutoutPrimitiveCount();
                    default -> segment.transmissivePrimitiveCount();
                };
                int positionWords = Math.multiplyExact(triangleCount, 9);
                int primitiveWords = Math.multiplyExact(
                        primitiveCount, CpuSectionMesh.PRIMITIVE_WORDS);
                if (triangleCount != 0) {
                    StagingArena.Slice positionSlice = staging.write(
                            segment.positions(), sourcePosition, positionWords, Float.BYTES);
                    copyBuffer(
                            commandBuffer,
                            positionSlice,
                            positions,
                            positionCursors[category]);
                    StagingArena.Slice primitiveSlice = staging.write(
                            segment.primitiveRecords(),
                            sourcePrimitive,
                            primitiveWords,
                            Integer.BYTES);
                    copyBuffer(
                            commandBuffer,
                            primitiveSlice,
                            primitives,
                            primitiveCursors[category]);
                }
                sourcePosition += positionWords;
                sourcePrimitive += primitiveWords;
                positionCursors[category] += (long) positionWords * Float.BYTES;
                primitiveCursors[category] += (long) primitiveWords * Integer.BYTES;
            }
        }
    }

    private static void copyBuffer(VkCommandBuffer commandBuffer, StagingArena.Slice source, VulkanBuffer destination) {
        copyBuffer(commandBuffer, source, destination, 0L);
    }

    private static void copyBuffer(
            VkCommandBuffer commandBuffer,
            StagingArena.Slice source,
            VulkanBuffer destination,
            long destinationOffset) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack)
                    .srcOffset(source.offset())
                    .dstOffset(destinationOffset)
                    .size(source.size());
            VK12.vkCmdCopyBuffer(commandBuffer, source.buffer(), destination.handle(), copy);
        }
    }

    private static void memoryBarrier(
            VkCommandBuffer commandBuffer,
            long sourceStage,
            long sourceAccess,
            long destinationStage,
            long destinationAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack);
            barrier.get(0)
                    .sType$Default()
                    .srcStageMask(sourceStage)
                    .srcAccessMask(sourceAccess)
                    .dstStageMask(destinationStage)
                    .dstAccessMask(destinationAccess);
            VkDependencyInfo dependency = VkDependencyInfo.calloc(stack)
                    .sType$Default()
                    .pMemoryBarriers(barrier);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer, dependency);
        }
    }

    static boolean invalidatesTemporalHistory(boolean needsRebase) {
        return needsRebase;
    }

    static PreparedBlas.CompactionPolicy compactionPolicy(boolean dynamic) {
        return dynamic
                ? PreparedBlas.CompactionPolicy.DISABLED
                : PreparedBlas.CompactionPolicy.ENABLED;
    }

    private record PreparedUpdate(
            Long2ObjectOpenHashMap<GpuCluster> resident,
            TopLevelAccelerationStructure tlas,
            VulkanBuffer worldLights,
            CpuWorldLightTree.Result worldLightTree,
            boolean replaceWorldLights,
            int originX,
            int originY,
            int originZ,
            long revision,
            long temporalRevision,
            long occluderRevision,
            ResidentSceneView view,
            List<GpuCluster> retired,
            TopLevelAccelerationStructure previousTlas,
            VulkanBuffer previousWorldLights) {
    }

    /** Immutable GPU-resident scene identity consumed by one or more frame plans. */
    public record ResidentSceneView(
            long tlas,
            long sectionTableAddress,
            int originX,
            int originY,
            int originZ,
            long revision,
            long resetRevision,
            long temporalRevision,
            long occluderRevision,
            List<TerrainOccluderChange> occluderChanges) {
        public ResidentSceneView {
            occluderChanges = List.copyOf(occluderChanges);
        }

        public ResidentSceneView(
                long tlas,
                long sectionTableAddress,
                int originX,
                int originY,
                int originZ,
                long revision,
                long resetRevision,
                long temporalRevision) {
            this(
                    tlas,
                    sectionTableAddress,
                    originX,
                    originY,
                    originZ,
                    revision,
                    resetRevision,
                    temporalRevision,
                    revision,
                    List.of());
        }
    }

    public record CompactionStats(
            int waiting,
            int ready,
            int retiring,
            long reservedTargetBytes,
            long highWaterTargetBytes,
            long reclaimedBytes,
            long completedCount) {}
}
