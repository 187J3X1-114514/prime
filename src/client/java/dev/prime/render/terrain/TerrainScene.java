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
    private final Long2ObjectOpenHashMap<GpuSection> resident = new Long2ObjectOpenHashMap<>();
    private final List<TopLevelAccelerationStructure> tlasSlots = new ArrayList<>(TLAS_SLOT_COUNT);
    private TopLevelAccelerationStructure currentTlas;
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
            List<SectionUpload> uploads,
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
        boolean requiresHistoryReset = this.changesExistingScene(uploads, evictions)
                || this.currentTlas != null && needsRebase;

        int finalSectionCount = this.estimateFinalSectionCount(uploads, evictions);
        TopLevelAccelerationStructure replacementTlas = null;
        if (finalSectionCount > 0) {
            replacementTlas = this.acquireTlas(finalSectionCount);
            if (replacementTlas == null) {
                return false;
            }
        }

        int nonEmptyUploadCount = 0;
        for (SectionUpload upload : uploads) {
            if (!upload.mesh().isEmpty()) {
                nonEmptyUploadCount++;
            }
        }
        StagingArena.Batch stagingBatch = nonEmptyUploadCount == 0 ? null : this.stagingArena.tryBeginBatch();
        if (nonEmptyUploadCount > 0 && stagingBatch == null) {
            if (replacementTlas != null) {
                replacementTlas.release();
            }
            return false;
        }

        List<GpuSection> replacements = new ArrayList<>(nonEmptyUploadCount);
        VkCommandBuffer commandBuffer = null;
        boolean submitted = false;
        try {
            if (nonEmptyUploadCount > 0 || replacementTlas != null) {
                commandBuffer = this.context.commandEncoder().allocateAndBeginTransientCommandBuffer();
                this.context.device().instance().debug().beginDebugGroup(commandBuffer, () -> "Prime terrain scene update");
            }

            if (stagingBatch != null) {
                for (SectionUpload upload : uploads) {
                    CpuSectionMesh mesh = upload.mesh();
                    if (mesh.isEmpty()) {
                        continue;
                    }
                    VulkanBuffer positions = this.context.createBuffer(
                            (long) mesh.positions().length * Float.BYTES,
                            VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                                    | KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                            false,
                            "Prime section " + upload.key() + " positions");
                    VulkanBuffer primitives = this.context.createBuffer(
                            (long) mesh.primitiveRecords().length * Integer.BYTES,
                            VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                            false,
                            "Prime section " + upload.key() + " primitives");
                    StagingArena.Slice positionSlice = stagingBatch.write(mesh.positions(), Float.BYTES);
                    StagingArena.Slice primitiveSlice = stagingBatch.write(mesh.primitiveRecords(), Integer.BYTES);
                    copyBuffer(commandBuffer, positionSlice, positions);
                    copyBuffer(commandBuffer, primitiveSlice, primitives);
                    PreparedBlas blas;
                    try {
                        blas = PreparedBlas.create(
                                this.context,
                                positions,
                                primitives,
                                mesh.opaqueTriangleCount(),
                                mesh.cutoutTriangleCount(),
                                "Prime section " + upload.key() + " BLAS");
                    } catch (RuntimeException exception) {
                        positions.destroy();
                        primitives.destroy();
                        throw exception;
                    }
                    replacements.add(new GpuSection(
                            upload.key(), upload.sectionX(), upload.sectionY(), upload.sectionZ(), blas));
                }
                memoryBarrier(
                        commandBuffer,
                        VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                        KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR
                                | KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                        KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR
                                | VK12.VK_ACCESS_SHADER_READ_BIT);
                for (GpuSection section : replacements) {
                    section.blas().recordBuild(commandBuffer);
                }
                memoryBarrier(
                        commandBuffer,
                        KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                        KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                        KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                        KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
            }

            List<GpuSection> finalSections = this.buildFinalSectionList(uploads, evictions, replacements);
            if (replacementTlas != null) {
                int nextOriginX = needsRebase ? RenderOrigin.alignToSection(cameraX) : this.originX;
                int nextOriginY = needsRebase ? RenderOrigin.alignToSection(cameraY) : this.originY;
                int nextOriginZ = needsRebase ? RenderOrigin.alignToSection(cameraZ) : this.originZ;
                List<TopLevelAccelerationStructure.Instance> instances = new ArrayList<>(finalSections.size());
                for (GpuSection section : finalSections) {
                    instances.add(new TopLevelAccelerationStructure.Instance(
                            section.blas().accelerationStructure().deviceAddress(),
                            section.blas().primitives().deviceAddress(),
                            section.blas().opaqueTriangleCount(),
                            (section.sectionX() << 4) - nextOriginX,
                            (section.sectionY() << 4) - nextOriginY,
                            (section.sectionZ() << 4) - nextOriginZ));
                }
                replacementTlas.populate(instances);
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
                if (stagingBatch != null) {
                    stagingBatch.submitForRetirement();
                }
                this.context.commandEncoder().execute(commandBuffer);
                submitted = true;
            }
            for (GpuSection replacement : replacements) {
                replacement.blas().retireScratch();
            }
            this.commit(uploads, evictions, replacements, replacementTlas, requiresHistoryReset);
            return true;
        } catch (RuntimeException exception) {
            for (GpuSection replacement : replacements) {
                if (submitted) {
                    this.context.defer(replacement.blas()::destroyAllResources);
                } else {
                    replacement.blas().destroyAllResources();
                }
            }
            if (replacementTlas != null) {
                if (submitted) {
                    this.context.defer(replacementTlas::release);
                } else {
                    replacementTlas.release();
                }
            }
            throw exception;
        } finally {
            if (stagingBatch != null) {
                stagingBatch.close();
            }
            if (commandBuffer != null && !submitted) {
                // The command pool owns command buffers. A failed recording is reclaimed when the pool resets.
            }
        }
    }

    public SceneView view() {
        if (this.currentTlas == null || this.resident.isEmpty()) {
            return null;
        }
        return new SceneView(
                this.currentTlas.handle(),
                this.currentTlas.sectionTableAddress(),
                this.originX,
                this.originY,
                this.originZ,
                this.revision,
                this.resetRevision);
    }

    public boolean contains(long key) {
        return this.resident.containsKey(key);
    }

    public int residentCount() {
        return this.resident.size();
    }

    public long[] residentKeys() {
        return this.resident.keySet().toLongArray();
    }

    @Override
    public void close() {
        for (GpuSection section : this.resident.values()) {
            section.destroy();
        }
        this.resident.clear();
        for (TopLevelAccelerationStructure slot : this.tlasSlots) {
            slot.destroy();
        }
        this.tlasSlots.clear();
        this.currentTlas = null;
    }

    private int estimateFinalSectionCount(List<SectionUpload> uploads, long[] evictions) {
        LongOpenHashSet finalKeys = new LongOpenHashSet(this.resident.keySet());
        for (long key : evictions) {
            finalKeys.remove(key);
        }
        for (SectionUpload upload : uploads) {
            if (upload.mesh().isEmpty()) {
                finalKeys.remove(upload.key());
            } else {
                finalKeys.add(upload.key());
            }
        }
        return finalKeys.size();
    }

    private boolean hasActualContentChange(List<SectionUpload> uploads, long[] evictions) {
        for (long key : evictions) {
            if (this.resident.containsKey(key)) {
                return true;
            }
        }
        for (SectionUpload upload : uploads) {
            if (!upload.mesh().isEmpty() || this.resident.containsKey(upload.key())) {
                return true;
            }
        }
        return false;
    }

    private boolean changesExistingScene(List<SectionUpload> uploads, long[] evictions) {
        for (long key : evictions) {
            if (this.resident.containsKey(key)) {
                return true;
            }
        }
        for (SectionUpload upload : uploads) {
            if (this.resident.containsKey(upload.key())) {
                return true;
            }
        }
        return false;
    }

    private List<GpuSection> buildFinalSectionList(
            List<SectionUpload> uploads,
            long[] evictions,
            List<GpuSection> replacements) {
        var removed = new LongOpenHashSet(evictions);
        for (SectionUpload upload : uploads) {
            removed.add(upload.key());
        }
        List<GpuSection> result = new ArrayList<>(this.resident.size() + replacements.size());
        for (GpuSection section : this.resident.values()) {
            if (!removed.contains(section.key())) {
                result.add(section);
            }
        }
        result.addAll(replacements);
        result.sort(Comparator.comparingLong(GpuSection::key));
        return result;
    }

    private void commit(
            List<SectionUpload> uploads,
            long[] evictions,
            List<GpuSection> replacements,
            TopLevelAccelerationStructure replacementTlas,
            boolean requiresHistoryReset) {
        List<GpuSection> retired = new ArrayList<>();
        for (long key : evictions) {
            GpuSection removed = this.resident.remove(key);
            if (removed != null) {
                retired.add(removed);
            }
        }
        for (SectionUpload upload : uploads) {
            GpuSection removed = this.resident.remove(upload.key());
            if (removed != null) {
                retired.add(removed);
            }
        }
        for (GpuSection replacement : replacements) {
            this.resident.put(replacement.key(), replacement);
        }
        for (GpuSection removed : retired) {
            this.context.defer(removed::destroy);
        }

        TopLevelAccelerationStructure previousTlas = this.currentTlas;
        this.currentTlas = replacementTlas;
        this.revision++;
        if (requiresHistoryReset) {
            this.resetRevision++;
        }
        if (previousTlas != null) {
            this.context.defer(previousTlas::release);
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
