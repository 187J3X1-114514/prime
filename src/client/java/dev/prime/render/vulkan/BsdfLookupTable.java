package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

/** Owns immutable BSDF energy and area-light proposal lookup data. */
final class BsdfLookupTable implements Destroyable {
    static final int WIDTH = 44;
    static final int HEIGHT = 32;
    static final int DEPTH = 159;
    static final int CHANNELS = 4;
    static final int BYTE_SIZE = WIDTH * HEIGHT * DEPTH * CHANNELS * Short.BYTES;

    static final int LTC_RESOLUTION = 64;
    static final int LTC_FRESNEL_COUNT = 51;
    static final int LTC_MATRIX_CHANNELS = 4;
    static final int LTC_AMPLITUDE_CHANNELS = 2;
    static final int LTC_MATRIX_BYTE_SIZE = LTC_RESOLUTION
            * LTC_RESOLUTION
            * LTC_FRESNEL_COUNT
            * LTC_MATRIX_CHANNELS
            * Short.BYTES;
    static final int LTC_AMPLITUDE_BYTE_SIZE = LTC_RESOLUTION
            * LTC_RESOLUTION
            * LTC_FRESNEL_COUNT
            * LTC_AMPLITUDE_CHANNELS
            * Short.BYTES;
    private static final long LTC_MATRIX_OFFSET = BYTE_SIZE;
    private static final long LTC_AMPLITUDE_OFFSET = LTC_MATRIX_OFFSET + LTC_MATRIX_BYTE_SIZE;
    private static final long UPLOAD_BYTE_SIZE = LTC_AMPLITUDE_OFFSET + LTC_AMPLITUDE_BYTE_SIZE;

    private final VulkanContext context;
    private final VulkanImage transmissionGgxEnergy;
    private final VulkanImage ggxLtcMatrix;
    private final VulkanImage ggxLtcAmplitude;
    private final VulkanBuffer upload;
    private final long sampler;
    private boolean prepared;
    private boolean pending;
    private boolean destroyed;

    BsdfLookupTable(VulkanContext context) {
        this.context = context;
        VulkanImage newTransmission = null;
        VulkanImage newMatrix = null;
        VulkanImage newAmplitude = null;
        VulkanBuffer newUpload = null;
        long newSampler = 0L;
        try {
            newTransmission = context.createSampledImage3D(
                    WIDTH,
                    HEIGHT,
                    DEPTH,
                    VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    "Prime transmission GGX energy");
            newMatrix = context.createSampledImage3D(
                    LTC_RESOLUTION,
                    LTC_RESOLUTION,
                    LTC_FRESNEL_COUNT,
                    VK12.VK_FORMAT_R16G16B16A16_UNORM,
                    "Prime GGX LTC matrix");
            newAmplitude = context.createSampledImage3D(
                    LTC_RESOLUTION,
                    LTC_RESOLUTION,
                    LTC_FRESNEL_COUNT,
                    VK12.VK_FORMAT_R16G16_UNORM,
                    "Prime GGX LTC amplitude");
            newUpload = context.createBuffer(
                    UPLOAD_BYTE_SIZE,
                    VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    true,
                    "Prime BSDF lookup upload");
            writeResources(newUpload);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkSamplerCreateInfo createInfo = VkSamplerCreateInfo.calloc(stack)
                        .sType$Default()
                        .magFilter(VK12.VK_FILTER_LINEAR)
                        .minFilter(VK12.VK_FILTER_LINEAR)
                        .mipmapMode(VK12.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                        .addressModeU(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeV(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeW(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .minLod(0.0F)
                        .maxLod(0.0F)
                        .maxAnisotropy(1.0F);
                LongBuffer pointer = stack.mallocLong(1);
                VulkanContext.check(
                        VK12.vkCreateSampler(context.vkDevice(), createInfo, null, pointer),
                        "create Prime BSDF lookup sampler");
                newSampler = pointer.get(0);
            }
            context.device().instance().debug().setObjectName(
                    context.vkDevice(),
                    VK12.VK_OBJECT_TYPE_SAMPLER,
                    newSampler,
                    "Prime BSDF lookup sampler");
            this.transmissionGgxEnergy = newTransmission;
            this.ggxLtcMatrix = newMatrix;
            this.ggxLtcAmplitude = newAmplitude;
            this.upload = newUpload;
            this.sampler = newSampler;
        } catch (RuntimeException exception) {
            if (newSampler != 0L) {
                VK12.vkDestroySampler(context.vkDevice(), newSampler, null);
            }
            destroy(newUpload);
            destroy(newAmplitude);
            destroy(newMatrix);
            destroy(newTransmission);
            throw exception;
        }
    }

    VulkanImage transmissionGgxEnergy() {
        return this.transmissionGgxEnergy;
    }

    VulkanImage ggxLtcMatrix() {
        return this.ggxLtcMatrix;
    }

    VulkanImage ggxLtcAmplitude() {
        return this.ggxLtcAmplitude;
    }

    long sampler() {
        return this.sampler;
    }

    boolean prepare(
            VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization) {
        if (this.prepared) {
            return false;
        }
        if (this.pending) {
            throw new IllegalStateException(
                    "BSDF lookup upload is already pending submission");
        }
        this.pending = true;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanImage[] images = images();
            for (VulkanImage image : images) {
                if (initialization.prepare(image)) {
                    throw new IllegalStateException(
                            "BSDF lookup image is initialized without committed upload state");
                }
            }
            VkImageMemoryBarrier2.Buffer toTransfer =
                    VkImageMemoryBarrier2.calloc(images.length, stack);
            for (int index = 0; index < images.length; index++) {
                fillBarrier(
                        toTransfer.get(index),
                        images[index],
                        VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                        0L,
                        VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK12.VK_IMAGE_LAYOUT_UNDEFINED,
                        VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            }
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                    commandBuffer,
                    VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toTransfer));

            VkBufferImageCopy.Buffer copies = VkBufferImageCopy.calloc(images.length, stack);
            fillCopy(copies.get(0), 0L, WIDTH, HEIGHT, DEPTH);
            fillCopy(
                    copies.get(1),
                    LTC_MATRIX_OFFSET,
                    LTC_RESOLUTION,
                    LTC_RESOLUTION,
                    LTC_FRESNEL_COUNT);
            fillCopy(
                    copies.get(2),
                    LTC_AMPLITUDE_OFFSET,
                    LTC_RESOLUTION,
                    LTC_RESOLUTION,
                    LTC_FRESNEL_COUNT);
            for (int index = 0; index < images.length; index++) {
                VK12.vkCmdCopyBufferToImage(
                        commandBuffer,
                        this.upload.handle(),
                        images[index].image(),
                        VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VkBufferImageCopy.create(copies.get(index).address(), 1));
            }

            VkImageMemoryBarrier2.Buffer toShader =
                    VkImageMemoryBarrier2.calloc(images.length, stack);
            for (int index = 0; index < images.length; index++) {
                fillBarrier(
                        toShader.get(index),
                        images[index],
                        VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                        KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                        VK12.VK_ACCESS_SHADER_READ_BIT,
                        VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                    commandBuffer,
                    VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toShader));
            return true;
        } catch (RuntimeException exception) {
            this.pending = false;
            throw exception;
        }
    }

    void submitted() {
        if (!this.pending) {
            throw new IllegalStateException(
                    "BSDF lookup upload is not pending submission");
        }
        this.prepared = true;
        this.pending = false;
    }

    void abandon() {
        if (!this.pending) {
            throw new IllegalStateException(
                    "BSDF lookup upload is not pending submission");
        }
        this.pending = false;
    }

    private VulkanImage[] images() {
        return new VulkanImage[] {
            this.transmissionGgxEnergy, this.ggxLtcMatrix, this.ggxLtcAmplitude
        };
    }

    private static void fillCopy(
            VkBufferImageCopy copy, long offset, int width, int height, int depth) {
        copy.bufferOffset(offset).bufferRowLength(0).bufferImageHeight(0);
        copy.imageSubresource()
                .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0)
                .baseArrayLayer(0)
                .layerCount(1);
        copy.imageOffset().set(0, 0, 0);
        copy.imageExtent().set(width, height, depth);
    }

    private static void fillBarrier(
            VkImageMemoryBarrier2 barrier,
            VulkanImage image,
            long sourceStage,
            long sourceAccess,
            long destinationStage,
            long destinationAccess,
            int oldLayout,
            int newLayout) {
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

    private static void writeResources(VulkanBuffer destination) {
        writeResource(
                destination,
                0L,
                BYTE_SIZE,
                "/prime/bsdf/trans_ggx.bytes.gz.b64",
                "transmission GGX energy");
        writeResource(
                destination,
                LTC_MATRIX_OFFSET,
                LTC_MATRIX_BYTE_SIZE,
                "/prime/light/ggx_ltc_matrix.bytes.gz.b64",
                "GGX LTC matrix");
        writeResource(
                destination,
                LTC_AMPLITUDE_OFFSET,
                LTC_AMPLITUDE_BYTE_SIZE,
                "/prime/light/ggx_ltc_amplitude.bytes.gz.b64",
                "GGX LTC amplitude");
    }

    private static void writeResource(
            VulkanBuffer destination,
            long offset,
            int expectedSize,
            String resource,
            String description) {
        byte[] bytes;
        try (InputStream encoded = BsdfLookupTable.class.getResourceAsStream(resource)) {
            if (encoded == null) {
                throw new IllegalStateException("Missing " + description + " table");
            }
            try (InputStream decoded = Base64.getMimeDecoder().wrap(encoded);
                    GZIPInputStream decompressed = new GZIPInputStream(decoded)) {
                bytes = decompressed.readAllBytes();
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + description + " table", exception);
        }
        if (bytes.length != expectedSize) {
            throw new IllegalStateException(
                    "Unexpected " + description + " byte size " + bytes.length);
        }
        ByteBuffer source = MemoryUtil.memAlloc(bytes.length);
        try {
            source.put(bytes).flip();
            destination.put(offset, source);
        } finally {
            MemoryUtil.memFree(source);
        }
    }

    private static void destroy(Destroyable resource) {
        if (resource != null) {
            resource.destroy();
        }
    }

    @Override
    public void destroy() {
        if (!this.destroyed) {
            this.destroyed = true;
            VK12.vkDestroySampler(this.context.vkDevice(), this.sampler, null);
            this.upload.destroy();
            this.ggxLtcAmplitude.destroy();
            this.ggxLtcMatrix.destroy();
            this.transmissionGgxEnergy.destroy();
        }
    }
}
