package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VkDevice;

public final class AccelerationStructure implements Destroyable {
    private final VkDevice device;
    private final long handle;
    private final long deviceAddress;
    private final VulkanBuffer backingBuffer;
    private boolean destroyed;

    AccelerationStructure(VkDevice device, long handle, long deviceAddress, VulkanBuffer backingBuffer) {
        this.device = device;
        this.handle = handle;
        this.deviceAddress = deviceAddress;
        this.backingBuffer = backingBuffer;
    }

    public long handle() {
        return this.handle;
    }

    public long deviceAddress() {
        return this.deviceAddress;
    }

    public long backingSize() {
        return this.backingBuffer.size();
    }

    @Override
    public void destroy() {
        if (!this.destroyed) {
            this.destroyed = true;
            KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(this.device, this.handle, null);
            this.backingBuffer.destroy();
        }
    }
}
