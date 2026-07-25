package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.ResourceCleanup;
import dev.prime.render.terrain.OpacityMicromapData;
import java.nio.LongBuffer;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTOpacityMicromap;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryTrianglesDataKHR;
import org.lwjgl.vulkan.VkAccelerationStructureTrianglesOpacityMicromapEXT;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMicromapBuildInfoEXT;
import org.lwjgl.vulkan.VkMicromapBuildSizesInfoEXT;
import org.lwjgl.vulkan.VkMicromapCreateInfoEXT;
import org.lwjgl.vulkan.VkMicromapTriangleEXT;
import org.lwjgl.vulkan.VkMicromapUsageEXT;

/** Owns one cluster's optional EXT opacity micromap and its BLAS-build indirection. */
final class OpacityMicromap implements Destroyable {
    // VUID-vkCmdBuildMicromapsEXT-pInfos-07515 requires both device addresses to be 256-byte
    // aligned even when the buffer allocation itself reports a weaker alignment.
    private static final long BUILD_INPUT_ALIGNMENT = 256L;

    private final VulkanContext context;
    private long handle;
    private VulkanBuffer storage;
    private VulkanBuffer data;
    private VulkanBuffer triangles;
    private VulkanBuffer indices;
    private VulkanBuffer scratch;
    private final long dataAddress;
    private final long triangleAddress;
    private final long scratchAddress;
    private final int twoStateBlockCount;
    private final int fourStateBlockCount;
    private final int mappedTwoStateTriangleCount;
    private final int mappedFourStateTriangleCount;
    private boolean destroyed;

    private OpacityMicromap(
            VulkanContext context,
            long handle,
            VulkanBuffer storage,
            VulkanBuffer data,
            VulkanBuffer triangles,
            VulkanBuffer indices,
            VulkanBuffer scratch,
            long dataAddress,
            long triangleAddress,
            long scratchAddress,
            int twoStateBlockCount,
            int fourStateBlockCount,
            int mappedTwoStateTriangleCount,
            int mappedFourStateTriangleCount) {
        this.context = context;
        this.handle = handle;
        this.storage = storage;
        this.data = data;
        this.triangles = triangles;
        this.indices = indices;
        this.scratch = scratch;
        this.dataAddress = dataAddress;
        this.triangleAddress = triangleAddress;
        this.scratchAddress = scratchAddress;
        this.twoStateBlockCount = twoStateBlockCount;
        this.fourStateBlockCount = fourStateBlockCount;
        this.mappedTwoStateTriangleCount = mappedTwoStateTriangleCount;
        this.mappedFourStateTriangleCount = mappedFourStateTriangleCount;
    }

    static OpacityMicromap create(
            VulkanContext context,
            OpacityMicromapData source,
            StagingArena.Batch staging,
            VkCommandBuffer commandBuffer,
            String label) {
        Objects.requireNonNull(source, "Opacity micromap data");
        if (!context.capabilities().opacityMicromapSupported() || source.isEmpty()) {
            return null;
        }
        VulkanBuffer indices = null;
        VulkanBuffer data = null;
        VulkanBuffer triangles = null;
        VulkanBuffer storage = null;
        VulkanBuffer scratch = null;
        long dataAddress = 0L;
        long triangleAddress = 0L;
        long scratchAddress = 0L;
        long handle = 0L;
        try {
            int[] sourceIndices = source.triangleIndices();
            validateIndices(sourceIndices, source.blockCount());
            int mappedTwoStateTriangleCount = 0;
            int mappedFourStateTriangleCount = 0;
            for (int index : sourceIndices) {
                if (index >= 0) {
                    int format = source.blockFormats()[index];
                    if (format == OpacityMicromapData.TWO_STATE_FORMAT) {
                        mappedTwoStateTriangleCount++;
                    } else if (format == OpacityMicromapData.FOUR_STATE_FORMAT) {
                        mappedFourStateTriangleCount++;
                    } else {
                        throw new IllegalArgumentException(
                                "Opacity micromap triangle references an unsupported format");
                    }
                }
            }
            indices = context.createBuffer(
                    (long) sourceIndices.length * Integer.BYTES,
                    VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                            | KHRAccelerationStructure
                                    .VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                    false,
                    label + " indices");
            copyBuffer(commandBuffer, staging.write(sourceIndices, Integer.BYTES), indices);

            int blockCount = source.blockCount();
            int twoStateBlockCount =
                    source.blockCount(OpacityMicromapData.TWO_STATE_FORMAT);
            int fourStateBlockCount =
                    source.blockCount(OpacityMicromapData.FOUR_STATE_FORMAT);
            if (blockCount == 0) {
                return new OpacityMicromap(
                        context,
                        0L,
                        null,
                        null,
                        null,
                        indices,
                        null,
                        0L,
                        0L,
                        0L,
                        0,
                        0,
                        0,
                        0);
            }

            data = context.createBuffer(
                    alignedAllocationSize(source.blocks().length, BUILD_INPUT_ALIGNMENT),
                    VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                            | EXTOpacityMicromap.VK_BUFFER_USAGE_MICROMAP_BUILD_INPUT_READ_ONLY_BIT_EXT,
                    false,
                    label + " states");
            int[] triangleDescriptors = triangleDescriptors(
                    source.blockOffsets(), source.blockFormats());
            triangles = context.createBuffer(
                    alignedAllocationSize(
                            (long) triangleDescriptors.length * Integer.BYTES,
                            BUILD_INPUT_ALIGNMENT),
                    VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                            | EXTOpacityMicromap.VK_BUFFER_USAGE_MICROMAP_BUILD_INPUT_READ_ONLY_BIT_EXT,
                    false,
                    label + " triangles");
            long dataOffset = alignedOffset(data.deviceAddress(), BUILD_INPUT_ALIGNMENT);
            long triangleOffset = alignedOffset(
                    triangles.deviceAddress(), BUILD_INPUT_ALIGNMENT);
            dataAddress = data.deviceAddress() + dataOffset;
            triangleAddress = triangles.deviceAddress() + triangleOffset;
            copyBuffer(
                    commandBuffer,
                    staging.write(source.blocks(), 16L),
                    data,
                    dataOffset);
            copyBuffer(
                    commandBuffer,
                    staging.write(triangleDescriptors, Integer.BYTES),
                    triangles,
                    triangleOffset);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkMicromapUsageEXT.Buffer usage = usage(
                        stack, twoStateBlockCount, fourStateBlockCount);
                VkMicromapBuildInfoEXT buildInfo = VkMicromapBuildInfoEXT.calloc(stack)
                        .sType$Default()
                        .type(EXTOpacityMicromap.VK_MICROMAP_TYPE_OPACITY_MICROMAP_EXT)
                        .flags(EXTOpacityMicromap.VK_BUILD_MICROMAP_PREFER_FAST_TRACE_BIT_EXT)
                        .mode(EXTOpacityMicromap.VK_BUILD_MICROMAP_MODE_BUILD_EXT)
                        .usageCountsCount(usage.remaining())
                        .pUsageCounts(usage)
                        .triangleArrayStride(VkMicromapTriangleEXT.SIZEOF);
                buildInfo.data().deviceAddress(dataAddress);
                buildInfo.triangleArray().deviceAddress(triangleAddress);
                VkMicromapBuildSizesInfoEXT sizes =
                        VkMicromapBuildSizesInfoEXT.calloc(stack).sType$Default();
                EXTOpacityMicromap.vkGetMicromapBuildSizesEXT(
                        context.vkDevice(),
                        KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                        buildInfo,
                        sizes);
                storage = context.createBuffer(
                        sizes.micromapSize(),
                        EXTOpacityMicromap.VK_BUFFER_USAGE_MICROMAP_STORAGE_BIT_EXT,
                        false,
                        label + " storage");
                if (sizes.buildScratchSize() > 0L) {
                    long scratchAlignment =
                            context.capabilities().accelerationStructureScratchAlignment();
                    scratch = context.createBuffer(
                            alignedAllocationSize(sizes.buildScratchSize(), scratchAlignment),
                            VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                            false,
                            label + " scratch");
                    scratchAddress = VulkanContext.alignUp(
                            scratch.deviceAddress(), scratchAlignment);
                }
                VkMicromapCreateInfoEXT createInfo = VkMicromapCreateInfoEXT.calloc(stack)
                        .sType$Default()
                        .buffer(storage.handle())
                        .offset(0L)
                        .size(sizes.micromapSize())
                        .type(EXTOpacityMicromap.VK_MICROMAP_TYPE_OPACITY_MICROMAP_EXT);
                LongBuffer pointer = stack.mallocLong(1);
                VulkanContext.check(
                        EXTOpacityMicromap.vkCreateMicromapEXT(
                                context.vkDevice(), createInfo, null, pointer),
                        "create " + label);
                handle = pointer.get(0);
                context.device().instance().debug().setObjectName(
                        context.vkDevice(),
                        EXTOpacityMicromap.VK_OBJECT_TYPE_MICROMAP_EXT,
                        handle,
                        label);
            }
            return new OpacityMicromap(
                    context,
                    handle,
                    storage,
                    data,
                    triangles,
                    indices,
                    scratch,
                    dataAddress,
                    triangleAddress,
                    scratchAddress,
                    twoStateBlockCount,
                    fourStateBlockCount,
                    mappedTwoStateTriangleCount,
                    mappedFourStateTriangleCount);
        } catch (RuntimeException exception) {
            if (handle != 0L) {
                EXTOpacityMicromap.vkDestroyMicromapEXT(context.vkDevice(), handle, null);
            }
            RuntimeException failure = ResourceCleanup.destroy(storage, exception);
            failure = ResourceCleanup.destroy(data, failure);
            failure = ResourceCleanup.destroy(triangles, failure);
            failure = ResourceCleanup.destroy(indices, failure);
            failure = ResourceCleanup.destroy(scratch, failure);
            throw failure;
        }
    }

    void recordBuild(VkCommandBuffer commandBuffer) {
        if (this.handle == 0L) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMicromapUsageEXT.Buffer usage = usage(
                    stack, this.twoStateBlockCount, this.fourStateBlockCount);
            VkMicromapBuildInfoEXT.Buffer buildInfos = VkMicromapBuildInfoEXT.calloc(1, stack);
            VkMicromapBuildInfoEXT buildInfo = buildInfos.get(0)
                    .sType$Default()
                    .type(EXTOpacityMicromap.VK_MICROMAP_TYPE_OPACITY_MICROMAP_EXT)
                    .flags(EXTOpacityMicromap.VK_BUILD_MICROMAP_PREFER_FAST_TRACE_BIT_EXT)
                    .mode(EXTOpacityMicromap.VK_BUILD_MICROMAP_MODE_BUILD_EXT)
                    .dstMicromap(this.handle)
                    .usageCountsCount(usage.remaining())
                    .pUsageCounts(usage)
                    .triangleArrayStride(VkMicromapTriangleEXT.SIZEOF);
            buildInfo.data().deviceAddress(this.dataAddress);
            buildInfo.triangleArray().deviceAddress(this.triangleAddress);
            buildInfo.scratchData().deviceAddress(this.scratchAddress);
            EXTOpacityMicromap.vkCmdBuildMicromapsEXT(commandBuffer, buildInfos);
        }
    }

    boolean requiresBuild() {
        return this.handle != 0L;
    }

    void attach(
            VkAccelerationStructureGeometryTrianglesDataKHR trianglesData,
            MemoryStack stack) {
        VkAccelerationStructureTrianglesOpacityMicromapEXT attachment =
                VkAccelerationStructureTrianglesOpacityMicromapEXT.calloc(stack)
                        .sType$Default()
                        .indexType(VK12.VK_INDEX_TYPE_UINT32)
                        .indexStride(Integer.BYTES)
                        .baseTriangle(0)
                        .micromap(this.handle);
        attachment.indexBuffer().deviceAddress(this.indices.deviceAddress());
        if (this.mappedTwoStateTriangleCount != 0
                || this.mappedFourStateTriangleCount != 0) {
            VkMicromapUsageEXT.Buffer usage = usage(
                    stack,
                    this.mappedTwoStateTriangleCount,
                    this.mappedFourStateTriangleCount);
            attachment
                    .usageCountsCount(usage.remaining())
                    .pUsageCounts(usage);
        }
        trianglesData.pNext(attachment.address());
    }

    void retireBuildResources() {
        VulkanBuffer retiredData = this.data;
        VulkanBuffer retiredTriangles = this.triangles;
        VulkanBuffer retiredIndices = this.indices;
        VulkanBuffer retiredScratch = this.scratch;
        this.data = null;
        this.triangles = null;
        this.indices = null;
        this.scratch = null;
        this.context.defer(() -> {
            RuntimeException failure = ResourceCleanup.destroy(retiredData, null);
            failure = ResourceCleanup.destroy(retiredTriangles, failure);
            failure = ResourceCleanup.destroy(retiredIndices, failure);
            failure = ResourceCleanup.destroy(retiredScratch, failure);
            ResourceCleanup.throwIfFailed(failure);
        });
    }

    @Override
    public void destroy() {
        if (this.destroyed) {
            return;
        }
        this.destroyed = true;
        if (this.handle != 0L) {
            EXTOpacityMicromap.vkDestroyMicromapEXT(this.context.vkDevice(), this.handle, null);
            this.handle = 0L;
        }
        RuntimeException failure = ResourceCleanup.destroy(this.storage, null);
        failure = ResourceCleanup.destroy(this.data, failure);
        failure = ResourceCleanup.destroy(this.triangles, failure);
        failure = ResourceCleanup.destroy(this.indices, failure);
        failure = ResourceCleanup.destroy(this.scratch, failure);
        this.storage = null;
        this.data = null;
        this.triangles = null;
        this.indices = null;
        this.scratch = null;
        ResourceCleanup.throwIfFailed(failure);
    }

    private static VkMicromapUsageEXT.Buffer usage(
            MemoryStack stack, int twoStateCount, int fourStateCount) {
        int usageCount = (twoStateCount == 0 ? 0 : 1) + (fourStateCount == 0 ? 0 : 1);
        if (usageCount == 0) {
            throw new IllegalArgumentException(
                    "Opacity micromap usage must contain at least one mapped block");
        }
        VkMicromapUsageEXT.Buffer usage = VkMicromapUsageEXT.calloc(usageCount, stack);
        int index = 0;
        if (twoStateCount != 0) {
            setUsage(usage.get(index++), twoStateCount, OpacityMicromapData.TWO_STATE_FORMAT);
        }
        if (fourStateCount != 0) {
            setUsage(usage.get(index), fourStateCount, OpacityMicromapData.FOUR_STATE_FORMAT);
        }
        return usage;
    }

    private static void setUsage(VkMicromapUsageEXT usage, int count, int format) {
        usage.count(count)
                .subdivisionLevel(OpacityMicromapData.SUBDIVISION_LEVEL)
                .format(format);
    }

    static int[] triangleDescriptors(int[] blockOffsets, int[] blockFormats) {
        if (blockOffsets.length != blockFormats.length) {
            throw new IllegalArgumentException("Opacity micromap block metadata is inconsistent");
        }
        int[] result = new int[Math.multiplyExact(blockOffsets.length, 2)];
        for (int index = 0; index < blockOffsets.length; index++) {
            result[index * 2] = blockOffsets[index];
            result[index * 2 + 1] = OpacityMicromapData.SUBDIVISION_LEVEL
                    | blockFormats[index] << 16;
        }
        return result;
    }

    private static void copyBuffer(
            VkCommandBuffer commandBuffer,
            StagingArena.Slice source,
            VulkanBuffer destination) {
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

    private static long alignedAllocationSize(long dataSize, long alignment) {
        return Math.addExact(dataSize, alignment - 1L);
    }

    private static long alignedOffset(long deviceAddress, long alignment) {
        return VulkanContext.alignUp(deviceAddress, alignment) - deviceAddress;
    }

    private static void validateIndices(int[] indices, int blockCount) {
        for (int index : indices) {
            if (index >= blockCount
                    || index < EXTOpacityMicromap
                            .VK_OPACITY_MICROMAP_SPECIAL_INDEX_FULLY_UNKNOWN_OPAQUE_EXT) {
                throw new IllegalArgumentException(
                        "Opacity micromap triangle references an invalid block: " + index);
            }
        }
    }

}
