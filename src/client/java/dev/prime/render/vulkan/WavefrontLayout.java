package dev.prime.render.vulkan;

/** Buffer sizing and device-limit validation for a wavefront queue ABI. */
record WavefrontLayout(
        int pathSlotsPerPixel,
        int queueEntriesPerPixel,
        int pathRecordSize,
        int areaRecordSize,
        int queueCount,
        int commandStride,
        int indexSize,
        String label) {
    private static final long QUEUE_OFFSET_ALIGNMENT = 256L;

    long wavefrontBytes(int width, int height) {
        return Math.addExact(queueOffset(width, height), queueBytes(width, height));
    }

    long queueOffset(int width, int height) {
        long pixels = pixels(width, height);
        long bytes = Math.multiplyExact(
                Math.multiplyExact(pixels, this.pathSlotsPerPixel),
                this.pathRecordSize);
        return VulkanContext.alignUp(bytes, QUEUE_OFFSET_ALIGNMENT);
    }

    long queueBytes(int width, int height) {
        long pixels = pixels(width, height);
        long capacity = Math.multiplyExact(pixels, this.queueEntriesPerPixel);
        long areas = Math.multiplyExact(pixels, this.areaRecordSize);
        long commands = Math.multiplyExact(this.queueCount, this.commandStride);
        long indices = Math.multiplyExact(
                Math.multiplyExact(this.queueCount, capacity), this.indexSize);
        return Math.addExact(areas, Math.addExact(commands, indices));
    }

    long queueCommandOffset(int width, int height) {
        long pixels = pixels(width, height);
        return Math.addExact(
                queueOffset(width, height),
                Math.multiplyExact(pixels, this.areaRecordSize));
    }

    void validateRanges(int width, int height, long maximumRange) {
        if (queueOffset(width, height) > maximumRange
                || queueBytes(width, height) > maximumRange) {
            throw new IllegalStateException(
                    this.label + " wavefront descriptor exceeds maxStorageBufferRange");
        }
    }

    void validateDispatch(int width, int height, int maximumInvocations) {
        long capacity = Math.multiplyExact(
                pixels(width, height), this.queueEntriesPerPixel);
        if (capacity > Integer.toUnsignedLong(maximumInvocations)) {
            throw new IllegalStateException(
                    this.label + " wavefront queue exceeds dispatch capacity");
        }
    }

    private static long pixels(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Wavefront extent must be positive");
        }
        return Math.multiplyExact((long) width, (long) height);
    }
}
