package dev.prime.render.vulkan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

/** Creates Vulkan shader modules from packaged SPIR-V or native-provided bytes. */
public final class VulkanShaderModules {
    private VulkanShaderModules() {
    }

    public static long create(VulkanContext context, String resourceName) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            return create(context, stack, resourceName);
        }
    }

    public static long create(
            VulkanContext context, MemoryStack stack, String resourceName) {
        byte[] bytes;
        try (InputStream input = VulkanShaderModules.class.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IllegalStateException("Missing shader resource " + resourceName);
            }
            bytes = input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to read shader resource " + resourceName, exception);
        }
        return create(context, stack, bytes, resourceName);
    }

    public static long create(
            VulkanContext context,
            MemoryStack stack,
            byte[] bytes,
            String label) {
        // Keep large SPIR-V payloads off LWJGL's fixed per-thread stack.
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length);
        try {
            code.put(bytes).flip();
            LongBuffer pointer = stack.mallocLong(1);
            VulkanContext.check(
                    VK12.vkCreateShaderModule(
                            context.vkDevice(),
                            VkShaderModuleCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .pCode(code),
                            null,
                            pointer),
                    "create " + label);
            return pointer.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
