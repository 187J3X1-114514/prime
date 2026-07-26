package dev.prime.render.terrain;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import dev.prime.render.ResourceCleanup;
import dev.prime.render.vulkan.PreparedBlas;
import dev.prime.render.vulkan.StagingArena;
import dev.prime.render.vulkan.TopLevelAccelerationStructure;
import dev.prime.render.vulkan.VulkanBuffer;
import dev.prime.render.vulkan.VulkanContext;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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
    private Long2ObjectOpenHashMap<GpuCluster> resident = new Long2ObjectOpenHashMap<>();
    private final List<TopLevelAccelerationStructure> tlasSlots = new ArrayList<>(TLAS_SLOT_COUNT);
    private final CpuWorldLightTree worldLightHistory =
            new CpuWorldLightTree();
    private TopLevelAccelerationStructure currentTlas;
    private VulkanBuffer currentWorldLights;
    private ResidentSceneView currentView;
    private int originX;
    private int originY;
    private int originZ;
    private long revision;
    private long resetRevision;
    private long temporalRevision;

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
        LongOpenHashSet removedKeys = removedKeys(uploads, evictions);
        boolean hasReadyCompaction = this.hasReadyCompaction(removedKeys);
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
        if (!contentChanged && !needsRebase && !hasReadyCompaction) {
            return true;
        }
        int finalClusterCount = this.estimateFinalClusterCount(uploads, removedKeys);
        TopLevelAccelerationStructure replacementTlas = null;
        if (finalClusterCount > 0) {
            replacementTlas = this.acquireTlas(finalClusterCount);
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
        boolean rebuildWorldLights = contentChanged || needsRebase;
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
        List<PreparedBlas.Compaction> compactions = new ArrayList<>();
        Map<PreparedBlas, PreparedBlas.Compaction> compactionByBlas = new IdentityHashMap<>();
        VulkanBuffer replacementWorldLights = null;
        VkCommandBuffer commandBuffer = null;
        boolean submitted = false;
        boolean ownershipTransferred = false;
        boolean cleanupHandled = false;
        try {
            if (hasReadyCompaction) {
                for (GpuCluster cluster : this.resident.values()) {
                    if (removedKeys.contains(cluster.key())) {
                        continue;
                    }
                    PreparedBlas.Compaction compaction = cluster.blas().prepareCompaction();
                    if (compaction != null) {
                        compactions.add(compaction);
                        compactionByBlas.put(cluster.blas(), compaction);
                    }
                }
                if (compactions.isEmpty() && !contentChanged && !needsRebase) {
                    if (replacementTlas != null) {
                        replacementTlas.release();
                        replacementTlas = null;
                    }
                    return true;
                }
            }
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
                    ? this.worldLightHistory.update(
                            WorldLightTreeInput.capture(
                                    finalClusters,
                                    nextOriginX,
                                    nextOriginY,
                                    nextOriginZ))
                    : this.worldLightHistory.result();
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
                        .anyMatch(cluster -> cluster.blas().hasOpacityMicromapBuild());
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
                        cluster.blas().recordOpacityMicromapBuild(commandBuffer);
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
                    cluster.blas().recordBuild(commandBuffer);
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

            for (PreparedBlas.Compaction compaction : compactions) {
                compaction.recordCopy(commandBuffer);
            }
            if (!compactions.isEmpty()) {
                // A compact BLAS is a new acceleration structure, not an in-place allocation.
                // Make every GPU copy visible before the replacement TLAS reads its device address;
                // the old BLAS remains alive until this submission's real timeline point retires.
                memoryBarrier(
                        commandBuffer,
                        KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                        KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                        KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                        KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
            }

            if (replacementTlas != null) {
                VulkanBuffer effectiveWorldLights = rebuildWorldLights
                        ? replacementWorldLights
                        : this.currentWorldLights;
                long worldLightAddress = effectiveWorldLights == null
                        ? 0L
                        : effectiveWorldLights.deviceAddress();
                long worldLightForwardAddress = effectiveWorldLights == null
                        ? 0L
                        : effectiveWorldLights.deviceAddress() + worldLightTree.forwardByteOffset();
                int worldLightNodeCount = effectiveWorldLights == null
                        ? 0
                        : worldLightTree.nodeCount();
                replacementTlas.populate(finalClusters.size(), writer -> {
                    for (int clusterIndex = 0; clusterIndex < finalClusters.size(); clusterIndex++) {
                        GpuCluster cluster = finalClusters.get(clusterIndex);
                        PreparedBlas.Compaction compaction = compactionByBlas.get(cluster.blas());
                        writer.write(
                                compaction == null
                                        ? cluster.blas().accelerationStructure().deviceAddress()
                                        : compaction.targetDeviceAddress(),
                                cluster.blas().primitives().deviceAddress(),
                                cluster.lightAddress(),
                                worldLightAddress,
                                worldLightForwardAddress,
                                cluster.blas().opaqueTriangleCount(),
                                cluster.blas().cutoutTriangleCount(),
                                worldLightTree.leafNode(clusterIndex),
                                cluster.lights().emitterCount(),
                                worldLightNodeCount,
                                (cluster.clusterX() << 4) - nextOriginX,
                                (cluster.clusterY() << 4) - nextOriginY,
                                (cluster.clusterZ() << 4) - nextOriginZ);
                    }
                });
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

            if (commandBuffer != null) {
                this.context.device().instance().debug().endDebugGroup(commandBuffer);
                VulkanContext.check(VK12.vkEndCommandBuffer(commandBuffer), "end Prime terrain command buffer");
                if (clusterStagingBatch != null) {
                    clusterStagingBatch.submitForRetirement();
                }
                if (worldStagingBatch != null) {
                    worldStagingBatch.submitForRetirement();
                }
                this.context.commandEncoder().execute(commandBuffer);
                submitted = true;
            }
            for (GpuCluster replacement : replacements) {
                replacement.blas().onBuildSubmitted();
                replacement.blas().retireBuildResources();
            }
            for (PreparedBlas.Compaction compaction : compactions) {
                compaction.commit();
            }
            RuntimeException retirementFailure = this.commit(
                    uploads,
                    removedKeys,
                    replacements,
                    replacementTlas,
                    replacementWorldLights,
                    rebuildWorldLights,
                    invalidateTemporalHistory,
                    nextOriginX,
                    nextOriginY,
                    nextOriginZ);
            ownershipTransferred = true;
            replacementTlas = null;
            replacementWorldLights = null;
            ResourceCleanup.throwIfFailed(retirementFailure);
            return true;
        } catch (RuntimeException exception) {
            RuntimeException failure = exception;
            if (!ownershipTransferred) {
                if (submitted) {
                    for (PreparedBlas.Compaction compaction : compactions) {
                        failure = ResourceCleanup.run(
                                compaction::abandonAfterSubmission, failure);
                    }
                }
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
            for (PreparedBlas.Compaction compaction : compactions) {
                failure = ResourceCleanup.close(compaction, failure);
            }
            failure = ResourceCleanup.close(clusterStagingBatch, failure);
            failure = ResourceCleanup.close(worldStagingBatch, failure);
            cleanupHandled = true;
            throw failure;
        } finally {
            if (!cleanupHandled) {
                RuntimeException failure = null;
                for (PreparedBlas.Compaction compaction : compactions) {
                    failure = ResourceCleanup.close(compaction, failure);
                }
                failure = ResourceCleanup.close(clusterStagingBatch, failure);
                failure = ResourceCleanup.close(worldStagingBatch, failure);
                ResourceCleanup.throwIfFailed(failure);
            }
        }
    }

    public ResidentSceneView residentView() {
        return this.currentView;
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
        RuntimeException failure = null;
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

    private boolean hasReadyCompaction(LongOpenHashSet removedKeys) {
        for (GpuCluster cluster : this.resident.values()) {
            if (!removedKeys.contains(cluster.key())
                    && cluster.blas().hasReadyCompaction()) {
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

    private RuntimeException commit(
            List<CompiledCluster> uploads,
            LongOpenHashSet removedKeys,
            List<GpuCluster> replacements,
            TopLevelAccelerationStructure replacementTlas,
            VulkanBuffer replacementWorldLights,
            boolean replaceWorldLights,
            boolean invalidateTemporalHistory,
            int nextOriginX,
            int nextOriginY,
            int nextOriginZ) {
        Long2ObjectOpenHashMap<GpuCluster> replacementGroups =
                groupReplacements(uploads, replacements);
        List<GpuCluster> retired = new ArrayList<>();
        Long2ObjectOpenHashMap<GpuCluster> nextResident = new Long2ObjectOpenHashMap<>();
        for (var entry : this.resident.long2ObjectEntrySet()) {
            if (removedKeys.contains(entry.getLongKey())) {
                retired.add(entry.getValue());
            } else {
                nextResident.put(entry.getLongKey(), entry.getValue());
            }
        }
        for (var entry : replacementGroups.long2ObjectEntrySet()) {
            nextResident.put(entry.getLongKey(), entry.getValue());
        }

        TopLevelAccelerationStructure previousTlas = this.currentTlas;
        VulkanBuffer previousWorldLights = replaceWorldLights ? this.currentWorldLights : null;
        long nextRevision = this.revision + 1L;
        long nextTemporalRevision = invalidateTemporalHistory
                ? this.temporalRevision + 1L
                : this.temporalRevision;
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
                        nextTemporalRevision);

        this.resident = nextResident;
        this.currentTlas = replacementTlas;
        if (replaceWorldLights) {
            this.currentWorldLights = replacementWorldLights;
        }
        this.originX = nextOriginX;
        this.originY = nextOriginY;
        this.originZ = nextOriginZ;
        this.revision = nextRevision;
        this.temporalRevision = nextTemporalRevision;
        this.currentView = nextView;

        RuntimeException retirementFailure = null;
        for (GpuCluster removed : retired) {
            retirementFailure = ResourceCleanup.run(
                    () -> this.context.defer(removed::destroy), retirementFailure);
        }
        if (previousTlas != null) {
            retirementFailure = ResourceCleanup.run(
                    () -> this.context.defer(previousTlas::release), retirementFailure);
        }
        if (previousWorldLights != null) {
            retirementFailure = ResourceCleanup.run(
                    () -> this.context.defer(previousWorldLights), retirementFailure);
        }
        return retirementFailure;
    }

    private static Long2ObjectOpenHashMap<GpuCluster> groupReplacements(
            List<CompiledCluster> uploads, List<GpuCluster> replacements) {
        Long2ObjectOpenHashMap<GpuCluster> result = new Long2ObjectOpenHashMap<>();
        int cursor = 0;
        for (CompiledCluster upload : uploads) {
            if (upload.isEmpty()) {
                continue;
            }
            if (cursor >= replacements.size()) {
                throw new IllegalStateException(
                        "Cluster replacement lost logical ownership");
            }
            GpuCluster replacement = replacements.get(cursor++);
            if (replacement.key() != upload.key()) {
                throw new IllegalStateException(
                        "Cluster replacement order disagrees with its upload");
            }
            result.put(upload.key(), replacement);
        }
        if (cursor != replacements.size()) {
            throw new IllegalStateException(
                    "Cluster replacement lost logical ownership");
        }
        return result;
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

    private GpuCluster prepareCluster(
            CompiledCluster upload,
            StagingArena.Batch stagingBatch,
            VkCommandBuffer commandBuffer) {
        CpuClusterMesh mesh = upload.mesh();
        VulkanBuffer positions = null;
        VulkanBuffer primitives = null;
        VulkanBuffer lights = null;
        PreparedBlas blas = null;
        try {
            positions = this.context.createBuffer(
                    mesh.positionBytes(),
                    VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                            | KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                    false,
                    "Prime cluster " + upload.key() + " positions");
            primitives = this.context.createBuffer(
                    mesh.primitiveBytes(),
                    VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    false,
                    "Prime cluster " + upload.key() + " primitives");
            if (!mesh.lights().isEmpty()) {
                lights = this.context.createBuffer(
                        mesh.lights().byteSize(),
                        VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                        false,
                        "Prime cluster " + upload.key() + " lights");
            }
            copyMeshSegments(commandBuffer, stagingBatch, mesh, positions, primitives);
            if (lights != null) {
                copyBuffer(
                        commandBuffer,
                        stagingBatch.write(mesh.lights().relocate(lights.deviceAddress()), 16L),
                        lights);
            }
            CompiledClusterLights.Summary lightSummary = mesh.lights().summary();
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
                    "Prime cluster " + upload.key() + " BLAS");
            return new GpuCluster(
                    upload.key(),
                    upload.clusterX(),
                    upload.clusterY(),
                    upload.clusterZ(),
                    blas,
                    lights,
                    lightSummary);
        } catch (RuntimeException exception) {
            RuntimeException failure = exception;
            if (blas != null) {
                failure = ResourceCleanup.run(blas::destroyAllResources, failure);
            } else {
                failure = ResourceCleanup.destroy(positions, failure);
                failure = ResourceCleanup.destroy(primitives, failure);
            }
            failure = ResourceCleanup.destroy(lights, failure);
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
                    mesh.opaqueTriangleCount(),
                    (long) CpuSectionMesh.PRIMITIVE_WORDS * Integer.BYTES),
            Math.multiplyExact(
                    Math.addExact(mesh.opaqueTriangleCount(), mesh.cutoutTriangleCount()),
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
                int positionWords = Math.multiplyExact(triangleCount, 9);
                int primitiveWords = Math.multiplyExact(
                        triangleCount, CpuSectionMesh.PRIMITIVE_WORDS);
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

    /** Immutable GPU-resident scene identity consumed by one or more frame plans. */
    public record ResidentSceneView(
            long tlas,
            long sectionTableAddress,
            int originX,
            int originY,
            int originZ,
            long revision,
            long resetRevision,
            long temporalRevision) {
    }
}
