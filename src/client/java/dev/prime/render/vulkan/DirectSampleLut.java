package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.infrastructure.ResourceCleanup;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.zip.GZIPInputStream;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Owns the immutable temporal Sobol and spatial blue-noise data for realtime direct lighting. */
final class DirectSampleLut implements Destroyable {
    static final int SAMPLE_COUNT = 65_536;
    static final int STREAM_COUNT = 3;
    static final int BLUE_NOISE_RESOLUTION = 128;
    static final int BLUE_NOISE_TEXEL_COUNT =
            BLUE_NOISE_RESOLUTION * BLUE_NOISE_RESOLUTION;
    static final int ENTRY_COUNT = STREAM_COUNT * (SAMPLE_COUNT + BLUE_NOISE_TEXEL_COUNT);
    static final int BYTE_SIZE = ENTRY_COUNT * 2 * Integer.BYTES;
    static final int BLUE_NOISE_BYTE_SIZE =
            STREAM_COUNT * 2 * BLUE_NOISE_TEXEL_COUNT * Short.BYTES;
    static final String BLUE_NOISE_SHA256 =
            "c0cefa926dc25913e8c982a3faaa8634e289806f79656a68e8cebda7571df7b2";

    private static final String RESOURCE =
            "/prime/sampling/pbrt_blue_noise_6x128x128.u16.gz.b64";
    private static final int[] DIMENSION_ONE_DIRECTIONS = {
        0x00000001, 0x00000003, 0x00000005, 0x0000000f,
        0x00000011, 0x00000033, 0x00000055, 0x000000ff,
        0x00000101, 0x00000303, 0x00000505, 0x00000f0f,
        0x00001111, 0x00003333, 0x00005555, 0x0000ffff,
        0x00010001, 0x00030003, 0x00050005, 0x000f000f,
        0x00110011, 0x00330033, 0x00550055, 0x00ff00ff,
        0x01010101, 0x03030303, 0x05050505, 0x0f0f0f0f,
        0x11111111, 0x33333333, 0x55555555, 0xffffffff
    };

    private final VulkanContext context;
    private final VulkanBuffer buffer;
    private VulkanBuffer upload;
    private boolean prepared;
    private boolean pending;
    private boolean destroyed;

    DirectSampleLut(VulkanContext context) {
        this.context = context;
        VulkanBuffer newBuffer = null;
        VulkanBuffer newUpload = null;
        try {
            newBuffer = context.createBuffer(
                    BYTE_SIZE,
                    VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                            | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    false,
                    "Prime direct sample LUT");
            newUpload = context.createBuffer(
                    BYTE_SIZE,
                    VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    true,
                    "Prime direct sample LUT upload");
            writeUpload(newUpload, readBlueNoise());
            this.buffer = newBuffer;
            this.upload = newUpload;
        } catch (RuntimeException exception) {
            ResourceCleanup.destroy(newUpload, exception);
            ResourceCleanup.destroy(newBuffer, exception);
            throw exception;
        }
    }

    VulkanBuffer buffer() {
        return this.buffer;
    }

    boolean prepare(VkCommandBuffer commandBuffer) {
        if (this.prepared) {
            return false;
        }
        if (this.pending) {
            throw new IllegalStateException(
                    "Direct sample LUT upload is already pending submission");
        }
        VulkanBuffer source = this.upload;
        if (source == null) {
            throw new IllegalStateException("Direct sample LUT upload storage is missing");
        }
        this.pending = true;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack)
                    .srcOffset(0L)
                    .dstOffset(0L)
                    .size(BYTE_SIZE);
            VK12.vkCmdCopyBuffer(
                    commandBuffer, source.handle(), this.buffer.handle(), copy);
            VulkanSync.bufferBarrier(
                    commandBuffer,
                    stack,
                    this.buffer,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    VK12.VK_ACCESS_SHADER_READ_BIT);
            return true;
        } catch (RuntimeException exception) {
            this.pending = false;
            throw exception;
        }
    }

    void submitted() {
        if (!this.pending) {
            throw new IllegalStateException(
                    "Direct sample LUT upload is not pending submission");
        }
        VulkanBuffer source = this.upload;
        if (source == null) {
            throw new IllegalStateException("Direct sample LUT upload storage is missing");
        }
        this.context.defer(source);
        this.upload = null;
        this.prepared = true;
        this.pending = false;
    }

    void abandon() {
        if (!this.pending) {
            throw new IllegalStateException(
                    "Direct sample LUT upload is not pending submission");
        }
        this.pending = false;
    }

    static int temporalValue(int stream, int sampleIndex, int component) {
        if (stream < 0 || stream >= STREAM_COUNT
                || sampleIndex < 0 || sampleIndex >= SAMPLE_COUNT
                || component < 0 || component >= 2) {
            throw new IllegalArgumentException("Direct sample LUT coordinate is outside its domain");
        }
        int effect = stream == 2 ? 2 : 5;
        int dimensionSet = stream == 1 ? 1 : 0;
        int mixedSeed = hashCombine(0, effect) ^ highQualityHash(dimensionSet);
        int shuffledIndex = reversedBitOwen(
                        Integer.reverse(sampleIndex), mixedSeed ^ 0xf8ade99a)
                & 0xffff0000;
        int outputSeed = component == 0
                ? mixedSeed ^ 0xe0aaaf76
                : mixedSeed ^ 0x94964d4e;
        return sobolBurley(shuffledIndex, component, outputSeed);
    }

    private static int sobolBurley(int reversedBitIndex, int dimension, int seed) {
        int result = 0;
        if (dimension == 0) {
            result = Integer.reverse(reversedBitIndex);
        } else {
            int index = reversedBitIndex;
            int tableIndex = 0;
            while (index != 0) {
                int leadingZeroes = Integer.numberOfLeadingZeros(index);
                result ^= DIMENSION_ONE_DIRECTIONS[tableIndex + leadingZeroes];
                tableIndex += leadingZeroes + 1;
                index <<= leadingZeroes;
                index <<= 1;
            }
        }
        return Integer.reverse(reversedBitOwen(result, seed));
    }

    private static int reversedBitOwen(int value, int seed) {
        value ^= value * 0x3d20adea;
        value += seed;
        value *= (seed >>> 16) | 1;
        value ^= value * 0x05526c56;
        value ^= value * 0x53a22864;
        return value;
    }

    private static int hash32(int value) {
        value ^= value >>> 16;
        value *= 0x21f0aaad;
        value ^= value >>> 15;
        value *= 0xf35a2d97;
        value ^= value >>> 15;
        return value;
    }

    private static int highQualityHash(int value) {
        value ^= value >>> 16;
        value *= 0x21f0aaad;
        value ^= value >>> 15;
        value *= 0xd35a2d97;
        value ^= value >>> 15;
        return value ^ 0xe6fe3beb;
    }

    private static int hashCombine(int seed, int value) {
        return seed ^ (hash32(value) + 0x9e3779b9 + (seed << 6) + (seed >>> 2));
    }

    private static void writeUpload(VulkanBuffer destination, byte[] blueNoise) {
        ByteBuffer data = MemoryUtil.memAlloc(BYTE_SIZE).order(ByteOrder.nativeOrder());
        try {
            for (int stream = 0; stream < STREAM_COUNT; stream++) {
                for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
                    data.putInt(temporalValue(stream, sample, 0));
                    data.putInt(temporalValue(stream, sample, 1));
                }
            }
            ByteBuffer rotations = ByteBuffer.wrap(blueNoise).order(ByteOrder.LITTLE_ENDIAN);
            for (int stream = 0; stream < STREAM_COUNT; stream++) {
                int firstLayer = stream * 2;
                int secondLayer = firstLayer + 1;
                for (int texel = 0; texel < BLUE_NOISE_TEXEL_COUNT; texel++) {
                    data.putInt(Short.toUnsignedInt(rotations.getShort(
                            (firstLayer * BLUE_NOISE_TEXEL_COUNT + texel) * Short.BYTES)));
                    data.putInt(Short.toUnsignedInt(rotations.getShort(
                            (secondLayer * BLUE_NOISE_TEXEL_COUNT + texel) * Short.BYTES)));
                }
            }
            data.flip();
            destination.put(0L, data);
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    static byte[] readBlueNoise() {
        byte[] bytes;
        try (InputStream encoded = DirectSampleLut.class.getResourceAsStream(RESOURCE)) {
            if (encoded == null) {
                throw new IllegalStateException("Missing direct-sampling blue-noise table");
            }
            try (InputStream decoded = Base64.getMimeDecoder().wrap(encoded);
                    GZIPInputStream decompressed = new GZIPInputStream(decoded)) {
                bytes = decompressed.readAllBytes();
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to read direct-sampling blue-noise table", exception);
        }
        if (bytes.length != BLUE_NOISE_BYTE_SIZE) {
            throw new IllegalStateException(
                    "Unexpected direct-sampling blue-noise byte size " + bytes.length);
        }
        String digest;
        try {
            digest = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is unavailable", exception);
        }
        if (!digest.equals(BLUE_NOISE_SHA256)) {
            throw new IllegalStateException(
                    "Direct-sampling blue-noise table failed its integrity check");
        }
        return bytes;
    }

    @Override
    public void destroy() {
        if (!this.destroyed) {
            this.destroyed = true;
            if (this.upload != null) {
                this.upload.destroy();
                this.upload = null;
            }
            this.buffer.destroy();
        }
    }
}
