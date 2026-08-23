package dev.prime.config;

import dev.prime.infrastructure.PrimeInfo;
import dev.prime.render.AstronomySettings;
import dev.prime.render.DisplaySettings;
import dev.prime.render.HdrOutput;
import dev.prime.render.LightingSettings;
import dev.prime.render.MaterialSettings;
import dev.prime.render.PrimaryChainSettings;
import dev.prime.render.RendererSettings;
import dev.prime.render.ScatterSettings;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.terrain.TerrainWorkerSettings;
import dev.prime.render.terrain.VoxelSurfaceSettings;
import java.io.IOException;
import java.nio.file.Path;

/** Client-thread owner of Prime's live settings and renderer revision. */
public final class PrimeConfig {
    // Fabric initializes and mutates video options on the client thread. One immutable snapshot
    // keeps every renderer read coherent without a shared lock or independently mutable globals.
    private static PrimeSettings settings = PrimeSettings.defaults();
    private static int scatterCount = ScatterSettings.DEFAULT_COUNT;
    private static int primaryChainLimit = PrimaryChainSettings.DEFAULT_LIMIT;
    private static int terrainWorkerPercentage = TerrainWorkerSettings.DEFAULT_PERCENTAGE;
    private static boolean hdrEnabled;
    private static int referenceWhiteNits = HdrOutput.AUTOMATIC_REFERENCE_WHITE_NITS;
    private static long rendererRevision;
    private static boolean dirty;

    private PrimeConfig() {
    }

    public static void load() {
        Path path = PrimeConfigFile.path();
        PrimeConfigData loaded = PrimeConfigData.defaults();
        boolean rewriteNeeded = false;
        if (PrimeConfigFile.exists(path)) {
            try {
                PrimeConfigCodec.DecodeResult decoded =
                        PrimeConfigCodec.decode(PrimeConfigFile.read(path));
                loaded = decoded.data();
                rewriteNeeded = decoded.rewriteNeeded();
            } catch (IOException | IllegalArgumentException exception) {
                PrimeInfo.LOGGER.warn(
                        "Could not read {}; using the default Prime settings",
                        path,
                        exception);
                rewriteNeeded = true;
            }
        }
        applyLoaded(loaded, rewriteNeeded);
        PrimeConfigCodec.log(loaded);
    }

    private static void applyLoaded(PrimeConfigData loaded, boolean rewriteNeeded) {
        settings = loaded.settings();
        scatterCount = loaded.scatterCount();
        primaryChainLimit = loaded.primaryChainLimit();
        terrainWorkerPercentage = loaded.terrainWorkerPercentage();
        hdrEnabled = loaded.hdrEnabled();
        HdrOutput.setRequested(hdrEnabled);
        referenceWhiteNits = loaded.referenceWhiteNits();
        HdrOutput.setReferenceWhiteNits(referenceWhiteNits);
        rendererRevision = 0L;
        dirty = rewriteNeeded;
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
                current.sharcEnabled(),
                current.voxelTextureSurfaces(),
                current.voxelTextureSurfaceStrengthSteps(),
                current.postProcessingMode(),
                current.reconstructionQuality(),
                current.astronomy(),
                current.lighting(),
                current.material(),
                current.display(),
                scatterCount,
                primaryChainLimit,
                terrainWorkerPercentage,
                revision);
    }

    public static void setPathTracingEnabled(boolean enabled) {
        update(settings.withPathTracingEnabled(enabled));
    }

    public static void setSharcEnabled(boolean enabled) {
        update(settings.withSharcEnabled(enabled));
    }

    public static int scatterCount() {
        return scatterCount;
    }

    public static void setScatterCount(int count) {
        int replacement = ScatterSettings.validateCount(count);
        if (replacement != scatterCount) {
            scatterCount = replacement;
            rendererRevision = Math.incrementExact(rendererRevision);
            dirty = true;
        }
    }

    public static int primaryChainLimit() {
        return primaryChainLimit;
    }

    public static void setPrimaryChainLimit(int limit) {
        int replacement = PrimaryChainSettings.validateLimit(limit);
        if (replacement != primaryChainLimit) {
            primaryChainLimit = replacement;
            rendererRevision = Math.incrementExact(rendererRevision);
            dirty = true;
        }
    }

    public static int terrainWorkerPercentage() {
        return terrainWorkerPercentage;
    }

    public static boolean hdrEnabled() {
        return hdrEnabled;
    }

    public static void setHdrEnabled(boolean enabled) {
        if (enabled != hdrEnabled) {
            hdrEnabled = enabled;
            HdrOutput.setRequested(enabled);
            dirty = true;
        }
    }

    public static int referenceWhiteNits() {
        return referenceWhiteNits;
    }

    public static void setReferenceWhiteNits(int value) {
        int replacement = HdrOutput.validateReferenceWhiteNits(value);
        if (replacement != referenceWhiteNits) {
            referenceWhiteNits = replacement;
            HdrOutput.setReferenceWhiteNits(replacement);
            dirty = true;
        }
    }

    public static void setTerrainWorkerPercentage(int percentage) {
        int replacement = TerrainWorkerSettings.validatePercentage(percentage);
        if (replacement != terrainWorkerPercentage) {
            terrainWorkerPercentage = replacement;
            dirty = true;
        }
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

    public static void setAutoExposureCompensationSteps(int steps) {
        update(settings.withAutoExposureCompensationSteps(steps));
    }

    public static void setDefaultRoughnessSteps(int steps) {
        update(settings.withDefaultRoughnessSteps(steps));
    }

    public static void setSeamlessGlass(boolean enabled) {
        update(settings.withSeamlessGlass(enabled));
    }

    public static void setAirGap(boolean enabled) {
        update(settings.withAirGap(enabled));
    }

    public static void setVanillaPbrPresets(boolean enabled) {
        update(settings.withVanillaPbrPresets(enabled));
    }

    public static void restoreDefaults() {
        update(restoredDefaults(settings));
        setScatterCount(ScatterSettings.DEFAULT_COUNT);
        setPrimaryChainLimit(PrimaryChainSettings.DEFAULT_LIMIT);
        setTerrainWorkerPercentage(TerrainWorkerSettings.DEFAULT_PERCENTAGE);
        setHdrEnabled(false);
        setReferenceWhiteNits(HdrOutput.AUTOMATIC_REFERENCE_WHITE_NITS);
    }

    static PrimeSettings restoredDefaults(PrimeSettings current) {
        return current
                .withPathTracingEnabled(true)
                .withSharcEnabled(true)
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
                .withAutoExposureCompensationSteps(
                        DisplaySettings.DEFAULT_AUTO_EXPOSURE_COMPENSATION_STEPS)
                .withDefaultRoughnessSteps(MaterialSettings.DEFAULT_ROUGHNESS_STEPS)
                .withSeamlessGlass(MaterialSettings.DEFAULT_SEAMLESS_GLASS)
                .withAirGap(MaterialSettings.DEFAULT_AIR_GAP)
                .withVanillaPbrPresets(MaterialSettings.DEFAULT_VANILLA_PBR_PRESETS);
    }

    public static void save() {
        Path path = PrimeConfigFile.path();
        if (!dirty && PrimeConfigFile.exists(path)) {
            return;
        }
        try {
            PrimeConfigFile.write(path, serializedContents());
            dirty = false;
        } catch (IOException exception) {
            PrimeInfo.LOGGER.error("Could not save Prime settings to {}", path, exception);
        }
    }

    static String serializedContents() {
        return PrimeConfigCodec.encode(currentData());
    }

    private static PrimeConfigData currentData() {
        return new PrimeConfigData(
                settings,
                scatterCount,
                primaryChainLimit,
                terrainWorkerPercentage,
                hdrEnabled,
                referenceWhiteNits);
    }

    private static void update(PrimeSettings replacement) {
        if (replacement != settings) {
            settings = replacement;
            rendererRevision = Math.incrementExact(rendererRevision);
            dirty = true;
        }
    }
}
