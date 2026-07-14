package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.vulkan.nrd.NrdDenoiser;
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
    private static final int GROUP_COUNT = 5;
    static final int MISS_GROUP_COUNT = 2;
    private static final int HIT_GROUP_COUNT = 2;
    private static final int ALL_RT_STAGES =
            KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR;
    static final int BLOCK_ATLAS_STAGES =
            KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
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
                    MISS_GROUP_COUNT,
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
                    MISS_GROUP_COUNT,
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
            VulkanImage accumulation,
            VulkanGpuTextureView atlasView,
            VulkanGpuSampler atlasSampler,
            AtmospherePipeline atmosphere,
            NrdDenoiser nrd) {
        if (this.descriptorBindings != null
                && this.descriptorBindings.matches(
                        tlas,
                        output.view(),
                        accumulation.view(),
                        atlasView.vkImageView(),
                        atlasSampler.vkSampler(),
                        atmosphere.skyView().view(),
                        atmosphere.transmittanceLow().view(),
                        atmosphere.transmittanceHigh().view(),
                        atmosphere.aerialRadiance().view(),
                        atmosphere.aerialTransmittance().view(),
                        nrd.noisyDiffuse().view(),
                        nrd.normalRoughness().view(),
                        nrd.viewZ().view(),
                        nrd.motion().view(),
                        nrd.material().view(),
                        nrd.primaryPosition().view())) {
            return;
        }
        DescriptorBindings replacement = DescriptorBindings.create(
                this.context,
                this.descriptorSetLayout,
                tlas,
                output,
                accumulation,
                atlasView,
                atlasSampler,
                atmosphere,
                nrd);
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
                    .size(this.recordStride * MISS_GROUP_COUNT);
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
            MemoryUtil.memCopy(
                    source + 2L * handleSize,
                    destination + layout.missOffset() + layout.recordStride(),
                    handleSize);
            MemoryUtil.memCopy(source + 3L * handleSize, destination + layout.hitOffset(), handleSize);
            MemoryUtil.memCopy(
                    source + 4L * handleSize,
                    destination + layout.hitOffset() + layout.recordStride(),
                    handleSize);
            shaderBindingTable.flush(0L, layout.totalSize());
        } finally {
            MemoryUtil.memFree(handles);
        }
    }

    private static long createDescriptorSetLayout(VulkanContext context, MemoryStack stack) {
        VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(15, stack);
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
                // Raygen evaluates sampled area lights, closest-hit shades visible surfaces, and
                // any-hit tests cutout opacity. All three therefore read the block atlas.
                .stageFlags(BLOCK_ATLAS_STAGES);
        bindings.get(3)
                .binding(ShaderAbi.DESCRIPTOR_ACCUMULATION_IMAGE)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        int[] atmosphereBindings = new int[] {
            ShaderAbi.DESCRIPTOR_SKY_VIEW,
            ShaderAbi.DESCRIPTOR_TRANSMITTANCE_LOW,
            ShaderAbi.DESCRIPTOR_TRANSMITTANCE_HIGH,
            ShaderAbi.DESCRIPTOR_AERIAL_RADIANCE,
            ShaderAbi.DESCRIPTOR_AERIAL_TRANSMITTANCE
        };
        for (int index = 0; index < atmosphereBindings.length; index++) {
            bindings.get(index + 4)
                    .binding(atmosphereBindings[index])
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        }
        int[] nrdBindings = new int[] {
            ShaderAbi.DESCRIPTOR_NRD_NOISY_DIFFUSE,
            ShaderAbi.DESCRIPTOR_NRD_NORMAL_ROUGHNESS,
            ShaderAbi.DESCRIPTOR_NRD_VIEW_Z,
            ShaderAbi.DESCRIPTOR_NRD_MOTION,
            ShaderAbi.DESCRIPTOR_NRD_MATERIAL,
            ShaderAbi.DESCRIPTOR_NRD_PRIMARY_POSITION
        };
        for (int index = 0; index < nrdBindings.length; index++) {
            bindings.get(index + 9)
                    .binding(nrdBindings[index])
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        }
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
        long[] modules = new long[5];
        try {
            modules[0] = createShaderModule(context, "/prime/shaders/world.rgen.spv");
            modules[1] = createShaderModule(context, "/prime/shaders/world.rmiss.spv");
            modules[2] = createShaderModule(context, "/prime/shaders/shadow.rmiss.spv");
            modules[3] = createShaderModule(context, "/prime/shaders/world.rchit.spv");
            modules[4] = createShaderModule(context, "/prime/shaders/world.rahit.spv");
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(5, stack);
            int[] stageFlags = new int[] {
                KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR,
                KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR,
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
            generalGroup(groups.get(2), 2);
            triangleGroup(groups.get(3), 3, KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR);
            triangleGroup(groups.get(4), 3, 4);

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
        private final long accumulationView;
        private final long atlasView;
        private final long atlasSampler;
        private final long skyView;
        private final long transmittanceLow;
        private final long transmittanceHigh;
        private final long aerialRadiance;
        private final long aerialTransmittance;
        private final long nrdNoisyDiffuse;
        private final long nrdNormalRoughness;
        private final long nrdViewZ;
        private final long nrdMotion;
        private final long nrdMaterial;
        private final long nrdPrimaryPosition;
        private boolean destroyed;

        private DescriptorBindings(
                VulkanContext context,
                long descriptorPool,
                long descriptorSet,
                long tlas,
                long outputView,
                long accumulationView,
                long atlasView,
                long atlasSampler,
                long skyView,
                long transmittanceLow,
                long transmittanceHigh,
                long aerialRadiance,
                long aerialTransmittance,
                long nrdNoisyDiffuse,
                long nrdNormalRoughness,
                long nrdViewZ,
                long nrdMotion,
                long nrdMaterial,
                long nrdPrimaryPosition) {
            this.context = context;
            this.descriptorPool = descriptorPool;
            this.descriptorSet = descriptorSet;
            this.tlas = tlas;
            this.outputView = outputView;
            this.accumulationView = accumulationView;
            this.atlasView = atlasView;
            this.atlasSampler = atlasSampler;
            this.skyView = skyView;
            this.transmittanceLow = transmittanceLow;
            this.transmittanceHigh = transmittanceHigh;
            this.aerialRadiance = aerialRadiance;
            this.aerialTransmittance = aerialTransmittance;
            this.nrdNoisyDiffuse = nrdNoisyDiffuse;
            this.nrdNormalRoughness = nrdNormalRoughness;
            this.nrdViewZ = nrdViewZ;
            this.nrdMotion = nrdMotion;
            this.nrdMaterial = nrdMaterial;
            this.nrdPrimaryPosition = nrdPrimaryPosition;
        }

        private static DescriptorBindings create(
                VulkanContext context,
                long descriptorSetLayout,
                long tlas,
                VulkanImage output,
                VulkanImage accumulation,
                VulkanGpuTextureView atlasView,
                VulkanGpuSampler atlasSampler,
                AtmospherePipeline atmosphere,
                NrdDenoiser nrd) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(3, stack);
                sizes.get(0).type(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
                sizes.get(1).type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(13);
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
                    VkDescriptorImageInfo.Buffer imageInfos = VkDescriptorImageInfo.calloc(14, stack);
                    imageInfos.get(0)
                            .imageView(output.view())
                            .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                    imageInfos.get(1)
                            .imageView(accumulation.view())
                            .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                    imageInfos.get(2)
                            .sampler(atlasSampler.vkSampler())
                            .imageView(atlasView.vkImageView())
                            .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                    VulkanImage[] atmosphereImages = new VulkanImage[] {
                        atmosphere.skyView(),
                        atmosphere.transmittanceLow(),
                        atmosphere.transmittanceHigh(),
                        atmosphere.aerialRadiance(),
                        atmosphere.aerialTransmittance()
                    };
                    for (int index = 0; index < atmosphereImages.length; index++) {
                        imageInfos.get(index + 3)
                                .imageView(atmosphereImages[index].view())
                                .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                    }
                    VulkanImage[] nrdImages = new VulkanImage[] {
                        nrd.noisyDiffuse(),
                        nrd.normalRoughness(),
                        nrd.viewZ(),
                        nrd.motion(),
                        nrd.material(),
                        nrd.primaryPosition()
                    };
                    for (int index = 0; index < nrdImages.length; index++) {
                        imageInfos.get(index + 8)
                                .imageView(nrdImages[index].view())
                                .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                    }
                    VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(15, stack);
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
                            .dstBinding(ShaderAbi.DESCRIPTOR_ACCUMULATION_IMAGE)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .pImageInfo(VkDescriptorImageInfo.create(imageInfos.get(1).address(), 1));
                    writes.get(3)
                            .sType$Default()
                            .dstSet(descriptorSet)
                            .dstBinding(ShaderAbi.DESCRIPTOR_BLOCK_ATLAS)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .pImageInfo(VkDescriptorImageInfo.create(imageInfos.get(2).address(), 1));
                    int[] atmosphereBindings = new int[] {
                        ShaderAbi.DESCRIPTOR_SKY_VIEW,
                        ShaderAbi.DESCRIPTOR_TRANSMITTANCE_LOW,
                        ShaderAbi.DESCRIPTOR_TRANSMITTANCE_HIGH,
                        ShaderAbi.DESCRIPTOR_AERIAL_RADIANCE,
                        ShaderAbi.DESCRIPTOR_AERIAL_TRANSMITTANCE
                    };
                    for (int index = 0; index < atmosphereBindings.length; index++) {
                        writes.get(index + 4)
                                .sType$Default()
                                .dstSet(descriptorSet)
                                .dstBinding(atmosphereBindings[index])
                                .descriptorCount(1)
                                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                                .pImageInfo(VkDescriptorImageInfo.create(
                                        imageInfos.get(index + 3).address(), 1));
                    }
                    int[] nrdBindings = new int[] {
                        ShaderAbi.DESCRIPTOR_NRD_NOISY_DIFFUSE,
                        ShaderAbi.DESCRIPTOR_NRD_NORMAL_ROUGHNESS,
                        ShaderAbi.DESCRIPTOR_NRD_VIEW_Z,
                        ShaderAbi.DESCRIPTOR_NRD_MOTION,
                        ShaderAbi.DESCRIPTOR_NRD_MATERIAL,
                        ShaderAbi.DESCRIPTOR_NRD_PRIMARY_POSITION
                    };
                    for (int index = 0; index < nrdBindings.length; index++) {
                        writes.get(index + 9)
                                .sType$Default()
                                .dstSet(descriptorSet)
                                .dstBinding(nrdBindings[index])
                                .descriptorCount(1)
                                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                                .pImageInfo(VkDescriptorImageInfo.create(
                                        imageInfos.get(index + 8).address(), 1));
                    }
                    VK12.vkUpdateDescriptorSets(context.vkDevice(), writes, null);
                    return new DescriptorBindings(
                            context,
                            pool,
                            descriptorSet,
                            tlas,
                            output.view(),
                            accumulation.view(),
                            atlasView.vkImageView(),
                            atlasSampler.vkSampler(),
                            atmosphere.skyView().view(),
                            atmosphere.transmittanceLow().view(),
                            atmosphere.transmittanceHigh().view(),
                            atmosphere.aerialRadiance().view(),
                            atmosphere.aerialTransmittance().view(),
                            nrd.noisyDiffuse().view(),
                            nrd.normalRoughness().view(),
                            nrd.viewZ().view(),
                            nrd.motion().view(),
                            nrd.material().view(),
                            nrd.primaryPosition().view());
                } catch (RuntimeException exception) {
                    VK12.vkDestroyDescriptorPool(context.vkDevice(), pool, null);
                    throw exception;
                }
            }
        }

        private boolean matches(
                long tlas,
                long outputView,
                long accumulationView,
                long atlasView,
                long atlasSampler,
                long skyView,
                long transmittanceLow,
                long transmittanceHigh,
                long aerialRadiance,
                long aerialTransmittance,
                long nrdNoisyDiffuse,
                long nrdNormalRoughness,
                long nrdViewZ,
                long nrdMotion,
                long nrdMaterial,
                long nrdPrimaryPosition) {
            return this.tlas == tlas
                    && this.outputView == outputView
                    && this.accumulationView == accumulationView
                    && this.atlasView == atlasView
                    && this.atlasSampler == atlasSampler
                    && this.skyView == skyView
                    && this.transmittanceLow == transmittanceLow
                    && this.transmittanceHigh == transmittanceHigh
                    && this.aerialRadiance == aerialRadiance
                    && this.aerialTransmittance == aerialTransmittance
                    && this.nrdNoisyDiffuse == nrdNoisyDiffuse
                    && this.nrdNormalRoughness == nrdNormalRoughness
                    && this.nrdViewZ == nrdViewZ
                    && this.nrdMotion == nrdMotion
                    && this.nrdMaterial == nrdMaterial
                    && this.nrdPrimaryPosition == nrdPrimaryPosition;
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
