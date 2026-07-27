package dev.prime.render.vulkan;

import dev.prime.render.ResourceCleanup;
import dev.prime.render.terrain.OpacityMicromapData;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildRangeInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureCreateInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCopyAccelerationStructureInfoKHR;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

public final class PreparedBlas {
    private static final int GEOMETRY_COUNT = 3;
    private static final long MAX_TRIANGLES_PER_GEOMETRY = 0x1_0000_0000L / 3L;
    private static final long MAX_PRIMITIVE_RECORDS = 0x1_0000_0000L;
    private static final int BUILD_FLAGS =
            KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
                    | KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR;

    private final VulkanContext context;
    private AccelerationStructure accelerationStructure;
    private VulkanBuffer positions;
    private final VulkanBuffer primitives;
    private VulkanBuffer scratch;
    private final OpacityMicromap opacityMicromap;
    private VulkanBuffer compactionResult;
    private long compactionQueryPool;
    private volatile boolean compactionReady;
    private boolean compactionResolved;
    private final String label;
    private final long opaqueTriangleCount;
    private final long cutoutTriangleCount;
    private final long transmissiveTriangleCount;

    private PreparedBlas(
            VulkanContext context,
            AccelerationStructure accelerationStructure,
            VulkanBuffer positions,
            VulkanBuffer primitives,
            VulkanBuffer scratch,
            OpacityMicromap opacityMicromap,
            VulkanBuffer compactionResult,
            long compactionQueryPool,
            long opaqueTriangleCount,
            long cutoutTriangleCount,
            long transmissiveTriangleCount,
            String label) {
        this.context = context;
        this.accelerationStructure = accelerationStructure;
        this.positions = positions;
        this.primitives = primitives;
        this.scratch = scratch;
        this.opacityMicromap = opacityMicromap;
        this.compactionResult = compactionResult;
        this.compactionQueryPool = compactionQueryPool;
        this.opaqueTriangleCount = opaqueTriangleCount;
        this.cutoutTriangleCount = cutoutTriangleCount;
        this.transmissiveTriangleCount = transmissiveTriangleCount;
        this.label = label;
    }

    public static PreparedBlas create(
            VulkanContext context,
            VulkanBuffer positions,
            VulkanBuffer primitives,
            OpacityMicromapData opacityMicromapData,
            StagingArena.Batch staging,
            VkCommandBuffer commandBuffer,
            long opaqueTriangleCount,
            long cutoutTriangleCount,
            long transmissiveTriangleCount,
            String label) {
        validateCounts(
                opaqueTriangleCount,
                cutoutTriangleCount,
                transmissiveTriangleCount,
                context.capabilities().maxAccelerationStructurePrimitiveCount());
        OpacityMicromap opacityMicromap = OpacityMicromap.create(
                context,
                opacityMicromapData,
                staging,
                commandBuffer,
                label + " OMM");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureGeometryKHR.Buffer geometries = geometries(
                    stack,
                    positions.deviceAddress(),
                    opaqueTriangleCount,
                    cutoutTriangleCount,
                    transmissiveTriangleCount,
                    opacityMicromap);
            VkAccelerationStructureBuildGeometryInfoKHR buildInfo = VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
                    .sType$Default()
                    .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                    .flags(BUILD_FLAGS)
                    .mode(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .geometryCount(GEOMETRY_COUNT)
                    .pGeometries(geometries);
            IntBuffer primitiveCounts = stack.ints(
                    (int) opaqueTriangleCount,
                    (int) cutoutTriangleCount,
                    (int) transmissiveTriangleCount);
            VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR(
                    context.vkDevice(),
                    KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    buildInfo,
                    primitiveCounts,
                    sizes);

            AccelerationStructure accelerationStructure = null;
            VulkanBuffer scratch = null;
            VulkanBuffer compactionResult = null;
            long compactionQueryPool = 0L;
            try {
                accelerationStructure = createAccelerationStructure(
                        context, sizes.accelerationStructureSize(), label);
                long scratchSize = sizes.buildScratchSize()
                        + context.capabilities().accelerationStructureScratchAlignment() - 1L;
                scratch = context.createBuffer(
                        scratchSize,
                        VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                        false,
                        label + " scratch");
                compactionResult = context.createBuffer(
                        Long.BYTES,
                        VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                        true,
                        label + " compacted size");
                VkQueryPoolCreateInfo queryInfo = VkQueryPoolCreateInfo.calloc(stack)
                        .sType$Default()
                        .queryType(KHRAccelerationStructure.VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR)
                        .queryCount(1);
                LongBuffer queryPointer = stack.mallocLong(1);
                VulkanContext.check(
                        VK10.vkCreateQueryPool(context.vkDevice(), queryInfo, null, queryPointer),
                        "create " + label + " compaction query");
                compactionQueryPool = queryPointer.get(0);
                return new PreparedBlas(
                        context,
                        accelerationStructure,
                        positions,
                        primitives,
                        scratch,
                        opacityMicromap,
                        compactionResult,
                        compactionQueryPool,
                        opaqueTriangleCount,
                        cutoutTriangleCount,
                        transmissiveTriangleCount,
                        label);
            } catch (RuntimeException exception) {
                RuntimeException failure = ResourceCleanup.destroy(
                        accelerationStructure, exception);
                failure = ResourceCleanup.destroy(scratch, failure);
                failure = ResourceCleanup.destroy(compactionResult, failure);
                if (compactionQueryPool != 0L) {
                    VK10.vkDestroyQueryPool(context.vkDevice(), compactionQueryPool, null);
                }
                throw failure;
            }
        } catch (RuntimeException exception) {
            throw ResourceCleanup.destroy(opacityMicromap, exception);
        }
    }

    public void recordBuild(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureGeometryKHR.Buffer geometries = geometries(
                    stack,
                    this.positions.deviceAddress(),
                    this.opaqueTriangleCount,
                    this.cutoutTriangleCount,
                    this.transmissiveTriangleCount,
                    this.opacityMicromap);
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer buildInfo =
                    VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            buildInfo.get(0)
                    .sType$Default()
                    .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                    .flags(BUILD_FLAGS)
                    .mode(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .geometryCount(GEOMETRY_COUNT)
                    .pGeometries(geometries)
                    .dstAccelerationStructure(this.accelerationStructure.handle());
            long scratchAddress = VulkanContext.alignUp(
                    this.scratch.deviceAddress(),
                    this.context.capabilities().accelerationStructureScratchAlignment());
            buildInfo.get(0).scratchData().deviceAddress(scratchAddress);

            VkAccelerationStructureBuildRangeInfoKHR.Buffer ranges =
                    VkAccelerationStructureBuildRangeInfoKHR.calloc(GEOMETRY_COUNT, stack);
            ranges.get(0)
                    .primitiveCount((int) this.opaqueTriangleCount)
                    .primitiveOffset(0)
                    .firstVertex(0)
                    .transformOffset(0);
            ranges.get(1)
                    .primitiveCount((int) this.cutoutTriangleCount)
                    .primitiveOffset(0)
                    .firstVertex(0)
                    .transformOffset(0);
            ranges.get(2)
                    .primitiveCount((int) this.transmissiveTriangleCount)
                    .primitiveOffset(0)
                    .firstVertex(0)
                    .transformOffset(0);
            PointerBuffer rangePointers = stack.mallocPointer(1).put(0, ranges.address());
            VK10.vkCmdResetQueryPool(commandBuffer, this.compactionQueryPool, 0, 1);
            KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR(commandBuffer, buildInfo, rangePointers);
            LongBuffer structures = stack.longs(this.accelerationStructure.handle());
            KHRAccelerationStructure.vkCmdWriteAccelerationStructuresPropertiesKHR(
                    commandBuffer,
                    structures,
                    KHRAccelerationStructure.VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR,
                    this.compactionQueryPool,
                    0);
            VK10.vkCmdCopyQueryPoolResults(
                    commandBuffer,
                    this.compactionQueryPool,
                    0,
                    1,
                    this.compactionResult.handle(),
                    0L,
                    Long.BYTES,
                    VK10.VK_QUERY_RESULT_64_BIT | VK10.VK_QUERY_RESULT_WAIT_BIT);
        }
    }

    /** Registers readiness only after the build/query submission has completed on the real queue. */
    public void onBuildSubmitted() {
        this.context.afterSubmission(() -> {
            synchronized (PreparedBlas.this) {
                if (PreparedBlas.this.compactionQueryPool != 0L) {
                    PreparedBlas.this.compactionReady = true;
                }
            }
        });
    }

    public void recordOpacityMicromapBuild(VkCommandBuffer commandBuffer) {
        if (this.opacityMicromap != null) {
            this.opacityMicromap.recordBuild(commandBuffer);
        }
    }

    public boolean hasOpacityMicromapBuild() {
        return this.opacityMicromap != null && this.opacityMicromap.requiresBuild();
    }

    public synchronized boolean hasReadyCompaction() {
        return this.compactionReady && !this.compactionResolved;
    }

    public synchronized Compaction prepareCompaction() {
        if (!this.hasReadyCompaction()) {
            return null;
        }
        this.compactionResult.invalidate(0L, Long.BYTES);
        long compactedSize = MemoryUtil.memGetLong(this.compactionResult.mappedAddress());
        this.destroyCompactionQuery();
        this.compactionResolved = true;
        if (compactedSize <= 0L || compactedSize >= this.accelerationStructure.backingSize()) {
            return null;
        }
        AccelerationStructure compacted = createAccelerationStructure(
                this.context, compactedSize, this.label + " compacted");
        return new Compaction(this, this.accelerationStructure, compacted);
    }

    public AccelerationStructure accelerationStructure() {
        return this.accelerationStructure;
    }

    public VulkanBuffer primitives() {
        return this.primitives;
    }

    public long opaqueTriangleCount() {
        return this.opaqueTriangleCount;
    }

    public long cutoutTriangleCount() {
        return this.cutoutTriangleCount;
    }

    public long transmissiveTriangleCount() {
        return this.transmissiveTriangleCount;
    }

    /** The vertex and scratch buffers are build inputs only and are retired on the real queue timeline. */
    public void retireBuildResources() {
        VulkanBuffer retiredPositions = this.positions;
        VulkanBuffer retiredScratch = this.scratch;
        this.positions = null;
        this.scratch = null;
        RuntimeException failure = null;
        if (retiredPositions != null || retiredScratch != null) {
            failure = ResourceCleanup.run(() -> this.context.defer(() -> {
                RuntimeException cleanupFailure = ResourceCleanup.destroy(retiredPositions, null);
                cleanupFailure = ResourceCleanup.destroy(retiredScratch, cleanupFailure);
                ResourceCleanup.throwIfFailed(cleanupFailure);
            }), null);
        }
        if (this.opacityMicromap != null) {
            failure = ResourceCleanup.run(
                    this.opacityMicromap::retireBuildResources, failure);
        }
        ResourceCleanup.throwIfFailed(failure);
    }

    public void destroyPersistentResources() {
        RuntimeException failure = ResourceCleanup.destroy(this.accelerationStructure, null);
        failure = ResourceCleanup.destroy(this.primitives, failure);
        failure = ResourceCleanup.destroy(this.opacityMicromap, failure);
        failure = ResourceCleanup.run(this::destroyCompactionQuery, failure);
        if (this.positions != null) {
            failure = ResourceCleanup.destroy(this.positions, failure);
            this.positions = null;
        }
        ResourceCleanup.throwIfFailed(failure);
    }

    public void destroyAllResources() {
        RuntimeException failure = null;
        if (this.scratch != null) {
            failure = ResourceCleanup.destroy(this.scratch, null);
            this.scratch = null;
        }
        failure = ResourceCleanup.run(this::destroyPersistentResources, failure);
        ResourceCleanup.throwIfFailed(failure);
    }

    private synchronized void destroyCompactionQuery() {
        if (this.compactionResult != null) {
            this.compactionResult.destroy();
            this.compactionResult = null;
        }
        if (this.compactionQueryPool != 0L) {
            VK10.vkDestroyQueryPool(this.context.vkDevice(), this.compactionQueryPool, null);
            this.compactionQueryPool = 0L;
        }
    }

    private static AccelerationStructure createAccelerationStructure(
            VulkanContext context, long size, String label) {
        VulkanBuffer backing = context.createBuffer(
                size,
                KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR,
                false,
                label + " backing");
        long handle = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureCreateInfoKHR createInfo = VkAccelerationStructureCreateInfoKHR.calloc(stack)
                    .sType$Default()
                    .buffer(backing.handle())
                    .offset(0L)
                    .size(size)
                    .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
            LongBuffer handlePointer = stack.mallocLong(1);
            VulkanContext.check(
                    KHRAccelerationStructure.vkCreateAccelerationStructureKHR(
                            context.vkDevice(), createInfo, null, handlePointer),
                    "create " + label);
            handle = handlePointer.get(0);
            context.device().instance().debug().setObjectName(
                    context.vkDevice(),
                    KHRAccelerationStructure.VK_OBJECT_TYPE_ACCELERATION_STRUCTURE_KHR,
                    handle,
                    label);
            VkAccelerationStructureDeviceAddressInfoKHR addressInfo =
                    VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                            .sType$Default()
                            .accelerationStructure(handle);
            long deviceAddress = KHRAccelerationStructure.vkGetAccelerationStructureDeviceAddressKHR(
                    context.vkDevice(), addressInfo);
            return new AccelerationStructure(context.vkDevice(), handle, deviceAddress, backing);
        } catch (RuntimeException exception) {
            if (handle != 0L) {
                KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(context.vkDevice(), handle, null);
            }
            throw ResourceCleanup.destroy(backing, exception);
        }
    }

    public static final class Compaction implements AutoCloseable {
        private final PreparedBlas owner;
        private final AccelerationStructure source;
        private final AccelerationStructure target;
        private boolean published;
        private boolean retired;

        private Compaction(
                PreparedBlas owner,
                AccelerationStructure source,
                AccelerationStructure target) {
            this.owner = owner;
            this.source = source;
            this.target = target;
        }

        public long targetDeviceAddress() {
            return this.target.deviceAddress();
        }

        public void recordCopy(VkCommandBuffer commandBuffer) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkCopyAccelerationStructureInfoKHR copy = VkCopyAccelerationStructureInfoKHR.calloc(stack)
                        .sType$Default()
                        .src(this.source.handle())
                        .dst(this.target.handle())
                        .mode(KHRAccelerationStructure.VK_COPY_ACCELERATION_STRUCTURE_MODE_COMPACT_KHR);
                KHRAccelerationStructure.vkCmdCopyAccelerationStructureKHR(commandBuffer, copy);
            }
        }

        public void requirePublishable() {
            synchronized (this.owner) {
                if (this.published
                        || this.retired
                        || this.owner.accelerationStructure != this.source) {
                    throw new IllegalStateException("BLAS changed before compaction publication");
                }
            }
        }

        /**
         * Publishes the copied address without performing fallible retirement work.
         *
         * <p>The containing scene must validate every compaction first and publish its replacement
         * TLAS immediately after this call.
         */
        public void publish() {
            synchronized (this.owner) {
                if (this.published
                        || this.retired
                        || this.owner.accelerationStructure != this.source) {
                    throw new IllegalStateException("BLAS changed before compaction publication");
                }
                this.owner.accelerationStructure = this.target;
            }
            this.published = true;
        }

        /** Retires the old address only after both the copy and replacement TLAS were published. */
        public void retireSource() {
            if (!this.published || this.retired) {
                throw new IllegalStateException(
                        "Compaction source can be retired only once after publication");
            }
            this.retired = true;
            this.owner.context.defer(this.source);
        }

        public void abandonAfterSubmission() {
            if (!this.published && !this.retired) {
                this.retired = true;
                this.owner.context.defer(this.target);
            }
        }

        @Override
        public void close() {
            if (!this.published && !this.retired) {
                this.retired = true;
                this.target.destroy();
            }
        }
    }

    private static VkAccelerationStructureGeometryKHR.Buffer geometries(
            MemoryStack stack,
            long positionAddress,
            long opaqueTriangleCount,
            long cutoutTriangleCount,
            long transmissiveTriangleCount,
            OpacityMicromap opacityMicromap) {
        VkAccelerationStructureGeometryKHR.Buffer geometries =
                VkAccelerationStructureGeometryKHR.calloc(GEOMETRY_COUNT, stack);
        fillGeometry(geometries.get(0), positionAddress, opaqueTriangleCount, true);
        fillGeometry(
                geometries.get(1),
                cutoutGeometryVertexAddress(positionAddress, opaqueTriangleCount, cutoutTriangleCount),
                cutoutTriangleCount,
                false);
        if (opacityMicromap != null) {
            opacityMicromap.attach(geometries.get(1).geometry().triangles(), stack);
        }
        fillGeometry(
                geometries.get(2),
                transmissiveGeometryVertexAddress(
                        positionAddress,
                        opaqueTriangleCount,
                        cutoutTriangleCount,
                        transmissiveTriangleCount),
                transmissiveTriangleCount,
                false);
        return geometries;
    }

    static long cutoutGeometryVertexAddress(
            long positionAddress,
            long opaqueTriangleCount,
            long cutoutTriangleCount) {
        if (opaqueTriangleCount < 0 || cutoutTriangleCount < 0) {
            throw new IllegalArgumentException("Triangle counts must not be negative");
        }
        if (cutoutTriangleCount == 0) {
            // The second geometry is retained so geometry index 1 always selects the cutout SBT record.
            // Vulkan still requires its vertex address to belong to a live build-input buffer even when
            // its primitive count is zero, so it must not point one byte past the opaque vertex data.
            return positionAddress;
        }
        long opaqueBytes = Math.multiplyExact(opaqueTriangleCount, 3L * 3L * Float.BYTES);
        return Math.addExact(positionAddress, opaqueBytes);
    }

    static long transmissiveGeometryVertexAddress(
            long positionAddress,
            long opaqueTriangleCount,
            long cutoutTriangleCount,
            long transmissiveTriangleCount) {
        if (opaqueTriangleCount < 0 || cutoutTriangleCount < 0 || transmissiveTriangleCount < 0) {
            throw new IllegalArgumentException("Triangle counts must not be negative");
        }
        if (transmissiveTriangleCount == 0) {
            // The fixed geometry/SBT mapping must not produce a one-past-end build address.
            return positionAddress;
        }
        long precedingTriangles = Math.addExact(
                opaqueTriangleCount, cutoutTriangleCount);
        long precedingBytes = Math.multiplyExact(
                precedingTriangles, 3L * 3L * Float.BYTES);
        return Math.addExact(positionAddress, precedingBytes);
    }

    private static void fillGeometry(
            VkAccelerationStructureGeometryKHR geometry,
            long vertexAddress,
            long triangleCount,
            boolean opaque) {
        geometry.sType$Default()
                .geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                .flags(opaque
                        ? KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR
                        : KHRAccelerationStructure.VK_GEOMETRY_NO_DUPLICATE_ANY_HIT_INVOCATION_BIT_KHR);
        geometry.geometry().triangles()
                .sType$Default()
                .vertexFormat(VK12.VK_FORMAT_R32G32B32_SFLOAT)
                .vertexStride(3L * Float.BYTES)
                .maxVertex(triangleCount == 0L
                        ? 0
                        : (int) (Math.multiplyExact(triangleCount, 3L) - 1L))
                .indexType(KHRAccelerationStructure.VK_INDEX_TYPE_NONE_KHR);
        geometry.geometry().triangles().vertexData().deviceAddress(vertexAddress);
        geometry.geometry().triangles().indexData().deviceAddress(0L);
        geometry.geometry().triangles().transformData().deviceAddress(0L);
    }

    private static void validateGeometryCount(String name, long triangleCount) {
        if (triangleCount > MAX_TRIANGLES_PER_GEOMETRY) {
            throw new IllegalStateException(
                    name + " geometry exceeds Vulkan's uint maxVertex range");
        }
    }

    static long validateCounts(
            long opaque,
            long cutout,
            long transmissive,
            long devicePrimitiveLimit) {
        if (opaque < 0L || cutout < 0L || transmissive < 0L) {
            throw new IllegalArgumentException("Triangle counts must not be negative");
        }
        long total = Math.addExact(Math.addExact(opaque, cutout), transmissive);
        if (total == 0L) {
            throw new IllegalArgumentException("A BLAS must contain at least one triangle");
        }
        if (Long.compareUnsigned(total, devicePrimitiveLimit) > 0) {
            throw new IllegalStateException(
                    "BLAS requires "
                            + total
                            + " primitives, but the Vulkan device supports "
                            + Long.toUnsignedString(devicePrimitiveLimit));
        }
        validateGeometryCount("opaque", opaque);
        validateGeometryCount("cutout", cutout);
        validateGeometryCount("transmissive", transmissive);
        if (total > MAX_PRIMITIVE_RECORDS) {
            throw new IllegalStateException(
                    "BLAS primitive records exceed the shader uint address space");
        }
        return total;
    }
}
