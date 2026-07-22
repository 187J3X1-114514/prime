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
import org.lwjgl.vulkan.EXTOpacityMicromap;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
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
    private static final int OPAQUE_RAYGEN_GROUP = 0;
    private static final int SCREENSHOT_RAYGEN_GROUP = 1;
    static final int RAYGEN_GROUP_COUNT = 2;
    static final int MISS_GROUP_COUNT = 2;
    static final int HIT_GROUP_COUNT = 6;
    private static final int GROUP_COUNT = RAYGEN_GROUP_COUNT + MISS_GROUP_COUNT + HIT_GROUP_COUNT;
    private static final int ALL_RT_STAGES =
            KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR;
    static final int BLOCK_ATLAS_STAGES =
            KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR;
    static final int LABPBR_SPECULAR_STAGES =
            KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR;

    private final VulkanContext context;
    private final long descriptorSetLayout;
    private final long pipelineLayout;
    private final TracePipeline tracePipeline;
    private final BsdfLookupTable bsdfLookup;
    private DescriptorBindings descriptorBindings;
    private boolean destroyed;

    public RayTracingPipeline(VulkanContext context) {
        this.context = context;
        long newDescriptorSetLayout = 0L;
        long newPipelineLayout = 0L;
        TracePipeline newTracePipeline = null;
        BsdfLookupTable newBsdfLookup = null;
        try {
            newBsdfLookup = new BsdfLookupTable(context);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                newDescriptorSetLayout = createDescriptorSetLayout(context, stack);
                newPipelineLayout = createPipelineLayout(context, stack, newDescriptorSetLayout);
                String opaqueRaygen = context.capabilities().invocationReorderSupported()
                        ? "/prime/shaders/world_ser.rgen.spv"
                        : "/prime/shaders/world.rgen.spv";
                // Both render paths share miss/hit stages and the pipeline layout. Keeping
                // their raygen groups in one pipeline lets the Vulkan driver compile that common
                // graph once; dispatch selects a base-aligned raygen SBT record.
                newTracePipeline = TracePipeline.create(
                        context,
                        stack,
                        newPipelineLayout,
                        opaqueRaygen,
                        "Prime unified ray tracing pipeline",
                        "Prime unified shader binding table");
            }

            this.descriptorSetLayout = newDescriptorSetLayout;
            this.pipelineLayout = newPipelineLayout;
            this.tracePipeline = newTracePipeline;
            this.bsdfLookup = newBsdfLookup;
        } catch (RuntimeException exception) {
            if (newTracePipeline != null) {
                newTracePipeline.destroy();
            }
            if (newBsdfLookup != null) {
                newBsdfLookup.destroy();
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
            VulkanImage labPbrNormalAtlas,
            VulkanImage labPbrSpecularAtlas,
            AtmospherePipeline atmosphere,
            DenoiserInputs targets) {
        if (this.descriptorBindings != null
                && this.descriptorBindings.matches(
                        tlas,
                        output.view(),
                        accumulation.view(),
                        atlasView.vkImageView(),
                        atlasSampler.vkSampler(),
                        labPbrNormalAtlas.view(),
                        labPbrSpecularAtlas.view(),
                        atmosphere.skyView().view(),
                        atmosphere.transmittanceLow().view(),
                        atmosphere.transmittanceHigh().view(),
                        atmosphere.aerialRadiance().view(),
                        atmosphere.aerialTransmittance().view(),
                        targets.noisyDiffuse().view(),
                        targets.noisySpecular().view(),
                        targets.normalRoughness().view(),
                        targets.viewZ().view(),
                        targets.motion().view(),
                        targets.material().view(),
                        targets.specularMaterial().view(),
                        targets.primaryPosition().view(),
                        targets.diffuseDirection().view(),
                        targets.specularDirection().view(),
                        targets.sunLighting().view(),
                        targets.sunPenumbra().view(),
                        targets.rawNumericalDiagnostic().view())) {
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
                labPbrNormalAtlas,
                labPbrSpecularAtlas,
                atmosphere,
                targets,
                this.bsdfLookup);
        DescriptorBindings previous = this.descriptorBindings;
        this.descriptorBindings = replacement;
        if (previous != null) {
            this.context.defer(previous);
        }
    }

    public void trace(VkCommandBuffer commandBuffer, ByteBuffer pushConstants, int width, int height) {
        this.bsdfLookup.prepare(commandBuffer);
        this.trace(commandBuffer, pushConstants, width, height, OPAQUE_RAYGEN_GROUP);
    }

    /** Records one complete, unsplit path sample per pixel for unbiased screenshot accumulation. */
    public void traceScreenshot(
            VkCommandBuffer commandBuffer, ByteBuffer pushConstants, int width, int height) {
        this.bsdfLookup.prepare(commandBuffer);
        this.trace(commandBuffer, pushConstants, width, height, SCREENSHOT_RAYGEN_GROUP);
    }

    private void trace(
            VkCommandBuffer commandBuffer,
            ByteBuffer pushConstants,
            int width,
            int height,
            int raygenGroup) {
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
                    this.tracePipeline.pipeline);
            VK12.vkCmdBindDescriptorSets(
                    commandBuffer,
                    KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,
                    this.pipelineLayout,
                    0,
                    stack.longs(this.descriptorBindings.descriptorSet),
                    null);
            VK12.vkCmdPushConstants(commandBuffer, this.pipelineLayout, ALL_RT_STAGES, 0, pushConstants);

            VkStridedDeviceAddressRegionKHR raygen = VkStridedDeviceAddressRegionKHR.calloc(stack)
                    .deviceAddress(this.tracePipeline.raygenAddress(raygenGroup))
                    .stride(this.tracePipeline.raygenRecordStride)
                    .size(this.tracePipeline.raygenRecordStride);
            VkStridedDeviceAddressRegionKHR miss = VkStridedDeviceAddressRegionKHR.calloc(stack)
                    .deviceAddress(this.tracePipeline.missAddress)
                    .stride(this.tracePipeline.recordStride)
                    .size(this.tracePipeline.recordStride * MISS_GROUP_COUNT);
            VkStridedDeviceAddressRegionKHR hit = VkStridedDeviceAddressRegionKHR.calloc(stack)
                    .deviceAddress(this.tracePipeline.hitAddress)
                    .stride(this.tracePipeline.recordStride)
                    .size(this.tracePipeline.recordStride * HIT_GROUP_COUNT);
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
            this.tracePipeline.destroy();
            VK12.vkDestroyPipelineLayout(this.context.vkDevice(), this.pipelineLayout, null);
            VK12.vkDestroyDescriptorSetLayout(this.context.vkDevice(), this.descriptorSetLayout, null);
            this.bsdfLookup.destroy();
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
            for (int raygenIndex = 0; raygenIndex < RAYGEN_GROUP_COUNT; raygenIndex++) {
                MemoryUtil.memCopy(
                        source + (long) raygenIndex * handleSize,
                        destination + layout.raygenOffset()
                                + raygenIndex * layout.raygenRecordStride(),
                        handleSize);
            }
            for (int missIndex = 0; missIndex < MISS_GROUP_COUNT; missIndex++) {
                MemoryUtil.memCopy(
                        source + (long) (RAYGEN_GROUP_COUNT + missIndex) * handleSize,
                        destination + layout.missOffset() + missIndex * layout.recordStride(),
                        handleSize);
            }
            for (int hitIndex = 0; hitIndex < HIT_GROUP_COUNT; hitIndex++) {
                MemoryUtil.memCopy(
                        source + (long) (RAYGEN_GROUP_COUNT + MISS_GROUP_COUNT + hitIndex)
                                * handleSize,
                        destination + layout.hitOffset() + hitIndex * layout.recordStride(),
                        handleSize);
            }
            shaderBindingTable.flush(0L, layout.totalSize());
        } finally {
            MemoryUtil.memFree(handles);
        }
    }

    private static long createDescriptorSetLayout(VulkanContext context, MemoryStack stack) {
        VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(25, stack);
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
            ShaderAbi.DESCRIPTOR_NRD_NOISY_SPECULAR,
            ShaderAbi.DESCRIPTOR_NRD_NORMAL_ROUGHNESS,
            ShaderAbi.DESCRIPTOR_NRD_VIEW_Z,
            ShaderAbi.DESCRIPTOR_NRD_MOTION,
            ShaderAbi.DESCRIPTOR_NRD_MATERIAL,
            ShaderAbi.DESCRIPTOR_NRD_SPECULAR_MATERIAL,
            ShaderAbi.DESCRIPTOR_NRD_PRIMARY_POSITION,
            ShaderAbi.DESCRIPTOR_NRD_DIFFUSE_DIRECTION,
            ShaderAbi.DESCRIPTOR_NRD_SPECULAR_DIRECTION
        };
        for (int index = 0; index < nrdBindings.length; index++) {
            bindings.get(index + 9)
                    .binding(nrdBindings[index])
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        }
        bindings.get(19)
                .binding(ShaderAbi.DESCRIPTOR_TRANSMISSION_GGX_ENERGY)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        bindings.get(20)
                .binding(ShaderAbi.DESCRIPTOR_LABPBR_NORMAL_ATLAS)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR);
        bindings.get(21)
                .binding(ShaderAbi.DESCRIPTOR_LABPBR_SPECULAR_ATLAS)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1)
                // Closest-hit transports material data; raygen evaluates the authored alpha
                // when an emissive area-light triangle is sampled or directly visible.
                .stageFlags(LABPBR_SPECULAR_STAGES);
        bindings.get(22)
                .binding(ShaderAbi.DESCRIPTOR_NRD_SUN_LIGHTING)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        bindings.get(23)
                .binding(ShaderAbi.DESCRIPTOR_NRD_SUN_PENUMBRA)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        bindings.get(24)
                .binding(ShaderAbi.DESCRIPTOR_RAW_NUMERICAL_DIAGNOSTIC)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
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

    private static long createPipeline(
            VulkanContext context,
            MemoryStack stack,
            long pipelineLayout,
            String opaqueRaygenResource,
            String debugName) {
        long[] modules = new long[7];
        try {
            modules[0] = createShaderModule(context, opaqueRaygenResource);
            modules[1] = createShaderModule(context, "/prime/shaders/screenshot.rgen.spv");
            modules[2] = createShaderModule(context, "/prime/shaders/world.rmiss.spv");
            modules[3] = createShaderModule(context, "/prime/shaders/shadow.rmiss.spv");
            modules[4] = createShaderModule(context, "/prime/shaders/world.rchit.spv");
            modules[5] = createShaderModule(context, "/prime/shaders/world.rahit.spv");
            modules[6] = createShaderModule(context, "/prime/shaders/shadow.rchit.spv");
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(7, stack);
            int[] stageFlags = new int[] {
                KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR,
                KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR,
                KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR,
                KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR,
                KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR,
                KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR,
                KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
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
            generalGroup(groups.get(3), 3);
            triangleGroup(groups.get(4), 4, KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR);
            triangleGroup(groups.get(5), 4, 5);
            triangleGroup(groups.get(6), 4, 5);
            triangleGroup(groups.get(7), 6, KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR);
            triangleGroup(groups.get(8), 6, 5);
            triangleGroup(groups.get(9), 6, KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR);

            VkRayTracingPipelineCreateInfoKHR.Buffer createInfo =
                    VkRayTracingPipelineCreateInfoKHR.calloc(1, stack);
            createInfo.get(0)
                    .sType$Default()
                    .flags(context.capabilities().opacityMicromapSupported()
                            ? EXTOpacityMicromap
                                    .VK_PIPELINE_CREATE_RAY_TRACING_OPACITY_MICROMAP_BIT_EXT
                            : 0)
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
                    context.vkDevice(),
                    VK12.VK_OBJECT_TYPE_PIPELINE,
                    pipeline,
                    debugName);
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

    private static final class TracePipeline implements Destroyable {
        private final VulkanContext context;
        private final long pipeline;
        private final VulkanBuffer shaderBindingTable;
        private final long raygenAddress;
        private final long raygenRecordStride;
        private final long missAddress;
        private final long hitAddress;
        private final long recordStride;
        private boolean destroyed;

        private TracePipeline(
                VulkanContext context,
                long pipeline,
                VulkanBuffer shaderBindingTable,
                ShaderBindingTableLayout layout) {
            this.context = context;
            this.pipeline = pipeline;
            this.shaderBindingTable = shaderBindingTable;
            this.raygenAddress = shaderBindingTable.deviceAddress() + layout.raygenOffset();
            this.raygenRecordStride = layout.raygenRecordStride();
            this.missAddress = shaderBindingTable.deviceAddress() + layout.missOffset();
            this.hitAddress = shaderBindingTable.deviceAddress() + layout.hitOffset();
            this.recordStride = layout.recordStride();
        }

        private static TracePipeline create(
                VulkanContext context,
                MemoryStack stack,
                long pipelineLayout,
                String raygenResource,
                String pipelineName,
                String sbtName) {
            long pipeline = 0L;
            VulkanBuffer shaderBindingTable = null;
            try {
                pipeline = createPipeline(
                        context, stack, pipelineLayout, raygenResource, pipelineName);
                int handleSize = context.capabilities().shaderGroupHandleSize();
                int handleAlignment = context.capabilities().shaderGroupHandleAlignment();
                int baseAlignment = context.capabilities().shaderGroupBaseAlignment();
                long bufferSize = ShaderBindingTableLayout.minimumBufferSize(
                        handleSize,
                        handleAlignment,
                        baseAlignment,
                        RAYGEN_GROUP_COUNT,
                        MISS_GROUP_COUNT,
                        HIT_GROUP_COUNT);
                shaderBindingTable = context.createBuffer(
                        bufferSize,
                        KHRRayTracingPipeline.VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR,
                        true,
                        sbtName);
                ShaderBindingTableLayout layout = ShaderBindingTableLayout.create(
                        handleSize,
                        handleAlignment,
                        baseAlignment,
                        RAYGEN_GROUP_COUNT,
                        MISS_GROUP_COUNT,
                        HIT_GROUP_COUNT,
                        shaderBindingTable.deviceAddress());
                if (layout.recordStride() > context.capabilities().maxShaderGroupStride()
                        || layout.raygenRecordStride()
                                > context.capabilities().maxShaderGroupStride()) {
                    throw new IllegalStateException("Prime SBT record stride exceeds the device limit");
                }
                writeShaderBindingTable(
                        context, pipeline, shaderBindingTable, handleSize, layout);
                return new TracePipeline(context, pipeline, shaderBindingTable, layout);
            } catch (RuntimeException exception) {
                if (shaderBindingTable != null) {
                    shaderBindingTable.destroy();
                }
                if (pipeline != 0L) {
                    VK12.vkDestroyPipeline(context.vkDevice(), pipeline, null);
                }
                throw exception;
            }
        }

        private long raygenAddress(int group) {
            if (group < 0 || group >= RAYGEN_GROUP_COUNT) {
                throw new IllegalArgumentException("Invalid Prime raygen group " + group);
            }
            return this.raygenAddress + group * this.raygenRecordStride;
        }

        @Override
        public void destroy() {
            if (!this.destroyed) {
                this.destroyed = true;
                this.shaderBindingTable.destroy();
                VK12.vkDestroyPipeline(this.context.vkDevice(), this.pipeline, null);
            }
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
        private final long labPbrNormalAtlas;
        private final long labPbrSpecularAtlas;
        private final long skyView;
        private final long transmittanceLow;
        private final long transmittanceHigh;
        private final long aerialRadiance;
        private final long aerialTransmittance;
        private final long nrdNoisyDiffuse;
        private final long nrdNoisySpecular;
        private final long nrdNormalRoughness;
        private final long nrdViewZ;
        private final long nrdMotion;
        private final long nrdMaterial;
        private final long nrdSpecularMaterial;
        private final long nrdPrimaryPosition;
        private final long nrdDiffuseDirection;
        private final long nrdSpecularDirection;
        private final long nrdSunLighting;
        private final long nrdSunPenumbra;
        private final long rawNumericalDiagnostic;
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
                long labPbrNormalAtlas,
                long labPbrSpecularAtlas,
                long skyView,
                long transmittanceLow,
                long transmittanceHigh,
                long aerialRadiance,
                long aerialTransmittance,
                long nrdNoisyDiffuse,
                long nrdNoisySpecular,
                long nrdNormalRoughness,
                long nrdViewZ,
                long nrdMotion,
                long nrdMaterial,
                long nrdSpecularMaterial,
                long nrdPrimaryPosition,
                long nrdDiffuseDirection,
                long nrdSpecularDirection,
                long nrdSunLighting,
                long nrdSunPenumbra,
                long rawNumericalDiagnostic) {
            this.context = context;
            this.descriptorPool = descriptorPool;
            this.descriptorSet = descriptorSet;
            this.tlas = tlas;
            this.outputView = outputView;
            this.accumulationView = accumulationView;
            this.atlasView = atlasView;
            this.atlasSampler = atlasSampler;
            this.labPbrNormalAtlas = labPbrNormalAtlas;
            this.labPbrSpecularAtlas = labPbrSpecularAtlas;
            this.skyView = skyView;
            this.transmittanceLow = transmittanceLow;
            this.transmittanceHigh = transmittanceHigh;
            this.aerialRadiance = aerialRadiance;
            this.aerialTransmittance = aerialTransmittance;
            this.nrdNoisyDiffuse = nrdNoisyDiffuse;
            this.nrdNoisySpecular = nrdNoisySpecular;
            this.nrdNormalRoughness = nrdNormalRoughness;
            this.nrdViewZ = nrdViewZ;
            this.nrdMotion = nrdMotion;
            this.nrdMaterial = nrdMaterial;
            this.nrdSpecularMaterial = nrdSpecularMaterial;
            this.nrdPrimaryPosition = nrdPrimaryPosition;
            this.nrdDiffuseDirection = nrdDiffuseDirection;
            this.nrdSpecularDirection = nrdSpecularDirection;
            this.nrdSunLighting = nrdSunLighting;
            this.nrdSunPenumbra = nrdSunPenumbra;
            this.rawNumericalDiagnostic = rawNumericalDiagnostic;
        }

        private static DescriptorBindings create(
                VulkanContext context,
                long descriptorSetLayout,
                long tlas,
                VulkanImage output,
                VulkanImage accumulation,
                VulkanGpuTextureView atlasView,
                VulkanGpuSampler atlasSampler,
                VulkanImage labPbrNormalAtlas,
                VulkanImage labPbrSpecularAtlas,
                AtmospherePipeline atmosphere,
                DenoiserInputs targets,
                BsdfLookupTable bsdfLookup) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(3, stack);
                sizes.get(0).type(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
                sizes.get(1).type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(20);
                sizes.get(2).type(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(4);
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
                    VkDescriptorImageInfo.Buffer imageInfos = VkDescriptorImageInfo.calloc(24, stack);
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
                        targets.noisyDiffuse(),
                        targets.noisySpecular(),
                        targets.normalRoughness(),
                        targets.viewZ(),
                        targets.motion(),
                        targets.material(),
                        targets.specularMaterial(),
                        targets.primaryPosition(),
                        targets.diffuseDirection(),
                        targets.specularDirection()
                    };
                    for (int index = 0; index < nrdImages.length; index++) {
                        imageInfos.get(index + 8)
                                .imageView(nrdImages[index].view())
                                .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                    }
                    imageInfos.get(18)
                            .sampler(bsdfLookup.sampler())
                            .imageView(bsdfLookup.transmissionGgxEnergy().view())
                            .imageLayout(VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                    imageInfos.get(19)
                            .sampler(atlasSampler.vkSampler())
                            .imageView(labPbrNormalAtlas.view())
                            .imageLayout(VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                    imageInfos.get(20)
                            .sampler(atlasSampler.vkSampler())
                            .imageView(labPbrSpecularAtlas.view())
                            .imageLayout(VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                    imageInfos.get(21)
                            .imageView(targets.sunLighting().view())
                            .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                    imageInfos.get(22)
                            .imageView(targets.sunPenumbra().view())
                            .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                    imageInfos.get(23)
                            .imageView(targets.rawNumericalDiagnostic().view())
                            .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                    VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(25, stack);
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
                        ShaderAbi.DESCRIPTOR_NRD_NOISY_SPECULAR,
                        ShaderAbi.DESCRIPTOR_NRD_NORMAL_ROUGHNESS,
                        ShaderAbi.DESCRIPTOR_NRD_VIEW_Z,
                        ShaderAbi.DESCRIPTOR_NRD_MOTION,
                        ShaderAbi.DESCRIPTOR_NRD_MATERIAL,
                        ShaderAbi.DESCRIPTOR_NRD_SPECULAR_MATERIAL,
                        ShaderAbi.DESCRIPTOR_NRD_PRIMARY_POSITION,
                        ShaderAbi.DESCRIPTOR_NRD_DIFFUSE_DIRECTION,
                        ShaderAbi.DESCRIPTOR_NRD_SPECULAR_DIRECTION
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
                    writes.get(19)
                            .sType$Default()
                            .dstSet(descriptorSet)
                            .dstBinding(ShaderAbi.DESCRIPTOR_TRANSMISSION_GGX_ENERGY)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .pImageInfo(VkDescriptorImageInfo.create(imageInfos.get(18).address(), 1));
                    writes.get(20)
                            .sType$Default()
                            .dstSet(descriptorSet)
                            .dstBinding(ShaderAbi.DESCRIPTOR_LABPBR_NORMAL_ATLAS)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .pImageInfo(VkDescriptorImageInfo.create(imageInfos.get(19).address(), 1));
                    writes.get(21)
                            .sType$Default()
                            .dstSet(descriptorSet)
                            .dstBinding(ShaderAbi.DESCRIPTOR_LABPBR_SPECULAR_ATLAS)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .pImageInfo(VkDescriptorImageInfo.create(imageInfos.get(20).address(), 1));
                    writes.get(22)
                            .sType$Default()
                            .dstSet(descriptorSet)
                            .dstBinding(ShaderAbi.DESCRIPTOR_NRD_SUN_LIGHTING)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .pImageInfo(VkDescriptorImageInfo.create(imageInfos.get(21).address(), 1));
                    writes.get(23)
                            .sType$Default()
                            .dstSet(descriptorSet)
                            .dstBinding(ShaderAbi.DESCRIPTOR_NRD_SUN_PENUMBRA)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .pImageInfo(VkDescriptorImageInfo.create(imageInfos.get(22).address(), 1));
                    writes.get(24)
                            .sType$Default()
                            .dstSet(descriptorSet)
                            .dstBinding(ShaderAbi.DESCRIPTOR_RAW_NUMERICAL_DIAGNOSTIC)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .pImageInfo(VkDescriptorImageInfo.create(imageInfos.get(23).address(), 1));
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
                            labPbrNormalAtlas.view(),
                            labPbrSpecularAtlas.view(),
                            atmosphere.skyView().view(),
                            atmosphere.transmittanceLow().view(),
                            atmosphere.transmittanceHigh().view(),
                            atmosphere.aerialRadiance().view(),
                            atmosphere.aerialTransmittance().view(),
                            targets.noisyDiffuse().view(),
                            targets.noisySpecular().view(),
                            targets.normalRoughness().view(),
                            targets.viewZ().view(),
                            targets.motion().view(),
                            targets.material().view(),
                            targets.specularMaterial().view(),
                            targets.primaryPosition().view(),
                            targets.diffuseDirection().view(),
                            targets.specularDirection().view(),
                            targets.sunLighting().view(),
                            targets.sunPenumbra().view(),
                            targets.rawNumericalDiagnostic().view());
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
                long labPbrNormalAtlas,
                long labPbrSpecularAtlas,
                long skyView,
                long transmittanceLow,
                long transmittanceHigh,
                long aerialRadiance,
                long aerialTransmittance,
                long nrdNoisyDiffuse,
                long nrdNoisySpecular,
                long nrdNormalRoughness,
                long nrdViewZ,
                long nrdMotion,
                long nrdMaterial,
                long nrdSpecularMaterial,
                long nrdPrimaryPosition,
                long nrdDiffuseDirection,
                long nrdSpecularDirection,
                long nrdSunLighting,
                long nrdSunPenumbra,
                long rawNumericalDiagnostic) {
            return this.tlas == tlas
                    && this.outputView == outputView
                    && this.accumulationView == accumulationView
                    && this.atlasView == atlasView
                    && this.atlasSampler == atlasSampler
                    && this.labPbrNormalAtlas == labPbrNormalAtlas
                    && this.labPbrSpecularAtlas == labPbrSpecularAtlas
                    && this.skyView == skyView
                    && this.transmittanceLow == transmittanceLow
                    && this.transmittanceHigh == transmittanceHigh
                    && this.aerialRadiance == aerialRadiance
                    && this.aerialTransmittance == aerialTransmittance
                    && this.nrdNoisyDiffuse == nrdNoisyDiffuse
                    && this.nrdNoisySpecular == nrdNoisySpecular
                    && this.nrdNormalRoughness == nrdNormalRoughness
                    && this.nrdViewZ == nrdViewZ
                    && this.nrdMotion == nrdMotion
                    && this.nrdMaterial == nrdMaterial
                    && this.nrdSpecularMaterial == nrdSpecularMaterial
                    && this.nrdPrimaryPosition == nrdPrimaryPosition
                    && this.nrdDiffuseDirection == nrdDiffuseDirection
                    && this.nrdSpecularDirection == nrdSpecularDirection
                    && this.nrdSunLighting == nrdSunLighting
                    && this.nrdSunPenumbra == nrdSunPenumbra
                    && this.rawNumericalDiagnostic == rawNumericalDiagnostic;
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
