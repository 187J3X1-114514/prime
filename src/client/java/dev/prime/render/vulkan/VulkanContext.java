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
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

public final class VulkanContext implements AutoCloseable {
    private final VulkanDevice device;
    private final VulkanCapabilities capabilities;
    private final long allocator;
    private final long uniformBufferOffsetAlignment;
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
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            VK12.vkGetPhysicalDeviceProperties(device.vkDevice().getPhysicalDevice(), properties);
            this.uniformBufferOffsetAlignment = properties.limits().minUniformBufferOffsetAlignment();
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

    public long uniformBufferOffsetAlignment() {
        return this.uniformBufferOffsetAlignment;
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
        return this.createImage(
                width,
                height,
                1,
                VK12.VK_FORMAT_R8G8B8A8_UNORM,
                VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                "Prime output");
    }

    public VulkanImage createAccumulationImage(int width, int height) {
        return this.createImage(
                width,
                height,
                1,
                VK12.VK_FORMAT_R32G32B32A32_SFLOAT,
                VK12.VK_IMAGE_USAGE_STORAGE_BIT,
                "Prime accumulation");
    }

    public VulkanImage createAtmosphereImage2D(int width, int height, String label) {
        return this.createImage(
                width,
                height,
                1,
                VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                VK12.VK_IMAGE_USAGE_STORAGE_BIT,
                label);
    }

    public VulkanImage createAtmosphereImage3D(int width, int height, int depth, String label) {
        return this.createImage(
                width,
                height,
                depth,
                VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                VK12.VK_IMAGE_USAGE_STORAGE_BIT,
                label);
    }

    public VulkanImage createImage2D(int width, int height, int format, int usage, String label) {
        return this.createImage(width, height, 1, format, usage, label);
    }

    private VulkanImage createImage(
            int width,
            int height,
            int depth,
            int format,
            int usage,
            String label) {
        if (width <= 0 || height <= 0 || depth <= 0) {
            throw new IllegalArgumentException("Vulkan image dimensions must be positive");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo imageCreateInfo = VkImageCreateInfo.calloc(stack)
                    .sType$Default()
                    .imageType(depth == 1 ? VK12.VK_IMAGE_TYPE_2D : VK12.VK_IMAGE_TYPE_3D)
                    .format(format)
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK12.VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK12.VK_IMAGE_TILING_OPTIMAL)
                    .usage(usage)
                    .sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED);
            imageCreateInfo.extent().set(width, height, depth);
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
                    null), "create " + label + " image");
            long image = imagePointer.get(0);
            long view = 0L;
            try {
                VkImageViewCreateInfo viewCreateInfo = VkImageViewCreateInfo.calloc(stack)
                        .sType$Default()
                        .image(image)
                        .viewType(depth == 1 ? VK12.VK_IMAGE_VIEW_TYPE_2D : VK12.VK_IMAGE_VIEW_TYPE_3D)
                        .format(format);
                viewCreateInfo.subresourceRange()
                        .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0)
                        .levelCount(1)
                        .baseArrayLayer(0)
                        .layerCount(1);
                LongBuffer viewPointer = stack.mallocLong(1);
                check(
                        VK12.vkCreateImageView(this.device.vkDevice(), viewCreateInfo, null, viewPointer),
                        "create " + label + " view");
                view = viewPointer.get(0);
                this.device.instance().debug().setObjectName(
                        this.device.vkDevice(), VK12.VK_OBJECT_TYPE_IMAGE, image, label);
                this.device.instance().debug().setObjectName(
                        this.device.vkDevice(), VK12.VK_OBJECT_TYPE_IMAGE_VIEW, view, label + " view");
                return new VulkanImage(
                        this.allocator,
                        this.device.vkDevice(),
                        image,
                        allocationPointer.get(0),
                        view,
                        format,
                        width,
                        height,
                        depth);
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

    /** Runs only after all commands submitted before this call have completed on the real queue timeline. */
    public void afterSubmission(Runnable callback) {
        if (this.closed) {
            throw new IllegalStateException("Cannot register a completion callback after the Vulkan context has closed");
        }
        this.commandEncoder().queueForDestroy(callback::run);
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
