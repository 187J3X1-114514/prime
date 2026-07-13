package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.render.shader.ShaderAbi;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkRayTracingPipelineCreateInfoKHR;
import org.lwjgl.vulkan.VkRayTracingShaderGroupCreateInfoKHR;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkStridedDeviceAddressRegionKHR;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkWriteDescriptorSetAccelerationStructureKHR;

public final class RayTracingPipeline implements Destroyable {
    private static final int GROUP_COUNT = 4;
    private static final int HIT_GROUP_COUNT = 2;
    private static final int ALL_RT_STAGES =
            KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR;

    private final VulkanContext context;
    private final long descriptorSetLayout;
    private final long pipelineLayout;
    private final long pipeline;
    private final VulkanBuffer shaderBindingTable;
    private final long raygenAddress;
    private final long missAddress;
    private final long hitAddress;
    private final long recordStride;
    private DescriptorBindings descriptorBindings;
    private boolean destroyed;

    public RayTracingPipeline(VulkanContext context) {
        this.context = context;
        long newDescriptorSetLayout = 0L;
        long newPipelineLayout = 0L;
        long newPipeline = 0L;
        VulkanBuffer newShaderBindingTable = null;
        try {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                newDescriptorSetLayout = createDescriptorSetLayout(context, stack);
                newPipelineLayout = createPipelineLayout(context, stack, newDescriptorSetLayout);
                newPipeline = createPipeline(context, stack, newPipelineLayout);
            }

            int handleSize = context.capabilities().shaderGroupHandleSize();
            int handleAlignment = context.capabilities().shaderGroupHandleAlignment();
            int baseAlignment = context.capabilities().shaderGroupBaseAlignment();
            long bufferSize = ShaderBindingTableLayout.minimumBufferSize(
                    handleSize,
                    handleAlignment,
                    baseAlignment,
                    HIT_GROUP_COUNT);
            newShaderBindingTable = context.createBuffer(
                    bufferSize,
                    KHRRayTracingPipeline.VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR,
                    true,
                    "Prime shader binding table");
            ShaderBindingTableLayout layout = ShaderBindingTableLayout.create(
                    handleSize,
                    handleAlignment,
                    baseAlignment,
                    HIT_GROUP_COUNT,
                    newShaderBindingTable.deviceAddress());
            if (layout.recordStride() > context.capabilities().maxShaderGroupStride()) {
                throw new IllegalStateException("Prime SBT record stride exceeds the device limit");
            }
            writeShaderBindingTable(
                    context,
                    newPipeline,
                    newShaderBindingTable,
                    handleSize,
                    layout);

            this.descriptorSetLayout = newDescriptorSetLayout;
            this.pipelineLayout = newPipelineLayout;
            this.pipeline = newPipeline;
            this.shaderBindingTable = newShaderBindingTable;
            this.recordStride = layout.recordStride();
            this.raygenAddress = newShaderBindingTable.deviceAddress() + layout.raygenOffset();
            this.missAddress = newShaderBindingTable.deviceAddress() + layout.missOffset();
            this.hitAddress = newShaderBindingTable.deviceAddress() + layout.hitOffset();
        } catch (RuntimeException exception) {
            if (newShaderBindingTable != null) {
                newShaderBindingTable.destroy();
            }
            if (newPipeline != 0L) {
                VK12.vkDestroyPipeline(context.vkDevice(), newPipeline, null);
            }
            if (newPipelineLayout != 0L) {
                VK12.vkDestroyPipelineLayout(context.vkDevice(), newPipelineLayout, null);
            }
            if (newDescriptorSetLayout != 0L) {
                VK12.vkDestroyDescriptorSetLayout(context.vkDevice(), newDescriptorSetLayout, null);
            }
            throw exception;
        }
    }

    public void ensureDescriptors(
            long tlas,
            VulkanImage output,
            VulkanGpuTextureView atlasView,
            VulkanGpuSampler atlasSampler) {
        if (this.descriptorBindings != null
                && this.descriptorBindings.matches(tlas, output.view(), atlasView.vkImageView(), atlasSampler.vkSampler())) {
            return;
        }
        DescriptorBindings replacement = DescriptorBindings.create(
                this.context,
                this.descriptorSetLayout,
                tlas,
                output,
                atlasView,
                atlasSampler);
        DescriptorBindings previous = this.descriptorBindings;
        this.descriptorBindings = replacement;
        if (previous != null) {
            this.context.defer(previous);
        }
    }

    public void trace(VkCommandBuffer commandBuffer, ByteBuffer pushConstants, int width, int height) {
        if (this.descriptorBindings == null) {
            throw new IllegalStateException("Ray tracing descriptors have not been initialized");
        }
        if (pushConstants.remaining() != ShaderAbi.PUSH_CONSTANT_SIZE) {
            throw new IllegalArgumentException("Unexpected Prime push constant size");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK12.vkCmdBindPipeline(
                    commandBuffer,
                    KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,
                    this.pipeline);
            VK12.vkCmdBindDescriptorSets(
                    commandBuffer,
                    KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,
                    this.pipelineLayout,
                    0,
                    stack.longs(this.descriptorBindings.descriptorSet),
                    null);
            VK12.vkCmdPushConstants(commandBuffer, this.pipelineLayout, ALL_RT_STAGES, 0, pushConstants);

            VkStridedDeviceAddressRegionKHR raygen = VkStridedDeviceAddressRegionKHR.calloc(stack)
                    .deviceAddress(this.raygenAddress)
                    .stride(this.recordStride)
                    .size(this.recordStride);
            VkStridedDeviceAddressRegionKHR miss = VkStridedDeviceAddressRegionKHR.calloc(stack)
                    .deviceAddress(this.missAddress)
                    .stride(this.recordStride)
                    .size(this.recordStride);
            VkStridedDeviceAddressRegionKHR hit = VkStridedDeviceAddressRegionKHR.calloc(stack)
                    .deviceAddress(this.hitAddress)
                    .stride(this.recordStride)
                    .size(this.recordStride * HIT_GROUP_COUNT);
            VkStridedDeviceAddressRegionKHR callable = VkStridedDeviceAddressRegionKHR.calloc(stack);
            KHRRayTracingPipeline.vkCmdTraceRaysKHR(commandBuffer, raygen, miss, hit, callable, width, height, 1);
        }
    }

    @Override
    public void destroy() {
        if (!this.destroyed) {
            this.destroyed = true;
            if (this.descriptorBindings != null) {
                this.descriptorBindings.destroy();
                this.descriptorBindings = null;
            }
            this.shaderBindingTable.destroy();
            VK12.vkDestroyPipeline(this.context.vkDevice(), this.pipeline, null);
            VK12.vkDestroyPipelineLayout(this.context.vkDevice(), this.pipelineLayout, null);
            VK12.vkDestroyDescriptorSetLayout(this.context.vkDevice(), this.descriptorSetLayout, null);
        }
    }

    private static void writeShaderBindingTable(
            VulkanContext context,
            long pipeline,
            VulkanBuffer shaderBindingTable,
            int handleSize,
            ShaderBindingTableLayout layout) {
        ByteBuffer handles = MemoryUtil.memAlloc(GROUP_COUNT * handleSize);
        try {
            VulkanContext.check(
                    KHRRayTracingPipeline.vkGetRayTracingShaderGroupHandlesKHR(
                            context.vkDevice(),
                            pipeline,
                            0,
                            GROUP_COUNT,
                            handles),
                    "read Prime shader group handles");
            long source = MemoryUtil.memAddress(handles);
            long destination = shaderBindingTable.mappedAddress();
            MemoryUtil.memCopy(source, destination + layout.raygenOffset(), handleSize);
            MemoryUtil.memCopy(source + handleSize, destination + layout.missOffset(), handleSize);
            MemoryUtil.memCopy(source + 2L * handleSize, destination + layout.hitOffset(), handleSize);
            MemoryUtil.memCopy(
                    source + 3L * handleSize,
                    destination + layout.hitOffset() + layout.recordStride(),
                    handleSize);
            shaderBindingTable.flush(0L, layout.totalSize());
        } finally {
            MemoryUtil.memFree(handles);
        }
    }

    private static long createDescriptorSetLayout(VulkanContext context, MemoryStack stack) {
        VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(3, stack);
        bindings.get(0)
                .binding(ShaderAbi.DESCRIPTOR_TLAS)
                .descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        bindings.get(1)
                .binding(ShaderAbi.DESCRIPTOR_OUTPUT_IMAGE)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        bindings.get(2)
                .binding(ShaderAbi.DESCRIPTOR_BLOCK_ATLAS)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
                        | KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR);
        VkDescriptorSetLayoutCreateInfo createInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .pBindings(bindings);
        LongBuffer pointer = stack.mallocLong(1);
        VulkanContext.check(
                VK12.vkCreateDescriptorSetLayout(context.vkDevice(), createInfo, null, pointer),
                "create Prime descriptor set layout");
        return pointer.get(0);
    }

    private static long createPipelineLayout(VulkanContext context, MemoryStack stack, long descriptorSetLayout) {
        VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
                .stageFlags(ALL_RT_STAGES)
                .offset(0)
                .size(ShaderAbi.PUSH_CONSTANT_SIZE);
        VkPipelineLayoutCreateInfo createInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .pSetLayouts(stack.longs(descriptorSetLayout))
                .pPushConstantRanges(pushRange);
        LongBuffer pointer = stack.mallocLong(1);
        VulkanContext.check(
                VK12.vkCreatePipelineLayout(context.vkDevice(), createInfo, null, pointer),
                "create Prime pipeline layout");
        return pointer.get(0);
    }

    private static long createPipeline(VulkanContext context, MemoryStack stack, long pipelineLayout) {
        long[] modules = new long[4];
        try {
            modules[0] = createShaderModule(context, "/prime/shaders/world.rgen.spv");
            modules[1] = createShaderModule(context, "/prime/shaders/world.rmiss.spv");
            modules[2] = createShaderModule(context, "/prime/shaders/world.rchit.spv");
            modules[3] = createShaderModule(context, "/prime/shaders/world.rahit.spv");
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(4, stack);
            int[] stageFlags = new int[] {
                KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR,
                KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR,
                KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR,
                KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR
            };
            ByteBuffer mainName = stack.UTF8("main");
            for (int index = 0; index < stages.capacity(); index++) {
                stages.get(index)
                        .sType$Default()
                        .stage(stageFlags[index])
                        .module(modules[index])
                        .pName(mainName);
            }

            VkRayTracingShaderGroupCreateInfoKHR.Buffer groups =
                    VkRayTracingShaderGroupCreateInfoKHR.calloc(GROUP_COUNT, stack);
            generalGroup(groups.get(0), 0);
            generalGroup(groups.get(1), 1);
            triangleGroup(groups.get(2), 2, KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR);
            triangleGroup(groups.get(3), 2, 3);

            VkRayTracingPipelineCreateInfoKHR.Buffer createInfo =
                    VkRayTracingPipelineCreateInfoKHR.calloc(1, stack);
            createInfo.get(0)
                    .sType$Default()
                    .pStages(stages)
                    .pGroups(groups)
                    .maxPipelineRayRecursionDepth(1)
                    .layout(pipelineLayout);
            LongBuffer pointer = stack.mallocLong(1);
            VulkanContext.check(
                    KHRRayTracingPipeline.vkCreateRayTracingPipelinesKHR(
                            context.vkDevice(), 0L, 0L, createInfo, null, pointer),
                    "create Prime ray tracing pipeline");
            long pipeline = pointer.get(0);
            context.device().instance().debug().setObjectName(
                    context.vkDevice(), VK12.VK_OBJECT_TYPE_PIPELINE, pipeline, "Prime primary ray pipeline");
            return pipeline;
        } finally {
            for (long module : modules) {
                if (module != 0L) {
                    VK12.vkDestroyShaderModule(context.vkDevice(), module, null);
                }
            }
        }
    }

    private static void generalGroup(VkRayTracingShaderGroupCreateInfoKHR group, int shaderIndex) {
        group.sType$Default()
                .type(KHRRayTracingPipeline.VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
                .generalShader(shaderIndex)
                .closestHitShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR)
                .anyHitShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR)
                .intersectionShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR);
    }

    private static void triangleGroup(VkRayTracingShaderGroupCreateInfoKHR group, int closestHit, int anyHit) {
        group.sType$Default()
                .type(KHRRayTracingPipeline.VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR)
                .generalShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR)
                .closestHitShader(closestHit)
                .anyHitShader(anyHit)
                .intersectionShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR);
    }

    private static long createShaderModule(VulkanContext context, String resourceName) {
        byte[] bytes;
        try (InputStream input = RayTracingPipeline.class.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IllegalStateException("Missing shader resource " + resourceName);
            }
            bytes = input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read shader resource " + resourceName, exception);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length);
        try {
            code.put(bytes).flip();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                        .sType$Default()
                        .pCode(code);
                LongBuffer pointer = stack.mallocLong(1);
                VulkanContext.check(
                        VK12.vkCreateShaderModule(context.vkDevice(), createInfo, null, pointer),
                        "create shader module " + resourceName);
                return pointer.get(0);
            }
        } finally {
            MemoryUtil.memFree(code);
        }
    }

    private static final class DescriptorBindings implements Destroyable {
        private final VulkanContext context;
        private final long descriptorPool;
        private final long descriptorSet;
        private final long tlas;
        private final long outputView;
        private final long atlasView;
        private final long atlasSampler;
        private boolean destroyed;

        private DescriptorBindings(
                VulkanContext context,
                long descriptorPool,
                long descriptorSet,
                long tlas,
                long outputView,
                long atlasView,
                long atlasSampler) {
            this.context = context;
            this.descriptorPool = descriptorPool;
            this.descriptorSet = descriptorSet;
            this.tlas = tlas;
            this.outputView = outputView;
            this.atlasView = atlasView;
            this.atlasSampler = atlasSampler;
        }

        private static DescriptorBindings create(
                VulkanContext context,
                long descriptorSetLayout,
                long tlas,
                VulkanImage output,
                VulkanGpuTextureView atlasView,
                VulkanGpuSampler atlasSampler) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(3, stack);
                sizes.get(0).type(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
                sizes.get(1).type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
                sizes.get(2).type(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
                VkDescriptorPoolCreateInfo poolCreateInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                        .sType$Default()
                        .maxSets(1)
                        .pPoolSizes(sizes);
                LongBuffer poolPointer = stack.mallocLong(1);
                VulkanContext.check(
                        VK12.vkCreateDescriptorPool(context.vkDevice(), poolCreateInfo, null, poolPointer),
                        "create Prime descriptor pool");
                long pool = poolPointer.get(0);
                try {
                    VkDescriptorSetAllocateInfo allocateInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                            .sType$Default()
                            .descriptorPool(pool)
                            .pSetLayouts(stack.longs(descriptorSetLayout));
                    LongBuffer setPointer = stack.mallocLong(1);
                    VulkanContext.check(
                            VK12.vkAllocateDescriptorSets(context.vkDevice(), allocateInfo, setPointer),
                            "allocate Prime descriptor set");
                    long descriptorSet = setPointer.get(0);

                    VkWriteDescriptorSetAccelerationStructureKHR acceleration =
                            VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                                    .sType$Default()
                                    .pAccelerationStructures(stack.longs(tlas));
                    VkDescriptorImageInfo.Buffer imageInfos = VkDescriptorImageInfo.calloc(2, stack);
                    imageInfos.get(0)
                            .imageView(output.view())
                            .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                    imageInfos.get(1)
                            .sampler(atlasSampler.vkSampler())
                            .imageView(atlasView.vkImageView())
                            .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                    VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(3, stack);
                    writes.get(0)
                            .sType$Default()
                            .pNext(acceleration.address())
                            .dstSet(descriptorSet)
                            .dstBinding(ShaderAbi.DESCRIPTOR_TLAS)
                            .descriptorCount(1)
                            .descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
                    writes.get(1)
                            .sType$Default()
                            .dstSet(descriptorSet)
                            .dstBinding(ShaderAbi.DESCRIPTOR_OUTPUT_IMAGE)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .pImageInfo(VkDescriptorImageInfo.create(imageInfos.get(0).address(), 1));
                    writes.get(2)
                            .sType$Default()
                            .dstSet(descriptorSet)
                            .dstBinding(ShaderAbi.DESCRIPTOR_BLOCK_ATLAS)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .pImageInfo(VkDescriptorImageInfo.create(imageInfos.get(1).address(), 1));
                    VK12.vkUpdateDescriptorSets(context.vkDevice(), writes, null);
                    return new DescriptorBindings(
                            context,
                            pool,
                            descriptorSet,
                            tlas,
                            output.view(),
                            atlasView.vkImageView(),
                            atlasSampler.vkSampler());
                } catch (RuntimeException exception) {
                    VK12.vkDestroyDescriptorPool(context.vkDevice(), pool, null);
                    throw exception;
                }
            }
        }

        private boolean matches(long tlas, long outputView, long atlasView, long atlasSampler) {
            return this.tlas == tlas
                    && this.outputView == outputView
                    && this.atlasView == atlasView
                    && this.atlasSampler == atlasSampler;
        }

        @Override
        public void destroy() {
            if (!this.destroyed) {
                this.destroyed = true;
                VK12.vkDestroyDescriptorPool(this.context.vkDevice(), this.descriptorPool, null);
            }
        }
    }
}
