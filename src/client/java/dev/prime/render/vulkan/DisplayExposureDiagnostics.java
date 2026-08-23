package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.ResourceCleanup;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Asynchronous, non-stalling readback of the four-word automatic-exposure state. */
public final class DisplayExposureDiagnostics implements Destroyable {
    private static final int STATE_SIZE = 16;

    private final VulkanContext context;
    private volatile VulkanBuffer pendingReadback;
    private volatile Snapshot latest;
    private volatile boolean destroyed;

    public DisplayExposureDiagnostics(VulkanContext context) {
        this.context = java.util.Objects.requireNonNull(context, "context");
    }

    public Capture record(VkCommandBuffer commandBuffer, long sourceBuffer) {
        if (this.destroyed) {
            throw new IllegalStateException("Exposure diagnostics are destroyed");
        }
        java.util.Objects.requireNonNull(commandBuffer, "commandBuffer");
        if (sourceBuffer == 0L) {
            throw new IllegalArgumentException("Exposure diagnostic source is null");
        }
        if (this.pendingReadback != null) {
            return null;
        }
        VulkanBuffer readback = this.context.createReadbackBuffer(
                STATE_SIZE,
                VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                "Prime automatic-exposure diagnostics");
        this.pendingReadback = readback;
        try {
            memoryBarrier(commandBuffer);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack)
                        .srcOffset(0L)
                        .dstOffset(0L)
                        .size(STATE_SIZE);
                VK12.vkCmdCopyBuffer(
                        commandBuffer, sourceBuffer, readback.handle(), copy);
            }
            return new Capture(this, readback);
        } catch (RuntimeException exception) {
            this.pendingReadback = null;
            ResourceCleanup.destroy(readback, exception);
            throw exception;
        }
    }

    public void submitted(Capture capture) {
        if (capture == null) {
            return;
        }
        capture.require(this);
        try {
            this.context.afterSubmission(() -> this.complete(capture.readback));
        } catch (RuntimeException exception) {
            if (this.pendingReadback == capture.readback) {
                this.pendingReadback = null;
            }
            ResourceCleanup.run(() -> this.context.defer(capture.readback), exception);
            throw exception;
        }
    }

    public void abandon(Capture capture) {
        if (capture == null) {
            return;
        }
        capture.require(this);
        if (this.pendingReadback == capture.readback) {
            this.pendingReadback = null;
        }
        capture.readback.destroy();
    }

    public Snapshot latest() {
        return this.latest;
    }

    private void complete(VulkanBuffer readback) {
        if (this.destroyed || this.pendingReadback != readback) {
            return;
        }
        try {
            ByteBuffer state = ByteBuffer.wrap(readback.read(0L, STATE_SIZE))
                    .order(ByteOrder.nativeOrder());
            this.latest = new Snapshot(
                    state.getFloat(0),
                    state.getInt(4) != 0,
                    state.getFloat(8),
                    state.getFloat(12));
        } finally {
            this.pendingReadback = null;
            readback.destroy();
        }
    }

    private static void memoryBarrier(VkCommandBuffer commandBuffer) {
        VulkanSync.memoryBarrier(
                commandBuffer,
                VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK12.VK_ACCESS_MEMORY_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK12.VK_ACCESS_TRANSFER_READ_BIT);
    }

    @Override
    public void destroy() {
        if (this.destroyed) {
            return;
        }
        this.destroyed = true;
        VulkanBuffer pending = this.pendingReadback;
        this.pendingReadback = null;
        if (pending != null) {
            pending.destroy();
        }
        this.latest = null;
    }

    public record Snapshot(
            float automaticExposureEv,
            boolean initialized,
            float targetExposureEv,
            float measuredLogBrightness) {
        public boolean finite() {
            return Float.isFinite(this.automaticExposureEv)
                    && Float.isFinite(this.targetExposureEv)
                    && Float.isFinite(this.measuredLogBrightness);
        }
    }

    public static final class Capture {
        private final DisplayExposureDiagnostics owner;
        private final VulkanBuffer readback;

        private Capture(DisplayExposureDiagnostics owner, VulkanBuffer readback) {
            this.owner = owner;
            this.readback = readback;
        }

        private void require(DisplayExposureDiagnostics expected) {
            if (this.owner != expected) {
                throw new IllegalArgumentException("Exposure capture belongs to another owner");
            }
        }
    }
}
