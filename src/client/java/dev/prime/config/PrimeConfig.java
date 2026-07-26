package dev.prime.config;

import dev.prime.PrimeClient;
import dev.prime.render.DisplaySettings;
import dev.prime.render.LightingSettings;
import dev.prime.render.MaterialSettings;
import dev.prime.render.fsr.FsrQualityMode;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionQualityMode;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/** Small, version-tolerant owner for Prime's user-facing settings. */
public final class PrimeConfig {
    private static final String PATH_TRACING_ENABLED_KEY = "renderer.path_tracing";
    private static final String MODE_KEY = "post_processing.mode";
    private static final String QUALITY_KEY = "post_processing.quality";
    private static final String LEGACY_QUALITY_KEY = "fsr.quality";
    // Former persisted debug keys are ignored and removed on the next settings save. Diagnostics
    // are observation tools, not product settings, and always start disabled for a new session.
    private static final String[] LEGACY_DEBUG_KEYS = {
        "dlss_rr.debug_view",
        "dlss_rr.debug_fullscreen",
        "fsr.debug_view",
        "nrd.debug_view"
    };
    private static final String SUN_EV_KEY = "lighting.sun_ev";
    private static final String STAR_EV_KEY = "lighting.star_ev";
    private static final String BLOCK_LIGHT_EV_KEY = "lighting.block_light_ev";
    private static final String OKLAB_OVEREXPOSURE_KEY = "display.oklab_overexposure";
    private static final String DEFAULT_ROUGHNESS_KEY = "material.default_roughness";
    // Fabric initializes and mutates video options on the client thread. One immutable snapshot
    // keeps every renderer read coherent without a shared lock or independently mutable globals.
    private static PrimeSettings settings = PrimeSettings.defaults();
    private static boolean dirty;

    private PrimeConfig() {
    }

    public static void load() {
        Path path = configPath();
        boolean pathTracingEnabled = true;
        PostProcessingMode postProcessingMode = PostProcessingMode.DEFAULT;
        ReconstructionQualityMode quality = ReconstructionQualityMode.DEFAULT;
        int sunQuarterSteps = LightingSettings.DEFAULT_SUN_QUARTER_STEPS;
        int starQuarterSteps = LightingSettings.DEFAULT_STAR_QUARTER_STEPS;
        int blockLightQuarterSteps = LightingSettings.DEFAULT_BLOCK_LIGHT_QUARTER_STEPS;
        int oklabOverexposureSteps = DisplaySettings.DEFAULT_OVEREXPOSURE_STEPS;
        int defaultRoughnessSteps = MaterialSettings.DEFAULT_ROUGHNESS_STEPS;
        boolean rewriteNeeded = false;
        if (Files.isRegularFile(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                Properties properties = new Properties();
                properties.load(reader);
                String pathTracing = properties.getProperty(PATH_TRACING_ENABLED_KEY);
                if (pathTracing != null) {
                    try {
                        pathTracingEnabled = parseBoolean(pathTracing);
                    } catch (IllegalArgumentException exception) {
                        PrimeClient.LOGGER.warn(
                                "Invalid Prime path-tracing switch '{}'; enabling path tracing",
                                pathTracing);
                        rewriteNeeded = true;
                    }
                } else {
                    rewriteNeeded = true;
                }
                String postProcessingId = properties.getProperty(MODE_KEY);
                if (postProcessingId != null) {
                    PostProcessingMode parsed = PostProcessingMode.findById(postProcessingId).orElse(null);
                    if (parsed == null) {
                        PrimeClient.LOGGER.warn(
                                "Unknown Prime post-processing mode '{}'; using {}",
                                postProcessingId,
                                PostProcessingMode.DEFAULT.id());
                        rewriteNeeded = true;
                    } else {
                        postProcessingMode = parsed;
                    }
                } else {
                    rewriteNeeded = true;
                }
                String qualityId = configuredQualityId(properties);
                if (!properties.containsKey(QUALITY_KEY)) {
                    rewriteNeeded = true;
                }
                if (qualityId != null) {
                    ReconstructionQualityMode parsed =
                            ReconstructionQualityMode.findById(qualityId).orElse(null);
                    if (parsed == null) {
                        PrimeClient.LOGGER.warn(
                                "Unknown Prime reconstruction quality '{}'; using {}",
                                qualityId,
                                ReconstructionQualityMode.DEFAULT.id());
                        rewriteNeeded = true;
                    } else {
                        quality = parsed;
                    }
                } else {
                    rewriteNeeded = true;
                }
                rewriteNeeded |= hasLegacyDebugProperties(properties);
                String sunEv = properties.getProperty(SUN_EV_KEY);
                if (sunEv != null) {
                    try {
                        sunQuarterSteps = parseEvQuarterSteps(sunEv);
                    } catch (IllegalArgumentException exception) {
                        PrimeClient.LOGGER.warn(
                                "Invalid Prime sun exposure '{}'; using 0 EV",
                                sunEv);
                        rewriteNeeded = true;
                    }
                } else {
                    rewriteNeeded = true;
                }
                String starEv = properties.getProperty(STAR_EV_KEY);
                if (starEv != null) {
                    try {
                        starQuarterSteps = parseStarEvQuarterSteps(starEv);
                    } catch (IllegalArgumentException exception) {
                        PrimeClient.LOGGER.warn(
                                "Invalid Prime star exposure '{}'; using 0 EV",
                                starEv);
                        rewriteNeeded = true;
                    }
                } else {
                    rewriteNeeded = true;
                }
                String blockLightEv = properties.getProperty(BLOCK_LIGHT_EV_KEY);
                if (blockLightEv != null) {
                    try {
                        blockLightQuarterSteps = parseEvQuarterSteps(blockLightEv);
                    } catch (IllegalArgumentException exception) {
                        PrimeClient.LOGGER.warn(
                                "Invalid Prime block-light exposure '{}'; using 0 EV",
                                blockLightEv);
                        rewriteNeeded = true;
                    }
                } else {
                    rewriteNeeded = true;
                }
                String oklabOverexposure = properties.getProperty(OKLAB_OVEREXPOSURE_KEY);
                if (oklabOverexposure != null) {
                    try {
                        oklabOverexposureSteps = parseOverexposureSteps(oklabOverexposure);
                    } catch (IllegalArgumentException exception) {
                        PrimeClient.LOGGER.warn(
                                "Invalid Prime Oklab DRT overexposure '{}'; using the default",
                                oklabOverexposure);
                        rewriteNeeded = true;
                    }
                } else {
                    rewriteNeeded = true;
                }
                String defaultRoughness = properties.getProperty(DEFAULT_ROUGHNESS_KEY);
                if (defaultRoughness != null) {
                    try {
                        defaultRoughnessSteps = parseRoughnessSteps(defaultRoughness);
                    } catch (IllegalArgumentException exception) {
                        PrimeClient.LOGGER.warn(
                                "Invalid Prime default material roughness '{}'; using the default",
                                defaultRoughness);
                        rewriteNeeded = true;
                    }
                } else {
                    rewriteNeeded = true;
                }
            } catch (IOException | IllegalArgumentException exception) {
                PrimeClient.LOGGER.warn(
                        "Could not read {}; using the default Prime settings",
                        path,
                        exception);
                rewriteNeeded = true;
            }
        }
        settings = new PrimeSettings(
                pathTracingEnabled,
                postProcessingMode,
                quality,
                sunQuarterSteps,
                starQuarterSteps,
                blockLightQuarterSteps,
                oklabOverexposureSteps,
                defaultRoughnessSteps,
                0L,
                0L);
        dirty = rewriteNeeded;
        PrimeClient.LOGGER.info(
                "Prime settings: path tracing {}, post-processing {} quality {} (NRD-FSR {}x), sun {} EV, stars {} EV, block lights {} EV, Oklab DRT overexposure {}, default roughness {}",
                pathTracingEnabled ? "enabled" : "disabled",
                postProcessingMode.id(),
                quality.id(),
                quality.upscaleRatio(),
                formatEv(sunQuarterSteps),
                formatStarEv(starQuarterSteps),
                formatEv(blockLightQuarterSteps),
                formatOverexposure(oklabOverexposureSteps),
                formatRoughness(defaultRoughnessSteps));
    }

    public static void setFsrQualityMode(FsrQualityMode mode) {
        setReconstructionQualityMode(ReconstructionQualityMode.fromId(mode.id()));
    }

    public static PrimeSettings settings() {
        return settings;
    }

    public static void setPathTracingEnabled(boolean enabled) {
        update(settings.withPathTracingEnabled(enabled));
    }

    public static void setPostProcessingMode(PostProcessingMode mode) {
        update(settings.withPostProcessingMode(mode));
    }

    public static void setReconstructionQualityMode(ReconstructionQualityMode mode) {
        update(settings.withReconstructionQuality(mode));
    }

    public static void setSunQuarterSteps(int quarterSteps) {
        update(settings.withSunQuarterSteps(quarterSteps));
    }

    public static void setStarQuarterSteps(int quarterSteps) {
        update(settings.withStarQuarterSteps(quarterSteps));
    }

    public static void setBlockLightQuarterSteps(int quarterSteps) {
        update(settings.withBlockLightQuarterSteps(quarterSteps));
    }

    public static void setOklabOverexposureSteps(int steps) {
        update(settings.withOklabOverexposureSteps(steps));
    }

    public static void setDefaultRoughnessSteps(int steps) {
        update(settings.withDefaultRoughnessSteps(steps));
    }

    public static void restoreDefaults() {
        setPathTracingEnabled(true);
        setPostProcessingMode(PostProcessingMode.DEFAULT);
        setReconstructionQualityMode(ReconstructionQualityMode.DEFAULT);
        setSunQuarterSteps(LightingSettings.DEFAULT_SUN_QUARTER_STEPS);
        setStarQuarterSteps(LightingSettings.DEFAULT_STAR_QUARTER_STEPS);
        setBlockLightQuarterSteps(LightingSettings.DEFAULT_BLOCK_LIGHT_QUARTER_STEPS);
        setOklabOverexposureSteps(DisplaySettings.DEFAULT_OVEREXPOSURE_STEPS);
        setDefaultRoughnessSteps(MaterialSettings.DEFAULT_ROUGHNESS_STEPS);
    }

    public static void save() {
        Path path = configPath();
        if (!dirty && Files.isRegularFile(path)) {
            return;
        }

        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            String contents = serializedContents();
            Files.writeString(
                    temporary,
                    contents,
                    StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            PrimeClient.LOGGER.error("Could not save Prime settings to {}", path, exception);
        }
    }

    static boolean hasLegacyDebugProperties(Properties properties) {
        for (String key : LEGACY_DEBUG_KEYS) {
            if (properties.containsKey(key)) return true;
        }
        return false;
    }

    static String serializedContents() {
        PrimeSettings current = settings;
        return PATH_TRACING_ENABLED_KEY + "=" + current.pathTracingEnabled() + "\n"
                    + MODE_KEY + "=" + current.postProcessingMode().id() + "\n"
                    + QUALITY_KEY + "=" + current.reconstructionQuality().id() + "\n"
                    + SUN_EV_KEY + "=" + formatEv(current.sunQuarterSteps()) + "\n"
                    + STAR_EV_KEY + "=" + formatStarEv(current.starQuarterSteps()) + "\n"
                    + BLOCK_LIGHT_EV_KEY + "="
                    + formatEv(current.blockLightQuarterSteps()) + "\n"
                    + OKLAB_OVEREXPOSURE_KEY + "="
                    + formatOverexposure(current.oklabOverexposureSteps()) + "\n"
                    + DEFAULT_ROUGHNESS_KEY + "="
                    + formatRoughness(current.defaultRoughnessSteps()) + "\n";
    }

    static boolean parseBoolean(String value) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException("Boolean setting must be true or false");
    }

    private static void update(PrimeSettings replacement) {
        if (replacement != settings) {
            settings = replacement;
            dirty = true;
        }
    }

    static int parseEvQuarterSteps(String value) {
        try {
            int quarterSteps = new BigDecimal(value)
                    .multiply(BigDecimal.valueOf(LightingSettings.QUARTER_STEPS_PER_EV))
                    .intValueExact();
            LightingSettings.linearMultiplier(quarterSteps);
            return quarterSteps;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException("EV must be an exact 0.25-EV step", exception);
        }
    }

    static String formatEv(int quarterSteps) {
        LightingSettings.linearMultiplier(quarterSteps);
        return BigDecimal.valueOf(quarterSteps)
                .divide(BigDecimal.valueOf(LightingSettings.QUARTER_STEPS_PER_EV))
                .toPlainString();
    }

    static int parseStarEvQuarterSteps(String value) {
        try {
            int quarterSteps = new BigDecimal(value)
                    .multiply(BigDecimal.valueOf(LightingSettings.QUARTER_STEPS_PER_EV))
                    .intValueExact();
            LightingSettings.starLinearMultiplier(quarterSteps);
            return quarterSteps;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException("Star EV must be an exact 0.25-EV step", exception);
        }
    }

    static String formatStarEv(int quarterSteps) {
        LightingSettings.starLinearMultiplier(quarterSteps);
        return BigDecimal.valueOf(quarterSteps)
                .divide(BigDecimal.valueOf(LightingSettings.QUARTER_STEPS_PER_EV))
                .toPlainString();
    }

    static int parseOverexposureSteps(String value) {
        try {
            int steps = new BigDecimal(value)
                    .multiply(BigDecimal.valueOf(DisplaySettings.STEPS_PER_UNIT))
                    .intValueExact();
            DisplaySettings.overexposure(steps);
            return steps;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Oklab DRT overexposure must be an exact 1/32 step",
                    exception);
        }
    }

    static String formatOverexposure(int steps) {
        DisplaySettings.overexposure(steps);
        return BigDecimal.valueOf(steps)
                .divide(BigDecimal.valueOf(DisplaySettings.STEPS_PER_UNIT))
                .toPlainString();
    }

    static int parseRoughnessSteps(String value) {
        try {
            int steps = new BigDecimal(value)
                    .multiply(BigDecimal.valueOf(MaterialSettings.STEPS_PER_UNIT))
                    .intValueExact();
            MaterialSettings.linearRoughness(steps);
            return steps;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Default material roughness must be an exact 0.01 step",
                    exception);
        }
    }

    static String formatRoughness(int steps) {
        MaterialSettings.linearRoughness(steps);
        return BigDecimal.valueOf(steps)
                .divide(BigDecimal.valueOf(MaterialSettings.STEPS_PER_UNIT))
                .toPlainString();
    }

    /** New shared quality wins; otherwise the former FSR key is migrated verbatim. */
    static String configuredQualityId(Properties properties) {
        String quality = properties.getProperty(QUALITY_KEY);
        return quality != null ? quality : properties.getProperty(LEGACY_QUALITY_KEY);
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("prime.properties");
    }
}
