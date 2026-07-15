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

/** Owns the immutable lookup data required by the connected RoboCute BSDF closures. */
final class BsdfLookupTable implements Destroyable {
    static final int RESOLUTION = 32;
    static final int BYTE_SIZE = RESOLUTION * RESOLUTION * RESOLUTION * 4 * Float.BYTES;

    private final VulkanContext context;
    private final VulkanImage transmissionGgxEnergy;
    private final VulkanBuffer upload;
    private final long sampler;
    private boolean prepared;
    private boolean destroyed;

    BsdfLookupTable(VulkanContext context) {
        this.context = context;
        VulkanImage newImage = null;
        VulkanBuffer newUpload = null;
        long newSampler = 0L;
        try {
            newImage = context.createSampledImage3D(
                    RESOLUTION,
                    RESOLUTION,
                    RESOLUTION,
                    VK12.VK_FORMAT_R32G32B32A32_SFLOAT,
                    "Prime transmission GGX energy");
            newUpload = context.createBuffer(
                    BYTE_SIZE,
                    VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    true,
                    "Prime transmission GGX energy upload");
            writeResource(newUpload);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkSamplerCreateInfo createInfo = VkSamplerCreateInfo.calloc(stack)
                        .sType$Default()
                        .magFilter(VK12.VK_FILTER_LINEAR)
                        .minFilter(VK12.VK_FILTER_LINEAR)
                        .mipmapMode(VK12.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                        .addressModeU(VK12.VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT)
                        .addressModeV(VK12.VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT)
                        .addressModeW(VK12.VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT)
                        .minLod(0.0F)
                        .maxLod(0.0F)
                        .maxAnisotropy(1.0F);
                LongBuffer pointer = stack.mallocLong(1);
                VulkanContext.check(
                        VK12.vkCreateSampler(context.vkDevice(), createInfo, null, pointer),
                        "create Prime transmission GGX sampler");
                newSampler = pointer.get(0);
            }
            context.device().instance().debug().setObjectName(
                    context.vkDevice(),
                    VK12.VK_OBJECT_TYPE_SAMPLER,
                    newSampler,
                    "Prime transmission GGX energy sampler");
            this.transmissionGgxEnergy = newImage;
            this.upload = newUpload;
            this.sampler = newSampler;
        } catch (RuntimeException exception) {
            if (newSampler != 0L) {
                VK12.vkDestroySampler(context.vkDevice(), newSampler, null);
            }
            if (newUpload != null) {
                newUpload.destroy();
            }
            if (newImage != null) {
                newImage.destroy();
            }
            throw exception;
        }
    }

    VulkanImage transmissionGgxEnergy() {
        return this.transmissionGgxEnergy;
    }

    long sampler() {
        return this.sampler;
    }

    void prepare(VkCommandBuffer commandBuffer) {
        if (this.prepared) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer toTransfer = VkImageMemoryBarrier2.calloc(1, stack);
            fillBarrier(
                    toTransfer.get(0),
                    VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    0L,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK12.VK_IMAGE_LAYOUT_UNDEFINED,
                    VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                    commandBuffer,
                    VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toTransfer));

            VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack);
            copy.get(0).bufferOffset(0L).bufferRowLength(0).bufferImageHeight(0);
            copy.get(0).imageSubresource()
                    .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0)
                    .baseArrayLayer(0)
                    .layerCount(1);
            copy.get(0).imageOffset().set(0, 0, 0);
            copy.get(0).imageExtent().set(RESOLUTION, RESOLUTION, RESOLUTION);
            VK12.vkCmdCopyBufferToImage(
                    commandBuffer,
                    this.upload.handle(),
                    this.transmissionGgxEnergy.image(),
                    VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    copy);

            VkImageMemoryBarrier2.Buffer toShader = VkImageMemoryBarrier2.calloc(1, stack);
            fillBarrier(
                    toShader.get(0),
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    VK12.VK_ACCESS_SHADER_READ_BIT,
                    VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                    commandBuffer,
                    VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toShader));
        }
        this.transmissionGgxEnergy.markInitialized();
        this.prepared = true;
    }

    private void fillBarrier(
            VkImageMemoryBarrier2 barrier,
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
                .image(this.transmissionGgxEnergy.image());
        barrier.subresourceRange()
                .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1);
    }

    private static void writeResource(VulkanBuffer destination) {
        byte[] bytes;
        try (InputStream encoded = BsdfLookupTable.class.getResourceAsStream(
                        "/prime/bsdf/trans_ggx.bytes.gz.b64")) {
            if (encoded == null) {
                throw new IllegalStateException("Missing transmission GGX energy table");
            }
            try (InputStream decoded = Base64.getMimeDecoder().wrap(encoded);
                    GZIPInputStream decompressed = new GZIPInputStream(decoded)) {
                bytes = decompressed.readAllBytes();
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read transmission GGX energy table", exception);
        }
        if (bytes.length != BYTE_SIZE) {
            throw new IllegalStateException(
                    "Unexpected transmission GGX energy byte size " + bytes.length);
        }
        ByteBuffer source = MemoryUtil.memAlloc(bytes.length);
        try {
            source.put(bytes).flip();
            destination.put(0L, source);
        } finally {
            MemoryUtil.memFree(source);
        }
    }

    @Override
    public void destroy() {
        if (!this.destroyed) {
            this.destroyed = true;
            VK12.vkDestroySampler(this.context.vkDevice(), this.sampler, null);
            this.upload.destroy();
            this.transmissionGgxEnergy.destroy();
        }
    }
}
