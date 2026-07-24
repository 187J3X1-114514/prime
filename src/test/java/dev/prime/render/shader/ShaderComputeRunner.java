package dev.prime.render.shader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/**
 * Minimal headless Vulkan owner for compute shader tests.
 *
 * <p>The runner deliberately does not reuse Minecraft's renderer: a test owns exactly one instance,
 * device, queue and command pool. Dispatches own their pipeline, descriptors and mapped buffers;
 * optional immutable sampled resources remain owned by the runner for its complete lifetime. This
 * keeps shader behavior tests independent of client initialization and render state.
 */
final class ShaderComputeRunner implements AutoCloseable {
    private static final int LOCAL_SIZE = 64;
    private static final int COMPUTE_STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;

    private final VkInstance instance;
    private final VkPhysicalDevice physicalDevice;
    private final VkDevice device;
    private final VkQueue queue;
    private final long commandPool;
    private SampledImage3D transmissionGgxEnergy;
    private boolean closed;

    private ShaderComputeRunner(
            VkInstance instance,
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue queue,
            long commandPool) {
        this.instance = instance;
        this.physicalDevice = physicalDevice;
        this.device = device;
        this.queue = queue;
        this.commandPool = commandPool;
    }

    static ShaderComputeRunner open() throws UnavailableException {
        VkInstance instance = null;
        VkDevice device = null;
        long commandPool = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkApplicationInfo applicationInfo = VkApplicationInfo.calloc(stack)
                    .sType$Default()
                    .pApplicationName(stack.UTF8("Prime shader tests"))
                    .applicationVersion(1)
                    .pEngineName(stack.UTF8("Prime"))
                    .engineVersion(1)
                    .apiVersion(VK12.VK_API_VERSION_1_2);
            VkInstanceCreateInfo instanceInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType$Default()
                    .pApplicationInfo(applicationInfo);
            PointerBuffer pointer = stack.mallocPointer(1);
            int result = VK12.vkCreateInstance(
                    instanceInfo,
                    null,
                    pointer);
            if (result != VK12.VK_SUCCESS) {
                throw new UnavailableException("vkCreateInstance returned " + result);
            }
            instance = new VkInstance(pointer.get(0), instanceInfo);

            SelectedDevice selected = selectDevice(instance, stack);
            VkDeviceQueueCreateInfo.Buffer queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack);
            queueInfo.get(0)
                    .sType$Default()
                    .queueFamilyIndex(selected.queueFamily())
                    .pQueuePriorities(stack.floats(1.0F));
            VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc(stack)
                    .sType$Default()
                    .pQueueCreateInfos(queueInfo);
            pointer.clear();
            result = VK12.vkCreateDevice(selected.physicalDevice(), deviceInfo, null, pointer);
            if (result != VK12.VK_SUCCESS) {
                throw new UnavailableException("vkCreateDevice returned " + result);
            }
            device = new VkDevice(pointer.get(0), selected.physicalDevice(), deviceInfo);

            pointer.clear();
            VK12.vkGetDeviceQueue(device, selected.queueFamily(), 0, pointer);
            VkQueue queue = new VkQueue(pointer.get(0), device);
            LongBuffer handle = stack.mallocLong(1);
            check(
                    VK12.vkCreateCommandPool(
                            device,
                            VkCommandPoolCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .flags(VK12.VK_COMMAND_POOL_CREATE_TRANSIENT_BIT)
                                    .queueFamilyIndex(selected.queueFamily()),
                            null,
                            handle),
                    "create shader-test command pool");
            commandPool = handle.get(0);
            return new ShaderComputeRunner(
                    instance, selected.physicalDevice(), device, queue, commandPool);
        } catch (UnavailableException exception) {
            if (commandPool != 0L && device != null) {
                VK12.vkDestroyCommandPool(device, commandPool, null);
            }
            if (device != null) {
                VK12.vkDestroyDevice(device, null);
            }
            if (instance != null) {
                VK12.vkDestroyInstance(instance, null);
            }
            throw exception;
        } catch (RuntimeException | LinkageError exception) {
            if (commandPool != 0L && device != null) {
                VK12.vkDestroyCommandPool(device, commandPool, null);
            }
            if (device != null) {
                VK12.vkDestroyDevice(device, null);
            }
            if (instance != null) {
                VK12.vkDestroyInstance(instance, null);
            }
            throw exception;
        }
    }

    ByteBuffer dispatch(
            Path shaderPath, ByteBuffer input, int outputBytes, int invocationCount)
            throws IOException {
        requireOpen();
        if (outputBytes <= 0 || invocationCount <= 0) {
            throw new IllegalArgumentException("Shader output and invocation count must be positive");
        }
        ByteBuffer inputData = input.duplicate();
        if (!inputData.hasRemaining()) {
            throw new IllegalArgumentException("Shader input must not be empty");
        }

        try (MappedBuffer inputBuffer = createMappedBuffer(inputData.remaining());
                MappedBuffer outputBuffer = createMappedBuffer(outputBytes)) {
            inputBuffer.bytes().put(inputData).flip();
            zero(outputBuffer.bytes());
            dispatch(shaderPath, inputBuffer, outputBuffer, invocationCount);

            ByteBuffer result = ByteBuffer.allocateDirect(outputBytes).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer mappedOutput = outputBuffer.bytes().duplicate();
            mappedOutput.clear().limit(outputBytes);
            result.put(mappedOutput).flip();
            return result;
        }
    }

    void loadTransmissionGgxEnergy(
            ByteBuffer pixels, int width, int height, int depth) {
        requireOpen();
        if (this.transmissionGgxEnergy != null) {
            throw new IllegalStateException("Transmission GGX energy table is already loaded");
        }
        if (width <= 0 || height <= 0 || depth <= 0) {
            throw new IllegalArgumentException("Shader-test image dimensions must be positive");
        }
        int byteSize = Math.multiplyExact(
                Math.multiplyExact(Math.multiplyExact(width, height), depth),
                4 * Short.BYTES);
        ByteBuffer source = pixels.duplicate();
        if (source.remaining() != byteSize) {
            throw new IllegalArgumentException(
                    "Transmission GGX energy table has "
                            + source.remaining()
                            + " bytes, expected "
                            + byteSize);
        }

        long image = 0L;
        long memory = 0L;
        long view = 0L;
        long sampler = 0L;
        try (MappedBuffer upload = createMappedBuffer(
                byteSize, VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
                MemoryStack stack = MemoryStack.stackPush()) {
            upload.bytes().put(source).flip();
            LongBuffer handle = stack.mallocLong(1);
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType$Default()
                    .imageType(VK12.VK_IMAGE_TYPE_3D)
                    .format(VK12.VK_FORMAT_R16G16B16A16_SFLOAT)
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK12.VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK12.VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT
                            | VK12.VK_IMAGE_USAGE_SAMPLED_BIT)
                    .sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.extent().set(width, height, depth);
            check(
                    VK12.vkCreateImage(this.device, imageInfo, null, handle),
                    "create shader-test transmission GGX image");
            image = handle.get(0);

            VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
            VK12.vkGetImageMemoryRequirements(this.device, image, requirements);
            int memoryType = findMemoryType(
                    requirements.memoryTypeBits(),
                    VK12.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                    stack);
            handle.clear();
            check(
                    VK12.vkAllocateMemory(
                            this.device,
                            VkMemoryAllocateInfo.calloc(stack)
                                    .sType$Default()
                                    .allocationSize(requirements.size())
                                    .memoryTypeIndex(memoryType),
                            null,
                            handle),
                    "allocate shader-test transmission GGX image memory");
            memory = handle.get(0);
            check(
                    VK12.vkBindImageMemory(this.device, image, memory, 0L),
                    "bind shader-test transmission GGX image memory");

            handle.clear();
            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType$Default()
                    .image(image)
                    .viewType(VK12.VK_IMAGE_VIEW_TYPE_3D)
                    .format(VK12.VK_FORMAT_R16G16B16A16_SFLOAT);
            viewInfo.subresourceRange()
                    .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1);
            check(
                    VK12.vkCreateImageView(this.device, viewInfo, null, handle),
                    "create shader-test transmission GGX image view");
            view = handle.get(0);

            handle.clear();
            check(
                    VK12.vkCreateSampler(
                            this.device,
                            VkSamplerCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .magFilter(VK12.VK_FILTER_LINEAR)
                                    .minFilter(VK12.VK_FILTER_LINEAR)
                                    .mipmapMode(VK12.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                                    .addressModeU(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                                    .addressModeV(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                                    .addressModeW(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                                    .minLod(0.0F)
                                    .maxLod(0.0F)
                                    .maxAnisotropy(1.0F),
                            null,
                            handle),
                    "create shader-test transmission GGX sampler");
            sampler = handle.get(0);

            uploadImage3D(upload, image, width, height, depth);
            this.transmissionGgxEnergy =
                    new SampledImage3D(this.device, image, memory, view, sampler);
            image = 0L;
            memory = 0L;
            view = 0L;
            sampler = 0L;
        } finally {
            if (sampler != 0L) {
                VK12.vkDestroySampler(this.device, sampler, null);
            }
            if (view != 0L) {
                VK12.vkDestroyImageView(this.device, view, null);
            }
            if (image != 0L) {
                VK12.vkDestroyImage(this.device, image, null);
            }
            if (memory != 0L) {
                VK12.vkFreeMemory(this.device, memory, null);
            }
        }
    }

    private void dispatch(
            Path shaderPath,
            MappedBuffer inputBuffer,
            MappedBuffer outputBuffer,
            int invocationCount)
            throws IOException {
        long setLayout = 0L;
        long pipelineLayout = 0L;
        long shaderModule = 0L;
        long pipeline = 0L;
        long descriptorPool = 0L;
        VkCommandBuffer commandBuffer = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer handle = stack.mallocLong(1);
            VkDescriptorSetLayoutBinding.Buffer bindings =
                    VkDescriptorSetLayoutBinding.calloc(3, stack);
            bindings.get(0)
                    .binding(0)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(COMPUTE_STAGE);
            bindings.get(1)
                    .binding(1)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(COMPUTE_STAGE);
            bindings.get(2)
                    .binding(2)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(COMPUTE_STAGE);
            check(
                    VK12.vkCreateDescriptorSetLayout(
                            this.device,
                            VkDescriptorSetLayoutCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .pBindings(bindings),
                            null,
                            handle),
                    "create shader-test descriptor layout");
            setLayout = handle.get(0);

            handle.clear();
            check(
                    VK12.vkCreatePipelineLayout(
                            this.device,
                            VkPipelineLayoutCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .pSetLayouts(stack.longs(setLayout)),
                            null,
                            handle),
                    "create shader-test pipeline layout");
            pipelineLayout = handle.get(0);

            shaderModule = createShaderModule(shaderPath, stack);
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default()
                    .stage(COMPUTE_STAGE)
                    .module(shaderModule)
                    .pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer pipelineInfo =
                    VkComputePipelineCreateInfo.calloc(1, stack);
            pipelineInfo.get(0).sType$Default().stage(stage).layout(pipelineLayout);
            handle.clear();
            check(
                    VK12.vkCreateComputePipelines(
                            this.device, 0L, pipelineInfo, null, handle),
                    "create shader-test compute pipeline");
            pipeline = handle.get(0);
            VK12.vkDestroyShaderModule(this.device, shaderModule, null);
            shaderModule = 0L;

            int poolTypeCount = this.transmissionGgxEnergy == null ? 1 : 2;
            VkDescriptorPoolSize.Buffer poolSizes =
                    VkDescriptorPoolSize.calloc(poolTypeCount, stack);
            poolSizes.get(0)
                    .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(2);
            if (this.transmissionGgxEnergy != null) {
                poolSizes.get(1)
                        .type(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(1);
            }
            handle.clear();
            check(
                    VK12.vkCreateDescriptorPool(
                            this.device,
                            VkDescriptorPoolCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .maxSets(1)
                                    .pPoolSizes(poolSizes),
                            null,
                            handle),
                    "create shader-test descriptor pool");
            descriptorPool = handle.get(0);

            handle.clear();
            check(
                    VK12.vkAllocateDescriptorSets(
                            this.device,
                            VkDescriptorSetAllocateInfo.calloc(stack)
                                    .sType$Default()
                                    .descriptorPool(descriptorPool)
                                    .pSetLayouts(stack.longs(setLayout)),
                            handle),
                    "allocate shader-test descriptor set");
            long descriptorSet = handle.get(0);
            VkDescriptorBufferInfo.Buffer bufferInfos =
                    VkDescriptorBufferInfo.calloc(2, stack);
            bufferInfos.get(0)
                    .buffer(inputBuffer.buffer())
                    .offset(0L)
                    .range(inputBuffer.size());
            bufferInfos.get(1)
                    .buffer(outputBuffer.buffer())
                    .offset(0L)
                    .range(outputBuffer.size());
            int writeCount = this.transmissionGgxEnergy == null ? 2 : 3;
            VkWriteDescriptorSet.Buffer writes =
                    VkWriteDescriptorSet.calloc(writeCount, stack);
            writes.get(0)
                    .sType$Default()
                    .dstSet(descriptorSet)
                    .dstBinding(0)
                    .descriptorCount(1)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(VkDescriptorBufferInfo.create(
                            bufferInfos.get(0).address(), 1));
            writes.get(1)
                    .sType$Default()
                    .dstSet(descriptorSet)
                    .dstBinding(1)
                    .descriptorCount(1)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(VkDescriptorBufferInfo.create(
                            bufferInfos.get(1).address(), 1));
            if (this.transmissionGgxEnergy != null) {
                VkDescriptorImageInfo.Buffer imageInfo =
                        VkDescriptorImageInfo.calloc(1, stack);
                imageInfo.get(0)
                        .sampler(this.transmissionGgxEnergy.sampler())
                        .imageView(this.transmissionGgxEnergy.view())
                        .imageLayout(VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                writes.get(2)
                        .sType$Default()
                        .dstSet(descriptorSet)
                        .dstBinding(2)
                        .descriptorCount(1)
                        .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .pImageInfo(imageInfo);
            }
            VK12.vkUpdateDescriptorSets(this.device, writes, null);

            PointerBuffer commandPointer = stack.mallocPointer(1);
            check(
                    VK12.vkAllocateCommandBuffers(
                            this.device,
                            VkCommandBufferAllocateInfo.calloc(stack)
                                    .sType$Default()
                                    .commandPool(this.commandPool)
                                    .level(VK12.VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                                    .commandBufferCount(1),
                            commandPointer),
                    "allocate shader-test command buffer");
            commandBuffer = new VkCommandBuffer(commandPointer.get(0), this.device);
            check(
                    VK12.vkBeginCommandBuffer(
                            commandBuffer,
                            VkCommandBufferBeginInfo.calloc(stack)
                                    .sType$Default()
                                    .flags(VK12.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)),
                    "begin shader-test command buffer");
            VK12.vkCmdBindPipeline(
                    commandBuffer, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK12.vkCmdBindDescriptorSets(
                    commandBuffer,
                    VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                    pipelineLayout,
                    0,
                    stack.longs(descriptorSet),
                    null);
            VK12.vkCmdDispatch(
                    commandBuffer,
                    Math.max(1, (invocationCount + LOCAL_SIZE - 1) / LOCAL_SIZE),
                    1,
                    1);
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
            barrier.get(0)
                    .sType$Default()
                    .srcAccessMask(VK12.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK12.VK_ACCESS_HOST_READ_BIT);
            VK12.vkCmdPipelineBarrier(
                    commandBuffer,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_PIPELINE_STAGE_HOST_BIT,
                    0,
                    barrier,
                    null,
                    null);
            check(VK12.vkEndCommandBuffer(commandBuffer), "end shader-test command buffer");

            VkSubmitInfo.Buffer submit = VkSubmitInfo.calloc(1, stack);
            submit.get(0)
                    .sType$Default()
                    .pCommandBuffers(stack.pointers(commandBuffer.address()));
            check(VK12.vkQueueSubmit(this.queue, submit, 0L), "submit shader-test dispatch");
            check(VK12.vkQueueWaitIdle(this.queue), "wait for shader-test dispatch");
        } finally {
            if (commandBuffer != null) {
                VK12.vkFreeCommandBuffers(this.device, this.commandPool, commandBuffer);
            }
            if (descriptorPool != 0L) {
                VK12.vkDestroyDescriptorPool(this.device, descriptorPool, null);
            }
            if (pipeline != 0L) {
                VK12.vkDestroyPipeline(this.device, pipeline, null);
            }
            if (shaderModule != 0L) {
                VK12.vkDestroyShaderModule(this.device, shaderModule, null);
            }
            if (pipelineLayout != 0L) {
                VK12.vkDestroyPipelineLayout(this.device, pipelineLayout, null);
            }
            if (setLayout != 0L) {
                VK12.vkDestroyDescriptorSetLayout(this.device, setLayout, null);
            }
        }
    }

    private void uploadImage3D(
            MappedBuffer upload, long image, int width, int height, int depth) {
        VkCommandBuffer commandBuffer = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer commandPointer = stack.mallocPointer(1);
            check(
                    VK12.vkAllocateCommandBuffers(
                            this.device,
                            VkCommandBufferAllocateInfo.calloc(stack)
                                    .sType$Default()
                                    .commandPool(this.commandPool)
                                    .level(VK12.VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                                    .commandBufferCount(1),
                            commandPointer),
                    "allocate shader-test image upload command buffer");
            commandBuffer = new VkCommandBuffer(commandPointer.get(0), this.device);
            check(
                    VK12.vkBeginCommandBuffer(
                            commandBuffer,
                            VkCommandBufferBeginInfo.calloc(stack)
                                    .sType$Default()
                                    .flags(VK12.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)),
                    "begin shader-test image upload");

            VkImageMemoryBarrier.Buffer toTransfer =
                    VkImageMemoryBarrier.calloc(1, stack);
            fillImageBarrier(
                    toTransfer.get(0),
                    image,
                    0,
                    VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK12.VK_IMAGE_LAYOUT_UNDEFINED,
                    VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            VK12.vkCmdPipelineBarrier(
                    commandBuffer,
                    VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0,
                    null,
                    null,
                    toTransfer);

            VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack);
            copy.get(0)
                    .bufferOffset(0L)
                    .bufferRowLength(0)
                    .bufferImageHeight(0);
            copy.get(0).imageSubresource()
                    .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0)
                    .baseArrayLayer(0)
                    .layerCount(1);
            copy.get(0).imageOffset().set(0, 0, 0);
            copy.get(0).imageExtent().set(width, height, depth);
            VK12.vkCmdCopyBufferToImage(
                    commandBuffer,
                    upload.buffer(),
                    image,
                    VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    copy);

            VkImageMemoryBarrier.Buffer toShader =
                    VkImageMemoryBarrier.calloc(1, stack);
            fillImageBarrier(
                    toShader.get(0),
                    image,
                    VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK12.VK_ACCESS_SHADER_READ_BIT,
                    VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            VK12.vkCmdPipelineBarrier(
                    commandBuffer,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0,
                    null,
                    null,
                    toShader);
            check(VK12.vkEndCommandBuffer(commandBuffer), "end shader-test image upload");

            VkSubmitInfo.Buffer submit = VkSubmitInfo.calloc(1, stack);
            submit.get(0)
                    .sType$Default()
                    .pCommandBuffers(stack.pointers(commandBuffer.address()));
            check(
                    VK12.vkQueueSubmit(this.queue, submit, 0L),
                    "submit shader-test image upload");
            check(
                    VK12.vkQueueWaitIdle(this.queue),
                    "wait for shader-test image upload");
        } finally {
            if (commandBuffer != null) {
                VK12.vkFreeCommandBuffers(
                        this.device, this.commandPool, commandBuffer);
            }
        }
    }

    private static void fillImageBarrier(
            VkImageMemoryBarrier barrier,
            long image,
            int sourceAccess,
            int destinationAccess,
            int oldLayout,
            int newLayout) {
        barrier.sType$Default()
                .srcAccessMask(sourceAccess)
                .dstAccessMask(destinationAccess)
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                .image(image);
        barrier.subresourceRange()
                .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1);
    }

    private MappedBuffer createMappedBuffer(int size) {
        return createMappedBuffer(size, VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
    }

    private MappedBuffer createMappedBuffer(int size, int usage) {
        long buffer = 0L;
        long memory = 0L;
        boolean mappedMemory = false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer handle = stack.mallocLong(1);
            check(
                    VK12.vkCreateBuffer(
                            this.device,
                            VkBufferCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .size(size)
                                    .usage(usage)
                                    .sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE),
                            null,
                            handle),
                    "create shader-test buffer");
            buffer = handle.get(0);

            VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
            VK12.vkGetBufferMemoryRequirements(this.device, buffer, requirements);
            int memoryType = findMemoryType(
                    requirements.memoryTypeBits(),
                    VK12.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                            | VK12.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                    stack);
            handle.clear();
            check(
                    VK12.vkAllocateMemory(
                            this.device,
                            VkMemoryAllocateInfo.calloc(stack)
                                    .sType$Default()
                                    .allocationSize(requirements.size())
                                    .memoryTypeIndex(memoryType),
                            null,
                            handle),
                    "allocate shader-test buffer memory");
            memory = handle.get(0);
            check(VK12.vkBindBufferMemory(this.device, buffer, memory, 0L),
                    "bind shader-test buffer memory");

            PointerBuffer mapped = stack.mallocPointer(1);
            check(
                    VK12.vkMapMemory(this.device, memory, 0L, size, 0, mapped),
                    "map shader-test buffer memory");
            mappedMemory = true;
            ByteBuffer bytes = MemoryUtil.memByteBuffer(mapped.get(0), size)
                    .order(ByteOrder.LITTLE_ENDIAN);
            return new MappedBuffer(this.device, buffer, memory, bytes, size);
        } catch (RuntimeException exception) {
            if (mappedMemory) {
                VK12.vkUnmapMemory(this.device, memory);
            }
            if (memory != 0L) {
                VK12.vkFreeMemory(this.device, memory, null);
            }
            if (buffer != 0L) {
                VK12.vkDestroyBuffer(this.device, buffer, null);
            }
            throw exception;
        }
    }

    private int findMemoryType(
            int memoryTypeBits, int required, MemoryStack stack) {
        VkPhysicalDeviceMemoryProperties properties =
                VkPhysicalDeviceMemoryProperties.calloc(stack);
        VK12.vkGetPhysicalDeviceMemoryProperties(this.physicalDevice, properties);
        for (int index = 0; index < properties.memoryTypeCount(); index++) {
            if ((memoryTypeBits & (1 << index)) != 0
                    && (properties.memoryTypes(index).propertyFlags() & required) == required) {
                return index;
            }
        }
        throw new IllegalStateException(
                "Vulkan compute device has no memory type with flags 0x"
                        + Integer.toHexString(required));
    }

    private long createShaderModule(Path shaderPath, MemoryStack stack) throws IOException {
        byte[] bytes = Files.readAllBytes(shaderPath);
        if (bytes.length == 0 || (bytes.length & 3) != 0) {
            throw new IllegalArgumentException("Shader module is empty or not word-aligned: " + shaderPath);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length);
        try {
            code.put(bytes).flip();
            LongBuffer handle = stack.mallocLong(1);
            check(
                    VK12.vkCreateShaderModule(
                            this.device,
                            VkShaderModuleCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .pCode(code),
                            null,
                            handle),
                    "create shader-test module " + shaderPath.getFileName());
            return handle.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }

    private static SelectedDevice selectDevice(VkInstance instance, MemoryStack stack)
            throws UnavailableException {
        IntBuffer count = stack.ints(0);
        int result = VK12.vkEnumeratePhysicalDevices(instance, count, null);
        if (result != VK12.VK_SUCCESS || count.get(0) == 0) {
            throw new UnavailableException("No Vulkan physical device is available");
        }
        PointerBuffer devices = stack.mallocPointer(count.get(0));
        check(VK12.vkEnumeratePhysicalDevices(instance, count, devices),
                "enumerate shader-test devices");
        for (int deviceIndex = 0; deviceIndex < devices.remaining(); deviceIndex++) {
            VkPhysicalDevice physicalDevice =
                    new VkPhysicalDevice(devices.get(deviceIndex), instance);
            VkPhysicalDeviceProperties deviceProperties =
                    VkPhysicalDeviceProperties.calloc(stack);
            VK12.vkGetPhysicalDeviceProperties(physicalDevice, deviceProperties);
            if (Integer.compareUnsigned(
                            deviceProperties.apiVersion(), VK12.VK_API_VERSION_1_2)
                    < 0) {
                continue;
            }
            count.put(0, 0);
            VK12.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, null);
            VkQueueFamilyProperties.Buffer queueProperties =
                    VkQueueFamilyProperties.calloc(count.get(0), stack);
            VK12.vkGetPhysicalDeviceQueueFamilyProperties(
                    physicalDevice, count, queueProperties);
            for (int queueFamily = 0; queueFamily < queueProperties.remaining(); queueFamily++) {
                if (queueProperties.get(queueFamily).queueCount() > 0
                        && (queueProperties.get(queueFamily).queueFlags()
                        & VK12.VK_QUEUE_COMPUTE_BIT) != 0) {
                    return new SelectedDevice(physicalDevice, queueFamily);
                }
            }
        }
        throw new UnavailableException("No Vulkan 1.2 compute queue is available");
    }

    private static void zero(ByteBuffer buffer) {
        ByteBuffer target = buffer.duplicate();
        target.clear();
        while (target.remaining() >= Long.BYTES) {
            target.putLong(0L);
        }
        while (target.hasRemaining()) {
            target.put((byte) 0);
        }
    }

    private static void check(int result, String operation) {
        if (result != VK10.VK_SUCCESS) {
            throw new IllegalStateException(operation + " failed with Vulkan result " + result);
        }
    }

    private void requireOpen() {
        if (this.closed) {
            throw new IllegalStateException("Shader compute runner is closed");
        }
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        VK12.vkDeviceWaitIdle(this.device);
        if (this.transmissionGgxEnergy != null) {
            this.transmissionGgxEnergy.close();
            this.transmissionGgxEnergy = null;
        }
        VK12.vkDestroyCommandPool(this.device, this.commandPool, null);
        VK12.vkDestroyDevice(this.device, null);
        VK12.vkDestroyInstance(this.instance, null);
    }

    static final class UnavailableException extends Exception {
        private static final long serialVersionUID = 1L;

        UnavailableException(String message) {
            super(message);
        }
    }

    private record SelectedDevice(VkPhysicalDevice physicalDevice, int queueFamily) {
    }

    private record MappedBuffer(
            VkDevice device,
            long buffer,
            long memory,
            ByteBuffer bytes,
            int size)
            implements AutoCloseable {
        @Override
        public void close() {
            VK12.vkUnmapMemory(this.device, this.memory);
            VK12.vkDestroyBuffer(this.device, this.buffer, null);
            VK12.vkFreeMemory(this.device, this.memory, null);
        }
    }

    private record SampledImage3D(
            VkDevice device,
            long image,
            long memory,
            long view,
            long sampler)
            implements AutoCloseable {
        @Override
        public void close() {
            VK12.vkDestroySampler(this.device, this.sampler, null);
            VK12.vkDestroyImageView(this.device, this.view, null);
            VK12.vkDestroyImage(this.device, this.image, null);
            VK12.vkFreeMemory(this.device, this.memory, null);
        }
    }
}
