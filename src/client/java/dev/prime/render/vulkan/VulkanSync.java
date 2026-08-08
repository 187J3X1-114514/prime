package dev.prime.render.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;

/** Shared synchronization2 memory barrier recording. */
public final class VulkanSync {
    private VulkanSync() {
    }

    public static void memoryBarrier(
            VkCommandBuffer commandBuffer,
            long sourceStage,
            long sourceAccess,
            long destinationStage,
            long destinationAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            memoryBarrier(
                    commandBuffer,
                    stack,
                    sourceStage,
                    sourceAccess,
                    destinationStage,
                    destinationAccess);
        }
    }

    public static void memoryBarrier(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            long sourceStage,
            long sourceAccess,
            long destinationStage,
            long destinationAccess) {
        VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack);
        barrier.get(0).sType$Default()
                .srcStageMask(sourceStage)
                .srcAccessMask(sourceAccess)
                .dstStageMask(destinationStage)
                .dstAccessMask(destinationAccess);
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                commandBuffer,
                VkDependencyInfo.calloc(stack)
                        .sType$Default()
                        .pMemoryBarriers(barrier));
    }
}
