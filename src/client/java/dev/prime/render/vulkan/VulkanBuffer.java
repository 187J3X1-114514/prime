package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;

public final class VulkanBuffer implements Destroyable {
    private final long allocator;
    private final long allocation;
    private final long handle;
    private final long deviceAddress;
    private final long mappedAddress;
    private final long size;
    private boolean destroyed;

    VulkanBuffer(
            long allocator,
            long allocation,
            long handle,
            long deviceAddress,
            long mappedAddress,
            long size) {
        this.allocator = allocator;
        this.allocation = allocation;
        this.handle = handle;
        this.deviceAddress = deviceAddress;
        this.mappedAddress = mappedAddress;
        this.size = size;
    }

    public long handle() {
        return this.handle;
    }

    public long deviceAddress() {
        return this.deviceAddress;
    }

    public long mappedAddress() {
        if (this.mappedAddress == 0L) {
            throw new IllegalStateException("Buffer is not host visible");
        }
        return this.mappedAddress;
    }

    public long size() {
        return this.size;
    }

    public void put(long offset, java.nio.ByteBuffer source) {
        long length = source.remaining();
        if (offset < 0L || offset + length > this.size) {
            throw new IndexOutOfBoundsException("Buffer write exceeds allocation");
        }
        MemoryUtil.memCopy(MemoryUtil.memAddress(source) + source.position(), this.mappedAddress() + offset, length);
        Vma.vmaFlushAllocation(this.allocator, this.allocation, offset, length);
    }

    public void put(long offset, long sourceAddress, long length) {
        if (sourceAddress == 0L && length != 0L) {
            throw new IllegalArgumentException("Buffer source address is null");
        }
        if (offset < 0L || length < 0L || offset > this.size - length) {
            throw new IndexOutOfBoundsException("Buffer write exceeds allocation");
        }
        MemoryUtil.memCopy(sourceAddress, this.mappedAddress() + offset, length);
        Vma.vmaFlushAllocation(this.allocator, this.allocation, offset, length);
    }

    public void flush(long offset, long length) {
        Vma.vmaFlushAllocation(this.allocator, this.allocation, offset, length);
    }

    public void invalidate(long offset, long length) {
        Vma.vmaInvalidateAllocation(this.allocator, this.allocation, offset, length);
    }

    @Override
    public void destroy() {
        if (!this.destroyed) {
            this.destroyed = true;
            Vma.vmaDestroyBuffer(this.allocator, this.handle, this.allocation);
        }
    }
}
