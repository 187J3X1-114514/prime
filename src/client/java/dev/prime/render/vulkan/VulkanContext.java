package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

public final class VulkanContext implements AutoCloseable {
    private final VulkanDevice device;
    private final VulkanCapabilities capabilities;
    private final long allocator;
    private final Set<Destroyable> deferred = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean closed;

    public VulkanContext(VulkanDevice device, VulkanCapabilities capabilities) {
        this.device = device;
        this.capabilities = capabilities;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VmaVulkanFunctions functions = VmaVulkanFunctions.calloc(stack)
                    .set(device.vkDevice().getPhysicalDevice().getInstance(), device.vkDevice());
            VmaAllocatorCreateInfo createInfo = VmaAllocatorCreateInfo.calloc(stack)
                    .flags(Vma.VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT)
                    .instance(device.vkDevice().getPhysicalDevice().getInstance())
                    .physicalDevice(device.vkDevice().getPhysicalDevice())
                    .device(device.vkDevice())
                    .vulkanApiVersion(VK12.VK_API_VERSION_1_2)
                    .pVulkanFunctions(functions);
            PointerBuffer pointer = stack.mallocPointer(1);
            check(Vma.vmaCreateAllocator(createInfo, pointer), "create ray tracing VMA allocator");
            this.allocator = pointer.get(0);
        }
    }

    public VulkanDevice device() {
        return this.device;
    }

    public VkDevice vkDevice() {
        return this.device.vkDevice();
    }

    public VulkanCapabilities capabilities() {
        return this.capabilities;
    }

    public VulkanCommandEncoder commandEncoder() {
        return this.device.createCommandEncoder();
    }

    public VulkanBuffer createBuffer(long size, int usage, boolean hostVisible, String label) {
        if (size <= 0L) {
            throw new IllegalArgumentException("Vulkan buffer size must be positive");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferCreateInfo = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(size)
                    .usage(usage | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT)
                    .sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo allocationCreateInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(hostVisible ? Vma.VMA_MEMORY_USAGE_AUTO_PREFER_HOST : Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            if (hostVisible) {
                allocationCreateInfo.flags(
                        Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT
                                | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
            }
            LongBuffer bufferPointer = stack.mallocLong(1);
            PointerBuffer allocationPointer = stack.mallocPointer(1);
            VmaAllocationInfo allocationInfo = VmaAllocationInfo.calloc(stack);
            check(Vma.vmaCreateBuffer(
                    this.allocator,
                    bufferCreateInfo,
                    allocationCreateInfo,
                    bufferPointer,
                    allocationPointer,
                    allocationInfo), "create " + label);
            long handle = bufferPointer.get(0);
            this.device.instance().debug().setObjectName(this.device.vkDevice(), VK12.VK_OBJECT_TYPE_BUFFER, handle, label);
            VkBufferDeviceAddressInfo addressInfo = VkBufferDeviceAddressInfo.calloc(stack)
                    .sType$Default()
                    .buffer(handle);
            long address = VK12.vkGetBufferDeviceAddress(this.device.vkDevice(), addressInfo);
            return new VulkanBuffer(
                    this.allocator,
                    allocationPointer.get(0),
                    handle,
                    address,
                    hostVisible ? allocationInfo.pMappedData() : 0L,
                    size);
        }
    }

    public VulkanImage createOutputImage(int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo imageCreateInfo = VkImageCreateInfo.calloc(stack)
                    .sType$Default()
                    .imageType(VK12.VK_IMAGE_TYPE_2D)
                    .format(VK12.VK_FORMAT_R8G8B8A8_UNORM)
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK12.VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK12.VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED);
            imageCreateInfo.extent().set(width, height, 1);
            VmaAllocationCreateInfo allocationCreateInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            LongBuffer imagePointer = stack.mallocLong(1);
            PointerBuffer allocationPointer = stack.mallocPointer(1);
            check(Vma.vmaCreateImage(
                    this.allocator,
                    imageCreateInfo,
                    allocationCreateInfo,
                    imagePointer,
                    allocationPointer,
                    null), "create ray tracing output image");
            long image = imagePointer.get(0);
            long view = 0L;
            try {
                VkImageViewCreateInfo viewCreateInfo = VkImageViewCreateInfo.calloc(stack)
                        .sType$Default()
                        .image(image)
                        .viewType(VK12.VK_IMAGE_VIEW_TYPE_2D)
                        .format(VK12.VK_FORMAT_R8G8B8A8_UNORM);
                viewCreateInfo.subresourceRange()
                        .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0)
                        .levelCount(1)
                        .baseArrayLayer(0)
                        .layerCount(1);
                LongBuffer viewPointer = stack.mallocLong(1);
                check(
                        VK12.vkCreateImageView(this.device.vkDevice(), viewCreateInfo, null, viewPointer),
                        "create ray tracing output view");
                view = viewPointer.get(0);
                this.device.instance().debug().setObjectName(
                        this.device.vkDevice(), VK12.VK_OBJECT_TYPE_IMAGE, image, "Prime output");
                this.device.instance().debug().setObjectName(
                        this.device.vkDevice(), VK12.VK_OBJECT_TYPE_IMAGE_VIEW, view, "Prime output view");
                return new VulkanImage(
                        this.allocator,
                        this.device.vkDevice(),
                        image,
                        allocationPointer.get(0),
                        view,
                        width,
                        height);
            } catch (RuntimeException exception) {
                if (view != 0L) {
                    VK12.vkDestroyImageView(this.device.vkDevice(), view, null);
                }
                Vma.vmaDestroyImage(this.allocator, image, allocationPointer.get(0));
                throw exception;
            }
        }
    }

    public void defer(Destroyable destroyable) {
        if (this.closed) {
            throw new IllegalStateException("Cannot defer a resource after the Vulkan context has closed");
        }
        synchronized (this.deferred) {
            this.deferred.add(destroyable);
        }
        try {
            this.commandEncoder().queueForDestroy(() -> {
                boolean shouldDestroy;
                synchronized (VulkanContext.this.deferred) {
                    shouldDestroy = VulkanContext.this.deferred.remove(destroyable);
                }
                if (shouldDestroy) {
                    destroyable.destroy();
                }
            });
        } catch (RuntimeException exception) {
            synchronized (this.deferred) {
                this.deferred.remove(destroyable);
            }
            throw exception;
        }
    }

    public void awaitIdle() {
        check(VK12.vkDeviceWaitIdle(this.device.vkDevice()), "wait for Vulkan device");
    }

    public void drainDeferredAfterIdle() {
        ArrayList<Destroyable> pending;
        synchronized (this.deferred) {
            pending = new ArrayList<>(this.deferred);
            this.deferred.clear();
        }
        for (Destroyable destroyable : pending) {
            destroyable.destroy();
        }
    }

    public static long alignUp(long value, long alignment) {
        if (alignment <= 0L || (alignment & alignment - 1L) != 0L) {
            throw new IllegalArgumentException("Alignment must be a positive power of two");
        }
        return value + alignment - 1L & -alignment;
    }

    public static void check(int result, String operation) {
        if (result != VK12.VK_SUCCESS) {
            throw new IllegalStateException(operation + " failed with Vulkan result " + result);
        }
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            this.awaitIdle();
            this.drainDeferredAfterIdle();
            Vma.vmaDestroyAllocator(this.allocator);
        }
    }
}
