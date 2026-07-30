package dev.prime.render.vulkan;

import com.mojang.blaze3d.platform.NativeImage;
import dev.prime.PrimeClient;
import dev.prime.mixin.SpriteContentsAccessor;
import dev.prime.mixin.TextureAtlasAccessor;
import dev.prime.mixin.TextureAtlasSpriteAccessor;
import dev.prime.render.ResourceCleanup;
import dev.prime.render.terrain.LabPbrEmissionMap;
import dev.prime.render.terrain.LabPbrHeightMap;
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
    private final ArrayList<AnimationUpdate> animationUpdates = new ArrayList<>();
    private final ArrayList<Copy> animationCopies = new ArrayList<>();
    private Resources resources;
    private FrameToken pending;
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
        if (this.pending != null) {
            throw new IllegalStateException(
                    "Cannot replace the LabPBR atlas with an outstanding upload");
        }
        Resources replacement = build(
                minecraft.getResourceManager(), atlas, vanillaAtlasView, generation);
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

    /** Invalidates source-pack data while leaving GPU ownership changes on the render thread. */
    public void requestReload() {
        this.requestedGeneration.incrementAndGet();
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
        current.collectAnimationChanges(this.animationUpdates);
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
                AnimatedMaterialSprite sprite = change.sprite;
                long spriteBudget = animationEndOffset(budget, sprite, current.mipLevels);
                if (spriteBudget > batch.capacity()) {
                    continue;
                }
                for (int mip = 0; mip < current.mipLevels; mip++) {
                    if (sprite.normal != null) {
                        addAnimatedCopy(
                                this.animationCopies,
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
                                this.animationCopies,
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
            update.sprite.lastSample = update.sample;
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
        Map<Identifier, LabPbrHeightMap> heightMaps = new java.util.HashMap<>();
        ArrayList<MaterialSprite> materialSprites = new ArrayList<>();
        if (supported) {
            for (TextureAtlasSprite sprite : sprites.values()) {
                Identifier name = sprite.contents().name();
                MaterialSource normal = readMaterial(resourceManager, materialResource(name, "_n"), sprite);
                MaterialSource specular = readMaterial(resourceManager, materialResource(name, "_s"), sprite);
                if (normal != null) {
                    normalSprites.add(name);
                    heightMaps.put(name, LabPbrHeightMap.fromNormal(
                            normal.pixels(),
                            normal.width(),
                            normal.height(),
                            normal.frameWidth(),
                            normal.frameHeight(),
                            normal.columns(),
                            normal.frameCount()));
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
                    normalSprites, specularSprites, emissionMaps, heightMaps);
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
            RuntimeException failure = ResourceCleanup.destroy(specularUpload, exception);
            failure = ResourceCleanup.destroy(normalUpload, failure);
            failure = ResourceCleanup.destroy(specularAtlas, failure);
            failure = ResourceCleanup.destroy(normalAtlas, failure);
            throw failure;
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
        long byteSize = totalMipBytes(atlasWidth, atlasHeight, mipLevels);
        long target = upload.mappedAddress();
        fillArgb(target, byteSize, defaultArgb);
        long mipOffset = 0L;
        for (int mip = 0; mip < mipLevels; mip++) {
            int mipWidth = Math.max(1, atlasWidth >> mip);
            for (MaterialSprite sprite : sprites) {
                MaterialSource source = normal ? sprite.normal : sprite.specular;
                if (source != null) {
                    sprite.write(
                            target,
                            mipOffset,
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
            AnimatedMaterialSprite sprite,
            MaterialSource source,
            AnimationSample sample,
            int mip,
            boolean specular) {
        int width = sprite.mipWidth(mip);
        int height = sprite.mipHeight(mip);
        long byteSize = Math.multiplyExact(Math.multiplyExact((long) width, height), 4L);
        StagingArena.Slice slice = batch.allocate(byteSize, 4L);
        sprite.write(
                slice.mappedAddress(), 0L, width, source, sample, mip, true, specular);
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
            long cursor, AnimatedMaterialSprite sprite, int mipLevels) {
        long result = cursor;
        for (int mip = 0; mip < mipLevels; mip++) {
            result = animationEndOffset(
                    result,
                    sprite.mipWidth(mip),
                    sprite.mipHeight(mip),
                    sprite.normal != null,
                    sprite.specular != null);
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
                long target,
                long baseOffset,
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

        void collectAnimationChanges(ArrayList<AnimationUpdate> result) {
            result.clear();
            for (AnimatedMaterialSprite sprite : this.animated) {
                AnimationSample sample = AnimationFrameAccess.sample(sprite.state);
                if (!sample.equals(sprite.lastSample)) {
                    result.add(new AnimationUpdate(sprite, sample));
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
