package dev.prime.mixin;

import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.SurfaceException;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import java.nio.IntBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanGpuSurface.class)
public abstract class VulkanGpuSurfaceMixin {
    @Unique private static final int PRIME_MAX_SURFACE_FORMAT_QUERY_ATTEMPTS = 4;

    @Shadow @Final private VulkanDevice device;
    @Shadow @Final private long surface;

    @Unique private int prime$swapchainImageFormat;
    @Unique private int prime$swapchainColorSpace;

    @Shadow
    public abstract VkSurfaceFormatKHR pickSwapchainSurfaceFormat(
            VkSurfaceFormatKHR.Buffer formats);

    @Inject(method = "configure", at = @At("HEAD"))
    private void prime$refreshSurfaceFormat(
            GpuSurface.Configuration configuration,
            CallbackInfo callbackInfo)
            throws SurfaceException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.callocInt(1);
            for (int attempt = 0;
                    attempt < PRIME_MAX_SURFACE_FORMAT_QUERY_ATTEMPTS;
                    attempt++) {
                count.put(0, 0);
                int countResult = KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(
                        this.device.vkDevice().getPhysicalDevice(),
                        this.surface,
                        count,
                        null);
                VulkanGpuSurface.throwIfFailure(
                        countResult, "Failed to get surface format count");
                if (countResult != VK12.VK_SUCCESS) {
                    throw new SurfaceException(
                            "Unexpected Vulkan result "
                                    + countResult
                                    + " while getting surface format count");
                }

                int capacity = count.get(0);
                if (capacity <= 0) {
                    throw new SurfaceException("Surface reported no supported formats");
                }
                VkSurfaceFormatKHR.Buffer formats =
                        VkSurfaceFormatKHR.calloc(capacity, stack);
                int formatsResult = KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(
                        this.device.vkDevice().getPhysicalDevice(),
                        this.surface,
                        count,
                        formats);
                if (formatsResult == VK12.VK_INCOMPLETE) {
                    continue;
                }
                VulkanGpuSurface.throwIfFailure(
                        formatsResult, "Failed to enumerate surface formats");
                if (formatsResult != VK12.VK_SUCCESS) {
                    throw new SurfaceException(
                            "Unexpected Vulkan result "
                                    + formatsResult
                                    + " while enumerating surface formats");
                }

                int returned = count.get(0);
                if (returned <= 0 || returned > capacity) {
                    throw new SurfaceException(
                            "Surface returned an invalid format count " + returned);
                }
                formats.limit(returned);
                VkSurfaceFormatKHR selected;
                try {
                    selected = this.pickSwapchainSurfaceFormat(formats);
                } catch (IllegalStateException exception) {
                    throw new SurfaceException(exception);
                }
                this.prime$swapchainImageFormat = selected.format();
                this.prime$swapchainColorSpace = selected.colorSpace();
                return;
            }
        }
        throw new SurfaceException("Surface formats kept changing during enumeration");
    }

    @ModifyArg(
            method = "configure",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;imageFormat(I)Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;"),
            index = 0)
    private int prime$useCurrentSurfaceFormat(int cachedFormat) {
        return this.prime$swapchainImageFormat;
    }

    @ModifyArg(
            method = "configure",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;imageColorSpace(I)Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;"),
            index = 0)
    private int prime$useCurrentColorSpace(int cachedColorSpace) {
        return this.prime$swapchainColorSpace;
    }
}
