package dev.prime.config;

import dev.prime.infrastructure.PrimeInfo;
import dev.prime.render.AstronomySettings;
import dev.prime.render.DisplaySettings;
import dev.prime.render.LightingSettings;
import dev.prime.render.PerformanceIntegratorSettings;
import dev.prime.render.MaterialSettings;
import dev.prime.render.RealtimeIntegratorMode;
import dev.prime.render.RendererSettings;
import dev.prime.render.fsr.FsrQualityMode;
import dev.prime.render.fsr.FsrReconstructionProfile;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.terrain.VoxelSurfaceSettings;
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
    private static final String INTEGRATOR_KEY = "renderer.integrator";
    private static final String PERFORMANCE_MAXIMUM_SCATTERS_KEY =
            "renderer.performance_maximum_bounces";
    private static final String LEGACY_LIGHTWEIGHT_MAXIMUM_SCATTERS_KEY =
            "renderer.lightweight_maximum_bounces";
    private static final String VOXEL_TEXTURE_SURFACES_KEY =
            "experimental.voxel_texture_surfaces";
    private static final String VOXEL_TEXTURE_SURFACE_STRENGTH_KEY =
            "experimental.voxel_texture_surface_strength";
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
    private static final String LATITUDE_DEGREES_KEY = "astronomy.latitude_degrees";
    private static final String SOLAR_LONGITUDE_DEGREES_KEY =
            "astronomy.solar_longitude_degrees";
    private static final String FINAL_EXPOSURE_EV_KEY = "display.final_exposure_ev";
    private static final String OKLAB_OVEREXPOSURE_KEY = "display.oklab_overexposure";
    private static final String OKLAB_CURVE_EXPONENT_KEY = "display.oklab_curve_exponent";
    private static final String AUTO_EXPOSURE_COMPENSATION_KEY =
            "display.auto_exposure_compensation";
    private static final String DEFAULT_ROUGHNESS_KEY = "material.default_roughness";
    // Fabric initializes and mutates video options on the client thread. One immutable snapshot
    // keeps every renderer read coherent without a shared lock or independently mutable globals.
    private static PrimeSettings settings = PrimeSettings.defaults();
    private static long rendererRevision;
    private static boolean dirty;

    private PrimeConfig() {
    }

    public static void load() {
        Path path = configPath();
        boolean pathTracingEnabled = true;
        RealtimeIntegratorMode realtimeIntegrator = RealtimeIntegratorMode.DEFAULT;
        int performanceMaximumScatters = PerformanceIntegratorSettings.DEFAULT_SCATTERS;
        boolean voxelTextureSurfaces = false;
        int voxelTextureSurfaceStrengthSteps = VoxelSurfaceSettings.DEFAULT_STEPS;
        PostProcessingMode postProcessingMode = PostProcessingMode.DEFAULT;
        ReconstructionQualityMode quality = ReconstructionQualityMode.DEFAULT;
        int latitudeDegrees = AstronomySettings.DEFAULT_LATITUDE_DEGREES;
        int solarLongitudeDegrees =
                AstronomySettings.DEFAULT_SOLAR_LONGITUDE_DEGREES;
        int sunQuarterSteps = LightingSettings.DEFAULT_SUN_QUARTER_STEPS;
        int starQuarterSteps = LightingSettings.DEFAULT_STAR_QUARTER_STEPS;
        int blockLightQuarterSteps = LightingSettings.DEFAULT_BLOCK_LIGHT_QUARTER_STEPS;
        int finalExposureQuarterSteps =
                DisplaySettings.DEFAULT_FINAL_EXPOSURE_QUARTER_STEPS;
        int oklabOverexposureSteps = DisplaySettings.DEFAULT_OVEREXPOSURE_STEPS;
        int curveExponentSteps = DisplaySettings.DEFAULT_CURVE_EXPONENT_STEPS;
        int autoExposureCompensationSteps =
                DisplaySettings.DEFAULT_AUTO_EXPOSURE_COMPENSATION_STEPS;
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
                        PrimeInfo.LOGGER.warn(
                                "Invalid Prime path-tracing switch '{}'; enabling path tracing",
                                pathTracing);
                        rewriteNeeded = true;
                    }
                } else {
                    rewriteNeeded = true;
                }
                String integratorId = properties.getProperty(INTEGRATOR_KEY);
                if (integratorId != null) {
                    RealtimeIntegratorMode parsed =
                            RealtimeIntegratorMode.findById(integratorId).orElse(null);
                    if (parsed == null) {
                        PrimeInfo.LOGGER.warn(
                                "Unknown Prime realtime integrator '{}'; using {}",
                                integratorId,
                                RealtimeIntegratorMode.DEFAULT.id());
                        rewriteNeeded = true;
                    } else {
                        realtimeIntegrator = parsed;
                        if (!parsed.id().equalsIgnoreCase(integratorId)) {
                            rewriteNeeded = true;
                        }
                    }
                } else {
                    rewriteNeeded = true;
                }
                String performanceScatters = properties.getProperty(
                        PERFORMANCE_MAXIMUM_SCATTERS_KEY);
                if (performanceScatters == null) {
                    performanceScatters = properties.getProperty(
                            LEGACY_LIGHTWEIGHT_MAXIMUM_SCATTERS_KEY);
                    rewriteNeeded = true;
                } else if (properties.containsKey(
                        LEGACY_LIGHTWEIGHT_MAXIMUM_SCATTERS_KEY)) {
                    rewriteNeeded = true;
                }
                if (performanceScatters != null) {
                    try {
                        performanceMaximumScatters =
                                parsePerformanceMaximumScatters(performanceScatters);
                    } catch (IllegalArgumentException exception) {
                        PrimeInfo.LOGGER.warn(
                                "Invalid Prime performance bounce limit '{}'; using the default",
                                performanceScatters);
                        rewriteNeeded = true;
                    }
                }
                String voxelSurfaces = properties.getProperty(
                        VOXEL_TEXTURE_SURFACES_KEY);
                if (voxelSurfaces != null) {
                    try {
                        voxelTextureSurfaces = parseBoolean(voxelSurfaces);
                    } catch (IllegalArgumentException exception) {
                        PrimeInfo.LOGGER.warn(
                                "Invalid Prime voxel-texture surface switch '{}'; disabling it",
                                voxelSurfaces);
                        rewriteNeeded = true;
                    }
                } else {
                    rewriteNeeded = true;
                }
                String voxelSurfaceStrength = properties.getProperty(
                        VOXEL_TEXTURE_SURFACE_STRENGTH_KEY);
                if (voxelSurfaceStrength != null) {
                    try {
                        voxelTextureSurfaceStrengthSteps =
                                parseVoxelSurfaceStrengthSteps(voxelSurfaceStrength);
                    } catch (IllegalArgumentException exception) {
                        PrimeInfo.LOGGER.warn(
                                "Invalid Prime voxel-surface strength '{}'; using the default",
                                voxelSurfaceStrength);
                        rewriteNeeded = true;
                    }
                } else {
                    rewriteNeeded = true;
                }
                String postProcessingId = properties.getProperty(MODE_KEY);
                if (postProcessingId != null) {
                    PostProcessingMode parsed = PostProcessingMode.findById(postProcessingId).orElse(null);
                    if (parsed == null) {
                        PrimeInfo.LOGGER.warn(
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
                        PrimeInfo.LOGGER.warn(
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
                AstronomyLoad astronomy = parseAstronomy(properties);
                latitudeDegrees = astronomy.settings().latitudeDegrees();
                solarLongitudeDegrees =
                        astronomy.settings().solarLongitudeDegrees();
                rewriteNeeded |= astronomy.rewriteNeeded();
                String sunEv = properties.getProperty(SUN_EV_KEY);
                if (sunEv != null) {
                    try {
                        sunQuarterSteps = parseEvQuarterSteps(sunEv);
                    } catch (IllegalArgumentException exception) {
                        PrimeInfo.LOGGER.warn(
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
                        PrimeInfo.LOGGER.warn(
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
                        PrimeInfo.LOGGER.warn(
                                "Invalid Prime block-light exposure '{}'; using 0 EV",
                                blockLightEv);
                        rewriteNeeded = true;
                    }
                } else {
                    rewriteNeeded = true;
                }
                String finalExposureEv = properties.getProperty(FINAL_EXPOSURE_EV_KEY);
                if (finalExposureEv != null) {
                    try {
                        finalExposureQuarterSteps =
                                parseFinalExposureQuarterSteps(finalExposureEv);
                    } catch (IllegalArgumentException exception) {
                        PrimeInfo.LOGGER.warn(
                                "Invalid Prime final exposure '{}'; using 0 EV",
                                finalExposureEv);
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
                        PrimeInfo.LOGGER.warn(
                                "Invalid Prime Oklab DRT overexposure '{}'; using the default",
                                oklabOverexposure);
                        rewriteNeeded = true;
                    }
                } else {
                    rewriteNeeded = true;
                }
                String curveExponent = properties.getProperty(OKLAB_CURVE_EXPONENT_KEY);
                if (curveExponent != null) {
                    try {
                        curveExponentSteps = parseCurveExponentSteps(curveExponent);
                    } catch (IllegalArgumentException exception) {
                        PrimeInfo.LOGGER.warn(
                                "Invalid Prime Oklab DRT curve exponent '{}'; using the default",
                                curveExponent);
                        rewriteNeeded = true;
                    }
                } else {
                    rewriteNeeded = true;
                }
                String autoExposureCompensation =
                        properties.getProperty(AUTO_EXPOSURE_COMPENSATION_KEY);
                if (autoExposureCompensation != null) {
                    try {
                        autoExposureCompensationSteps =
                                parseAutoExposureCompensationSteps(autoExposureCompensation);
                    } catch (IllegalArgumentException exception) {
                        PrimeInfo.LOGGER.warn(
                                "Invalid Prime auto-exposure compensation '{}'; using the default",
                                autoExposureCompensation);
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
                        PrimeInfo.LOGGER.warn(
                                "Invalid Prime default material roughness '{}'; using the default",
                                defaultRoughness);
                        rewriteNeeded = true;
                    }
                } else {
                    rewriteNeeded = true;
                }
            } catch (IOException | IllegalArgumentException exception) {
                PrimeInfo.LOGGER.warn(
                        "Could not read {}; using the default Prime settings",
                        path,
                        exception);
                rewriteNeeded = true;
            }
        }
        settings = new PrimeSettings(
                pathTracingEnabled,
                realtimeIntegrator,
                performanceMaximumScatters,
                voxelTextureSurfaces,
                voxelTextureSurfaceStrengthSteps,
                postProcessingMode,
                quality,
                new AstronomySettings(latitudeDegrees, solarLongitudeDegrees),
                sunQuarterSteps,
                starQuarterSteps,
                blockLightQuarterSteps,
                finalExposureQuarterSteps,
                oklabOverexposureSteps,
                curveExponentSteps,
                autoExposureCompensationSteps,
                defaultRoughnessSteps,
                0L,
                0L);
        rendererRevision = 0L;
        dirty = rewriteNeeded;
        PrimeInfo.LOGGER.info(
                "Prime settings: path tracing {}, realtime integrator {}, performance limit {} bounces, voxel surfaces {} at {}x, post-processing {} quality {} (NRD-FSR {}x), latitude {} degrees, solar longitude {} degrees, sun {} EV, stars {} EV, block lights {} EV, final exposure {} EV, Oklab DRT overexposure {}, curve exponent {}, auto-exposure compensation {}, default roughness {}",
                pathTracingEnabled ? "enabled" : "disabled",
                realtimeIntegrator.id(),
                performanceMaximumScatters,
                voxelTextureSurfaces ? "enabled" : "disabled",
                formatVoxelSurfaceStrength(voxelTextureSurfaceStrengthSteps),
                postProcessingMode.id(),
                quality.id(),
                FsrReconstructionProfile.forQuality(quality).upscaleRatio(),
                latitudeDegrees,
                solarLongitudeDegrees,
                formatEv(sunQuarterSteps),
                formatStarEv(starQuarterSteps),
                formatEv(blockLightQuarterSteps),
                formatFinalExposure(finalExposureQuarterSteps),
                formatOverexposure(oklabOverexposureSteps),
                formatCurveExponent(curveExponentSteps),
                formatAutoExposureCompensation(autoExposureCompensationSteps),
                formatRoughness(defaultRoughnessSteps));
    }

    public static void setFsrQualityMode(FsrQualityMode mode) {
        setReconstructionQualityMode(ReconstructionQualityMode.fromId(mode.id()));
    }

    public static PrimeSettings settings() {
        return settings;
    }

    public static RendererSettings rendererSettings() {
        PrimeSettings current = settings;
        long revision = rendererRevision;
        return rendererSettings(current, revision);
    }

    static RendererSettings rendererSettings(PrimeSettings current, long revision) {
        return new RendererSettings(
                current.pathTracingEnabled(),
                current.realtimeIntegrator(),
                current.performanceMaximumScatters(),
                current.voxelTextureSurfaces(),
                current.voxelTextureSurfaceStrengthSteps(),
                current.postProcessingMode(),
                current.reconstructionQuality(),
                current.astronomy(),
                current.lighting(),
                current.material(),
                current.display(),
                revision);
    }

    public static void setPathTracingEnabled(boolean enabled) {
        update(settings.withPathTracingEnabled(enabled));
    }

    public static void setRealtimeIntegrator(RealtimeIntegratorMode mode) {
        update(settings.withRealtimeIntegrator(mode));
    }

    public static void setPerformanceMaximumScatters(int value) {
        update(settings.withPerformanceMaximumScatters(value));
    }

    public static void setVoxelTextureSurfaces(boolean enabled) {
        update(settings.withVoxelTextureSurfaces(enabled));
    }

    public static void setVoxelTextureSurfaceStrengthSteps(int steps) {
        update(settings.withVoxelTextureSurfaceStrengthSteps(steps));
    }

    public static void setPostProcessingMode(PostProcessingMode mode) {
        update(settings.withPostProcessingMode(mode));
    }

    public static void setReconstructionQualityMode(ReconstructionQualityMode mode) {
        update(settings.withReconstructionQuality(mode));
    }

    public static void setLatitudeDegrees(int degrees) {
        update(settings.withLatitudeDegrees(degrees));
    }

    public static void setSolarLongitudeDegrees(int degrees) {
        update(settings.withSolarLongitudeDegrees(degrees));
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

    public static void setFinalExposureQuarterSteps(int quarterSteps) {
        update(settings.withFinalExposureQuarterSteps(quarterSteps));
    }

    public static void setOklabOverexposureSteps(int steps) {
        update(settings.withOklabOverexposureSteps(steps));
    }

    public static void setCurveExponentSteps(int steps) {
        update(settings.withCurveExponentSteps(steps));
    }

    public static void setAutoExposureCompensationSteps(int steps) {
        update(settings.withAutoExposureCompensationSteps(steps));
    }

    public static void setDefaultRoughnessSteps(int steps) {
        update(settings.withDefaultRoughnessSteps(steps));
    }

    public static void restoreDefaults() {
        update(restoredDefaults(settings));
    }

    static PrimeSettings restoredDefaults(PrimeSettings current) {
        return current
                .withPathTracingEnabled(true)
                .withRealtimeIntegrator(RealtimeIntegratorMode.DEFAULT)
                .withPerformanceMaximumScatters(
                        PerformanceIntegratorSettings.DEFAULT_SCATTERS)
                .withVoxelTextureSurfaces(false)
                .withVoxelTextureSurfaceStrengthSteps(VoxelSurfaceSettings.DEFAULT_STEPS)
                .withPostProcessingMode(PostProcessingMode.DEFAULT)
                .withReconstructionQuality(ReconstructionQualityMode.DEFAULT)
                .withLatitudeDegrees(AstronomySettings.DEFAULT_LATITUDE_DEGREES)
                .withSolarLongitudeDegrees(AstronomySettings.DEFAULT_SOLAR_LONGITUDE_DEGREES)
                .withSunQuarterSteps(LightingSettings.DEFAULT_SUN_QUARTER_STEPS)
                .withStarQuarterSteps(LightingSettings.DEFAULT_STAR_QUARTER_STEPS)
                .withBlockLightQuarterSteps(LightingSettings.DEFAULT_BLOCK_LIGHT_QUARTER_STEPS)
                .withFinalExposureQuarterSteps(
                        DisplaySettings.DEFAULT_FINAL_EXPOSURE_QUARTER_STEPS)
                .withOklabOverexposureSteps(DisplaySettings.DEFAULT_OVEREXPOSURE_STEPS)
                .withCurveExponentSteps(DisplaySettings.DEFAULT_CURVE_EXPONENT_STEPS)
                .withAutoExposureCompensationSteps(
                        DisplaySettings.DEFAULT_AUTO_EXPOSURE_COMPENSATION_STEPS)
                .withDefaultRoughnessSteps(MaterialSettings.DEFAULT_ROUGHNESS_STEPS);
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
            PrimeInfo.LOGGER.error("Could not save Prime settings to {}", path, exception);
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
                    + INTEGRATOR_KEY + "=" + current.realtimeIntegrator().id() + "\n"
                    + PERFORMANCE_MAXIMUM_SCATTERS_KEY + "="
                    + current.performanceMaximumScatters() + "\n"
                    + VOXEL_TEXTURE_SURFACES_KEY + "="
                    + current.voxelTextureSurfaces() + "\n"
                    + VOXEL_TEXTURE_SURFACE_STRENGTH_KEY + "="
                    + formatVoxelSurfaceStrength(
                            current.voxelTextureSurfaceStrengthSteps()) + "\n"
                    + MODE_KEY + "=" + current.postProcessingMode().id() + "\n"
                    + QUALITY_KEY + "=" + current.reconstructionQuality().id() + "\n"
                    + LATITUDE_DEGREES_KEY + "="
                    + current.astronomy().latitudeDegrees() + "\n"
                    + SOLAR_LONGITUDE_DEGREES_KEY + "="
                    + current.astronomy().solarLongitudeDegrees() + "\n"
                    + SUN_EV_KEY + "=" + formatEv(current.sunQuarterSteps()) + "\n"
                    + STAR_EV_KEY + "=" + formatStarEv(current.starQuarterSteps()) + "\n"
                    + BLOCK_LIGHT_EV_KEY + "="
                    + formatEv(current.blockLightQuarterSteps()) + "\n"
                    + FINAL_EXPOSURE_EV_KEY + "="
                    + formatFinalExposure(current.finalExposureQuarterSteps()) + "\n"
                    + OKLAB_OVEREXPOSURE_KEY + "="
                    + formatOverexposure(current.oklabOverexposureSteps()) + "\n"
                    + OKLAB_CURVE_EXPONENT_KEY + "="
                    + formatCurveExponent(current.curveExponentSteps()) + "\n"
                    + AUTO_EXPOSURE_COMPENSATION_KEY + "="
                    + formatAutoExposureCompensation(
                            current.autoExposureCompensationSteps()) + "\n"
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

    static int parseLatitudeDegrees(String value) {
        try {
            int degrees = Integer.parseInt(value);
            return new AstronomySettings(
                    degrees,
                    AstronomySettings.DEFAULT_SOLAR_LONGITUDE_DEGREES)
                    .latitudeDegrees();
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Observer latitude must be an integer degree", exception);
        }
    }

    static int parseSolarLongitudeDegrees(String value) {
        try {
            int degrees = Integer.parseInt(value);
            return new AstronomySettings(
                    AstronomySettings.DEFAULT_LATITUDE_DEGREES,
                    degrees)
                    .solarLongitudeDegrees();
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Solar longitude must be an integer degree", exception);
        }
    }

    static AstronomyLoad parseAstronomy(Properties properties) {
        int latitudeDegrees = AstronomySettings.DEFAULT_LATITUDE_DEGREES;
        int solarLongitudeDegrees =
                AstronomySettings.DEFAULT_SOLAR_LONGITUDE_DEGREES;
        boolean rewriteNeeded = false;
        String latitude = properties.getProperty(LATITUDE_DEGREES_KEY);
        if (latitude != null) {
            try {
                latitudeDegrees = parseLatitudeDegrees(latitude);
            } catch (IllegalArgumentException exception) {
                PrimeInfo.LOGGER.warn(
                        "Invalid Prime observer latitude '{}'; using {} degrees north",
                        latitude,
                        AstronomySettings.DEFAULT_LATITUDE_DEGREES);
                rewriteNeeded = true;
            }
        } else {
            rewriteNeeded = true;
        }
        String solarLongitude =
                properties.getProperty(SOLAR_LONGITUDE_DEGREES_KEY);
        if (solarLongitude != null) {
            try {
                solarLongitudeDegrees =
                        parseSolarLongitudeDegrees(solarLongitude);
            } catch (IllegalArgumentException exception) {
                PrimeInfo.LOGGER.warn(
                        "Invalid Prime solar longitude '{}'; using the March equinox",
                        solarLongitude);
                rewriteNeeded = true;
            }
        } else {
            rewriteNeeded = true;
        }
        return new AstronomyLoad(
                new AstronomySettings(
                        latitudeDegrees,
                        solarLongitudeDegrees),
                rewriteNeeded);
    }

    record AstronomyLoad(
            AstronomySettings settings,
            boolean rewriteNeeded) {
    }

    private static void update(PrimeSettings replacement) {
        if (replacement != settings) {
            settings = replacement;
            rendererRevision = Math.incrementExact(rendererRevision);
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

    static int parseVoxelSurfaceStrengthSteps(String value) {
        try {
            int steps = new BigDecimal(value)
                    .multiply(BigDecimal.valueOf(VoxelSurfaceSettings.STEPS_PER_UNIT))
                    .intValueExact();
            VoxelSurfaceSettings.maximumHeight(steps);
            return steps;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Voxel-surface strength must be an exact 0.01 step",
                    exception);
        }
    }

    static int parsePerformanceMaximumScatters(String value) {
        try {
            return PerformanceIntegratorSettings.validateScatters(
                    Integer.parseInt(value));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Performance bounce limit must be an integer", exception);
        }
    }

    static String formatVoxelSurfaceStrength(int steps) {
        VoxelSurfaceSettings.maximumHeight(steps);
        return BigDecimal.valueOf(steps)
                .divide(BigDecimal.valueOf(VoxelSurfaceSettings.STEPS_PER_UNIT))
                .toPlainString();
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

    static int parseFinalExposureQuarterSteps(String value) {
        try {
            int quarterSteps = new BigDecimal(value)
                    .multiply(BigDecimal.valueOf(DisplaySettings.QUARTER_STEPS_PER_EV))
                    .intValueExact();
            DisplaySettings.finalExposureMultiplier(quarterSteps);
            return quarterSteps;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Final exposure must be an exact 0.25-EV step",
                    exception);
        }
    }

    static String formatFinalExposure(int quarterSteps) {
        DisplaySettings.finalExposureMultiplier(quarterSteps);
        return BigDecimal.valueOf(quarterSteps)
                .divide(BigDecimal.valueOf(DisplaySettings.QUARTER_STEPS_PER_EV))
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

    static int parseCurveExponentSteps(String value) {
        try {
            int steps = parseHundredthSteps(value);
            DisplaySettings.curveExponent(steps);
            return steps;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Oklab DRT curve exponent must be an exact 0.01 step",
                    exception);
        }
    }

    static String formatCurveExponent(int steps) {
        DisplaySettings.curveExponent(steps);
        return formatHundredthSteps(steps);
    }

    static int parseAutoExposureCompensationSteps(String value) {
        try {
            int steps = parseHundredthSteps(value);
            DisplaySettings.autoExposureCompensation(steps);
            return steps;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Auto-exposure compensation must be an exact 0.01 step",
                    exception);
        }
    }

    static String formatAutoExposureCompensation(int steps) {
        DisplaySettings.autoExposureCompensation(steps);
        return formatHundredthSteps(steps);
    }

    private static int parseHundredthSteps(String value) {
        return new BigDecimal(value)
                .multiply(BigDecimal.valueOf(DisplaySettings.HUNDREDTH_STEPS_PER_UNIT))
                .intValueExact();
    }

    private static String formatHundredthSteps(int steps) {
        return BigDecimal.valueOf(steps)
                .divide(BigDecimal.valueOf(DisplaySettings.HUNDREDTH_STEPS_PER_UNIT))
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
