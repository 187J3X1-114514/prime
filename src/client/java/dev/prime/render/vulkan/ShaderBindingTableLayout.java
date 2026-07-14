package dev.prime.render.vulkan;

record ShaderBindingTableLayout(
        long recordStride,
        long raygenOffset,
        long missOffset,
        long hitOffset,
        long totalSize) {

    static ShaderBindingTableLayout create(
            int handleSize,
            int handleAlignment,
            int baseAlignment,
            int missGroupCount,
            int hitGroupCount,
            long bufferAddress) {
        if (handleSize <= 0 || missGroupCount <= 0 || hitGroupCount <= 0) {
            throw new IllegalArgumentException("Shader binding table dimensions must be positive");
        }
        long recordStride = VulkanContext.alignUp(handleSize, handleAlignment);
        long raygenOffset = VulkanContext.alignUp(bufferAddress, baseAlignment) - bufferAddress;
        long missOffset = VulkanContext.alignUp(bufferAddress + raygenOffset + recordStride, baseAlignment)
                - bufferAddress;
        long missSize = Math.multiplyExact(recordStride, missGroupCount);
        long hitOffset = VulkanContext.alignUp(bufferAddress + missOffset + missSize, baseAlignment) - bufferAddress;
        long totalSize = Math.addExact(hitOffset, Math.multiplyExact(recordStride, hitGroupCount));
        return new ShaderBindingTableLayout(recordStride, raygenOffset, missOffset, hitOffset, totalSize);
    }

    static long minimumBufferSize(
            int handleSize,
            int handleAlignment,
            int baseAlignment,
            int missGroupCount,
            int hitGroupCount) {
        ShaderBindingTableLayout aligned = create(
                handleSize, handleAlignment, baseAlignment, missGroupCount, hitGroupCount, 0L);
        return Math.addExact(aligned.totalSize(), baseAlignment - 1L);
    }
}
