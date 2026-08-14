package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.ResourceCleanup;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

/** Submission-safe SHARC counters and timestamp queries with asynchronous readback. */
final class RealtimeSharcDiagnostics implements Destroyable {
    static final int COUNTER_COUNT = 6;
    static final int SAMPLING_PERIOD = 256;
    private static final int TIMESTAMP_COUNT = 4;
    private static final int TIMESTAMP_BYTES = TIMESTAMP_COUNT * Long.BYTES;
    private static final int COUNTER_BYTES = COUNTER_COUNT * Integer.BYTES;
    private static final int READBACK_BYTES = TIMESTAMP_BYTES + COUNTER_BYTES;
    private static final double SMOOTHING = 0.1;

    private final VulkanContext context;
    private final VulkanBuffer counters;
    private final long queryPool;
    private final boolean timestampsSupported;
    private final double nanosecondsPerTick;
    // Queue-retirement callbacks run from VulkanCommandEncoder rotation on the renderer thread.
    private Capture pending;
    private SharcDiagnosticsSnapshot latest;
    private long captureCount;
    private double updateMilliseconds;
    private double resolveMilliseconds;
    private double queryMilliseconds;
    private long referenceCaptureCount;
    private double referenceQueryMilliseconds;
    private long sampledQueries;
    private long discreteSkips;
    private long shortSegmentSkips;
    private long glossyFootprintSkips;
    private long lookupAttempts;
    private long hits;
    private boolean destroyed;

    RealtimeSharcDiagnostics(VulkanContext context) {
        this.context = java.util.Objects.requireNonNull(context, "context");
        VulkanBuffer counterBuffer = null;
        long timestamps = 0L;
        boolean timestampSupport;
        double timestampPeriod;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            VK12.vkGetPhysicalDeviceProperties(
                    context.vkDevice().getPhysicalDevice(), properties);
            timestampSupport = properties.limits().timestampComputeAndGraphics();
            timestampPeriod = properties.limits().timestampPeriod();
            try {
                counterBuffer = context.createBuffer(
                        COUNTER_BYTES,
                        VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                                | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                                | VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                        false,
                        "Prime SHARC diagnostic counters");
                if (timestampSupport) {
                    VkQueryPoolCreateInfo info = VkQueryPoolCreateInfo.calloc(stack)
                            .sType$Default()
                            .queryType(VK10.VK_QUERY_TYPE_TIMESTAMP)
                            .queryCount(TIMESTAMP_COUNT);
                    LongBuffer pointer = stack.mallocLong(1);
                    VulkanContext.check(
                            VK10.vkCreateQueryPool(
                                    context.vkDevice(), info, null, pointer),
                            "create Prime SHARC timestamp query pool");
                    timestamps = pointer.get(0);
                }
            } catch (RuntimeException exception) {
                if (timestamps != 0L) {
                    VK10.vkDestroyQueryPool(context.vkDevice(), timestamps, null);
                }
                throw ResourceCleanup.destroy(counterBuffer, exception);
            }
        }
        this.counters = counterBuffer;
        this.queryPool = timestamps;
        this.timestampsSupported = timestampSupport;
        this.nanosecondsPerTick = timestampPeriod;
    }

    Capture prepare(
            VkCommandBuffer commandBuffer,
            boolean enabled,
            boolean sharcEnabled) {
        if (!enabled
                || this.pending != null
                || (!sharcEnabled && !this.timestampsSupported)) {
            return null;
        }
        if (this.destroyed) {
            throw new IllegalStateException("SHARC diagnostics are destroyed");
        }
        VulkanBuffer readback = this.context.createReadbackBuffer(
                READBACK_BYTES,
                VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                "Prime SHARC diagnostic readback");
        Capture capture = new Capture(readback, sharcEnabled);
        this.pending = capture;
        try {
            if (sharcEnabled) {
                VK10.vkCmdFillBuffer(
                        commandBuffer, this.counters.handle(), 0L, COUNTER_BYTES, 0);
                VulkanSync.memoryBarrier(
                        commandBuffer,
                        VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                        KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                        VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
            }
            if (this.queryPool != 0L) {
                VK10.vkCmdResetQueryPool(
                        commandBuffer, this.queryPool, 0, TIMESTAMP_COUNT);
            }
            return capture;
        } catch (RuntimeException exception) {
            this.pending = null;
            readback.destroy();
            throw exception;
        }
    }

    long counterAddress(Capture capture) {
        return capture == null || !capture.sharcEnabled
                ? 0L
                : this.counters.deviceAddress();
    }

    void recordQueryStart(VkCommandBuffer commandBuffer, Capture capture) {
        if (capture != null && capture.sharcEnabled) {
            this.writeTimestamp(
                    commandBuffer, capture, 0, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
        }
    }

    void recordUpdateStart(VkCommandBuffer commandBuffer, Capture capture) {
        if (capture != null && capture.sharcEnabled) {
            this.writeTimestamp(
                    commandBuffer, capture, 1, VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
        }
    }

    void recordUpdateEnd(VkCommandBuffer commandBuffer, Capture capture) {
        if (capture != null && capture.sharcEnabled) {
            this.writeTimestamp(
                    commandBuffer, capture, 2, VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
        }
    }

    void recordResolveEnd(VkCommandBuffer commandBuffer, Capture capture) {
        if (capture != null && capture.sharcEnabled) {
            this.writeTimestamp(
                    commandBuffer, capture, 3, VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
        }
    }

    void recordReferenceQueryStart(
            VkCommandBuffer commandBuffer, Capture capture) {
        if (capture != null && !capture.sharcEnabled) {
            this.writeTimestamp(
                    commandBuffer, capture, 2, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
        }
    }

    void finish(VkCommandBuffer commandBuffer, Capture capture) {
        if (capture == null) {
            return;
        }
        if (!capture.sharcEnabled) {
            this.writeTimestamp(
                    commandBuffer, capture, 3, VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
        }
        if (capture.sharcEnabled) {
            VulkanSync.memoryBarrier(
                    commandBuffer,
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    VK12.VK_ACCESS_SHADER_WRITE_BIT,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_TRANSFER_READ_BIT);
        }
        if (this.queryPool != 0L) {
            int firstQuery = capture.sharcEnabled ? 0 : 2;
            int queryCount = capture.sharcEnabled ? TIMESTAMP_COUNT : 2;
            long destinationOffset = capture.sharcEnabled ? 0L : 2L * Long.BYTES;
            VK10.vkCmdCopyQueryPoolResults(
                    commandBuffer,
                    this.queryPool,
                    firstQuery,
                    queryCount,
                    capture.readback.handle(),
                    destinationOffset,
                    Long.BYTES,
                    VK10.VK_QUERY_RESULT_64_BIT | VK10.VK_QUERY_RESULT_WAIT_BIT);
        }
        if (capture.sharcEnabled) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack)
                        .srcOffset(0L)
                        .dstOffset(TIMESTAMP_BYTES)
                        .size(COUNTER_BYTES);
                VK10.vkCmdCopyBuffer(
                        commandBuffer,
                        this.counters.handle(),
                        capture.readback.handle(),
                        copy);
            }
        }
    }

    void submitted(Capture capture) {
        if (capture != null) {
            this.context.afterSubmission(() -> this.complete(capture));
        }
    }

    void abandon(Capture capture) {
        if (capture == null || this.pending != capture) {
            return;
        }
        this.pending = null;
        capture.readback.destroy();
    }

    SharcDiagnosticsSnapshot latest() {
        return this.latest;
    }

    long resourceBytes() {
        return this.counters.size();
    }

    private void writeTimestamp(
            VkCommandBuffer commandBuffer,
            Capture capture,
            int query,
            int stage) {
        if (capture != null && this.queryPool != 0L) {
            VK10.vkCmdWriteTimestamp(commandBuffer, stage, this.queryPool, query);
        }
    }

    private void complete(Capture capture) {
        if (this.destroyed || this.pending != capture) {
            return;
        }
        try {
            ByteBuffer data = ByteBuffer.wrap(
                    capture.readback.read(0L, READBACK_BYTES))
                    .order(ByteOrder.nativeOrder());
            if (capture.sharcEnabled) {
                if (this.timestampsSupported) {
                    double query = milliseconds(data.getLong(0), data.getLong(8));
                    double update = milliseconds(data.getLong(8), data.getLong(16));
                    double resolve = milliseconds(data.getLong(16), data.getLong(24));
                    this.updateMilliseconds = smooth(
                            this.updateMilliseconds, update, this.captureCount);
                    this.resolveMilliseconds = smooth(
                            this.resolveMilliseconds, resolve, this.captureCount);
                    this.queryMilliseconds = smooth(
                            this.queryMilliseconds, query, this.captureCount);
                }
                this.sampledQueries += unsignedCounter(data, 0);
                this.discreteSkips += unsignedCounter(data, 1);
                this.shortSegmentSkips += unsignedCounter(data, 2);
                this.glossyFootprintSkips += unsignedCounter(data, 3);
                this.lookupAttempts += unsignedCounter(data, 4);
                this.hits += unsignedCounter(data, 5);
                this.captureCount++;
            } else {
                double reference = milliseconds(
                        data.getLong(2 * Long.BYTES),
                        data.getLong(3 * Long.BYTES));
                this.referenceQueryMilliseconds = smooth(
                        this.referenceQueryMilliseconds,
                        reference,
                        this.referenceCaptureCount);
                this.referenceCaptureCount++;
            }
            this.latest = new SharcDiagnosticsSnapshot(
                    this.timestampsSupported,
                    this.captureCount,
                    this.updateMilliseconds,
                    this.resolveMilliseconds,
                    this.queryMilliseconds,
                    this.referenceCaptureCount,
                    this.referenceQueryMilliseconds,
                    this.sampledQueries,
                    this.discreteSkips,
                    this.shortSegmentSkips,
                    this.glossyFootprintSkips,
                    this.lookupAttempts,
                    this.hits,
                    SAMPLING_PERIOD);
        } finally {
            this.pending = null;
            capture.readback.destroy();
        }
    }

    private double milliseconds(long start, long end) {
        return (end - start) * this.nanosecondsPerTick * 1.0e-6;
    }

    private static double smooth(double previous, double current, long samples) {
        return samples == 0L
                ? current
                : previous + (current - previous) * SMOOTHING;
    }

    private static long unsignedCounter(ByteBuffer data, int index) {
        return Integer.toUnsignedLong(
                data.getInt(TIMESTAMP_BYTES + index * Integer.BYTES));
    }

    @Override
    public void destroy() {
        if (this.destroyed) {
            return;
        }
        this.destroyed = true;
        Capture capture = this.pending;
        this.pending = null;
        if (capture != null) {
            capture.readback.destroy();
        }
        if (this.queryPool != 0L) {
            VK10.vkDestroyQueryPool(this.context.vkDevice(), this.queryPool, null);
        }
        this.counters.destroy();
        this.latest = null;
    }

    record Capture(VulkanBuffer readback, boolean sharcEnabled) {
        Capture {
            java.util.Objects.requireNonNull(readback, "readback");
        }
    }
}
