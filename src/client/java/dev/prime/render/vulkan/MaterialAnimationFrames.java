package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.terrain.LabPbrAtlasFrame;
import org.lwjgl.system.MemoryUtil;

/** Immutable, translated mip chains for one animated auxiliary material channel. */
final class MaterialAnimationFrames implements Destroyable {
    private final TexturePageLayout.Placement placement;
    private final boolean specular;
    private final int frameCount;
    private final int mipLevels;
    private final long frameStride;
    private final long[] mipOffsets;
    private final long byteSize;
    private long address;

    static MaterialAnimationFrames create(
            TexturePageLayout.Placement placement,
            LabPbrAtlasFrame.MaterialSource source,
            int mipLevels,
            boolean specular) {
        return new MaterialAnimationFrames(placement, source, mipLevels, specular);
    }

    private MaterialAnimationFrames(
            TexturePageLayout.Placement placement,
            LabPbrAtlasFrame.MaterialSource source,
            int mipLevels,
            boolean specular) {
        if (mipLevels <= 0) {
            throw new IllegalArgumentException("Animation mip count must be positive");
        }
        this.placement = placement;
        this.specular = specular;
        this.frameCount = source.frameCount();
        this.mipLevels = mipLevels;
        this.mipOffsets = new long[mipLevels];
        long stride = 0L;
        LabPbrAtlasFrame.Sprite sprite = placement.sprite();
        for (int mip = 0; mip < mipLevels; mip++) {
            this.mipOffsets[mip] = stride;
            stride = Math.addExact(
                    stride,
                    Math.multiplyExact(
                            Math.multiplyExact(
                                    (long) sprite.mipWidth(mip), sprite.mipHeight(mip)),
                            4L));
        }
        this.frameStride = stride;
        this.byteSize = Math.multiplyExact(stride, this.frameCount);
        this.address = MemoryUtil.nmemAllocChecked(this.byteSize);
        try {
            for (int frame = 0; frame < this.frameCount; frame++) {
                LabPbrAtlasFrame.AnimationSample sample =
                        new LabPbrAtlasFrame.AnimationSample(frame, frame, 0);
                for (int mip = 0; mip < mipLevels; mip++) {
                    int width = sprite.mipWidth(mip);
                    MaterialTexturePages.writeSprite(
                            this.address + (long) frame * this.frameStride + this.mipOffsets[mip],
                            0L,
                            width,
                            placement,
                            source,
                            sample,
                            mip,
                            true,
                            specular);
                }
            }
        } catch (RuntimeException | Error failure) {
            MemoryUtil.nmemFree(this.address);
            this.address = 0L;
            throw failure;
        }
    }

    TexturePageLayout.Placement placement() {
        return this.placement;
    }

    int mipLevels() {
        return this.mipLevels;
    }

    long byteSize() {
        return this.byteSize;
    }

    void write(long target, LabPbrAtlasFrame.AnimationSample sample, int mip) {
        if (this.address == 0L) {
            throw new IllegalStateException("Material animation frames are destroyed");
        }
        if (mip < 0 || mip >= this.mipLevels) {
            throw new IllegalArgumentException("Material animation mip is out of range");
        }
        int current = this.frameIndex(sample.currentFrame());
        int next = this.frameIndex(sample.nextFrame());
        long mipBytes = this.mipBytes(mip);
        long currentAddress = this.frameAddress(current, mip);
        int progress = this.frameCount == 1 ? 0 : sample.progressThousandths();
        if (progress == 0 || current == next) {
            MemoryUtil.memCopy(currentAddress, target, mipBytes);
            return;
        }
        long nextAddress = this.frameAddress(next, mip);
        for (long offset = 0L; offset < mipBytes; offset += 4L) {
            int blended = LabPbrAtlasFrame.MaterialSource.blendFiltered(
                    readArgb(currentAddress + offset),
                    readArgb(nextAddress + offset),
                    progress,
                    this.specular);
            MaterialTexturePages.writeArgb(target, offset, blended);
        }
    }

    private int frameIndex(int requested) {
        return this.frameCount == 1
                ? 0
                : Math.max(0, Math.min(requested, this.frameCount - 1));
    }

    private long frameAddress(int frame, int mip) {
        return this.address + (long) frame * this.frameStride + this.mipOffsets[mip];
    }

    private long mipBytes(int mip) {
        LabPbrAtlasFrame.Sprite sprite = this.placement.sprite();
        return (long) sprite.mipWidth(mip) * sprite.mipHeight(mip) * 4L;
    }

    private static int readArgb(long address) {
        return (MemoryUtil.memGetByte(address + 3L) & 0xff) << 24
                | (MemoryUtil.memGetByte(address) & 0xff) << 16
                | (MemoryUtil.memGetByte(address + 1L) & 0xff) << 8
                | MemoryUtil.memGetByte(address + 2L) & 0xff;
    }

    @Override
    public void destroy() {
        long released = this.address;
        this.address = 0L;
        if (released != 0L) {
            MemoryUtil.nmemFree(released);
        }
    }
}
