package dev.prime.render.vulkan.dlss;

import dev.prime.PrimeClient;
import dev.prime.render.post.ReconstructionQualityMode;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import org.joml.Matrix4fc;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Stable, fixed-width Java binding for Prime's private DLSS Ray Reconstruction bridge. */
public final class DlssRrNative {
    public static final int ABI_VERSION = 1;
    static final int EXTENSION_QUERY_SIZE = 56;
    static final int INIT_DESCRIPTION_SIZE = 56;
    static final int OPTIMAL_SETTINGS_SIZE = 32;
    static final int FEATURE_DESCRIPTION_SIZE = 48;
    static final int IMAGE_SIZE = 32;
    static final int IMAGE_COUNT = 9;
    static final int EVALUATE_DESCRIPTION_SIZE = 176 + IMAGE_COUNT * IMAGE_SIZE;
    private static final int EXTENSION_CAPACITY = 64;
    private static final int EXTENSION_NAME_STRIDE = 256;
    private static final String BRIDGE_RESOURCE =
            "/prime/natives/windows-x86_64/prime_dlss_rr.dll";
    private static final String FEATURE_RESOURCE =
            "/prime/natives/windows-x86_64/nvngx_dlssd.dll";

    private final SharedLibrary library;
    private final Path featureDirectory;
    private final Path applicationDataDirectory;
    private final String engineVersion;
    private final long instanceExtensionsFunction;
    private final long deviceExtensionsFunction;
    private final long initializeFunction;
    private final long optimalSettingsFunction;
    private final long createFeatureFunction;
    private final long evaluateFunction;
    private final long releaseFeatureFunction;
    private final long shutdownFunction;

    private DlssRrNative() {
        ExtractedRuntime runtime = extractRuntime();
        this.featureDirectory = runtime.directory();
        this.applicationDataDirectory = createApplicationDataDirectory();
        this.engineVersion = FabricLoader.getInstance()
                .getModContainer(PrimeClient.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        this.library = APIUtil.apiCreateLibrary(runtime.bridge().toString());
        long getAbiVersionFunction = requireFunction("primeDlssRrGetAbiVersion");
        this.instanceExtensionsFunction = requireFunction("primeDlssRrGetInstanceExtensions");
        this.deviceExtensionsFunction = requireFunction("primeDlssRrGetDeviceExtensions");
        this.initializeFunction = requireFunction("primeDlssRrInitialize");
        this.optimalSettingsFunction = requireFunction("primeDlssRrGetOptimalSettings");
        this.createFeatureFunction = requireFunction("primeDlssRrCreateFeature");
        this.evaluateFunction = requireFunction("primeDlssRrEvaluate");
        this.releaseFeatureFunction = requireFunction("primeDlssRrReleaseFeature");
        this.shutdownFunction = requireFunction("primeDlssRrShutdown");
        int abiVersion = JNI.invokeI(getAbiVersionFunction);
        if (abiVersion != ABI_VERSION) {
            throw new IllegalStateException(
                    "Prime DLSS RR bridge ABI mismatch: expected "
                            + ABI_VERSION
                            + ", found "
                            + abiVersion);
        }
    }

    public static boolean isSupportedPlatform() {
        return isSupportedPlatform(
                System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    static boolean isSupportedPlatform(String osName, String architecture) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = architecture.toLowerCase(Locale.ROOT);
        return os.startsWith("windows") && (arch.equals("amd64") || arch.equals("x86_64"));
    }

    public static List<String> instanceExtensions() {
        return Holder.INSTANCE.queryExtensions(0L, 0L, Holder.INSTANCE.instanceExtensionsFunction);
    }

    public static List<String> deviceExtensions(long instance, long physicalDevice) {
        if (instance == 0L || physicalDevice == 0L) {
            throw new IllegalArgumentException("Vulkan instance and physical-device handles are required");
        }
        return Holder.INSTANCE.queryExtensions(
                instance, physicalDevice, Holder.INSTANCE.deviceExtensionsFunction);
    }

    public static Context initialize(VulkanContext context) {
        return Holder.INSTANCE.initializeContext(context);
    }

    private List<String> queryExtensions(long instance, long physicalDevice, long function) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer names = stack.calloc(EXTENSION_CAPACITY * EXTENSION_NAME_STRIDE);
            ByteBuffer query = stack.calloc(EXTENSION_QUERY_SIZE).order(ByteOrder.nativeOrder());
            query.putLong(0, instance);
            query.putLong(8, physicalDevice);
            query.putInt(16, EXTENSION_CAPACITY);
            query.putLong(24, MemoryUtil.memAddress(names));
            query.putLong(32, MemoryUtil.memAddress(stack.UTF16(this.featureDirectory.toString())));
            query.putLong(40, MemoryUtil.memAddress(stack.UTF16(this.applicationDataDirectory.toString())));
            query.putLong(48, MemoryUtil.memAddress(stack.UTF8(this.engineVersion)));
            checkResult(JNI.invokePI(MemoryUtil.memAddress(query), function), "query DLSS RR Vulkan extensions");
            int count = query.getInt(20);
            if (count < 0 || count > EXTENSION_CAPACITY) {
                throw new IllegalStateException("DLSS RR returned an invalid extension count " + count);
            }
            ArrayList<String> extensions = new ArrayList<>(count);
            long namesAddress = MemoryUtil.memAddress(names);
            for (int index = 0; index < count; index++) {
                long address = namesAddress + (long) index * EXTENSION_NAME_STRIDE;
                String extension = MemoryUtil.memUTF8(address);
                if (!extension.isBlank()) {
                    extensions.add(extension);
                }
            }
            return List.copyOf(extensions);
        }
    }

    private Context initializeContext(VulkanContext context) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer description = stack.calloc(INIT_DESCRIPTION_SIZE).order(ByteOrder.nativeOrder());
            description.putLong(0, context.vkDevice().getPhysicalDevice().getInstance().address());
            description.putLong(8, context.vkDevice().getPhysicalDevice().address());
            description.putLong(16, context.vkDevice().address());
            description.putLong(24, MemoryUtil.memAddress(stack.UTF16(this.featureDirectory.toString())));
            description.putLong(32, MemoryUtil.memAddress(stack.UTF16(this.applicationDataDirectory.toString())));
            description.putLong(40, MemoryUtil.memAddress(stack.UTF8(this.engineVersion)));
            ByteBuffer output = stack.calloc(Long.BYTES).order(ByteOrder.nativeOrder());
            description.putLong(48, MemoryUtil.memAddress(output));
            checkResult(
                    JNI.invokePI(MemoryUtil.memAddress(description), this.initializeFunction),
                    "initialize NVIDIA NGX DLSS RR");
            long handle = output.getLong(0);
            if (handle == MemoryUtil.NULL) {
                throw new IllegalStateException("DLSS RR returned a null context");
            }
            return new Context(this, handle);
        }
    }

    private long requireFunction(String name) {
        long function = this.library.getFunctionAddress(name);
        if (function == MemoryUtil.NULL) {
            throw new IllegalStateException("The DLSS RR bridge is missing " + name);
        }
        return function;
    }

    private static ExtractedRuntime extractRuntime() {
        if (!isSupportedPlatform()) {
            throw new IllegalStateException("DLSS RR currently supports Windows x86-64 only");
        }
        byte[] bridge = readResource(BRIDGE_RESOURCE);
        byte[] feature = readResource(FEATURE_RESOURCE);
        String digest = sha256(bridge, feature);
        Path directory = Path.of(System.getProperty("java.io.tmpdir"), "prime-dlss-rr", digest)
                .toAbsolutePath();
        Path bridgePath = directory.resolve("prime_dlss_rr.dll");
        Path featurePath = directory.resolve("nvngx_dlssd.dll");
        try {
            Files.createDirectories(directory);
            publish(bridgePath, bridge);
            publish(featurePath, feature);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to extract the bundled DLSS RR runtime", exception);
        }
        return new ExtractedRuntime(directory, bridgePath);
    }

    private static Path createApplicationDataDirectory() {
        Path path = FabricLoader.getInstance()
                .getConfigDir()
                .resolve("prime")
                .resolve("ngx")
                .toAbsolutePath();
        try {
            Files.createDirectories(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create Prime's NGX application-data directory", exception);
        }
        return path;
    }

    private static byte[] readResource(String name) {
        try (InputStream input = DlssRrNative.class.getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException("Missing bundled DLSS RR runtime " + name);
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read bundled DLSS RR runtime " + name, exception);
        }
    }

    private static void publish(Path target, byte[] bytes) throws IOException {
        if (Files.exists(target)) {
            return;
        }
        Path temporary = target.resolveSibling(target.getFileName()
                + "-"
                + ProcessHandle.current().pid()
                + ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException ignored) {
                // Another client process published the same content-addressed runtime.
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String sha256(byte[]... inputs) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] input : inputs) {
                digest.update(input);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("The Java runtime does not provide SHA-256", exception);
        }
    }

    private static void checkResult(int result, String operation) {
        if (result != 0) {
            throw new IllegalStateException(operation + " failed with native result " + result);
        }
    }

    static void putMatrixForNgx(ByteBuffer target, int offset, Matrix4fc matrix) {
        // NGX specifies row-major matrices multiplied from the left. JOML's column-major memory
        // for M is byte-identical to a row-major representation of transpose(M), which is the
        // equivalent transform under row-vector multiplication.
        matrix.get(offset, target);
    }

    public static final class Context implements AutoCloseable {
        private final DlssRrNative nativeApi;
        private long handle;

        private Context(DlssRrNative nativeApi, long handle) {
            this.nativeApi = nativeApi;
            this.handle = handle;
        }

        public OptimalSettings optimalSettings(
                int outputWidth, int outputHeight, ReconstructionQualityMode quality) {
            long context = requireOpen();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer settings = stack.calloc(OPTIMAL_SETTINGS_SIZE).order(ByteOrder.nativeOrder());
                settings.putLong(0, context);
                settings.putInt(8, outputWidth);
                settings.putInt(12, outputHeight);
                settings.putInt(16, quality.ngxPerfQualityValue());
                checkResult(
                        JNI.invokePI(
                                MemoryUtil.memAddress(settings),
                                this.nativeApi.optimalSettingsFunction),
                        "query DLSS RR optimal settings");
                int width = settings.getInt(20);
                int height = settings.getInt(24);
                if (width <= 0 || height <= 0) {
                    throw new IllegalStateException("DLSS RR returned invalid optimal dimensions");
                }
                return new OptimalSettings(width, height);
            }
        }

        public Feature createFeature(
                VkCommandBuffer commandBuffer,
                int renderWidth,
                int renderHeight,
                int outputWidth,
                int outputHeight,
                ReconstructionQualityMode quality) {
            long context = requireOpen();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer description = stack.calloc(FEATURE_DESCRIPTION_SIZE)
                        .order(ByteOrder.nativeOrder());
                description.putLong(0, context);
                description.putLong(8, commandBuffer.address());
                description.putInt(16, renderWidth);
                description.putInt(20, renderHeight);
                description.putInt(24, outputWidth);
                description.putInt(28, outputHeight);
                description.putInt(32, quality.ngxPerfQualityValue());
                ByteBuffer output = stack.calloc(Long.BYTES).order(ByteOrder.nativeOrder());
                description.putLong(40, MemoryUtil.memAddress(output));
                checkResult(
                        JNI.invokePI(
                                MemoryUtil.memAddress(description),
                                this.nativeApi.createFeatureFunction),
                        "create DLSS RR feature");
                long feature = output.getLong(0);
                if (feature == MemoryUtil.NULL) {
                    throw new IllegalStateException("DLSS RR returned a null feature");
                }
                return new Feature(this.nativeApi, feature);
            }
        }

        private long requireOpen() {
            if (this.handle == MemoryUtil.NULL) {
                throw new IllegalStateException("DLSS RR context is closed");
            }
            return this.handle;
        }

        @Override
        public void close() {
            long context = this.handle;
            if (context != MemoryUtil.NULL) {
                this.handle = MemoryUtil.NULL;
                checkResult(
                        JNI.invokePI(context, this.nativeApi.shutdownFunction),
                        "shut down NVIDIA NGX");
            }
        }
    }

    public static final class Feature implements AutoCloseable {
        private final DlssRrNative nativeApi;
        private long handle;

        private Feature(DlssRrNative nativeApi, long handle) {
            this.nativeApi = nativeApi;
            this.handle = handle;
        }

        public void evaluate(VkCommandBuffer commandBuffer, Evaluation evaluation) {
            if (this.handle == MemoryUtil.NULL) {
                throw new IllegalStateException("DLSS RR feature is closed");
            }
            if (evaluation.images().size() != IMAGE_COUNT) {
                throw new IllegalArgumentException("DLSS RR evaluation requires exactly nine images");
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer description = stack.calloc(EVALUATE_DESCRIPTION_SIZE)
                        .order(ByteOrder.nativeOrder());
                description.putLong(0, this.handle);
                description.putLong(8, commandBuffer.address());
                description.putInt(16, evaluation.renderWidth());
                description.putInt(20, evaluation.renderHeight());
                description.putFloat(24, evaluation.jitterX());
                description.putFloat(28, evaluation.jitterY());
                description.putFloat(32, evaluation.motionScaleX());
                description.putFloat(36, evaluation.motionScaleY());
                description.putInt(40, evaluation.reset() ? 1 : 0);
                description.putFloat(44, evaluation.frameTimeMilliseconds());
                putMatrixForNgx(description, 48, evaluation.worldToView());
                putMatrixForNgx(description, 112, evaluation.viewToClip());
                for (int index = 0; index < IMAGE_COUNT; index++) {
                    putImage(description, 176 + index * IMAGE_SIZE, evaluation.images().get(index));
                }
                checkResult(
                        JNI.invokePI(MemoryUtil.memAddress(description), this.nativeApi.evaluateFunction),
                        "evaluate DLSS RR");
            }
        }

        private static void putImage(ByteBuffer target, int offset, VulkanImage image) {
            target.putLong(offset, image.image());
            target.putLong(offset + 8, image.view());
            target.putInt(offset + 16, image.format());
            target.putInt(offset + 20, image.width());
            target.putInt(offset + 24, image.height());
        }

        @Override
        public void close() {
            long feature = this.handle;
            if (feature != MemoryUtil.NULL) {
                this.handle = MemoryUtil.NULL;
                checkResult(
                        JNI.invokePI(feature, this.nativeApi.releaseFeatureFunction),
                        "release DLSS RR feature");
            }
        }
    }

    public record OptimalSettings(int renderWidth, int renderHeight) {}

    /** Images are ordered exactly as the bridge ABI: both albedos, normal/roughness, color,
     * color-before-transparency, output, depth, motion, and specular hit distance. */
    public record Evaluation(
            int renderWidth,
            int renderHeight,
            float jitterX,
            float jitterY,
            float motionScaleX,
            float motionScaleY,
            boolean reset,
            float frameTimeMilliseconds,
            Matrix4fc worldToView,
            Matrix4fc viewToClip,
            List<VulkanImage> images) {
        public Evaluation {
            images = List.copyOf(images);
        }
    }

    private record ExtractedRuntime(Path directory, Path bridge) {}

    private static final class Holder {
        private static final DlssRrNative INSTANCE = new DlssRrNative();
    }
}
