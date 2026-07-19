package dev.prime.render.vulkan;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK12;

public final class StagingArena implements AutoCloseable {
    public static final long PAGE_SIZE = 16L * 1024L * 1024L;
    private static final int MAX_PAGES = 3;

    private final VulkanContext context;
    private final List<Page> pages = new ArrayList<>();

    public StagingArena(VulkanContext context) {
        this.context = context;
    }

    public Batch tryBeginBatch() {
        return this.tryBeginBatch(PAGE_SIZE);
    }

    /** Acquires one reusable page large enough for an indivisible upload transaction. */
    public Batch tryBeginBatch(long minimumCapacity) {
        if (minimumCapacity < 0L) {
            throw new IllegalArgumentException("Staging capacity must not be negative");
        }
        long requiredCapacity = allocationCapacity(minimumCapacity);
        for (Page page : this.pages) {
            if (page.tryAcquire(requiredCapacity)) {
                return new Batch(page);
            }
        }
        if (this.pages.size() < MAX_PAGES) {
            int index = this.pages.size();
            Page page = this.createPage(requiredCapacity, index);
            page.acquireNew();
            this.pages.add(page);
            return new Batch(page);
        }
        for (int index = 0; index < this.pages.size(); index++) {
            Page page = this.pages.get(index);
            if (!page.reserveForGrowth(requiredCapacity)) {
                continue;
            }
            page.buffer.destroy();
            Page replacement = this.createPage(requiredCapacity, index);
            replacement.acquireNew();
            this.pages.set(index, replacement);
            return new Batch(replacement);
        }
        return null;
    }

    private Page createPage(long capacity, int index) {
        return new Page(this.context.createBuffer(
                capacity,
                VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                true,
                "Prime staging page " + index + " (" + capacity + " bytes)"), capacity);
    }

    static long allocationCapacity(long minimumCapacity) {
        long requested = Math.max(1L, minimumCapacity);
        if (requested > Long.MAX_VALUE - PAGE_SIZE + 1L) {
            throw new IllegalArgumentException("Staging capacity is too large to align safely");
        }
        return VulkanContext.alignUp(requested, PAGE_SIZE);
    }

    @Override
    public void close() {
        for (Page page : this.pages) {
            page.buffer.destroy();
        }
        this.pages.clear();
    }

    public final class Batch implements AutoCloseable {
        private final Page page;
        private long cursor;
        private boolean submitted;
        private boolean closed;

        private Batch(Page page) {
            this.page = page;
        }

        public Slice write(ByteBuffer data, long alignment) {
            long size = data.remaining();
            Slice slice = this.allocate(size, alignment);
            MemoryUtil.memCopy(
                    MemoryUtil.memAddress(data) + data.position(),
                    this.page.buffer.mappedAddress() + slice.offset(),
                    size);
            return slice;
        }

        public Slice write(float[] data, long alignment) {
            Slice slice = this.allocate((long) data.length * Float.BYTES, alignment);
            MemoryUtil.memFloatBuffer(this.page.buffer.mappedAddress() + slice.offset(), data.length).put(data);
            return slice;
        }

        public Slice write(int[] data, long alignment) {
            Slice slice = this.allocate((long) data.length * Integer.BYTES, alignment);
            MemoryUtil.memIntBuffer(this.page.buffer.mappedAddress() + slice.offset(), data.length).put(data);
            return slice;
        }

        private Slice allocate(long size, long alignment) {
            long endOffset = requiredEndOffset(this.cursor, size, alignment);
            if (endOffset > this.page.capacity) {
                throw new IllegalStateException(
                        "Prime staging batch exceeded its " + this.page.capacity + " byte capacity");
            }
            long offset = endOffset - size;
            this.cursor = endOffset;
            return new Slice(this.page.buffer.handle(), offset, size);
        }

        public void submitForRetirement() {
            if (this.submitted) {
                throw new IllegalStateException("Staging batch already submitted");
            }
            this.page.buffer.flush(0L, this.cursor);
            this.submitted = true;
            StagingArena.this.context.defer(this.page::release);
        }

        @Override
        public void close() {
            if (!this.closed) {
                this.closed = true;
                if (!this.submitted) {
                    this.page.release();
                }
            }
        }
    }

    public record Slice(long buffer, long offset, long size) {
    }

    /**
     * Computes the exact cursor after one allocation, including the alignment consumed before it.
     * Upload scheduling uses this same function so its byte budget cannot drift from the arena.
     */
    public static long requiredEndOffset(long cursor, long size, long alignment) {
        if (cursor < 0L || size < 0L) {
            throw new IllegalArgumentException("Staging offsets and sizes must not be negative");
        }
        return Math.addExact(VulkanContext.alignUp(cursor, alignment), size);
    }

    private static final class Page {
        private final VulkanBuffer buffer;
        private final long capacity;
        private boolean available = true;

        private Page(VulkanBuffer buffer, long capacity) {
            this.buffer = buffer;
            this.capacity = capacity;
        }

        private synchronized boolean tryAcquire(long requiredCapacity) {
            if (!this.available || this.capacity < requiredCapacity) {
                return false;
            }
            this.available = false;
            return true;
        }

        private synchronized boolean reserveForGrowth(long requiredCapacity) {
            if (!this.available || this.capacity >= requiredCapacity) {
                return false;
            }
            this.available = false;
            return true;
        }

        private synchronized void acquireNew() {
            if (!this.available) {
                throw new IllegalStateException("New staging page was unexpectedly busy");
            }
            this.available = false;
        }

        private synchronized void release() {
            this.available = true;
        }
    }
}
