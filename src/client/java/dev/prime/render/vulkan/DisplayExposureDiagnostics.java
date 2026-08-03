package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.ResourceCleanup;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;

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

    public void capture(long sourceBuffer) {
        if (this.destroyed) {
            throw new IllegalStateException("Exposure diagnostics are destroyed");
        }
        if (sourceBuffer == 0L) {
            throw new IllegalArgumentException("Exposure diagnostic source is null");
        }
        if (this.pendingReadback != null) {
            return;
        }
        VulkanBuffer readback = this.context.createReadbackBuffer(
                STATE_SIZE,
                VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                "Prime automatic-exposure diagnostics");
        boolean submitted = false;
        this.pendingReadback = readback;
        try {
            var encoder = this.context.commandEncoder();
            VkCommandBuffer commandBuffer =
                    encoder.allocateAndBeginTransientCommandBuffer();
            memoryBarrier(commandBuffer);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack)
                        .srcOffset(0L)
                        .dstOffset(0L)
                        .size(STATE_SIZE);
                VK12.vkCmdCopyBuffer(
                        commandBuffer, sourceBuffer, readback.handle(), copy);
            }
            VulkanContext.check(
                    VK12.vkEndCommandBuffer(commandBuffer),
                    "end automatic-exposure diagnostic copy command buffer");
            encoder.execute(commandBuffer);
            submitted = true;
            this.context.afterSubmission(() -> this.complete(readback));
        } catch (RuntimeException exception) {
            this.pendingReadback = null;
            if (submitted) {
                ResourceCleanup.run(() -> this.context.defer(readback), exception);
            } else {
                ResourceCleanup.destroy(readback, exception);
            }
            throw exception;
        }
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
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack);
            barrier.get(0).sType$Default()
                    .srcStageMask(VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT)
                    .srcAccessMask(VK12.VK_ACCESS_MEMORY_WRITE_BIT)
                    .dstStageMask(VK12.VK_PIPELINE_STAGE_TRANSFER_BIT)
                    .dstAccessMask(VK12.VK_ACCESS_TRANSFER_READ_BIT);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                    commandBuffer,
                    VkDependencyInfo.calloc(stack)
                            .sType$Default()
                            .pMemoryBarriers(barrier));
        }
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
}
