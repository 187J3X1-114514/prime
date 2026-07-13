package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;

public final class VulkanImage implements Destroyable {
    private final long allocator;
    private final VkDevice device;
    private final long image;
    private final long allocation;
    private final long view;
    private final int width;
    private final int height;
    private boolean initialized;
    private boolean destroyed;

    VulkanImage(long allocator, VkDevice device, long image, long allocation, long view, int width, int height) {
        this.allocator = allocator;
        this.device = device;
        this.image = image;
        this.allocation = allocation;
        this.view = view;
        this.width = width;
        this.height = height;
    }

    public long image() {
        return this.image;
    }

    public long view() {
        return this.view;
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    public boolean initialized() {
        return this.initialized;
    }

    public void markInitialized() {
        this.initialized = true;
    }

    @Override
    public void destroy() {
        if (!this.destroyed) {
            this.destroyed = true;
            VK12.vkDestroyImageView(this.device, this.view, null);
            Vma.vmaDestroyImage(this.allocator, this.image, this.allocation);
        }
    }
}
