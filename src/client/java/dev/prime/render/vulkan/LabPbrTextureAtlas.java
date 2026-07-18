package dev.prime.render.vulkan;

import com.mojang.blaze3d.platform.NativeImage;
import dev.prime.PrimeClient;
import dev.prime.mixin.SpriteContentsAccessor;
import dev.prime.mixin.TextureAtlasAccessor;
import dev.prime.mixin.TextureAtlasSpriteAccessor;
import dev.prime.render.terrain.LabPbrEmissionMap;
import dev.prime.render.terrain.LabPbrMaterialSet;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
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
    private static final Identifier FORMAT_RESOURCE = Identifier.withDefaultNamespace(
            "optifine/texture.properties");
    private static final String SUPPORTED_FORMAT = "lab-pbr/1.3";
    private static final int NORMAL_DEFAULT_ARGB = 0xff8080ff;
    private static final int SPECULAR_DEFAULT_ARGB = 0xff000400;

    private final VulkanContext context;
    private final StagingArena stagingArena;
    private final AtomicLong requestedGeneration = new AtomicLong();
    private Resources resources;
    private boolean closed;

    public LabPbrTextureAtlas(VulkanContext context, StagingArena stagingArena) {
        this.context = context;
        this.stagingArena = stagingArena;
    }

    public LabPbrMaterialSet ensure(
            Minecraft minecraft, TextureAtlas atlas, long vanillaAtlasView) {
        if (this.closed) {
            throw new IllegalStateException("LabPBR atlas is closed");
        }
        long generation = this.requestedGeneration.get();
        if (this.resources != null
                && this.resources.sourceGeneration == generation
                && this.resources.vanillaAtlasView == vanillaAtlasView) {
            return this.resources.materials;
        }
        Resources replacement;
        try {
            replacement = build(
                    minecraft.getResourceManager(), atlas, vanillaAtlasView, generation);
        } catch (RuntimeException exception) {
            throw exception;
        }
        Resources previous = this.resources;
        this.resources = replacement;
        if (previous != null) {
            if (previous.prepared) {
                this.context.defer(previous);
            } else {
                previous.destroy();
            }
        }
        return replacement.materials;
    }

    /** Invalidates source-pack data while leaving GPU ownership changes on the render thread. */
    public void requestReload() {
        this.requestedGeneration.incrementAndGet();
    }

    public VulkanImage normalAtlas() {
        return requireResources().normalAtlas;
    }

    public VulkanImage specularAtlas() {
        return requireResources().specularAtlas;
    }

    /** Records the initial upload and any base-animation frame changes. */
    public FrameToken prepare(VkCommandBuffer commandBuffer) {
        Resources current = requireResources();
        boolean retireInitialUploads = false;
        if (!current.prepared) {
            recordInitialUpload(commandBuffer, current);
            current.prepared = true;
            retireInitialUploads = true;
        }
        List<AnimationUpdate> changes = current.animationChanges();
        if (changes.isEmpty()) {
            return retireInitialUploads
                    ? new FrameToken(null, current, true)
                    : null;
        }
        StagingArena.Batch batch = this.stagingArena.tryBeginBatch();
        if (batch == null) {
            return retireInitialUploads
                    ? new FrameToken(null, current, true)
                    : null;
        }
        ArrayList<Copy> copies = new ArrayList<>();
        try {
            long budget = 0L;
            for (AnimationUpdate change : changes) {
                AnimatedMaterialSprite sprite = change.sprite;
                long spriteBudget = budget;
                for (int mip = 0; mip < current.mipLevels; mip++) {
                    int width = sprite.mipWidth(mip);
                    int height = sprite.mipHeight(mip);
                    long bytes = (long) width * height * 4L;
                    if (sprite.normal != null) {
                        spriteBudget = StagingArena.requiredEndOffset(spriteBudget, bytes, 4L);
                    }
                    if (sprite.specular != null) {
                        spriteBudget = StagingArena.requiredEndOffset(spriteBudget, bytes, 4L);
                    }
                }
                if (spriteBudget > StagingArena.PAGE_SIZE) {
                    continue;
                }
                for (int mip = 0; mip < current.mipLevels; mip++) {
                    if (sprite.normal != null) {
                        addAnimatedCopy(
                                copies,
                                batch,
                                current.normalAtlas,
                                sprite,
                                sprite.normal,
                                change.sample,
                                mip,
                                false);
                    }
                    if (sprite.specular != null) {
                        addAnimatedCopy(
                                copies,
                                batch,
                                current.specularAtlas,
                                sprite,
                                sprite.specular,
                                change.sample,
                                mip,
                                true);
                    }
                }
                budget = spriteBudget;
                sprite.lastSample = change.sample;
            }
            if (copies.isEmpty()) {
                batch.close();
                return retireInitialUploads
                        ? new FrameToken(null, current, true)
                        : null;
            }
            boolean normalChanged = copies.stream().anyMatch(copy -> copy.image == current.normalAtlas);
            boolean specularChanged = copies.stream().anyMatch(copy -> copy.image == current.specularAtlas);
            transitionForCopies(commandBuffer, current, normalChanged, specularChanged, true);
            for (Copy copy : copies) {
                recordCopy(commandBuffer, copy);
            }
            transitionForCopies(commandBuffer, current, normalChanged, specularChanged, false);
            return new FrameToken(batch, current, retireInitialUploads);
        } catch (RuntimeException exception) {
            batch.close();
            throw exception;
        }
    }

    /** Must be called immediately after the command buffer containing the token is submitted. */
    public void submitted(FrameToken token) {
        if (token == null) {
            return;
        }
        if (token.batch != null) {
            token.batch.submitForRetirement();
            token.batch.close();
        }
        if (token.retireInitialUploads) {
            token.owner.retireUploads(this.context);
        }
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            if (this.resources != null) {
                this.resources.destroy();
                this.resources = null;
            }
        }
    }

    private Resources requireResources() {
        if (this.resources == null) {
            throw new IllegalStateException("LabPBR atlas was not synchronized with Minecraft");
        }
        return this.resources;
    }

    private Resources build(
            ResourceManager resourceManager,
            TextureAtlas atlas,
            long vanillaAtlasView,
            long sourceGeneration) {
        TextureAtlasAccessor atlasAccess = (TextureAtlasAccessor) (Object) atlas;
        int atlasWidth = atlasAccess.prime$width();
        int atlasHeight = atlasAccess.prime$height();
        int atlasMipLevels = Math.max(1, atlasAccess.prime$maxMipLevel() + 1);
        boolean supported = readsLabPbr13(resourceManager);
        Map<Identifier, TextureAtlasSprite> sprites = atlasAccess.prime$texturesByName();
        Set<Identifier> normalSprites = new HashSet<>();
        Set<Identifier> specularSprites = new HashSet<>();
        Map<Identifier, LabPbrEmissionMap> emissionMaps = new java.util.HashMap<>();
        ArrayList<MaterialSprite> materialSprites = new ArrayList<>();
        if (supported) {
            for (TextureAtlasSprite sprite : sprites.values()) {
                Identifier name = sprite.contents().name();
                MaterialSource normal = readMaterial(resourceManager, materialResource(name, "_n"), sprite);
                MaterialSource specular = readMaterial(resourceManager, materialResource(name, "_s"), sprite);
                if (normal != null) {
                    normalSprites.add(name);
                }
                if (specular != null) {
                    specularSprites.add(name);
                    LabPbrEmissionMap emission = LabPbrEmissionMap.fromSpecular(
                            specular.pixels(),
                            specular.width(),
                            specular.height(),
                            specular.frameWidth(),
                            specular.frameHeight(),
                            specular.columns(),
                            specular.frameCount());
                    if (emission != null) {
                        emissionMaps.put(name, emission);
                    }
                }
                if (normal != null || specular != null) {
                    materialSprites.add(new MaterialSprite(
                            sprite,
                            normal,
                            specular));
                }
            }
        }
        // Keep valid descriptors without paying for two full-size empty atlases when the active
        // resource pack does not declare LabPBR or contains no auxiliary maps.
        int width = materialSprites.isEmpty() ? 1 : atlasWidth;
        int height = materialSprites.isEmpty() ? 1 : atlasHeight;
        int mipLevels = materialSprites.isEmpty() ? 1 : atlasMipLevels;

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
                    materialSprites,
                    true,
                    NORMAL_DEFAULT_ARGB);
            fillAtlas(
                    specularUpload,
                    width,
                    height,
                    mipLevels,
                    materialSprites,
                    false,
                    SPECULAR_DEFAULT_ARGB);
            List<AnimatedMaterialSprite> animated = bindAnimations(atlasAccess, materialSprites);
            LabPbrMaterialSet materials = new LabPbrMaterialSet(
                    normalSprites, specularSprites, emissionMaps);
            PrimeClient.LOGGER.info(
                    "Loaded LabPBR 1.3 material atlas: {} normal maps, {} specular maps, {} emissive maps, {} animated sprites",
                    normalSprites.size(), specularSprites.size(), emissionMaps.size(), animated.size());
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
                    materials,
                    animated);
        } catch (RuntimeException exception) {
            if (specularUpload != null) {
                specularUpload.destroy();
            }
            if (normalUpload != null) {
                normalUpload.destroy();
            }
            if (specularAtlas != null) {
                specularAtlas.destroy();
            }
            if (normalAtlas != null) {
                normalAtlas.destroy();
            }
            throw exception;
        }
    }

    private static boolean readsLabPbr13(ResourceManager manager) {
        Optional<Resource> resource = manager.getResource(FORMAT_RESOURCE);
        if (resource.isEmpty()) {
            return false;
        }
        Properties properties = new Properties();
        try (InputStream input = resource.orElseThrow().open()) {
            properties.load(input);
        } catch (IOException exception) {
            PrimeClient.LOGGER.warn("Unable to read LabPBR format declaration", exception);
            return false;
        }
        String format = properties.getProperty("format", "").trim();
        if (!SUPPORTED_FORMAT.equalsIgnoreCase(format)) {
            PrimeClient.LOGGER.warn(
                    "Ignoring unsupported material format '{}'; Prime currently requires {}",
                    format,
                    SUPPORTED_FORMAT);
            return false;
        }
        return true;
    }

    private static Identifier materialResource(Identifier sprite, String suffix) {
        return Identifier.fromNamespaceAndPath(
                sprite.getNamespace(), "textures/" + sprite.getPath() + suffix + ".png");
    }

    private static MaterialSource readMaterial(
            ResourceManager manager, Identifier resourceId, TextureAtlasSprite baseSprite) {
        Optional<Resource> resource = manager.getResource(resourceId);
        if (resource.isEmpty()) {
            return null;
        }
        try (InputStream input = resource.orElseThrow().open();
                NativeImage image = NativeImage.read(input)) {
            SpriteContents contents = baseSprite.contents();
            NativeImage baseImage = ((SpriteContentsAccessor) (Object) contents).prime$originalImage();
            return MaterialSource.create(
                    image.getPixels(),
                    image.getWidth(),
                    image.getHeight(),
                    contents.width(),
                    contents.height(),
                    baseImage.getWidth(),
                    baseImage.getHeight());
        } catch (IOException | RuntimeException exception) {
            PrimeClient.LOGGER.warn("Unable to read LabPBR material {}", resourceId, exception);
            return null;
        }
    }

    private static void fillAtlas(
            VulkanBuffer upload,
            int atlasWidth,
            int atlasHeight,
            int mipLevels,
            List<MaterialSprite> sprites,
            boolean normal,
            int defaultArgb) {
        int byteSize = Math.toIntExact(totalMipBytes(atlasWidth, atlasHeight, mipLevels));
        ByteBuffer target = MemoryUtil.memByteBuffer(upload.mappedAddress(), byteSize);
        for (int offset = 0; offset < byteSize; offset += Integer.BYTES) {
            writeArgb(target, offset, defaultArgb);
        }
        long mipOffset = 0L;
        for (int mip = 0; mip < mipLevels; mip++) {
            int mipWidth = Math.max(1, atlasWidth >> mip);
            for (MaterialSprite sprite : sprites) {
                MaterialSource source = normal ? sprite.normal : sprite.specular;
                if (source != null) {
                    sprite.write(
                            target,
                            Math.toIntExact(mipOffset),
                            mipWidth,
                            source,
                            AnimationSample.ZERO,
                            mip,
                            false,
                            !normal);
                }
            }
            mipOffset += (long) mipWidth * Math.max(1, atlasHeight >> mip) * 4L;
        }
        upload.flush(0L, byteSize);
    }

    private static List<AnimatedMaterialSprite> bindAnimations(
            TextureAtlasAccessor atlas,
            List<MaterialSprite> materials) {
        Map<Identifier, MaterialSprite> byName = new java.util.HashMap<>();
        for (MaterialSprite material : materials) {
            byName.put(material.sprite.contents().name(), material);
        }
        List<SpriteContents.AnimationState> states = atlas.prime$animatedTextureStates();
        ArrayList<AnimatedMaterialSprite> result = new ArrayList<>();
        int stateIndex = 0;
        for (TextureAtlasSprite sprite : atlas.prime$sprites()) {
            if (!sprite.contents().isAnimated()) {
                continue;
            }
            if (stateIndex >= states.size()) {
                break;
            }
            MaterialSprite material = byName.get(sprite.contents().name());
            SpriteContents.AnimationState state = states.get(stateIndex++);
            if (material != null && AnimationFrameAccess.hasMultipleFrames(state)) {
                // The initial upload contains source frame zero. Force the first submitted frame
                // to synchronize with vanilla even when the animation was already mid-sequence.
                result.add(new AnimatedMaterialSprite(material, state, null));
            }
        }
        return List.copyOf(result);
    }

    private static void recordInitialUpload(VkCommandBuffer commandBuffer, Resources resources) {
        transitionForCopies(commandBuffer, resources, true, true, true);
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
        transitionForCopies(commandBuffer, resources, true, true, false);
        resources.normalAtlas.markInitialized();
        resources.specularAtlas.markInitialized();
    }

    private static void addAnimatedCopy(
            List<Copy> copies,
            StagingArena.Batch batch,
            VulkanImage image,
            AnimatedMaterialSprite sprite,
            MaterialSource source,
            AnimationSample sample,
            int mip,
            boolean specular) {
        int width = sprite.mipWidth(mip);
        int height = sprite.mipHeight(mip);
        int byteSize = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        ByteBuffer pixels = MemoryUtil.memAlloc(byteSize);
        try {
            sprite.write(pixels, 0, width, source, sample, mip, true, specular);
            pixels.position(0).limit(byteSize);
            StagingArena.Slice slice = batch.write(pixels, 4L);
            copies.add(new Copy(
                    image,
                    slice.buffer(),
                    slice.offset(),
                    mip,
                    sprite.mipX(mip),
                    sprite.mipY(mip),
                    width,
                    height));
        } finally {
            MemoryUtil.memFree(pixels);
        }
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
            boolean toTransfer) {
        int count = (normal ? 1 : 0) + (specular ? 1 : 0);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(count, stack);
            int index = 0;
            if (normal) {
                fillBarrier(barriers.get(index++), resources.normalAtlas, resources.mipLevels, resources.prepared, toTransfer);
            }
            if (specular) {
                fillBarrier(barriers.get(index), resources.specularAtlas, resources.mipLevels, resources.prepared, toTransfer);
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

    private static long totalMipBytes(int width, int height, int mipLevels) {
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

    public static final class FrameToken {
        private final StagingArena.Batch batch;
        private final Resources owner;
        private final boolean retireInitialUploads;

        private FrameToken(
                StagingArena.Batch batch,
                Resources owner,
                boolean retireInitialUploads) {
            this.batch = batch;
            this.owner = owner;
            this.retireInitialUploads = retireInitialUploads;
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

    private record AnimationUpdate(AnimatedMaterialSprite sprite, AnimationSample sample) {
    }

    record AnimationSample(int currentFrame, int nextFrame, int progressThousandths) {
        private static final AnimationSample ZERO = new AnimationSample(0, 0, 0);
    }

    private static class MaterialSprite {
        final TextureAtlasSprite sprite;
        final MaterialSource normal;
        final MaterialSource specular;
        final int padding;

        MaterialSprite(
                TextureAtlasSprite sprite,
                MaterialSource normal,
                MaterialSource specular) {
            this.sprite = sprite;
            this.normal = normal;
            this.specular = specular;
            this.padding = ((TextureAtlasSpriteAccessor) (Object) sprite).prime$padding();
        }

        int mipX(int mip) {
            return this.sprite.getX() >> mip;
        }

        int mipY(int mip) {
            return this.sprite.getY() >> mip;
        }

        int mipWidth(int mip) {
            return Math.max(1, (this.sprite.contents().width() + 2 * this.padding) >> mip);
        }

        int mipHeight(int mip) {
            return Math.max(1, (this.sprite.contents().height() + 2 * this.padding) >> mip);
        }

        void write(
                ByteBuffer target,
                int baseOffset,
                int rowWidth,
                MaterialSource source,
                AnimationSample sample,
                int mip,
                boolean tightlyPacked,
                boolean specular) {
            int outputWidth = this.mipWidth(mip);
            int outputHeight = this.mipHeight(mip);
            int destinationX = tightlyPacked ? 0 : this.mipX(mip);
            int destinationY = tightlyPacked ? 0 : this.mipY(mip);
            int baseWidth = this.sprite.contents().width() + 2 * this.padding;
            int baseHeight = this.sprite.contents().height() + 2 * this.padding;
            for (int y = 0; y < outputHeight; y++) {
                double baseY0 = (double) y * baseHeight / outputHeight - this.padding;
                double baseY1 = (double) (y + 1) * baseHeight / outputHeight - this.padding;
                for (int x = 0; x < outputWidth; x++) {
                    double baseX0 = (double) x * baseWidth / outputWidth - this.padding;
                    double baseX1 = (double) (x + 1) * baseWidth / outputWidth - this.padding;
                    int pixel = source.filtered(
                            sample,
                            baseX0,
                            baseY0,
                            baseX1,
                            baseY1,
                            this.sprite.contents().width(),
                            this.sprite.contents().height(),
                            specular);
                    int offset = baseOffset
                            + ((destinationY + y) * rowWidth + destinationX + x) * 4;
                    writeArgb(target, offset, pixel);
                }
            }
        }
    }

    private static final class AnimatedMaterialSprite extends MaterialSprite {
        private final SpriteContents.AnimationState state;
        private AnimationSample lastSample;

        AnimatedMaterialSprite(
                MaterialSprite source,
                SpriteContents.AnimationState state,
                AnimationSample lastSample) {
            super(source.sprite, source.normal, source.specular);
            this.state = state;
            this.lastSample = lastSample;
        }
    }

    record MaterialSource(
            int[] pixels,
            int width,
            int height,
            int frameWidth,
            int frameHeight,
            int columns,
            int frameCount) {
        static MaterialSource create(
                int[] pixels,
                int width,
                int height,
                int baseFrameWidth,
                int baseFrameHeight,
                int baseImageWidth,
                int baseImageHeight) {
            int baseColumns = Math.max(1, baseImageWidth / baseFrameWidth);
            int baseRows = Math.max(1, baseImageHeight / baseFrameHeight);
            int frameWidth;
            int frameHeight;
            int columns;
            int frameCount;
            if (width == baseFrameWidth && height == baseFrameHeight) {
                frameWidth = width;
                frameHeight = height;
                columns = 1;
                frameCount = 1;
            } else if (width % baseColumns == 0 && height % baseRows == 0) {
                frameWidth = width / baseColumns;
                frameHeight = height / baseRows;
                columns = baseColumns;
                frameCount = baseColumns * baseRows;
            } else {
                frameWidth = width;
                frameHeight = height;
                columns = 1;
                frameCount = 1;
            }
            return new MaterialSource(
                    pixels, width, height, frameWidth, frameHeight, columns, frameCount);
        }

        int filtered(
                AnimationSample sample,
                double baseX0,
                double baseY0,
                double baseX1,
                double baseY1,
                int baseFrameWidth,
                int baseFrameHeight) {
            return filtered(
                    sample,
                    baseX0,
                    baseY0,
                    baseX1,
                    baseY1,
                    baseFrameWidth,
                    baseFrameHeight,
                    false);
        }

        int filtered(
                AnimationSample sample,
                double baseX0,
                double baseY0,
                double baseX1,
                double baseY1,
                int baseFrameWidth,
                int baseFrameHeight,
                boolean specular) {
            int current = filteredFrame(
                    sample.currentFrame,
                    baseX0,
                    baseY0,
                    baseX1,
                    baseY1,
                    baseFrameWidth,
                    baseFrameHeight,
                    specular);
            int progress = this.frameCount == 1 ? 0 : sample.progressThousandths;
            if (progress <= 0 || sample.currentFrame == sample.nextFrame) {
                return current;
            }
            int next = filteredFrame(
                    sample.nextFrame,
                    baseX0,
                    baseY0,
                    baseX1,
                    baseY1,
                    baseFrameWidth,
                    baseFrameHeight,
                    specular);
            return blendArgb(current, next, progress, specular);
        }

        private int filteredFrame(
                int requestedFrame,
                double baseX0,
                double baseY0,
                double baseX1,
                double baseY1,
                int baseFrameWidth,
                int baseFrameHeight,
                boolean specular) {
            int frame = this.frameCount == 1
                    ? 0
                    : Math.max(0, Math.min(requestedFrame, this.frameCount - 1));
            int frameX = frame % this.columns * this.frameWidth;
            int frameY = frame / this.columns * this.frameHeight;
            int sourceX0 = clamp(
                    (int) Math.floor(baseX0 * this.frameWidth / baseFrameWidth),
                    0,
                    this.frameWidth - 1);
            int sourceY0 = clamp(
                    (int) Math.floor(baseY0 * this.frameHeight / baseFrameHeight),
                    0,
                    this.frameHeight - 1);
            int sourceX1 = clamp(
                    (int) Math.ceil(baseX1 * this.frameWidth / baseFrameWidth),
                    sourceX0 + 1,
                    this.frameWidth);
            int sourceY1 = clamp(
                    (int) Math.ceil(baseY1 * this.frameHeight / baseFrameHeight),
                    sourceY0 + 1,
                    this.frameHeight);
            long alpha = 0L;
            long red = 0L;
            long green = 0L;
            long blue = 0L;
            int count = 0;
            long emission = 0L;
            int sentinelCount = 0;
            for (int y = sourceY0; y < sourceY1; y++) {
                for (int x = sourceX0; x < sourceX1; x++) {
                    int pixel = this.pixels[(frameY + y) * this.width + frameX + x];
                    int encodedAlpha = pixel >>> 24;
                    alpha += encodedAlpha;
                    if (specular) {
                        if (encodedAlpha == 255) {
                            sentinelCount++;
                        } else {
                            emission += encodedAlpha;
                        }
                    }
                    red += pixel >>> 16 & 0xff;
                    green += pixel >>> 8 & 0xff;
                    blue += pixel & 0xff;
                    count++;
                }
            }
            int filteredAlpha = specular
                    ? (sentinelCount == count
                            ? 255
                            : (int) ((emission + count / 2L) / count))
                    : (int) ((alpha + count / 2L) / count);
            return filteredAlpha << 24
                    | (int) ((red + count / 2L) / count) << 16
                    | (int) ((green + count / 2L) / count) << 8
                    | (int) ((blue + count / 2L) / count);
        }

        private static int blendArgb(
                int current, int next, int progress, boolean specular) {
            int inverse = 1000 - progress;
            int currentAlpha = current >>> 24;
            int nextAlpha = next >>> 24;
            int alpha;
            if (specular) {
                if (currentAlpha == 255 && nextAlpha == 255) {
                    alpha = 255;
                } else {
                    int currentEmission = currentAlpha == 255 ? 0 : currentAlpha;
                    int nextEmission = nextAlpha == 255 ? 0 : nextAlpha;
                    alpha = (currentEmission * inverse + nextEmission * progress + 500) / 1000;
                }
            } else {
                alpha = (currentAlpha * inverse + nextAlpha * progress + 500) / 1000;
            }
            int red = ((current >>> 16 & 0xff) * inverse
                    + (next >>> 16 & 0xff) * progress + 500) / 1000;
            int green = ((current >>> 8 & 0xff) * inverse
                    + (next >>> 8 & 0xff) * progress + 500) / 1000;
            int blue = ((current & 0xff) * inverse + (next & 0xff) * progress + 500) / 1000;
            return alpha << 24 | red << 16 | green << 8 | blue;
        }

        private static int clamp(int value, int minimum, int maximum) {
            return Math.max(minimum, Math.min(maximum, value));
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
                List<AnimatedMaterialSprite> animated) {
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
            this.animated = animated;
        }

        List<AnimationUpdate> animationChanges() {
            ArrayList<AnimationUpdate> result = new ArrayList<>();
            for (AnimatedMaterialSprite sprite : this.animated) {
                AnimationSample sample = AnimationFrameAccess.sample(sprite.state);
                if (!sample.equals(sprite.lastSample)) {
                    result.add(new AnimationUpdate(sprite, sample));
                }
            }
            return result;
        }

        void retireUploads(VulkanContext context) {
            VulkanBuffer normal = this.normalUpload;
            VulkanBuffer specular = this.specularUpload;
            this.normalUpload = null;
            this.specularUpload = null;
            if (normal != null) {
                context.defer(normal);
            }
            if (specular != null) {
                context.defer(specular);
            }
        }

        @Override
        public void destroy() {
            if (!this.destroyed) {
                this.destroyed = true;
                if (this.specularUpload != null) {
                    this.specularUpload.destroy();
                }
                if (this.normalUpload != null) {
                    this.normalUpload.destroy();
                }
                this.specularAtlas.destroy();
                this.normalAtlas.destroy();
            }
        }
    }

    /** Reads Minecraft's authoritative immutable timeline and live animation cursor. */
    private static final class AnimationFrameAccess {
        private AnimationFrameAccess() {
        }

        static boolean hasMultipleFrames(SpriteContents.AnimationState state) {
            return state.animationInfo.frames.size() > 1;
        }

        static AnimationSample sample(SpriteContents.AnimationState state) {
            List<SpriteContents.FrameInfo> frames = state.animationInfo.frames;
            if (frames.isEmpty()) {
                return AnimationSample.ZERO;
            }
            int sequenceIndex = Math.max(
                    0, Math.min(state.frame, frames.size() - 1));
            SpriteContents.FrameInfo frame = frames.get(sequenceIndex);
            if (!state.animationInfo.interpolateFrames) {
                return new AnimationSample(frame.index(), frame.index(), 0);
            }
            SpriteContents.FrameInfo nextFrame = frames.get(
                    (sequenceIndex + 1) % frames.size());
            int frameTime = Math.max(1, frame.time());
            int progress = Math.min(
                    999,
                    (int) ((long) state.subFrame * 1000L / frameTime));
            return new AnimationSample(frame.index(), nextFrame.index(), progress);
        }
    }
}
