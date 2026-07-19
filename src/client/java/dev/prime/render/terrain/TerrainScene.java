package dev.prime.render.terrain;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
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
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.KHRSynchronization2;
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
    private final Long2ObjectOpenHashMap<GpuCluster> resident = new Long2ObjectOpenHashMap<>();
    private final List<TopLevelAccelerationStructure> tlasSlots = new ArrayList<>(TLAS_SLOT_COUNT);
    private TopLevelAccelerationStructure currentTlas;
    private VulkanBuffer currentWorldLights;
    private SceneView currentView;
    private int originX;
    private int originY;
    private int originZ;
    private long revision;
    private long resetRevision;

    public TerrainScene(VulkanContext context, StagingArena stagingArena) {
        this.context = context;
        this.stagingArena = stagingArena;
    }

    public boolean update(
            List<ClusterUpload> uploads,
            long[] evictions,
            double cameraX,
            double cameraY,
            double cameraZ) {
        boolean contentChanged = this.hasActualContentChange(uploads, evictions);
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
        if (!contentChanged && !needsRebase) {
            return true;
        }
        LongOpenHashSet removedKeys = removedKeys(uploads, evictions);
        int finalClusterCount = this.estimateFinalClusterCount(uploads, removedKeys);
        TopLevelAccelerationStructure replacementTlas = null;
        if (finalClusterCount > 0) {
            replacementTlas = this.acquireTlas(finalClusterCount);
            if (replacementTlas == null) {
                return false;
            }
        }

        int nonEmptyUploadCount = 0;
        for (ClusterUpload upload : uploads) {
            if (!upload.mesh().isEmpty()) {
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
            for (ClusterUpload upload : uploads) {
                if (!upload.mesh().lights().isEmpty()) {
                    hasPotentialLights = true;
                    break;
                }
            }
        }
        boolean needsClusterStaging = nonEmptyUploadCount > 0;
        boolean needsWorldStaging = finalClusterCount > 0 && hasPotentialLights;
        long clusterStagingBytes = 0L;
        for (ClusterUpload upload : uploads) {
            clusterStagingBytes = TerrainStreamer.stagingEndOffset(
                    clusterStagingBytes, upload.mesh());
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
        try {
            if (nonEmptyUploadCount > 0 || replacementTlas != null) {
                commandBuffer = this.context.commandEncoder().allocateAndBeginTransientCommandBuffer();
                this.context.device().instance().debug().beginDebugGroup(commandBuffer, () -> "Prime terrain scene update");
            }

            if (clusterStagingBatch != null) {
                for (ClusterUpload upload : uploads) {
                    CpuSectionMesh mesh = upload.mesh();
                    if (mesh.isEmpty()) {
                        continue;
                    }
                    replacements.add(this.prepareCluster(
                            upload, mesh, clusterStagingBatch, commandBuffer));
                }
            }

            List<GpuCluster> finalClusters = this.buildFinalClusterList(
                    removedKeys, replacements, finalClusterCount);
            int nextOriginX = needsRebase ? RenderOrigin.alignToSection(cameraX) : this.originX;
            int nextOriginY = needsRebase ? RenderOrigin.alignToSection(cameraY) : this.originY;
            int nextOriginZ = needsRebase ? RenderOrigin.alignToSection(cameraZ) : this.originZ;
            CpuWorldLightTree.Result worldLightTree = CpuWorldLightTree.build(
                    finalClusters, nextOriginX, nextOriginY, nextOriginZ);
            if (!worldLightTree.isEmpty()) {
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
                memoryBarrier(
                        commandBuffer,
                        VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                        KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR
                                | KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                        KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR
                                | VK12.VK_ACCESS_SHADER_READ_BIT);
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

            if (replacementTlas != null) {
                long worldLightAddress = replacementWorldLights == null
                        ? 0L
                        : replacementWorldLights.deviceAddress();
                long worldLightForwardAddress = replacementWorldLights == null
                        ? 0L
                        : replacementWorldLights.deviceAddress() + worldLightTree.forwardByteOffset();
                int worldLightNodeCount = replacementWorldLights == null
                        ? 0
                        : worldLightTree.nodeCount();
                replacementTlas.populate(finalClusters.size(), writer -> {
                    for (int clusterIndex = 0; clusterIndex < finalClusters.size(); clusterIndex++) {
                        GpuCluster cluster = finalClusters.get(clusterIndex);
                        writer.write(
                                cluster.blas().accelerationStructure().deviceAddress(),
                                cluster.blas().primitives().deviceAddress(),
                                cluster.lightAddress(),
                                worldLightAddress,
                                worldLightForwardAddress,
                                cluster.blas().opaqueTriangleCount(),
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
                this.originX = nextOriginX;
                this.originY = nextOriginY;
                this.originZ = nextOriginZ;
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
                replacement.blas().retireScratch();
            }
            this.commit(
                    uploads,
                    evictions,
                    replacements,
                    replacementTlas,
                    replacementWorldLights);
            replacementWorldLights = null;
            return true;
        } catch (RuntimeException exception) {
            for (GpuCluster replacement : replacements) {
                if (submitted) {
                    this.context.defer(replacement::destroyAllResources);
                } else {
                    replacement.destroyAllResources();
                }
            }
            if (replacementTlas != null) {
                if (submitted) {
                    this.context.defer(replacementTlas::release);
                } else {
                    replacementTlas.release();
                }
            }
            if (replacementWorldLights != null) {
                if (submitted) {
                    VulkanBuffer failedWorldLights = replacementWorldLights;
                    this.context.defer(failedWorldLights::destroy);
                } else {
                    replacementWorldLights.destroy();
                }
            }
            throw exception;
        } finally {
            if (clusterStagingBatch != null) {
                clusterStagingBatch.close();
            }
            if (worldStagingBatch != null) {
                worldStagingBatch.close();
            }
            if (commandBuffer != null && !submitted) {
                // The command pool owns command buffers. A failed recording is reclaimed when the pool resets.
            }
        }
    }

    public SceneView view() {
        return this.currentView;
    }

    public boolean contains(long key) {
        return this.resident.containsKey(key);
    }

    /** Marks every temporal consumer as unrelated to its previous world. */
    void beginUnrelatedWorld() {
        this.resetRevision++;
    }

    public int residentCount() {
        return this.resident.size();
    }

    public long[] residentKeys() {
        return this.resident.keySet().toLongArray();
    }

    @Override
    public void close() {
        for (GpuCluster cluster : this.resident.values()) {
            cluster.destroy();
        }
        this.resident.clear();
        for (TopLevelAccelerationStructure slot : this.tlasSlots) {
            slot.destroy();
        }
        this.tlasSlots.clear();
        this.currentTlas = null;
        this.currentView = null;
        if (this.currentWorldLights != null) {
            this.currentWorldLights.destroy();
            this.currentWorldLights = null;
        }
    }

    private static LongOpenHashSet removedKeys(
            List<ClusterUpload> uploads, long[] evictions) {
        LongOpenHashSet result = new LongOpenHashSet(evictions);
        for (ClusterUpload upload : uploads) {
            result.add(upload.key());
        }
        return result;
    }

    private int estimateFinalClusterCount(
            List<ClusterUpload> uploads, LongOpenHashSet removedKeys) {
        int count = this.resident.size();
        for (long key : removedKeys) {
            if (this.resident.containsKey(key)) {
                count--;
            }
        }
        for (ClusterUpload upload : uploads) {
            if (!upload.mesh().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private boolean hasActualContentChange(List<ClusterUpload> uploads, long[] evictions) {
        for (long key : evictions) {
            if (this.resident.containsKey(key)) {
                return true;
            }
        }
        for (ClusterUpload upload : uploads) {
            if (!upload.mesh().isEmpty() || this.resident.containsKey(upload.key())) {
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

    private void commit(
            List<ClusterUpload> uploads,
            long[] evictions,
            List<GpuCluster> replacements,
            TopLevelAccelerationStructure replacementTlas,
            VulkanBuffer replacementWorldLights) {
        List<GpuCluster> retired = new ArrayList<>(evictions.length + uploads.size());
        for (long key : evictions) {
            GpuCluster removed = this.resident.remove(key);
            if (removed != null) {
                retired.add(removed);
            }
        }
        for (ClusterUpload upload : uploads) {
            GpuCluster removed = this.resident.remove(upload.key());
            if (removed != null) {
                retired.add(removed);
            }
        }
        for (GpuCluster replacement : replacements) {
            this.resident.put(replacement.key(), replacement);
        }
        for (GpuCluster removed : retired) {
            this.context.defer(removed::destroy);
        }

        TopLevelAccelerationStructure previousTlas = this.currentTlas;
        VulkanBuffer previousWorldLights = this.currentWorldLights;
        this.currentTlas = replacementTlas;
        this.currentWorldLights = replacementWorldLights;
        this.revision++;
        this.currentView = this.currentTlas == null || this.resident.isEmpty()
                ? null
                : new SceneView(
                        this.currentTlas.handle(),
                        this.currentTlas.sectionTableAddress(),
                        this.originX,
                        this.originY,
                        this.originZ,
                        this.revision,
                        this.resetRevision);
        if (previousTlas != null) {
            this.context.defer(previousTlas::release);
        }
        if (previousWorldLights != null) {
            this.context.defer(previousWorldLights::destroy);
        }
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
            ClusterUpload upload,
            CpuSectionMesh mesh,
            StagingArena.Batch stagingBatch,
            VkCommandBuffer commandBuffer) {
        VulkanBuffer positions = null;
        VulkanBuffer primitives = null;
        VulkanBuffer lights = null;
        PreparedBlas blas = null;
        try {
            positions = this.context.createBuffer(
                    (long) mesh.positions().length * Float.BYTES,
                    VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                            | KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                    false,
                    "Prime cluster " + upload.key() + " positions");
            primitives = this.context.createBuffer(
                    (long) mesh.primitiveRecords().length * Integer.BYTES,
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
            copyBuffer(
                    commandBuffer,
                    stagingBatch.write(mesh.positions(), Float.BYTES),
                    positions);
            copyBuffer(
                    commandBuffer,
                    stagingBatch.write(mesh.primitiveRecords(), Integer.BYTES),
                    primitives);
            if (lights != null) {
                copyBuffer(
                        commandBuffer,
                        stagingBatch.write(mesh.lights().pack(lights.deviceAddress()), 16L),
                        lights);
            }
            CpuSectionLights.Summary lightSummary = mesh.lights().summary();
            blas = PreparedBlas.create(
                    this.context,
                    positions,
                    primitives,
                    mesh.opaqueTriangleCount(),
                    mesh.cutoutTriangleCount(),
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
            if (blas != null) {
                blas.destroyAllResources();
            } else {
                if (positions != null) {
                    positions.destroy();
                }
                if (primitives != null) {
                    primitives.destroy();
                }
            }
            if (lights != null) {
                lights.destroy();
            }
            throw exception;
        }
    }

    private static void copyBuffer(VkCommandBuffer commandBuffer, StagingArena.Slice source, VulkanBuffer destination) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack)
                    .srcOffset(source.offset())
                    .dstOffset(0L)
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

    public record SceneView(
            long tlas,
            long sectionTableAddress,
            int originX,
            int originY,
            int originZ,
            long revision,
            long resetRevision) {
    }
}
