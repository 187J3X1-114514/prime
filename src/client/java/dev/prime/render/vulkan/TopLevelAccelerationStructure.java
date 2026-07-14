package dev.prime.render.vulkan;

import dev.prime.render.shader.ShaderAbi;
import java.nio.LongBuffer;
import java.util.List;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildRangeInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureCreateInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkAccelerationStructureInstanceKHR;
import org.lwjgl.vulkan.VkCommandBuffer;

public final class TopLevelAccelerationStructure {
    private final VulkanContext context;
    private final int capacity;
    private final VulkanBuffer instances;
    private final VulkanBuffer sectionTable;
    private final VulkanBuffer scratch;
    private final AccelerationStructure accelerationStructure;
    private boolean available = true;
    private boolean destroyed;
    private int instanceCount;

    private TopLevelAccelerationStructure(
            VulkanContext context,
            int capacity,
            VulkanBuffer instances,
            VulkanBuffer sectionTable,
            VulkanBuffer scratch,
            AccelerationStructure accelerationStructure) {
        this.context = context;
        this.capacity = capacity;
        this.instances = instances;
        this.sectionTable = sectionTable;
        this.scratch = scratch;
        this.accelerationStructure = accelerationStructure;
    }

    public static TopLevelAccelerationStructure create(VulkanContext context, int requestedCapacity, String label) {
        int capacity = Math.max(64, Integer.highestOneBit(Math.max(1, requestedCapacity - 1)) << 1);
        VulkanBuffer instances = null;
        VulkanBuffer sectionTable = null;
        VulkanBuffer backing = null;
        VulkanBuffer scratch = null;
        AccelerationStructure accelerationStructure = null;
        long handle = 0L;
        try {
            instances = context.createBuffer(
                    (long) capacity * VkAccelerationStructureInstanceKHR.SIZEOF,
                    KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                    true,
                    label + " instances");
            sectionTable = context.createBuffer(
                    (long) capacity * ShaderAbi.SECTION_RECORD_SIZE,
                    VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    true,
                    label + " section table");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkAccelerationStructureGeometryKHR.Buffer geometry = tlasGeometry(stack, instances.deviceAddress());
                VkAccelerationStructureBuildGeometryInfoKHR buildInfo = VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
                        .sType$Default()
                        .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                        .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                        .mode(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                        .geometryCount(1)
                        .pGeometries(geometry);
                VkAccelerationStructureBuildSizesInfoKHR sizes =
                        VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
                KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR(
                        context.vkDevice(),
                        KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                        buildInfo,
                        stack.ints(capacity),
                        sizes);
                backing = context.createBuffer(
                        sizes.accelerationStructureSize(),
                        KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR,
                        false,
                        label + " backing");
                VkAccelerationStructureCreateInfoKHR createInfo = VkAccelerationStructureCreateInfoKHR.calloc(stack)
                        .sType$Default()
                        .buffer(backing.handle())
                        .offset(0L)
                        .size(sizes.accelerationStructureSize())
                        .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);
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
                long address = KHRAccelerationStructure.vkGetAccelerationStructureDeviceAddressKHR(
                        context.vkDevice(), addressInfo);
                accelerationStructure = new AccelerationStructure(context.vkDevice(), handle, address, backing);
                backing = null;
                long scratchSize = sizes.buildScratchSize()
                        + context.capabilities().accelerationStructureScratchAlignment() - 1L;
                scratch = context.createBuffer(
                        scratchSize,
                        VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                        false,
                        label + " scratch");
                return new TopLevelAccelerationStructure(
                        context, capacity, instances, sectionTable, scratch, accelerationStructure);
            }
        } catch (RuntimeException exception) {
            if (accelerationStructure != null) {
                accelerationStructure.destroy();
            } else {
                if (handle != 0L) {
                    KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(context.vkDevice(), handle, null);
                }
                if (backing != null) {
                    backing.destroy();
                }
            }
            if (scratch != null) {
                scratch.destroy();
            }
            if (sectionTable != null) {
                sectionTable.destroy();
            }
            if (instances != null) {
                instances.destroy();
            }
            throw exception;
        }
    }

    public synchronized boolean tryAcquire() {
        if (this.destroyed || !this.available) {
            return false;
        }
        this.available = false;
        return true;
    }

    public synchronized void release() {
        if (!this.destroyed) {
            this.available = true;
        }
    }

    public boolean hasCapacity(int count) {
        return count <= this.capacity;
    }

    public void populate(List<Instance> source) {
        if (source.size() > this.capacity) {
            throw new IllegalArgumentException("TLAS capacity exceeded");
        }
        this.instanceCount = source.size();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureInstanceKHR instance = VkAccelerationStructureInstanceKHR.calloc(stack);
            var matrix = stack.mallocFloat(12);
            for (int index = 0; index < source.size(); index++) {
                Instance value = source.get(index);
                matrix.clear();
                matrix.put(new float[] {
                    1.0F, 0.0F, 0.0F, value.translateX(),
                    0.0F, 1.0F, 0.0F, value.translateY(),
                    0.0F, 0.0F, 1.0F, value.translateZ()
                }).flip();
                instance.transform().matrix(matrix);
                instance.instanceCustomIndex(index)
                        .mask(0xff)
                        .instanceShaderBindingTableRecordOffset(0)
                        .flags(KHRAccelerationStructure.VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR)
                        .accelerationStructureReference(value.blasAddress());
                MemoryUtil.memCopy(
                        instance.address(),
                        this.instances.mappedAddress() + (long) index * VkAccelerationStructureInstanceKHR.SIZEOF,
                        VkAccelerationStructureInstanceKHR.SIZEOF);

                long sectionAddress = this.sectionTable.mappedAddress()
                        + (long) index * ShaderAbi.SECTION_RECORD_SIZE;
                MemoryUtil.memPutLong(
                        sectionAddress + ShaderAbi.SECTION_PRIMITIVE_ADDRESS_OFFSET,
                        value.primitiveAddress());
                MemoryUtil.memPutLong(
                        sectionAddress + ShaderAbi.SECTION_LIGHT_ADDRESS_OFFSET,
                        value.lightAddress());
                MemoryUtil.memPutLong(
                        sectionAddress + ShaderAbi.SECTION_WORLD_LIGHT_ADDRESS_OFFSET,
                        value.worldLightAddress());
                MemoryUtil.memPutInt(sectionAddress + ShaderAbi.SECTION_OPAQUE_BASE_OFFSET, 0);
                MemoryUtil.memPutInt(
                        sectionAddress + ShaderAbi.SECTION_CUTOUT_BASE_OFFSET,
                        value.opaqueTriangleCount());
                MemoryUtil.memPutInt(
                        sectionAddress + ShaderAbi.SECTION_WORLD_LEAF_NODE_OFFSET,
                        value.worldLeafNode());
                MemoryUtil.memPutInt(
                        sectionAddress + ShaderAbi.SECTION_LIGHT_COUNT_OFFSET,
                        value.lightCount());
                MemoryUtil.memPutInt(sectionAddress + ShaderAbi.SECTION_RESERVED0_OFFSET, 0);
                MemoryUtil.memPutInt(sectionAddress + ShaderAbi.SECTION_RESERVED1_OFFSET, 0);
                MemoryUtil.memPutFloat(
                        sectionAddress + ShaderAbi.SECTION_TRANSLATION_OFFSET,
                        value.translateX());
                MemoryUtil.memPutFloat(
                        sectionAddress + ShaderAbi.SECTION_TRANSLATION_OFFSET + Float.BYTES,
                        value.translateY());
                MemoryUtil.memPutFloat(
                        sectionAddress + ShaderAbi.SECTION_TRANSLATION_OFFSET + 2L * Float.BYTES,
                        value.translateZ());
                MemoryUtil.memPutInt(sectionAddress + ShaderAbi.SECTION_RESERVED2_OFFSET, 0);
            }
        }
        this.instances.flush(0L, (long) source.size() * VkAccelerationStructureInstanceKHR.SIZEOF);
        this.sectionTable.flush(0L, (long) source.size() * ShaderAbi.SECTION_RECORD_SIZE);
    }

    public void recordBuild(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureGeometryKHR.Buffer geometry = tlasGeometry(stack, this.instances.deviceAddress());
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer buildInfo =
                    VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            buildInfo.get(0)
                    .sType$Default()
                    .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                    .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                    .mode(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .geometryCount(1)
                    .pGeometries(geometry)
                    .dstAccelerationStructure(this.accelerationStructure.handle());
            buildInfo.get(0).scratchData().deviceAddress(VulkanContext.alignUp(
                    this.scratch.deviceAddress(),
                    this.context.capabilities().accelerationStructureScratchAlignment()));
            VkAccelerationStructureBuildRangeInfoKHR.Buffer range =
                    VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
            range.get(0)
                    .primitiveCount(this.instanceCount)
                    .primitiveOffset(0)
                    .firstVertex(0)
                    .transformOffset(0);
            PointerBuffer rangePointers = stack.mallocPointer(1).put(0, range.address());
            KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR(commandBuffer, buildInfo, rangePointers);
        }
    }

    public long handle() {
        return this.accelerationStructure.handle();
    }

    public long sectionTableAddress() {
        return this.sectionTable.deviceAddress();
    }

    public synchronized void destroy() {
        if (!this.destroyed) {
            this.destroyed = true;
            this.available = false;
            this.accelerationStructure.destroy();
            this.instances.destroy();
            this.sectionTable.destroy();
            this.scratch.destroy();
        }
    }

    private static VkAccelerationStructureGeometryKHR.Buffer tlasGeometry(MemoryStack stack, long instanceAddress) {
        VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
        geometry.get(0)
                .sType$Default()
                .geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_INSTANCES_KHR)
                .flags(KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR);
        geometry.get(0).geometry().instances()
                .sType$Default()
                .arrayOfPointers(false);
        geometry.get(0).geometry().instances().data().deviceAddress(instanceAddress);
        return geometry;
    }

    public record Instance(
            long blasAddress,
            long primitiveAddress,
            long lightAddress,
            long worldLightAddress,
            int opaqueTriangleCount,
            int worldLeafNode,
            int lightCount,
            float translateX,
            float translateY,
            float translateZ) {
    }
}
