package dev.prime.render.vulkan;

import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.terrain.LabPbrAtlasFrame;
import dev.prime.render.terrain.LabPbrMaterialSet;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;

/**
 * Owns LabPBR 1.3 material atlases that exactly mirror Minecraft's stitched block-atlas layout.
 *
 * <p>The two source maps remain separate because all eight channels are part of the public
 * material contract. A missing map is represented by immutable availability bits in terrain
 * primitives, not by an ambiguous texel sentinel. Animated maps follow the base sprite's real
 * source-frame sequence; a single-frame auxiliary map is intentionally reused for every frame.
 */
public final class LabPbrTextureAtlas implements AutoCloseable {
    private static final int NORMAL_DEFAULT_ARGB = 0xff8080ff;
    private static final int SPECULAR_DEFAULT_ARGB = 0xff000400;

    private final VulkanContext context;
    private final StagingArena stagingArena;
    private final ArrayList<AnimationUpdate> animationUpdates = new ArrayList<>();
    private final ArrayList<Copy> animationCopies = new ArrayList<>();
    private List<LabPbrAtlasFrame.AnimationSample> animationSamples = List.of();
    private Resources resources;
    private FrameToken pending;
    private boolean closed;

    public LabPbrTextureAtlas(VulkanContext context, StagingArena stagingArena) {
        this.context = context;
        this.stagingArena = stagingArena;
    }

    public LabPbrMaterialSet ensure(
            LabPbrAtlasFrame frame, long vanillaAtlasView) {
        if (this.closed) {
            throw new IllegalStateException("LabPBR atlas is closed");
        }
        long generation = frame.sourceGeneration();
        this.animationSamples = frame.animations();
        if (this.resources != null
                && this.resources.sourceGeneration == generation
                && this.resources.vanillaAtlasView == vanillaAtlasView) {
            return this.resources.materials;
        }
        if (this.pending != null) {
            throw new IllegalStateException(
                    "Cannot replace the LabPBR atlas with an outstanding upload");
        }
        Resources replacement = build(frame.snapshot(), vanillaAtlasView, generation);
        Resources previous = this.resources;
        this.resources = replacement;
        this.animationUpdates.clear();
        this.animationCopies.clear();
        if (previous != null) {
            if (previous.prepared) {
                this.context.defer(previous);
            } else {
                previous.destroy();
            }
        }
        return replacement.materials;
    }

    /** Source-pack generation currently represented by the resident auxiliary atlases. */
    public long sourceGeneration() {
        return requireResources().sourceGeneration;
    }

    public VulkanImage normalAtlas() {
        return requireResources().normalAtlas;
    }

    public VulkanImage specularAtlas() {
        return requireResources().specularAtlas;
    }

    /** Records the initial upload and any base-animation frame changes. */
    public FrameToken prepare(VkCommandBuffer commandBuffer) {
        if (this.pending != null) {
            throw new IllegalStateException(
                    "Previous LabPBR upload has not been submitted or abandoned");
        }
        Resources current = requireResources();
        this.animationCopies.clear();
        boolean initialUpload = !current.prepared;
        if (initialUpload) {
            recordInitialUpload(commandBuffer, current);
        }
        current.collectAnimationChanges(this.animationSamples, this.animationUpdates);
        if (this.animationUpdates.isEmpty()) {
            return this.publish(null, current, initialUpload, 0);
        }
        long requiredCapacity = 0L;
        for (AnimationUpdate update : this.animationUpdates) {
            requiredCapacity = Math.max(
                    requiredCapacity,
                    animationEndOffset(0L, update.sprite, current.mipLevels));
        }
        StagingArena.Batch batch = this.stagingArena.tryBeginBatch(requiredCapacity);
        if (batch == null) {
            return this.publish(null, current, initialUpload, 0);
        }
        try {
            long budget = 0L;
            int acceptedCount = 0;
            for (int index = 0; index < this.animationUpdates.size(); index++) {
                AnimationUpdate change = this.animationUpdates.get(index);
                LabPbrAtlasFrame.Sprite sprite = change.sprite;
                long spriteBudget = animationEndOffset(budget, sprite, current.mipLevels);
                if (spriteBudget > batch.capacity()) {
                    continue;
                }
                for (int mip = 0; mip < current.mipLevels; mip++) {
                    if (sprite.normal() != null) {
                        addAnimatedCopy(
                                this.animationCopies,
                                batch,
                                current.normalAtlas,
                                sprite,
                                sprite.normal(),
                                change.sample,
                                mip,
                                false);
                    }
                    if (sprite.specular() != null) {
                        addAnimatedCopy(
                                this.animationCopies,
                                batch,
                                current.specularAtlas,
                                sprite,
                                sprite.specular(),
                                change.sample,
                                mip,
                                true);
                    }
                }
                budget = spriteBudget;
                this.animationUpdates.set(acceptedCount++, change);
            }
            if (this.animationCopies.isEmpty()) {
                batch.close();
                return this.publish(null, current, initialUpload, 0);
            }
            boolean normalChanged = false;
            boolean specularChanged = false;
            for (Copy copy : this.animationCopies) {
                normalChanged |= copy.image == current.normalAtlas;
                specularChanged |= copy.image == current.specularAtlas;
            }
            transitionForCopies(
                    commandBuffer,
                    current,
                    normalChanged,
                    specularChanged,
                    current.prepared || initialUpload,
                    true);
            for (Copy copy : this.animationCopies) {
                recordCopy(commandBuffer, copy);
            }
            transitionForCopies(
                    commandBuffer,
                    current,
                    normalChanged,
                    specularChanged,
                    true,
                    false);
            return this.publish(
                    batch, current, initialUpload, acceptedCount);
        } catch (RuntimeException exception) {
            throw ResourceCleanup.close(batch, exception);
        }
    }

    /** Must be called immediately after the command buffer containing the token is submitted. */
    public void submitted(FrameToken token) {
        if (token == null) {
            return;
        }
        if (token.atlas != this
                || token != this.pending
                || token.finished) {
            throw new IllegalArgumentException("LabPBR frame token does not belong to this submission");
        }
        token.finished = true;
        this.pending = null;
        if (token.initialUpload) {
            token.owner.prepared = true;
            token.owner.normalAtlas.markInitialized();
            token.owner.specularAtlas.markInitialized();
        }
        for (int index = 0; index < token.animationUpdateCount; index++) {
            AnimationUpdate update = this.animationUpdates.get(index);
            update.owner.lastSample = update.sample;
        }
        RuntimeException failure = null;
        if (token.batch != null) {
            failure = ResourceCleanup.run(token.batch::submitted, null);
            failure = ResourceCleanup.close(token.batch, failure);
        }
        if (token.initialUpload) {
            failure = ResourceCleanup.run(
                    () -> token.owner.retireUploads(this.context), failure);
        }
        ResourceCleanup.throwIfFailed(failure);
    }

    /** Releases staging for a recorded upload whose command buffer was not submitted. */
    public void abandon(FrameToken token) {
        if (token == null) {
            return;
        }
        if (token.atlas != this
                || token != this.pending
                || token.finished) {
            throw new IllegalArgumentException(
                    "LabPBR frame token does not belong to this atlas");
        }
        token.finished = true;
        this.pending = null;
        ResourceCleanup.throwIfFailed(ResourceCleanup.close(token.batch, null));
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            RuntimeException failure = null;
            if (this.pending != null) {
                FrameToken abandoned = this.pending;
                this.pending = null;
                abandoned.finished = true;
                failure = ResourceCleanup.close(abandoned.batch, failure);
            }
            if (this.resources != null) {
                failure = ResourceCleanup.destroy(this.resources, failure);
                this.resources = null;
            }
            ResourceCleanup.throwIfFailed(failure);
        }
    }

    private FrameToken publish(
            StagingArena.Batch batch,
            Resources owner,
            boolean initialUpload,
            int animationUpdateCount) {
        if (!initialUpload && batch == null) {
            return null;
        }
        if (batch != null) {
            batch.prepareForSubmission();
        }
        FrameToken token = new FrameToken(
                this,
                batch,
                owner,
                initialUpload,
                animationUpdateCount);
        this.pending = token;
        return token;
    }

    private Resources requireResources() {
        if (this.resources == null) {
            throw new IllegalStateException("LabPBR atlas was not synchronized with Minecraft");
        }
        return this.resources;
    }

    private Resources build(
            LabPbrAtlasFrame.Snapshot source,
            long vanillaAtlasView,
            long sourceGeneration) {
        int width = source.width();
        int height = source.height();
        int mipLevels = source.mipLevels();
        VulkanImage normalAtlas = null;
        VulkanImage specularAtlas = null;
        VulkanBuffer normalUpload = null;
        VulkanBuffer specularUpload = null;
        try {
            int usage = VK12.VK_IMAGE_USAGE_SAMPLED_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
            normalAtlas = this.context.createMipmappedImage2D(
                    width, height, mipLevels, VK12.VK_FORMAT_R8G8B8A8_UNORM, usage,
                    "Prime LabPBR normal atlas");
            specularAtlas = this.context.createMipmappedImage2D(
                    width, height, mipLevels, VK12.VK_FORMAT_R8G8B8A8_UNORM, usage,
                    "Prime LabPBR specular atlas");
            long byteSize = totalMipBytes(width, height, mipLevels);
            normalUpload = this.context.createBuffer(
                    byteSize, VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true,
                    "Prime LabPBR normal atlas upload");
            specularUpload = this.context.createBuffer(
                    byteSize, VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true,
                    "Prime LabPBR specular atlas upload");
            fillAtlas(
                    normalUpload,
                    width,
                    height,
                    mipLevels,
                    source.sprites(),
                    true,
                    NORMAL_DEFAULT_ARGB);
            fillAtlas(
                    specularUpload,
                    width,
                    height,
                    mipLevels,
                    source.sprites(),
                    false,
                    SPECULAR_DEFAULT_ARGB);
            return new Resources(
                    sourceGeneration,
                    vanillaAtlasView,
                    width,
                    height,
                    mipLevels,
                    normalAtlas,
                    specularAtlas,
                    normalUpload,
                    specularUpload,
                    source.materials(),
                    source.sprites());
        } catch (RuntimeException exception) {
            RuntimeException failure = ResourceCleanup.destroy(specularUpload, exception);
            failure = ResourceCleanup.destroy(normalUpload, failure);
            failure = ResourceCleanup.destroy(specularAtlas, failure);
            failure = ResourceCleanup.destroy(normalAtlas, failure);
            throw failure;
        }
    }

    private static void fillAtlas(
            VulkanBuffer upload,
            int atlasWidth,
            int atlasHeight,
            int mipLevels,
            List<LabPbrAtlasFrame.Sprite> sprites,
            boolean normal,
            int defaultArgb) {
        long byteSize = totalMipBytes(atlasWidth, atlasHeight, mipLevels);
        long target = upload.mappedAddress();
        fillArgb(target, byteSize, defaultArgb);
        long mipOffset = 0L;
        for (int mip = 0; mip < mipLevels; mip++) {
            int mipWidth = Math.max(1, atlasWidth >> mip);
            for (LabPbrAtlasFrame.Sprite sprite : sprites) {
                LabPbrAtlasFrame.MaterialSource source =
                        normal ? sprite.normal() : sprite.specular();
                if (source != null) {
                    writeSprite(
                            target,
                            mipOffset,
                            mipWidth,
                            sprite,
                            source,
                            LabPbrAtlasFrame.AnimationSample.ZERO,
                            mip,
                            false,
                            !normal);
                }
            }
            mipOffset += (long) mipWidth * Math.max(1, atlasHeight >> mip) * 4L;
        }
        upload.flush(0L, byteSize);
    }

    private static void writeSprite(
            long target,
            long baseOffset,
            int rowWidth,
            LabPbrAtlasFrame.Sprite sprite,
            LabPbrAtlasFrame.MaterialSource source,
            LabPbrAtlasFrame.AnimationSample sample,
            int mip,
            boolean tightlyPacked,
            boolean specular) {
        int outputWidth = sprite.mipWidth(mip);
        int outputHeight = sprite.mipHeight(mip);
        int destinationX = tightlyPacked ? 0 : sprite.mipX(mip);
        int destinationY = tightlyPacked ? 0 : sprite.mipY(mip);
        int baseWidth = sprite.contentWidth() + 2 * sprite.padding();
        int baseHeight = sprite.contentHeight() + 2 * sprite.padding();
        for (int y = 0; y < outputHeight; y++) {
            double baseY0 = (double) y * baseHeight / outputHeight - sprite.padding();
            double baseY1 = (double) (y + 1) * baseHeight / outputHeight - sprite.padding();
            for (int x = 0; x < outputWidth; x++) {
                double baseX0 = (double) x * baseWidth / outputWidth - sprite.padding();
                double baseX1 = (double) (x + 1) * baseWidth / outputWidth - sprite.padding();
                int pixel = source.filtered(
                        sample,
                        baseX0,
                        baseY0,
                        baseX1,
                        baseY1,
                        sprite.contentWidth(),
                        sprite.contentHeight(),
                        specular);
                long offset = Math.addExact(
                        baseOffset,
                        Math.multiplyExact(
                                Math.addExact(
                                        Math.multiplyExact((long) destinationY + y, rowWidth),
                                        (long) destinationX + x),
                                4L));
                writeArgb(target, offset, pixel);
            }
        }
    }

    private static void recordInitialUpload(VkCommandBuffer commandBuffer, Resources resources) {
        transitionForCopies(
                commandBuffer, resources, true, true, false, true);
        long offset = 0L;
        for (int mip = 0; mip < resources.mipLevels; mip++) {
            int width = Math.max(1, resources.width >> mip);
            int height = Math.max(1, resources.height >> mip);
            recordCopy(
                    commandBuffer,
                    new Copy(resources.normalAtlas, resources.normalUpload.handle(), offset, mip, 0, 0, width, height));
            recordCopy(
                    commandBuffer,
                    new Copy(resources.specularAtlas, resources.specularUpload.handle(), offset, mip, 0, 0, width, height));
            offset += (long) width * height * 4L;
        }
        transitionForCopies(
                commandBuffer, resources, true, true, true, false);
    }

    private static void addAnimatedCopy(
            List<Copy> copies,
            StagingArena.Batch batch,
            VulkanImage image,
            LabPbrAtlasFrame.Sprite sprite,
            LabPbrAtlasFrame.MaterialSource source,
            LabPbrAtlasFrame.AnimationSample sample,
            int mip,
            boolean specular) {
        int width = sprite.mipWidth(mip);
        int height = sprite.mipHeight(mip);
        long byteSize = Math.multiplyExact(Math.multiplyExact((long) width, height), 4L);
        StagingArena.Slice slice = batch.allocate(byteSize, 4L);
        writeSprite(
                slice.mappedAddress(), 0L, width, sprite, source, sample, mip, true, specular);
        copies.add(new Copy(
                image,
                slice.buffer(),
                slice.offset(),
                mip,
                sprite.mipX(mip),
                sprite.mipY(mip),
                width,
                height));
    }

    private static void recordCopy(VkCommandBuffer commandBuffer, Copy copy) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0)
                    .bufferOffset(copy.bufferOffset)
                    .bufferRowLength(0)
                    .bufferImageHeight(0);
            region.get(0).imageSubresource()
                    .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(copy.mip)
                    .baseArrayLayer(0)
                    .layerCount(1);
            region.get(0).imageOffset().set(copy.x, copy.y, 0);
            region.get(0).imageExtent().set(copy.width, copy.height, 1);
            VK12.vkCmdCopyBufferToImage(
                    commandBuffer,
                    copy.buffer,
                    copy.image.image(),
                    VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    region);
        }
    }

    private static void transitionForCopies(
            VkCommandBuffer commandBuffer,
            Resources resources,
            boolean normal,
            boolean specular,
            boolean initialized,
            boolean toTransfer) {
        int count = (normal ? 1 : 0) + (specular ? 1 : 0);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(count, stack);
            int index = 0;
            if (normal) {
                fillBarrier(
                        barriers.get(index++),
                        resources.normalAtlas,
                        resources.mipLevels,
                        initialized,
                        toTransfer);
            }
            if (specular) {
                fillBarrier(
                        barriers.get(index),
                        resources.specularAtlas,
                        resources.mipLevels,
                        initialized,
                        toTransfer);
            }
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                    commandBuffer,
                    VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers));
        }
    }

    private static void fillBarrier(
            VkImageMemoryBarrier2 barrier,
            VulkanImage image,
            int mipLevels,
            boolean initialized,
            boolean toTransfer) {
        barrier.sType$Default()
                .srcStageMask(toTransfer
                        ? (initialized ? KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR : VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT)
                        : VK12.VK_PIPELINE_STAGE_TRANSFER_BIT)
                .srcAccessMask(toTransfer && initialized ? VK12.VK_ACCESS_SHADER_READ_BIT : toTransfer ? 0L : VK12.VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstStageMask(toTransfer
                        ? VK12.VK_PIPELINE_STAGE_TRANSFER_BIT
                        : KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR)
                .dstAccessMask(toTransfer ? VK12.VK_ACCESS_TRANSFER_WRITE_BIT : VK12.VK_ACCESS_SHADER_READ_BIT)
                .oldLayout(toTransfer
                        ? (initialized ? VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : VK12.VK_IMAGE_LAYOUT_UNDEFINED)
                        : VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .newLayout(toTransfer
                        ? VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                        : VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                .image(image.image());
        barrier.subresourceRange()
                .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(mipLevels)
                .baseArrayLayer(0)
                .layerCount(1);
    }

    static long totalMipBytes(int width, int height, int mipLevels) {
        long result = 0L;
        for (int mip = 0; mip < mipLevels; mip++) {
            result = Math.addExact(
                    result,
                    Math.multiplyExact(
                            (long) Math.max(1, width >> mip) * Math.max(1, height >> mip),
                            4L));
        }
        return result;
    }

    static void writeArgb(ByteBuffer target, int offset, int argb) {
        target.put(offset, (byte) (argb >>> 16));
        target.put(offset + 1, (byte) (argb >>> 8));
        target.put(offset + 2, (byte) argb);
        target.put(offset + 3, (byte) (argb >>> 24));
    }

    private static void writeArgb(long target, long offset, int argb) {
        MemoryUtil.memPutByte(target + offset, (byte) (argb >>> 16));
        MemoryUtil.memPutByte(target + offset + 1L, (byte) (argb >>> 8));
        MemoryUtil.memPutByte(target + offset + 2L, (byte) argb);
        MemoryUtil.memPutByte(target + offset + 3L, (byte) (argb >>> 24));
    }

    private static void fillArgb(long target, long byteSize, int argb) {
        if ((byteSize & 3L) != 0L) {
            throw new IllegalArgumentException("RGBA atlas byte size must be pixel aligned");
        }
        int patternSize = (int) Math.min(byteSize, 1L << 20);
        ByteBuffer pattern = MemoryUtil.memAlloc(patternSize);
        try {
            for (int offset = 0; offset < patternSize; offset += Integer.BYTES) {
                writeArgb(pattern, offset, argb);
            }
            long source = MemoryUtil.memAddress(pattern);
            for (long offset = 0L; offset < byteSize; offset += patternSize) {
                MemoryUtil.memCopy(
                        source,
                        target + offset,
                        Math.min(patternSize, byteSize - offset));
            }
        } finally {
            MemoryUtil.memFree(pattern);
        }
    }

    static long animationEndOffset(
            long cursor, int width, int height, boolean normal, boolean specular) {
        long bytes = Math.multiplyExact(Math.multiplyExact((long) width, height), 4L);
        long result = cursor;
        if (normal) {
            result = StagingArena.requiredEndOffset(result, bytes, 4L);
        }
        return specular
                ? StagingArena.requiredEndOffset(result, bytes, 4L)
                : result;
    }

    private static long animationEndOffset(
            long cursor, LabPbrAtlasFrame.Sprite sprite, int mipLevels) {
        long result = cursor;
        for (int mip = 0; mip < mipLevels; mip++) {
            result = animationEndOffset(
                    result,
                    sprite.mipWidth(mip),
                    sprite.mipHeight(mip),
                    sprite.normal() != null,
                    sprite.specular() != null);
        }
        return result;
    }

    public static final class FrameToken {
        private final LabPbrTextureAtlas atlas;
        private final StagingArena.Batch batch;
        private final Resources owner;
        private final boolean initialUpload;
        private final int animationUpdateCount;
        private boolean finished;

        private FrameToken(
                LabPbrTextureAtlas atlas,
                StagingArena.Batch batch,
                Resources owner,
                boolean initialUpload,
                int animationUpdateCount) {
            this.atlas = atlas;
            this.batch = batch;
            this.owner = owner;
            this.initialUpload = initialUpload;
            this.animationUpdateCount = animationUpdateCount;
        }
    }

    private record Copy(
            VulkanImage image,
            long buffer,
            long bufferOffset,
            int mip,
            int x,
            int y,
            int width,
            int height) {
    }

    private record AnimationUpdate(
            LabPbrAtlasFrame.Sprite sprite,
            LabPbrAtlasFrame.AnimationSample sample,
            AnimatedMaterialSprite owner) {
    }

    private static class MaterialSprite {
        final LabPbrAtlasFrame.Sprite sprite;

        MaterialSprite(LabPbrAtlasFrame.Sprite sprite) {
            this.sprite = sprite;
        }

        int mipX(int mip) {
            return this.sprite.mipX(mip);
        }

        int mipY(int mip) {
            return this.sprite.mipY(mip);
        }

        int mipWidth(int mip) {
            return this.sprite.mipWidth(mip);
        }

        int mipHeight(int mip) {
            return this.sprite.mipHeight(mip);
        }

        void write(
                long target,
                long baseOffset,
                int rowWidth,
                LabPbrAtlasFrame.MaterialSource source,
                LabPbrAtlasFrame.AnimationSample sample,
                int mip,
                boolean tightlyPacked,
                boolean specular) {
            int outputWidth = this.mipWidth(mip);
            int outputHeight = this.mipHeight(mip);
            int destinationX = tightlyPacked ? 0 : this.mipX(mip);
            int destinationY = tightlyPacked ? 0 : this.mipY(mip);
            int baseWidth = this.sprite.contentWidth() + 2 * this.sprite.padding();
            int baseHeight = this.sprite.contentHeight() + 2 * this.sprite.padding();
            for (int y = 0; y < outputHeight; y++) {
                double baseY0 = (double) y * baseHeight / outputHeight - this.sprite.padding();
                double baseY1 = (double) (y + 1) * baseHeight / outputHeight - this.sprite.padding();
                for (int x = 0; x < outputWidth; x++) {
                    double baseX0 = (double) x * baseWidth / outputWidth - this.sprite.padding();
                    double baseX1 = (double) (x + 1) * baseWidth / outputWidth - this.sprite.padding();
                    int pixel = source.filtered(
                            sample,
                            baseX0,
                            baseY0,
                            baseX1,
                            baseY1,
                            this.sprite.contentWidth(),
                            this.sprite.contentHeight(),
                            specular);
                    long offset = Math.addExact(
                            baseOffset,
                            Math.multiplyExact(
                                    Math.addExact(
                                            Math.multiplyExact(
                                                    (long) destinationY + y, rowWidth),
                                            (long) destinationX + x),
                                    4L));
                    writeArgb(target, offset, pixel);
                }
            }
        }
    }

    private static final class AnimatedMaterialSprite extends MaterialSprite {
        private final int animationIndex;
        private LabPbrAtlasFrame.AnimationSample lastSample;

        AnimatedMaterialSprite(LabPbrAtlasFrame.Sprite source) {
            super(source);
            this.animationIndex = source.animationIndex();
            this.lastSample = null;
        }
    }

    private static final class Resources implements com.mojang.blaze3d.vulkan.Destroyable {
        private final long sourceGeneration;
        private final long vanillaAtlasView;
        private final int width;
        private final int height;
        private final int mipLevels;
        private final VulkanImage normalAtlas;
        private final VulkanImage specularAtlas;
        private VulkanBuffer normalUpload;
        private VulkanBuffer specularUpload;
        private final LabPbrMaterialSet materials;
        private final List<AnimatedMaterialSprite> animated;
        private boolean prepared;
        private boolean destroyed;

        Resources(
                long sourceGeneration,
                long vanillaAtlasView,
                int width,
                int height,
                int mipLevels,
                VulkanImage normalAtlas,
                VulkanImage specularAtlas,
                VulkanBuffer normalUpload,
                VulkanBuffer specularUpload,
                LabPbrMaterialSet materials,
                List<LabPbrAtlasFrame.Sprite> sprites) {
            this.sourceGeneration = sourceGeneration;
            this.vanillaAtlasView = vanillaAtlasView;
            this.width = width;
            this.height = height;
            this.mipLevels = mipLevels;
            this.normalAtlas = normalAtlas;
            this.specularAtlas = specularAtlas;
            this.normalUpload = normalUpload;
            this.specularUpload = specularUpload;
            this.materials = materials;
            ArrayList<AnimatedMaterialSprite> animated = new ArrayList<>();
            for (LabPbrAtlasFrame.Sprite sprite : sprites) {
                if (sprite.animated()) {
                    animated.add(new AnimatedMaterialSprite(sprite));
                }
            }
            this.animated = List.copyOf(animated);
        }

        void collectAnimationChanges(
                List<LabPbrAtlasFrame.AnimationSample> samples,
                ArrayList<AnimationUpdate> result) {
            result.clear();
            for (AnimatedMaterialSprite animation : this.animated) {
                if (animation.animationIndex >= samples.size()) {
                    throw new IllegalStateException("LabPBR animation sample set is incomplete");
                }
                LabPbrAtlasFrame.AnimationSample sample = samples.get(animation.animationIndex);
                if (!sample.equals(animation.lastSample)) {
                    result.add(new AnimationUpdate(animation.sprite, sample, animation));
                }
            }
        }

        void retireUploads(VulkanContext context) {
            VulkanBuffer normal = this.normalUpload;
            VulkanBuffer specular = this.specularUpload;
            this.normalUpload = null;
            this.specularUpload = null;
            RuntimeException failure = ResourceCleanup.run(
                    normal == null ? null : () -> context.defer(normal), null);
            failure = ResourceCleanup.run(
                    specular == null ? null : () -> context.defer(specular), failure);
            ResourceCleanup.throwIfFailed(failure);
        }

        @Override
        public void destroy() {
            if (!this.destroyed) {
                this.destroyed = true;
                RuntimeException failure = ResourceCleanup.destroy(this.specularUpload, null);
                failure = ResourceCleanup.destroy(this.normalUpload, failure);
                failure = ResourceCleanup.destroy(this.specularAtlas, failure);
                failure = ResourceCleanup.destroy(this.normalAtlas, failure);
                ResourceCleanup.throwIfFailed(failure);
            }
        }
    }

}
