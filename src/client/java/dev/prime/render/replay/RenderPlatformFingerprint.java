package dev.prime.render.replay;

import dev.prime.render.vulkan.VulkanCapabilities;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.BufferUnderflowException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

/**
 * Canonical device and feature identity required for strict replay comparisons.
 *
 * <p>This is captured once at Vulkan-context creation. It contains no handles and is safe to
 * persist with a replay.
 */
public record RenderPlatformFingerprint(
        String deviceName,
        int vendorId,
        int deviceId,
        int deviceType,
        int driverVersion,
        int apiVersion,
        String pipelineCacheUuid,
        int shaderGroupHandleSize,
        int shaderGroupHandleAlignment,
        int shaderGroupBaseAlignment,
        int maxShaderGroupStride,
        int maxRayDispatchInvocationCount,
        int maxRayRecursionDepth,
        long maxAccelerationStructurePrimitiveCount,
        long maxAccelerationStructureInstanceCount,
        int accelerationStructureScratchAlignment,
        boolean wavefrontSubgroupSupported,
        boolean invocationReorderSupported,
        boolean opacityMicromapSupported,
        int maxOpacityMicromapSubdivisionLevel,
        boolean fsrFp16Supported) {
    private static final int FORMAT_VERSION = 1;
    private static final int PIPELINE_CACHE_UUID_BYTES = VK10.VK_UUID_SIZE;

    public RenderPlatformFingerprint {
        Objects.requireNonNull(deviceName, "deviceName");
        Objects.requireNonNull(pipelineCacheUuid, "pipelineCacheUuid");
        if (pipelineCacheUuid.length() != PIPELINE_CACHE_UUID_BYTES * 2
                || !isLowercaseHex(pipelineCacheUuid)) {
            throw new IllegalArgumentException(
                    "Pipeline-cache UUID must be sixteen lowercase hexadecimal bytes");
        }
    }

    public static RenderPlatformFingerprint capture(
            VkPhysicalDeviceProperties properties,
            VulkanCapabilities capabilities) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(capabilities, "capabilities");
        if (!capabilities.available()) {
            throw new IllegalArgumentException(
                    "Cannot fingerprint an unavailable Vulkan device");
        }
        byte[] uuid = new byte[PIPELINE_CACHE_UUID_BYTES];
        ByteBuffer source = properties.pipelineCacheUUID();
        for (int index = 0; index < uuid.length; index++) {
            uuid[index] = source.get(index);
        }
        return new RenderPlatformFingerprint(
                properties.deviceNameString(),
                properties.vendorID(),
                properties.deviceID(),
                properties.deviceType(),
                properties.driverVersion(),
                properties.apiVersion(),
                HexFormat.of().formatHex(uuid),
                capabilities.shaderGroupHandleSize(),
                capabilities.shaderGroupHandleAlignment(),
                capabilities.shaderGroupBaseAlignment(),
                capabilities.maxShaderGroupStride(),
                capabilities.maxRayDispatchInvocationCount(),
                capabilities.maxRayRecursionDepth(),
                capabilities.maxAccelerationStructurePrimitiveCount(),
                capabilities.maxAccelerationStructureInstanceCount(),
                capabilities.accelerationStructureScratchAlignment(),
                capabilities.wavefrontSubgroupSupported(),
                capabilities.invocationReorderSupported(),
                capabilities.opacityMicromapSupported(),
                capabilities.maxOpacityMicromapSubdivisionLevel(),
                capabilities.fsrFp16Supported());
    }

    public byte[] canonicalBytes() {
        byte[] name = this.deviceName.getBytes(StandardCharsets.UTF_8);
        byte[] uuid = HexFormat.of().parseHex(this.pipelineCacheUuid);
        ByteBuffer output = ByteBuffer.allocate(
                        8 + name.length
                                + 6 * Integer.BYTES
                                + uuid.length
                                + 8 * Integer.BYTES
                                + 2 * Long.BYTES
                                + 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(FORMAT_VERSION);
        output.putInt(name.length);
        output.put(name);
        output.putInt(this.vendorId);
        output.putInt(this.deviceId);
        output.putInt(this.deviceType);
        output.putInt(this.driverVersion);
        output.putInt(this.apiVersion);
        output.putInt(uuid.length);
        output.put(uuid);
        output.putInt(this.shaderGroupHandleSize);
        output.putInt(this.shaderGroupHandleAlignment);
        output.putInt(this.shaderGroupBaseAlignment);
        output.putInt(this.maxShaderGroupStride);
        output.putInt(this.maxRayDispatchInvocationCount);
        output.putInt(this.maxRayRecursionDepth);
        output.putLong(this.maxAccelerationStructurePrimitiveCount);
        output.putLong(this.maxAccelerationStructureInstanceCount);
        output.putInt(this.accelerationStructureScratchAlignment);
        output.put((byte) (this.wavefrontSubgroupSupported ? 1 : 0));
        output.put((byte) (this.invocationReorderSupported ? 1 : 0));
        output.put((byte) (this.opacityMicromapSupported ? 1 : 0));
        output.putInt(this.maxOpacityMicromapSubdivisionLevel);
        output.put((byte) (this.fsrFp16Supported ? 1 : 0));
        if (output.hasRemaining()) {
            throw new AssertionError("Platform fingerprint size calculation is incomplete");
        }
        return output.array();
    }

    public static RenderPlatformFingerprint decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        try {
            ByteBuffer input =
                    ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
            if (input.remaining() < 2 * Integer.BYTES
                    || input.getInt() != FORMAT_VERSION) {
                throw new IllegalArgumentException(
                        "Unsupported platform-fingerprint header");
            }
            int nameLength = readableLength(input, "device name");
            byte[] name = new byte[nameLength];
            input.get(name);
            int vendorId = input.getInt();
            int deviceId = input.getInt();
            int deviceType = input.getInt();
            int driverVersion = input.getInt();
            int apiVersion = input.getInt();
            int uuidLength = readableLength(input, "pipeline-cache UUID");
            if (uuidLength != PIPELINE_CACHE_UUID_BYTES) {
                throw new IllegalArgumentException(
                        "Platform fingerprint has an invalid pipeline-cache UUID");
            }
            byte[] uuid = new byte[uuidLength];
            input.get(uuid);
            RenderPlatformFingerprint result =
                    new RenderPlatformFingerprint(
                            new String(name, StandardCharsets.UTF_8),
                            vendorId,
                            deviceId,
                            deviceType,
                            driverVersion,
                            apiVersion,
                            HexFormat.of().formatHex(uuid),
                            input.getInt(),
                            input.getInt(),
                            input.getInt(),
                            input.getInt(),
                            input.getInt(),
                            input.getInt(),
                            input.getLong(),
                            input.getLong(),
                            input.getInt(),
                            readBoolean(input, "wavefront subgroup"),
                            readBoolean(input, "invocation reorder"),
                            readBoolean(input, "opacity micromap"),
                            input.getInt(),
                            readBoolean(input, "FSR fp16"));
            if (input.hasRemaining()) {
                throw new IllegalArgumentException(
                        "Platform fingerprint contains trailing data");
            }
            return result;
        } catch (BufferUnderflowException exception) {
            throw new IllegalArgumentException(
                    "Platform fingerprint is truncated", exception);
        }
    }

    public String sha256() {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonicalBytes()));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("Required SHA-256 algorithm is unavailable", exception);
        }
    }

    public boolean isStrictlyCompatibleWith(RenderPlatformFingerprint other) {
        return other != null
                && MessageDigest.isEqual(canonicalBytes(), other.canonicalBytes());
    }

    private static boolean isLowercaseHex(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= '0' && character <= '9')
                    && !(character >= 'a' && character <= 'f')) {
                return false;
            }
        }
        return true;
    }

    private static int readableLength(ByteBuffer input, String label) {
        if (input.remaining() < Integer.BYTES) {
            throw new IllegalArgumentException(
                    "Platform fingerprint is truncated before " + label);
        }
        int length = input.getInt();
        if (length < 0 || length > input.remaining()) {
            throw new IllegalArgumentException(
                    "Platform fingerprint " + label + " length is invalid");
        }
        return length;
    }

    private static boolean readBoolean(ByteBuffer input, String label) {
        if (!input.hasRemaining()) {
            throw new IllegalArgumentException(
                    "Platform fingerprint is truncated before " + label);
        }
        byte value = input.get();
        if (value != 0 && value != 1) {
            throw new IllegalArgumentException(
                    "Platform fingerprint " + label + " flag is invalid");
        }
        return value != 0;
    }
}
