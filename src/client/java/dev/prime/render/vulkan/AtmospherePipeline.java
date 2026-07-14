package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.FrameCamera;
import dev.prime.render.SunDirection;
import dev.prime.render.shader.ShaderAbi;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/**
 * Owns Prime's spectral atmosphere lookup tables and native Vulkan compute pipelines.
 *
 * <p>The transmittance and multiple-scattering tables depend only on the immutable atmosphere
 * model and are generated once. The sky table changes with eye altitude and sun elevation;
 * aerial perspective also changes with the relative camera projection and complete sun direction.
 * No mutable uniform buffer is shared with an in-flight frame: per-dispatch data travels through
 * push constants.
 */
public final class AtmospherePipeline implements Destroyable {
    public static final float WORLD_SEA_LEVEL_Y = ShaderAbi.ATMOSPHERE_WORLD_SEA_LEVEL_Y;
    public static final float WORLD_UNIT_SCALE_KM = ShaderAbi.ATMOSPHERE_WORLD_UNIT_SCALE_KM;
    public static final float AERIAL_MAX_DISTANCE_KM = ShaderAbi.ATMOSPHERE_AERIAL_MAX_DISTANCE_KM;

    private static final float BOTTOM_RADIUS_KM = ShaderAbi.ATMOSPHERE_BOTTOM_RADIUS_KM;
    private static final float TOP_RADIUS_KM = ShaderAbi.ATMOSPHERE_TOP_RADIUS_KM;
    private static final int PUSH_CONSTANT_SIZE = 96;
    private static final int IMAGE_COUNT = 7;
    private static final int BINDING_COUNT = 8;
    private static final int PHASE_LUT_BINDING = 7;
    private static final int PHASE_LUT_BYTE_SIZE = 131_072;
    private static final int COMPUTE_STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;

    private final VulkanContext context;
    private final VulkanImage transmittanceLow;
    private final VulkanImage transmittanceHigh;
    private final VulkanImage multiScatteringLow;
    private final VulkanImage multiScatteringHigh;
    private final VulkanImage skyView;
    private final VulkanImage aerialRadiance;
    private final VulkanImage aerialTransmittance;
    private final VulkanBuffer phaseLut;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long descriptorSet;
    private final long pipelineLayout;
    private final long transmittancePipeline;
    private final long multiScatteringPipeline;
    private final long skyPipeline;
    private final long aerialPipeline;
    private int skyEyeRadiusBits;
    private int skySunElevationBits;
    private int[] aerialKey;
    private boolean staticPrepared;
    private boolean destroyed;

    public AtmospherePipeline(VulkanContext context) {
        this.context = context;
        VulkanImage[] images = new VulkanImage[IMAGE_COUNT];
        VulkanBuffer newPhaseLut = null;
        long newDescriptorSetLayout = 0L;
        long newDescriptorPool = 0L;
        long newPipelineLayout = 0L;
        long newTransmittancePipeline = 0L;
        long newMultiScatteringPipeline = 0L;
        long newSkyPipeline = 0L;
        long newAerialPipeline = 0L;
        long newDescriptorSet = 0L;
        try {
            images[0] = context.createAtmosphereImage2D(256, 64, "Prime atmosphere transmittance low");
            images[1] = context.createAtmosphereImage2D(256, 64, "Prime atmosphere transmittance high");
            images[2] = context.createAtmosphereImage2D(64, 64, "Prime atmosphere multiple scattering low");
            images[3] = context.createAtmosphereImage2D(64, 64, "Prime atmosphere multiple scattering high");
            images[4] = context.createAtmosphereImage2D(256, 256, "Prime atmosphere sky view");
            images[5] = context.createAtmosphereImage3D(32, 32, 32, "Prime atmosphere aerial radiance");
            images[6] = context.createAtmosphereImage3D(32, 32, 32, "Prime atmosphere aerial transmittance");
            newPhaseLut = createPhaseLut(context);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                newDescriptorSetLayout = createDescriptorSetLayout(context, stack);
                newPipelineLayout = createPipelineLayout(context, stack, newDescriptorSetLayout);
                newTransmittancePipeline = createComputePipeline(
                        context,
                        stack,
                        newPipelineLayout,
                        "/prime/shaders/atmosphere_transmittance.comp.spv",
                        "Prime atmosphere transmittance pipeline");
                newMultiScatteringPipeline = createComputePipeline(
                        context,
                        stack,
                        newPipelineLayout,
                        "/prime/shaders/atmosphere_multi_scattering.comp.spv",
                        "Prime atmosphere multiple scattering pipeline");
                newSkyPipeline = createComputePipeline(
                        context,
                        stack,
                        newPipelineLayout,
                        "/prime/shaders/atmosphere_sky.comp.spv",
                        "Prime atmosphere sky pipeline");
                newAerialPipeline = createComputePipeline(
                        context,
                        stack,
                        newPipelineLayout,
                        "/prime/shaders/atmosphere_aerial.comp.spv",
                        "Prime atmosphere aerial perspective pipeline");
                DescriptorAllocation allocation = createDescriptors(
                        context, stack, newDescriptorSetLayout, images, newPhaseLut);
                newDescriptorPool = allocation.pool();
                newDescriptorSet = allocation.set();
            }
            this.transmittanceLow = images[0];
            this.transmittanceHigh = images[1];
            this.multiScatteringLow = images[2];
            this.multiScatteringHigh = images[3];
            this.skyView = images[4];
            this.aerialRadiance = images[5];
            this.aerialTransmittance = images[6];
            this.phaseLut = newPhaseLut;
            this.descriptorSetLayout = newDescriptorSetLayout;
            this.descriptorPool = newDescriptorPool;
            this.descriptorSet = newDescriptorSet;
            this.pipelineLayout = newPipelineLayout;
            this.transmittancePipeline = newTransmittancePipeline;
            this.multiScatteringPipeline = newMultiScatteringPipeline;
            this.skyPipeline = newSkyPipeline;
            this.aerialPipeline = newAerialPipeline;
        } catch (RuntimeException exception) {
            if (newDescriptorPool != 0L) {
                VK12.vkDestroyDescriptorPool(context.vkDevice(), newDescriptorPool, null);
            }
            destroyPipeline(context, newAerialPipeline);
            destroyPipeline(context, newSkyPipeline);
            destroyPipeline(context, newMultiScatteringPipeline);
            destroyPipeline(context, newTransmittancePipeline);
            if (newPipelineLayout != 0L) {
                VK12.vkDestroyPipelineLayout(context.vkDevice(), newPipelineLayout, null);
            }
            if (newDescriptorSetLayout != 0L) {
                VK12.vkDestroyDescriptorSetLayout(context.vkDevice(), newDescriptorSetLayout, null);
            }
            if (newPhaseLut != null) {
                newPhaseLut.destroy();
            }
            for (VulkanImage image : images) {
                if (image != null) {
                    image.destroy();
                }
            }
            throw exception;
        }
    }

    public VulkanImage skyView() {
        return this.skyView;
    }

    public VulkanImage transmittanceLow() {
        return this.transmittanceLow;
    }

    public VulkanImage transmittanceHigh() {
        return this.transmittanceHigh;
    }

    public VulkanImage aerialRadiance() {
        return this.aerialRadiance;
    }

    public VulkanImage aerialTransmittance() {
        return this.aerialTransmittance;
    }

    public static float eyeRadiusKm(double worldY) {
        float radius = BOTTOM_RADIUS_KM + worldAltitudeKm(worldY);
        return Math.max(BOTTOM_RADIUS_KM + 0.001F, Math.min(TOP_RADIUS_KM - 0.001F, radius));
    }

    /**
     * Maps Minecraft height into the deliberately compressed atmosphere used for rendering.
     * Y=-64 is sea level and every block represents four metres, so Y=320 is 1.536 km high.
     */
    public static float worldAltitudeKm(double worldY) {
        return (float) ((worldY - WORLD_SEA_LEVEL_Y) * WORLD_UNIT_SCALE_KM);
    }

    public void prepare(
            VkCommandBuffer commandBuffer,
            FrameCamera camera,
            SunDirection sunDirection) {
        float eyeRadiusKm = AtmospherePipeline.eyeRadiusKm(camera.y());
        int eyeRadiusBits = Float.floatToIntBits(eyeRadiusKm);
        int sunElevationBits = Float.floatToIntBits(sunDirection.y());
        int[] nextAerialKey = createAerialKey(camera, eyeRadiusBits, sunDirection);
        boolean prepareStatic = !this.staticPrepared;
        // Sky-view azimuth is defined relative to the sun's horizontal projection, so rotating
        // that projection around world Y changes only the lookup orientation, not the table data.
        boolean prepareSky = prepareStatic
                || eyeRadiusBits != this.skyEyeRadiusBits
                || sunElevationBits != this.skySunElevationBits;
        boolean prepareAerial = prepareStatic || !Arrays.equals(this.aerialKey, nextAerialKey);
        if (!prepareStatic && !prepareSky && !prepareAerial) {
            return;
        }

        if (prepareStatic) {
            transitionAllToGeneral(commandBuffer);
            dispatch(commandBuffer, this.transmittancePipeline, 32, 8, 1, null);
            computeWriteBarrier(commandBuffer, new VulkanImage[] {
                this.transmittanceLow, this.transmittanceHigh
            }, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                    | KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR);
            // Split the two spectral groups along dispatch Z. This preserves the reference's
            // 256 directions × 128 steps while avoiding one twice-as-long shader invocation,
            // which matters for Windows GPU timeout resilience during the one-time precompute.
            dispatch(commandBuffer, this.multiScatteringPipeline, 8, 8, 2, null);
            computeWriteBarrier(commandBuffer, new VulkanImage[] {
                this.multiScatteringLow, this.multiScatteringHigh
            }, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        } else {
            VulkanImage[] overwritten = prepareSky && prepareAerial
                    ? new VulkanImage[] {this.skyView, this.aerialRadiance, this.aerialTransmittance}
                    : prepareSky
                            ? new VulkanImage[] {this.skyView}
                            : new VulkanImage[] {this.aerialRadiance, this.aerialTransmittance};
            rayReadToComputeWriteBarrier(commandBuffer, overwritten);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer pushConstants = createPushConstants(
                    stack,
                    camera,
                    eyeRadiusKm,
                    sunDirection);
            if (prepareSky) {
                dispatch(commandBuffer, this.skyPipeline, 32, 32, 1, pushConstants);
            }
            if (prepareAerial) {
                dispatch(commandBuffer, this.aerialPipeline, 8, 8, 8, pushConstants);
            }
        }
        VulkanImage[] written = prepareSky && prepareAerial
                ? new VulkanImage[] {this.skyView, this.aerialRadiance, this.aerialTransmittance}
                : prepareSky
                        ? new VulkanImage[] {this.skyView}
                        : new VulkanImage[] {this.aerialRadiance, this.aerialTransmittance};
        computeWriteBarrier(
                commandBuffer,
                written,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR);
        this.staticPrepared = true;
        this.skyEyeRadiusBits = eyeRadiusBits;
        this.skySunElevationBits = sunElevationBits;
        this.aerialKey = nextAerialKey;
    }

    @Override
    public void destroy() {
        if (!this.destroyed) {
            this.destroyed = true;
            VK12.vkDestroyDescriptorPool(this.context.vkDevice(), this.descriptorPool, null);
            VK12.vkDestroyPipeline(this.context.vkDevice(), this.aerialPipeline, null);
            VK12.vkDestroyPipeline(this.context.vkDevice(), this.skyPipeline, null);
            VK12.vkDestroyPipeline(this.context.vkDevice(), this.multiScatteringPipeline, null);
            VK12.vkDestroyPipeline(this.context.vkDevice(), this.transmittancePipeline, null);
            VK12.vkDestroyPipelineLayout(this.context.vkDevice(), this.pipelineLayout, null);
            VK12.vkDestroyDescriptorSetLayout(this.context.vkDevice(), this.descriptorSetLayout, null);
            this.aerialTransmittance.destroy();
            this.aerialRadiance.destroy();
            this.skyView.destroy();
            this.multiScatteringHigh.destroy();
            this.multiScatteringLow.destroy();
            this.transmittanceHigh.destroy();
            this.transmittanceLow.destroy();
            this.phaseLut.destroy();
        }
    }

    private void dispatch(
            VkCommandBuffer commandBuffer,
            long pipeline,
            int groupsX,
            int groupsY,
            int groupsZ,
            ByteBuffer pushConstants) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK12.vkCmdBindPipeline(commandBuffer, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK12.vkCmdBindDescriptorSets(
                    commandBuffer,
                    VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                    this.pipelineLayout,
                    0,
                    stack.longs(this.descriptorSet),
                    null);
            if (pushConstants != null) {
                VK12.vkCmdPushConstants(
                        commandBuffer,
                        this.pipelineLayout,
                        COMPUTE_STAGE,
                        0,
                        pushConstants);
            }
            VK12.vkCmdDispatch(commandBuffer, groupsX, groupsY, groupsZ);
        }
    }

    private void transitionAllToGeneral(VkCommandBuffer commandBuffer) {
        VulkanImage[] images = images();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(images.length, stack);
            for (int index = 0; index < images.length; index++) {
                fillBarrier(
                        barriers.get(index),
                        images[index],
                        VK12.VK_IMAGE_LAYOUT_UNDEFINED,
                        VK12.VK_IMAGE_LAYOUT_GENERAL,
                        VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                        0L,
                        VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
                images[index].markInitialized();
            }
            issueBarrier(commandBuffer, stack, barriers);
        }
    }

    private static void rayReadToComputeWriteBarrier(
            VkCommandBuffer commandBuffer,
            VulkanImage[] images) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(images.length, stack);
            for (int index = 0; index < images.length; index++) {
                fillBarrier(
                        barriers.get(index),
                        images[index],
                        VK12.VK_IMAGE_LAYOUT_GENERAL,
                        VK12.VK_IMAGE_LAYOUT_GENERAL,
                        KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                        VK12.VK_ACCESS_SHADER_READ_BIT,
                        VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK12.VK_ACCESS_SHADER_WRITE_BIT);
            }
            issueBarrier(commandBuffer, stack, barriers);
        }
    }

    private static void computeWriteBarrier(
            VkCommandBuffer commandBuffer,
            VulkanImage[] images,
            long destinationStage) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(images.length, stack);
            for (int index = 0; index < images.length; index++) {
                fillBarrier(
                        barriers.get(index),
                        images[index],
                        VK12.VK_IMAGE_LAYOUT_GENERAL,
                        VK12.VK_IMAGE_LAYOUT_GENERAL,
                        VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK12.VK_ACCESS_SHADER_WRITE_BIT,
                        destinationStage,
                        VK12.VK_ACCESS_SHADER_READ_BIT);
            }
            issueBarrier(commandBuffer, stack, barriers);
        }
    }

    private static void issueBarrier(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VkImageMemoryBarrier2.Buffer barriers) {
        VkDependencyInfo dependency = VkDependencyInfo.calloc(stack)
                .sType$Default()
                .pImageMemoryBarriers(barriers);
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer, dependency);
    }

    private static void fillBarrier(
            VkImageMemoryBarrier2 barrier,
            VulkanImage image,
            int oldLayout,
            int newLayout,
            long sourceStage,
            long sourceAccess,
            long destinationStage,
            long destinationAccess) {
        barrier.sType$Default()
                .srcStageMask(sourceStage)
                .srcAccessMask(sourceAccess)
                .dstStageMask(destinationStage)
                .dstAccessMask(destinationAccess)
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                .image(image.image());
        barrier.subresourceRange()
                .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1);
    }

    private VulkanImage[] images() {
        return new VulkanImage[] {
            this.transmittanceLow,
            this.transmittanceHigh,
            this.multiScatteringLow,
            this.multiScatteringHigh,
            this.skyView,
            this.aerialRadiance,
            this.aerialTransmittance
        };
    }

    private static ByteBuffer createPushConstants(
            MemoryStack stack,
            FrameCamera camera,
            float eyeRadiusKm,
            SunDirection sunDirection) {
        ByteBuffer buffer = stack.calloc(PUSH_CONSTANT_SIZE).order(ByteOrder.nativeOrder());
        float[] matrix = new float[16];
        camera.inverseViewProjection().get(matrix);
        for (int index = 0; index < matrix.length; index++) {
            buffer.putFloat(index * Float.BYTES, matrix[index]);
        }
        buffer.putFloat(64, eyeRadiusKm);
        buffer.putFloat(68, AERIAL_MAX_DISTANCE_KM);
        buffer.putFloat(80, sunDirection.x());
        buffer.putFloat(84, sunDirection.y());
        buffer.putFloat(88, sunDirection.z());
        buffer.putFloat(92, ShaderAbi.ATMOSPHERE_SPACE_SUN_INTENSITY);
        return buffer.position(0).limit(PUSH_CONSTANT_SIZE);
    }

    private static int[] createAerialKey(
            FrameCamera camera,
            int eyeRadiusBits,
            SunDirection sunDirection) {
        float[] matrix = new float[16];
        camera.inverseViewProjection().get(matrix);
        int[] key = new int[20];
        for (int index = 0; index < matrix.length; index++) {
            key[index] = Float.floatToIntBits(matrix[index]);
        }
        key[16] = eyeRadiusBits;
        key[17] = Float.floatToIntBits(sunDirection.x());
        key[18] = Float.floatToIntBits(sunDirection.y());
        key[19] = Float.floatToIntBits(sunDirection.z());
        return key;
    }

    private static long createDescriptorSetLayout(VulkanContext context, MemoryStack stack) {
        VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(BINDING_COUNT, stack);
        for (int index = 0; index < IMAGE_COUNT; index++) {
            bindings.get(index)
                    .binding(index)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .stageFlags(COMPUTE_STAGE);
        }
        bindings.get(PHASE_LUT_BINDING)
                .binding(PHASE_LUT_BINDING)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .stageFlags(COMPUTE_STAGE);
        VkDescriptorSetLayoutCreateInfo createInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .pBindings(bindings);
        LongBuffer pointer = stack.mallocLong(1);
        VulkanContext.check(
                VK12.vkCreateDescriptorSetLayout(context.vkDevice(), createInfo, null, pointer),
                "create Prime atmosphere descriptor set layout");
        return pointer.get(0);
    }

    private static long createPipelineLayout(
            VulkanContext context,
            MemoryStack stack,
            long descriptorSetLayout) {
        VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
                .stageFlags(COMPUTE_STAGE)
                .offset(0)
                .size(PUSH_CONSTANT_SIZE);
        VkPipelineLayoutCreateInfo createInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .pSetLayouts(stack.longs(descriptorSetLayout))
                .pPushConstantRanges(pushRange);
        LongBuffer pointer = stack.mallocLong(1);
        VulkanContext.check(
                VK12.vkCreatePipelineLayout(context.vkDevice(), createInfo, null, pointer),
                "create Prime atmosphere pipeline layout");
        return pointer.get(0);
    }

    private static long createComputePipeline(
            VulkanContext context,
            MemoryStack stack,
            long pipelineLayout,
            String resourceName,
            String label) {
        long module = createShaderModule(context, resourceName);
        try {
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default()
                    .stage(COMPUTE_STAGE)
                    .module(module)
                    .pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer createInfo = VkComputePipelineCreateInfo.calloc(1, stack);
            createInfo.get(0)
                    .sType$Default()
                    .stage(stage)
                    .layout(pipelineLayout);
            LongBuffer pointer = stack.mallocLong(1);
            VulkanContext.check(
                    VK12.vkCreateComputePipelines(
                            context.vkDevice(), 0L, createInfo, null, pointer),
                    "create " + label);
            long pipeline = pointer.get(0);
            context.device().instance().debug().setObjectName(
                    context.vkDevice(), VK12.VK_OBJECT_TYPE_PIPELINE, pipeline, label);
            return pipeline;
        } finally {
            VK12.vkDestroyShaderModule(context.vkDevice(), module, null);
        }
    }

    private static DescriptorAllocation createDescriptors(
            VulkanContext context,
            MemoryStack stack,
            long descriptorSetLayout,
            VulkanImage[] images,
            VulkanBuffer phaseLut) {
        VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
        sizes.get(0)
                .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(IMAGE_COUNT);
        sizes.get(1)
                .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1);
        VkDescriptorPoolCreateInfo poolCreateInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                .sType$Default()
                .maxSets(1)
                .pPoolSizes(sizes);
        LongBuffer poolPointer = stack.mallocLong(1);
        VulkanContext.check(
                VK12.vkCreateDescriptorPool(context.vkDevice(), poolCreateInfo, null, poolPointer),
                "create Prime atmosphere descriptor pool");
        long pool = poolPointer.get(0);
        try {
            VkDescriptorSetAllocateInfo allocateInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default()
                    .descriptorPool(pool)
                    .pSetLayouts(stack.longs(descriptorSetLayout));
            LongBuffer setPointer = stack.mallocLong(1);
            VulkanContext.check(
                    VK12.vkAllocateDescriptorSets(context.vkDevice(), allocateInfo, setPointer),
                    "allocate Prime atmosphere descriptor set");
            long descriptorSet = setPointer.get(0);
            VkDescriptorImageInfo.Buffer imageInfos = VkDescriptorImageInfo.calloc(IMAGE_COUNT, stack);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(BINDING_COUNT, stack);
            for (int index = 0; index < IMAGE_COUNT; index++) {
                imageInfos.get(index)
                        .imageView(images[index].view())
                        .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                writes.get(index)
                        .sType$Default()
                        .dstSet(descriptorSet)
                        .dstBinding(index)
                        .descriptorCount(1)
                        .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(VkDescriptorImageInfo.create(imageInfos.get(index).address(), 1));
            }
            VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack)
                            .buffer(phaseLut.handle())
                            .offset(0L)
                            .range(phaseLut.size());
            writes.get(PHASE_LUT_BINDING)
                    .sType$Default()
                    .dstSet(descriptorSet)
                    .dstBinding(PHASE_LUT_BINDING)
                    .descriptorCount(1)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(bufferInfo);
            VK12.vkUpdateDescriptorSets(context.vkDevice(), writes, null);
            return new DescriptorAllocation(pool, descriptorSet);
        } catch (RuntimeException exception) {
            VK12.vkDestroyDescriptorPool(context.vkDevice(), pool, null);
            throw exception;
        }
    }

    private static long createShaderModule(VulkanContext context, String resourceName) {
        byte[] bytes;
        try (InputStream input = AtmospherePipeline.class.getResourceAsStream(resourceName)) {
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

    private static VulkanBuffer createPhaseLut(VulkanContext context) {
        // The immutable payload is the reference engine's little-endian AoS table:
        // 2048 entries × four species × RGBA wavelengths. Keeping it compressed textual source
        // avoids platform-dependent generation while the hash test guards its physical meaning.
        byte[] bytes;
        try (InputStream encoded = AtmospherePipeline.class.getResourceAsStream(
                        "/prime/atmosphere/phase_lut.bin.gz.b64")) {
            if (encoded == null) {
                throw new IllegalStateException("Missing Prime atmosphere phase LUT");
            }
            try (InputStream decoded = Base64.getMimeDecoder().wrap(encoded);
                    GZIPInputStream decompressed = new GZIPInputStream(decoded)) {
                bytes = decompressed.readAllBytes();
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read Prime atmosphere phase LUT", exception);
        }
        if (bytes.length != PHASE_LUT_BYTE_SIZE) {
            throw new IllegalStateException(
                    "Unexpected Prime atmosphere phase LUT size " + bytes.length);
        }
        VulkanBuffer buffer = context.createBuffer(
                bytes.length,
                VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                true,
                "Prime atmosphere phase LUT");
        ByteBuffer data = MemoryUtil.memAlloc(bytes.length);
        try {
            data.put(bytes).flip();
            buffer.put(0L, data);
            return buffer;
        } catch (RuntimeException exception) {
            buffer.destroy();
            throw exception;
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    private static void destroyPipeline(VulkanContext context, long pipeline) {
        if (pipeline != 0L) {
            VK12.vkDestroyPipeline(context.vkDevice(), pipeline, null);
        }
    }

    private record DescriptorAllocation(long pool, long set) {
    }
}
