package dev.prime.render.vulkan.fsr;

import dev.prime.render.FrameCamera;
import dev.prime.render.fsr.FsrDebugView;
import dev.prime.render.fsr.FsrSettings;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Narrow Java binding for AMD's signed FidelityFX Vulkan DLL.
 *
 * <p>The DLL owns the FSR context, private resources, pipelines and internal synchronization. Prime
 * supplies the existing Vulkan device, command buffer and external images. Every external image is
 * declared as {@code UNORDERED_ACCESS}: Prime keeps these compute images in
 * {@code VK_IMAGE_LAYOUT_GENERAL}, and FidelityFX restores imported resources to the declared
 * initial state before returning from {@code ffxDispatch}. This state/layout agreement is part of
 * the native boundary and must not be changed independently on either side.
 */
final class FsrNative {
    static final String EXPECTED_UPSCALER_VERSION = FsrSettings.UPSCALER_VERSION;

    private static final String WINDOWS_RESOURCE =
            "/prime/natives/windows-x86_64/amd_fidelityfx_vk.dll";

    private static final long CREATE_CONTEXT_UPSCALE = 0x0001_0000L;
    private static final long CREATE_BACKEND_VK = 0x0000_0003L;
    private static final long DISPATCH_UPSCALE = 0x0001_0001L;
    private static final long QUERY_GET_VERSIONS = 0x0000_0004L;
    private static final long QUERY_PROVIDER_VERSION = 0x0000_0006L;

    private static final int CREATE_UPSCALE_SIZE = 48;
    private static final int CREATE_BACKEND_SIZE = 40;
    private static final int GET_VERSIONS_SIZE = 56;
    private static final int PROVIDER_VERSION_SIZE = 32;
    private static final int DISPATCH_SIZE = 432;
    private static final int RESOURCE_SIZE = 48;

    private static final int CREATE_FLAG_HDR = 1 << 0;
    private static final int CREATE_FLAG_DEPTH_INVERTED = 1 << 3;
    private static final int CREATE_FLAG_DEPTH_INFINITE = 1 << 4;
    private static final int CREATE_FLAGS = CREATE_FLAG_HDR
            | CREATE_FLAG_DEPTH_INVERTED
            | CREATE_FLAG_DEPTH_INFINITE;

    private static final int DISPATCH_FLAG_DEBUG_VIEW = 1 << 0;
    private static final int RESOURCE_TYPE_TEXTURE_2D = 2;
    private static final int RESOURCE_USAGE_READ_ONLY = 0;
    private static final int RESOURCE_USAGE_UAV = 1 << 1;
    private static final int RESOURCE_STATE_UNORDERED_ACCESS = 1 << 1;

    private final SharedLibrary library;
    private final long createFunction;
    private final long destroyFunction;
    private final long dispatchFunction;
    private final long queryFunction;

    private FsrNative() {
        this.library = loadLibrary();
        this.createFunction = requireFunction(this.library, "ffxCreateContext");
        this.destroyFunction = requireFunction(this.library, "ffxDestroyContext");
        this.dispatchFunction = requireFunction(this.library, "ffxDispatch");
        this.queryFunction = requireFunction(this.library, "ffxQuery");
        String availableVersion = this.queryAvailableVersion();
        if (!EXPECTED_UPSCALER_VERSION.equals(availableVersion)) {
            throw new IllegalStateException(
                    "Bundled FidelityFX upscaler version is "
                            + availableVersion
                            + "; expected "
                            + EXPECTED_UPSCALER_VERSION);
        }
    }

    static Instance create(
            VulkanContext context,
            int renderWidth,
            int renderHeight,
            int displayWidth,
            int displayHeight) {
        try {
            return Holder.INSTANCE.createInstance(
                    context, renderWidth, renderHeight, displayWidth, displayHeight);
        } catch (LinkageError error) {
            throw new IllegalStateException(
                    "Unable to load the bundled FidelityFX Vulkan library", error);
        }
    }

    static void verifyLibrary() {
        Holder.INSTANCE.getClass();
    }

    private Instance createInstance(
            VulkanContext context,
            int renderWidth,
            int renderHeight,
            int displayWidth,
            int displayHeight) {
        if (renderWidth <= 0 || renderHeight <= 0 || displayWidth <= 0 || displayHeight <= 0) {
            throw new IllegalArgumentException("FSR native extents must be positive");
        }
        ByteBuffer creationStorage = MemoryUtil.memCalloc(
                        CREATE_UPSCALE_SIZE + CREATE_BACKEND_SIZE)
                .order(ByteOrder.nativeOrder());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer create = creationStorage
                    .slice(0, CREATE_UPSCALE_SIZE)
                    .order(ByteOrder.nativeOrder());
            ByteBuffer backend = creationStorage
                    .slice(CREATE_UPSCALE_SIZE, CREATE_BACKEND_SIZE)
                    .order(ByteOrder.nativeOrder());
            putHeader(backend, CREATE_BACKEND_VK, MemoryUtil.NULL);
            backend.putLong(16, context.vkDevice().address());
            backend.putLong(24, context.vkDevice().getPhysicalDevice().address());
            long getDeviceProcAddress = VK.getFunctionProvider()
                    .getFunctionAddress("vkGetDeviceProcAddr");
            if (getDeviceProcAddress == MemoryUtil.NULL) {
                throw new IllegalStateException("Vulkan loader does not expose vkGetDeviceProcAddr");
            }
            backend.putLong(32, getDeviceProcAddress);

            putHeader(create, CREATE_CONTEXT_UPSCALE, MemoryUtil.memAddress(backend));
            create.putInt(16, CREATE_FLAGS);
            putExtent(create, 20, renderWidth, renderHeight);
            putExtent(create, 28, displayWidth, displayHeight);
            create.putLong(40, MemoryUtil.NULL);

            ByteBuffer contextPointer = stack.calloc(Long.BYTES).order(ByteOrder.nativeOrder());
            checkResult(
                    JNI.invokePPPI(
                            MemoryUtil.memAddress(contextPointer),
                            MemoryUtil.memAddress(create),
                            MemoryUtil.NULL,
                            this.createFunction),
                    "create FidelityFX upscaler context");
            long handle = contextPointer.getLong(0);
            if (handle == MemoryUtil.NULL) {
                throw new IllegalStateException("FidelityFX returned a null upscaler context");
            }
            try {
                String version = this.queryVersion(stack, handle);
                if (!EXPECTED_UPSCALER_VERSION.equals(version)) {
                    throw new IllegalStateException(
                            "Unsupported FidelityFX upscaler version "
                                    + version
                                    + "; expected "
                                    + EXPECTED_UPSCALER_VERSION);
                }
                return new Instance(this, handle, version, creationStorage);
            } catch (RuntimeException exception) {
                this.destroy(stack, handle);
                throw exception;
            }
        } catch (RuntimeException exception) {
            MemoryUtil.memFree(creationStorage);
            throw exception;
        }
    }

    private String queryVersion(MemoryStack stack, long handle) {
        ByteBuffer contextPointer = stack.calloc(Long.BYTES).order(ByteOrder.nativeOrder());
        contextPointer.putLong(0, handle);
        ByteBuffer query = stack.calloc(PROVIDER_VERSION_SIZE).order(ByteOrder.nativeOrder());
        putHeader(query, QUERY_PROVIDER_VERSION, MemoryUtil.NULL);
        checkResult(
                JNI.invokePPI(
                        MemoryUtil.memAddress(contextPointer),
                        MemoryUtil.memAddress(query),
                        this.queryFunction),
                "query FidelityFX upscaler version");
        long name = query.getLong(24);
        if (name == MemoryUtil.NULL) {
            throw new IllegalStateException("FidelityFX returned a null provider version name");
        }
        return MemoryUtil.memUTF8(name);
    }

    private String queryAvailableVersion() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer count = stack.calloc(Long.BYTES).order(ByteOrder.nativeOrder());
            ByteBuffer versionId = stack.calloc(Long.BYTES).order(ByteOrder.nativeOrder());
            ByteBuffer versionName = stack.calloc(Long.BYTES).order(ByteOrder.nativeOrder());
            count.putLong(0, 1L);
            ByteBuffer query = stack.calloc(GET_VERSIONS_SIZE).order(ByteOrder.nativeOrder());
            putHeader(query, QUERY_GET_VERSIONS, MemoryUtil.NULL);
            query.putLong(16, CREATE_CONTEXT_UPSCALE);
            query.putLong(24, MemoryUtil.NULL);
            query.putLong(32, MemoryUtil.memAddress(count));
            query.putLong(40, MemoryUtil.memAddress(versionId));
            query.putLong(48, MemoryUtil.memAddress(versionName));
            checkResult(
                    JNI.invokePPI(
                            MemoryUtil.NULL,
                            MemoryUtil.memAddress(query),
                            this.queryFunction),
                    "query available FidelityFX upscaler version");
            if (count.getLong(0) != 1L || versionId.getLong(0) == 0L) {
                throw new IllegalStateException("FidelityFX DLL does not expose exactly one upscaler provider");
            }
            long name = versionName.getLong(0);
            if (name == MemoryUtil.NULL) {
                throw new IllegalStateException("FidelityFX returned a null available-version name");
            }
            return MemoryUtil.memUTF8(name);
        }
    }

    private void dispatch(
            long handle,
            VkCommandBuffer commandBuffer,
            Dispatch dispatch) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer description = stack.calloc(DISPATCH_SIZE).order(ByteOrder.nativeOrder());
            putHeader(description, DISPATCH_UPSCALE, MemoryUtil.NULL);
            description.putLong(16, commandBuffer.address());
            putResource(description, 24, dispatch.color(), false);
            putResource(description, 72, dispatch.depth(), false);
            putResource(description, 120, dispatch.motion(), false);
            // A null exposure selects FidelityFX's internal 1.0 texture. Prime's working-space and
            // display contracts both use exposure 1.0, so no extra external image is required.
            putNullResource(description, 168);
            putResource(description, 216, dispatch.reactive(), false);
            putResource(description, 264, dispatch.transparencyComposition(), false);
            putResource(description, 312, dispatch.output(), true);

            FsrSettings.Jitter nativeJitter = dispatch.jitter().forFsrDispatch();
            putVector2(description, 360, nativeJitter.x(), nativeJitter.y());
            // Prime stores current-to-previous motion as normalized UV displacement. The public
            // FidelityFX host API divides this scale by the motion-vector target extent before
            // shaders consume it, so passing the render extent produces the required internal
            // scale of (1, 1). These are deliberately not the old direct-shader constants.
            putVector2(
                    description, 368, (float) dispatch.renderWidth(), (float) dispatch.renderHeight());
            putExtent(description, 376, dispatch.renderWidth(), dispatch.renderHeight());
            putExtent(description, 384, dispatch.displayWidth(), dispatch.displayHeight());
            description.put(392, (byte) 1);
            description.putFloat(396, FsrSettings.RCAS_SHARPNESS);
            description.putFloat(400, dispatch.frameTimeMilliseconds());
            description.putFloat(404, FsrSettings.EXPOSURE);
            description.put(408, dispatch.reset() ? (byte) 1 : (byte) 0);
            // FidelityFX expresses a reversed infinite projection by swapping the public near/far
            // values: cameraNear is FLT_MAX and cameraFar is the physical near distance.
            description.putFloat(412, Float.MAX_VALUE);
            description.putFloat(416, Fsr3Upscaler.NEAR_PLANE);
            description.putFloat(420, verticalFieldOfView(dispatch.camera()));
            description.putFloat(424, 1.0F);
            description.putInt(
                    428,
                    dispatch.debugView() == FsrDebugView.OVERVIEW
                            ? DISPATCH_FLAG_DEBUG_VIEW
                            : 0);

            ByteBuffer contextPointer = stack.calloc(Long.BYTES).order(ByteOrder.nativeOrder());
            contextPointer.putLong(0, handle);
            checkResult(
                    JNI.invokePPI(
                            MemoryUtil.memAddress(contextPointer),
                            MemoryUtil.memAddress(description),
                            this.dispatchFunction),
                    "dispatch FidelityFX upscaler");
        }
    }

    private void destroy(MemoryStack stack, long handle) {
        ByteBuffer contextPointer = stack.calloc(Long.BYTES).order(ByteOrder.nativeOrder());
        contextPointer.putLong(0, handle);
        checkResult(
                JNI.invokePPI(
                        MemoryUtil.memAddress(contextPointer),
                        MemoryUtil.NULL,
                        this.destroyFunction),
                "destroy FidelityFX upscaler context");
    }

    private static void putHeader(ByteBuffer buffer, long type, long next) {
        buffer.putLong(0, type);
        buffer.putLong(8, next);
    }

    private static void putExtent(ByteBuffer buffer, int offset, int width, int height) {
        buffer.putInt(offset, width);
        buffer.putInt(offset + Integer.BYTES, height);
    }

    private static void putVector2(ByteBuffer buffer, int offset, float x, float y) {
        buffer.putFloat(offset, x);
        buffer.putFloat(offset + Float.BYTES, y);
    }

    private static void putNullResource(ByteBuffer buffer, int offset) {
        for (int byteOffset = 0; byteOffset < RESOURCE_SIZE; byteOffset += Long.BYTES) {
            buffer.putLong(offset + byteOffset, 0L);
        }
    }

    private static void putResource(
            ByteBuffer buffer, int offset, VulkanImage image, boolean writable) {
        buffer.putLong(offset, image.image());
        buffer.putInt(offset + 8, RESOURCE_TYPE_TEXTURE_2D);
        buffer.putInt(offset + 12, surfaceFormat(image.format()));
        buffer.putInt(offset + 16, image.width());
        buffer.putInt(offset + 20, image.height());
        buffer.putInt(offset + 24, 1);
        buffer.putInt(offset + 28, image.mipLevels());
        buffer.putInt(offset + 32, 0);
        buffer.putInt(offset + 36, writable ? RESOURCE_USAGE_UAV : RESOURCE_USAGE_READ_ONLY);
        buffer.putInt(offset + 40, RESOURCE_STATE_UNORDERED_ACCESS);
    }

    private static int surfaceFormat(int vkFormat) {
        return switch (vkFormat) {
            case VK12.VK_FORMAT_R32G32B32A32_SFLOAT -> 3;
            case VK12.VK_FORMAT_R16G16B16A16_SFLOAT -> 4;
            case VK12.VK_FORMAT_R32G32_SFLOAT -> 6;
            case VK12.VK_FORMAT_R8G8B8A8_UNORM -> 10;
            case VK12.VK_FORMAT_R16G16_SFLOAT -> 18;
            case VK12.VK_FORMAT_R8_UNORM -> 25;
            case VK12.VK_FORMAT_R32_SFLOAT -> 28;
            default -> throw new IllegalArgumentException(
                    "Unsupported FidelityFX Vulkan image format " + vkFormat);
        };
    }

    private static float verticalFieldOfView(FrameCamera camera) {
        float inverseCotangent = Math.abs(1.0F / camera.projection().m11());
        float fieldOfView = 2.0F * (float) Math.atan(inverseCotangent);
        if (!Float.isFinite(fieldOfView) || fieldOfView <= 0.0F || fieldOfView > Math.PI) {
            throw new IllegalArgumentException("FSR camera projection has an invalid vertical FOV");
        }
        return fieldOfView;
    }

    static boolean isSupportedPlatform() {
        return isSupportedPlatform(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    static boolean isSupportedPlatform(String osName, String architecture) {
        String normalizedOs = osName.toLowerCase(Locale.ROOT);
        String normalizedArchitecture = architecture.toLowerCase(Locale.ROOT);
        return normalizedOs.startsWith("windows")
                && (normalizedArchitecture.equals("amd64")
                        || normalizedArchitecture.equals("x86_64"));
    }

    private static SharedLibrary loadLibrary() {
        if (!isSupportedPlatform()) {
            throw new IllegalStateException(
                    "The bundled FidelityFX Vulkan library supports Windows x86-64 only");
        }
        byte[] bytes;
        try (InputStream input = FsrNative.class.getResourceAsStream(WINDOWS_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing bundled FidelityFX library " + WINDOWS_RESOURCE);
            }
            bytes = input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read the bundled FidelityFX library", exception);
        }
        String digest = sha256(bytes);
        Path directory = Path.of(System.getProperty("java.io.tmpdir"), "prime-fsr", digest);
        Path libraryPath = directory.resolve("amd_fidelityfx_vk.dll");
        try {
            Files.createDirectories(directory);
            if (!Files.exists(libraryPath)) {
                Path temporary = directory.resolve(
                        "amd_fidelityfx_vk-" + ProcessHandle.current().pid() + ".tmp");
                try {
                    Files.write(temporary, bytes);
                    try {
                        Files.move(temporary, libraryPath, StandardCopyOption.ATOMIC_MOVE);
                    } catch (FileAlreadyExistsException ignored) {
                        // Another process published the same content-addressed DLL.
                    } finally {
                        Files.deleteIfExists(temporary);
                    }
                } catch (FileAlreadyExistsException ignored) {
                    // Another thread in this process won the extraction race.
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to extract the bundled FidelityFX library", exception);
        }
        return APIUtil.apiCreateLibrary(libraryPath.toAbsolutePath().toString());
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("The Java runtime does not provide SHA-256", exception);
        }
    }

    private static long requireFunction(SharedLibrary library, String name) {
        long address = library.getFunctionAddress(name);
        if (address == MemoryUtil.NULL) {
            throw new IllegalStateException("The FidelityFX native library is missing " + name);
        }
        return address;
    }

    private static void checkResult(int result, String operation) {
        if (result != 0) {
            throw new IllegalStateException(operation + " failed with native result " + result);
        }
    }

    record Dispatch(
            FrameCamera camera,
            VulkanImage color,
            VulkanImage depth,
            VulkanImage motion,
            VulkanImage reactive,
            VulkanImage transparencyComposition,
            VulkanImage output,
            int renderWidth,
            int renderHeight,
            int displayWidth,
            int displayHeight,
            FsrSettings.Jitter jitter,
            float frameTimeMilliseconds,
            boolean reset,
            FsrDebugView debugView) {
    }

    static final class Instance implements AutoCloseable {
        private final FsrNative api;
        private final String version;
        private final ByteBuffer creationStorage;
        private long handle;

        private Instance(
                FsrNative api, long handle, String version, ByteBuffer creationStorage) {
            this.api = api;
            this.handle = handle;
            this.version = version;
            this.creationStorage = creationStorage;
        }

        String version() {
            return this.version;
        }

        void dispatch(VkCommandBuffer commandBuffer, Dispatch dispatch) {
            this.api.dispatch(this.requireOpen(), commandBuffer, dispatch);
        }

        private long requireOpen() {
            if (this.handle == MemoryUtil.NULL) {
                throw new IllegalStateException("FidelityFX upscaler context has been destroyed");
            }
            return this.handle;
        }

        @Override
        public void close() {
            long instance = this.handle;
            if (instance == MemoryUtil.NULL) {
                return;
            }
            this.handle = MemoryUtil.NULL;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                try {
                    this.api.destroy(stack, instance);
                } finally {
                    MemoryUtil.memFree(this.creationStorage);
                }
            }
        }
    }

    private static final class Holder {
        private static final FsrNative INSTANCE = new FsrNative();
    }
}
