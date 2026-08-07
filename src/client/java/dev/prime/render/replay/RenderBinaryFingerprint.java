package dev.prime.render.replay;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.security.CodeSource;

/**
 * Prime executable and static-resource identity used by strict same-platform replay.
 *
 * <p>Capture performs file/resource I/O only for an explicitly requested replay. Dynamic block and
 * LabPBR atlas contents remain separate scene-asset identities.
 */
public record RenderBinaryFingerprint(
        String executableSha256, List<ResourceDigest> resources) {
    private static final int FORMAT_VERSION = 1;
    private static final int DIGEST_BYTES = 32;
    private static final int MAX_RESOURCES = 128;
    private static final int MAX_ENCODED_BYTES = 64 * 1024;
    private static final List<String> COMMON_RESOURCES = List.of(
            "/prime/shaders/world.rmiss.spv",
            "/prime/shaders/shadow.rmiss.spv",
            "/prime/shaders/world.rchit.spv",
            "/prime/shaders/world.rahit.spv",
            "/prime/shaders/shadow.rchit.spv",
            "/prime/shaders/nrd_motion.comp.spv",
            "/prime/shaders/nrd_composite.comp.spv",
            "/prime/shaders/atmosphere_transmittance.comp.spv",
            "/prime/shaders/atmosphere_multi_scattering.comp.spv",
            "/prime/shaders/atmosphere_sky.comp.spv",
            "/prime/shaders/atmosphere_aerial.comp.spv",
            "/prime/shaders/replay_capture_raw.comp.spv",
            "/prime/shaders/replay_capture_prepared_nrd.comp.spv",
            "/prime/shaders/replay_capture_post_nrd.comp.spv",
            "/prime/natives/windows-x86_64/prime_nrd.dll",
            "/prime/atmosphere/phase_lut.bin.gz.b64",
            "/prime/bsdf/trans_ggx.bytes.gz.b64",
            "/prime/starmap/starmap_2020_8k.json",
            "/prime/starmap/starmap_2020_8k_0.rgba16f.gz",
            "/prime/starmap/starmap_2020_8k_1.rgba16f.gz",
            "/prime/starmap/starmap_2020_8k_2.rgba16f.gz",
            "/prime/starmap/starmap_2020_8k_3.rgba16f.gz");
    private static final List<String> EXECUTION_CLASS_RESOURCES = List.of(
            "/dev/prime/render/runtime/VulkanRenderer.class",
            "/dev/prime/render/runtime/VulkanRenderer$BlockAtlasFrame.class",
            "/dev/prime/render/runtime/RealtimeRenderer.class",
            "/dev/prime/render/runtime/RealtimeRenderer$RenderInput.class",
            "/dev/prime/render/RealtimeFrameInput.class",
            "/dev/prime/render/RealtimeFramePlan.class",
            "/dev/prime/render/RealtimeRenderSettings.class",
            "/dev/prime/render/fsr/FsrReconstructionProfile.class",
            "/dev/prime/render/post/ReconstructionFrame.class",
            "/dev/prime/render/post/ReconstructionFrameParameters.class",
            "/dev/prime/render/post/SubpixelJitter.class",
            "/dev/prime/render/post/TransparentGuideMode.class",
            "/dev/prime/render/vulkan/RealtimeRayTracingPipeline.class",
            "/dev/prime/render/vulkan/RealtimeRayTracingPipeline$OutputBindings.class",
            "/dev/prime/render/vulkan/RealtimeIntegratorPipeline.class",
            "/dev/prime/render/vulkan/RealtimeFrameExecutor.class",
            "/dev/prime/render/vulkan/SunShadowPipeline.class",
            "/dev/prime/render/vulkan/TraceBackend.class",
            "/dev/prime/render/vulkan/TraceBackend$SceneBindings.class",
            "/dev/prime/render/vulkan/TraceBackend$SceneTexture.class",
            "/dev/prime/render/vulkan/TraceProgram.class",
            "/dev/prime/render/vulkan/RayTracingPushConstants.class",
            "/dev/prime/render/vulkan/dlss/DlssRrProfile.class",
            "/dev/prime/render/vulkan/reconstruction/DlssRrBackend.class",
            "/dev/prime/render/vulkan/reconstruction/NrdFsrBackend.class",
            "/dev/prime/render/vulkan/reconstruction/NoisyBackend.class",
            "/dev/prime/render/vulkan/reconstruction/ReconstructionBackend.class",
            "/dev/prime/render/vulkan/reconstruction/ReconstructionBackend$Capability.class",
            "/dev/prime/render/vulkan/reconstruction/ReconstructionBackend$CreateInput.class",
            "/dev/prime/render/vulkan/reconstruction/ReconstructionBackendRegistry.class",
            "/dev/prime/render/vulkan/reconstruction/ReconstructionBackendRegistry$FailureReporter.class",
            "/dev/prime/render/vulkan/reconstruction/ReconstructionBackendRegistry$DefaultFailureReporter.class",
            "/dev/prime/render/vulkan/reconstruction/ResolvedReconstruction.class",
            "/dev/prime/render/vulkan/reconstruction/VulkanReconstructionProcessor.class",
            "/dev/prime/render/vulkan/reconstruction/VulkanReconstructionProcessor$Frame.class",
            "/dev/prime/render/vulkan/reconstruction/VulkanReconstructionResources.class",
            "/dev/prime/render/vulkan/nrd/NrdDenoiser.class",
            "/dev/prime/render/vulkan/nrd/NrdDenoiser$CompositePipeline.class",
            "/dev/prime/render/vulkan/nrd/NrdDenoiser$ComputePipeline.class",
            "/dev/prime/render/vulkan/nrd/NrdDenoiser$FrameBindings.class",
            "/dev/prime/render/vulkan/nrd/NrdDenoiser$FrameToken.class",
            "/dev/prime/render/vulkan/nrd/NrdDenoiser$Images.class",
            "/dev/prime/render/vulkan/nrd/NrdDenoiser$InputPreparationPipeline.class",
            "/dev/prime/render/vulkan/nrd/NrdDenoiser$PreparedFrame.class",
            "/dev/prime/render/vulkan/nrd/NrdDenoiser$RawSignals.class",
            "/dev/prime/render/vulkan/nrd/NrdCompositeFrame.class",
            "/dev/prime/render/post/nrd/NrdCameraTransform.class",
            "/dev/prime/render/post/nrd/NrdDiagnostics.class",
            "/dev/prime/render/post/nrd/NrdDiagnostics$Mode.class",
            "/dev/prime/render/post/nrd/NrdFrameHistory.class",
            "/dev/prime/render/post/nrd/NrdFrameHistory$PlannedFrame.class",
            "/dev/prime/render/post/nrd/NrdFrameInput.class",
            "/dev/prime/render/post/nrd/NrdFramePlan.class",
            "/dev/prime/render/post/nrd/NrdTemporalState.class",
            "/dev/prime/render/post/nrd/NrdTemporalState$Plan.class",
            "/dev/prime/render/vulkan/replay/NrdReplayProbe.class",
            "/dev/prime/render/vulkan/replay/NrdReplayProbe$PlannedFrame.class",
            "/dev/prime/render/vulkan/replay/NrdReplayProbe$RecordedFrame.class",
            "/dev/prime/render/vulkan/replay/NrdReplayProbe$Stages.class",
            "/dev/prime/render/vulkan/replay/ReplayProbeController.class",
            "/dev/prime/render/vulkan/replay/ReplayProbeController$RunInput.class",
            "/dev/prime/render/vulkan/replay/ReplayProbeController$Session.class",
            "/dev/prime/render/vulkan/replay/ReplayProbeFrameExecutor.class",
            "/dev/prime/render/vulkan/replay/ReplayProbeRequestState.class",
            "/dev/prime/render/vulkan/replay/ReplayProbeRequestState$Request.class",
            "/dev/prime/render/vulkan/replay/ReplayStageCapturePass.class");

    public RenderBinaryFingerprint {
        requireDigest(executableSha256, "executable");
        Objects.requireNonNull(resources, "resources");
        ArrayList<ResourceDigest> ordered = new ArrayList<>(resources);
        ordered.sort(Comparator.comparing(ResourceDigest::name));
        if (ordered.size() > MAX_RESOURCES) {
            throw new IllegalArgumentException(
                    "Render binary fingerprint has too many resources");
        }
        HashSet<String> names = new HashSet<>();
        for (ResourceDigest resource : ordered) {
            if (!names.add(resource.name())) {
                throw new IllegalArgumentException(
                        "Render binary fingerprint repeats " + resource.name());
            }
        }
        resources = List.copyOf(ordered);
    }

    public static RenderBinaryFingerprint capture(boolean ser) {
        ArrayList<String> names = new ArrayList<>(COMMON_RESOURCES);
        String suffix = ser ? "_ser.rgen.spv" : ".rgen.spv";
        List<String> stages = List.of(
                "realtime_wavefront_head",
                "realtime_wavefront_step",
                "realtime_wavefront_area",
                "realtime_wavefront_tail",
                "realtime_wavefront_resolve");
        for (String stage : stages) {
            names.add("/prime/shaders/" + stage + suffix);
        }
        names.sort(String::compareTo);
        ArrayList<ResourceDigest> resources = new ArrayList<>(names.size());
        for (String name : names) {
            resources.add(new ResourceDigest(name, digestResource(name)));
        }
        return new RenderBinaryFingerprint(
                digestExecutable(), resources);
    }

    public byte[] canonicalBytes() {
        long size = 2L * Integer.BYTES + DIGEST_BYTES;
        for (ResourceDigest resource : this.resources) {
            byte[] name = resource.name().getBytes(StandardCharsets.UTF_8);
            size = Math.addExact(
                    size,
                    Math.addExact(
                            Integer.BYTES + DIGEST_BYTES,
                            name.length));
        }
        if (size > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException(
                    "Render binary fingerprint exceeds its format limit");
        }
        ByteBuffer output =
                ByteBuffer.allocate((int) size).order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(FORMAT_VERSION);
        putDigest(output, this.executableSha256);
        output.putInt(this.resources.size());
        for (ResourceDigest resource : this.resources) {
            byte[] name = resource.name().getBytes(StandardCharsets.UTF_8);
            output.putInt(name.length);
            output.put(name);
            putDigest(output, resource.sha256());
        }
        return output.array();
    }

    public static RenderBinaryFingerprint decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length < 2 * Integer.BYTES + DIGEST_BYTES
                || encoded.length > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException(
                    "Render binary fingerprint has an invalid byte size");
        }
        ByteBuffer input =
                ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        if (input.getInt() != FORMAT_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported render-binary-fingerprint header");
        }
        String executable = getDigest(input);
        int count = input.getInt();
        if (count < 0 || count > MAX_RESOURCES) {
            throw new IllegalArgumentException(
                    "Render binary fingerprint has an invalid resource count");
        }
        ArrayList<ResourceDigest> resources = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            if (input.remaining() < Integer.BYTES) {
                throw new IllegalArgumentException(
                        "Render binary fingerprint is truncated");
            }
            int length = input.getInt();
            if (length <= 0
                    || length > input.remaining() - DIGEST_BYTES) {
                throw new IllegalArgumentException(
                        "Render binary fingerprint has an invalid resource name");
            }
            byte[] name = new byte[length];
            input.get(name);
            resources.add(new ResourceDigest(
                    new String(name, StandardCharsets.UTF_8),
                    getDigest(input)));
        }
        if (input.hasRemaining()) {
            throw new IllegalArgumentException(
                    "Render binary fingerprint contains trailing data");
        }
        return new RenderBinaryFingerprint(executable, resources);
    }

    public boolean isStrictlyCompatibleWith(RenderBinaryFingerprint other) {
        return other != null
                && MessageDigest.isEqual(
                        canonicalBytes(), other.canonicalBytes());
    }

    private static String digestExecutable() {
        try {
            CodeSource source = RenderBinaryFingerprint.class
                    .getProtectionDomain()
                    .getCodeSource();
            if (source == null) {
                return digestExecutionClasses();
            }
            URL location = source.getLocation();
            Path path = Path.of(location.toURI());
            MessageDigest digest = sha256();
            if (Files.isRegularFile(path)) {
                updateFile(digest, path);
            } else if (Files.isDirectory(path)) {
                List<Path> files;
                try (var paths = Files.walk(path)) {
                    files = paths.filter(Files::isRegularFile)
                            .sorted(Comparator.comparing(
                                    file -> path.relativize(file).toString()))
                            .toList();
                }
                for (Path file : files) {
                    byte[] name = path.relativize(file)
                            .toString()
                            .replace('\\', '/')
                            .getBytes(StandardCharsets.UTF_8);
                    digest.update(ByteBuffer.allocate(Integer.BYTES)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(name.length)
                            .array());
                    digest.update(name);
                    updateFile(digest, file);
                }
            } else {
                throw new IllegalStateException(
                        "Prime executable code source is not a file or directory");
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | URISyntaxException | RuntimeException exception) {
            // Some mod launchers expose a non-file nested-jar URL. Class resources still identify
            // every Prime-owned stage that can affect this capture without trusting that URL.
            return digestExecutionClasses();
        }
    }

    static String digestExecutionClasses() {
        MessageDigest digest = sha256();
        for (String name : EXECUTION_CLASS_RESOURCES) {
            byte[] label = name.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(label.length)
                    .array());
            digest.update(label);
            try (InputStream input =
                    RenderBinaryFingerprint.class.getResourceAsStream(name)) {
                if (input == null) {
                    throw new IllegalStateException(
                            "Missing replay execution class " + name);
                }
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read != 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "Unable to fingerprint replay execution class " + name,
                        exception);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String digestResource(String name) {
        try (InputStream input =
                RenderBinaryFingerprint.class.getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Missing replay-identity resource " + name);
            }
            MessageDigest digest = sha256();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read != 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to fingerprint replay resource " + name,
                    exception);
        }
    }

    private static void updateFile(MessageDigest digest, Path file)
            throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read != 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(
                    "Required SHA-256 algorithm is unavailable", exception);
        }
    }

    private static void putDigest(ByteBuffer output, String hex) {
        output.put(HexFormat.of().parseHex(hex));
    }

    private static String getDigest(ByteBuffer input) {
        if (input.remaining() < DIGEST_BYTES) {
            throw new IllegalArgumentException(
                    "Render binary fingerprint is truncated before a digest");
        }
        byte[] digest = new byte[DIGEST_BYTES];
        input.get(digest);
        return HexFormat.of().formatHex(digest);
    }

    private static void requireDigest(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.length() != DIGEST_BYTES * 2) {
            throw new IllegalArgumentException(
                    "Render " + label + " digest is not SHA-256");
        }
        try {
            if (!HexFormat.of().formatHex(
                    HexFormat.of().parseHex(value)).equals(value)) {
                throw new IllegalArgumentException(
                        "Render " + label + " digest must use lowercase hexadecimal");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Render " + label + " digest is invalid", exception);
        }
    }

    public record ResourceDigest(String name, String sha256) {
        public ResourceDigest {
            Objects.requireNonNull(name, "name");
            if (name.isBlank() || !name.startsWith("/")) {
                throw new IllegalArgumentException(
                        "Replay resource name must be absolute");
            }
            requireDigest(sha256, name);
        }
    }
}
