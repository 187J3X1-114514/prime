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
        for (Page page : this.pages) {
            if (page.tryAcquire()) {
                return new Batch(page);
            }
        }
        if (this.pages.size() >= MAX_PAGES) {
            return null;
        }
        Page page = new Page(this.context.createBuffer(
                PAGE_SIZE,
                VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                true,
                "Prime staging page " + this.pages.size()));
        page.acquireNew();
        this.pages.add(page);
        return new Batch(page);
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
            long offset = VulkanContext.alignUp(this.cursor, alignment);
            if (offset + size > PAGE_SIZE) {
                throw new IllegalStateException("Prime staging batch exceeded 16 MiB upload budget");
            }
            this.cursor = offset + size;
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

    private static final class Page {
        private final VulkanBuffer buffer;
        private boolean available = true;

        private Page(VulkanBuffer buffer) {
            this.buffer = buffer;
        }

        private synchronized boolean tryAcquire() {
            if (!this.available) {
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
