package dev.prime.config;

import dev.prime.render.AstronomySettings;
import dev.prime.render.DisplaySettings;
import dev.prime.render.LightingSettings;
import dev.prime.render.MaterialSettings;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.terrain.VoxelSurfaceSettings;
import java.util.Objects;

/** Immutable product settings and renderer revision markers owned by {@link PrimeConfig}. */
public record PrimeSettings(
        boolean pathTracingEnabled,
        boolean voxelTextureSurfaces,
        int voxelTextureSurfaceStrengthSteps,
        PostProcessingMode postProcessingMode,
        ReconstructionQualityMode reconstructionQuality,
        AstronomySettings astronomy,
        int sunQuarterSteps,
        int starQuarterSteps,
        int blockLightQuarterSteps,
        int finalExposureQuarterSteps,
        int oklabOverexposureSteps,
        int curveExponentSteps,
        int autoExposureCompensationSteps,
        int defaultRoughnessSteps,
        boolean seamlessGlass,
        boolean airGap,
        long lightingRevision,
        long materialRevision,
        boolean sharcEnabled) {
    public PrimeSettings {
        postProcessingMode = Objects.requireNonNull(postProcessingMode, "postProcessingMode");
        reconstructionQuality = Objects.requireNonNull(
                reconstructionQuality, "reconstructionQuality");
        astronomy = Objects.requireNonNull(astronomy, "astronomy");
        LightingSettings.linearMultiplier(sunQuarterSteps);
        LightingSettings.starLinearMultiplier(starQuarterSteps);
        LightingSettings.linearMultiplier(blockLightQuarterSteps);
        DisplaySettings.finalExposureMultiplier(finalExposureQuarterSteps);
        DisplaySettings.overexposure(oklabOverexposureSteps);
        DisplaySettings.curveExponent(curveExponentSteps);
        DisplaySettings.autoExposureCompensation(autoExposureCompensationSteps);
        MaterialSettings.linearRoughness(defaultRoughnessSteps);
        VoxelSurfaceSettings.maximumHeight(voxelTextureSurfaceStrengthSteps);
        if (lightingRevision < 0L || materialRevision < 0L) {
            throw new IllegalArgumentException("Prime setting revisions must not be negative");
        }
    }

    public static PrimeSettings defaults() {
        return new PrimeSettings(
                true,
                false,
                VoxelSurfaceSettings.DEFAULT_STEPS,
                PostProcessingMode.DEFAULT,
                ReconstructionQualityMode.DEFAULT,
                AstronomySettings.defaults(),
                LightingSettings.DEFAULT_SUN_QUARTER_STEPS,
                LightingSettings.DEFAULT_STAR_QUARTER_STEPS,
                LightingSettings.DEFAULT_BLOCK_LIGHT_QUARTER_STEPS,
                DisplaySettings.DEFAULT_FINAL_EXPOSURE_QUARTER_STEPS,
                DisplaySettings.DEFAULT_OVEREXPOSURE_STEPS,
                DisplaySettings.DEFAULT_CURVE_EXPONENT_STEPS,
                DisplaySettings.DEFAULT_AUTO_EXPOSURE_COMPENSATION_STEPS,
                MaterialSettings.DEFAULT_ROUGHNESS_STEPS,
                MaterialSettings.DEFAULT_SEAMLESS_GLASS,
                MaterialSettings.DEFAULT_AIR_GAP,
                0L,
                0L,
                true);
    }

    public PrimeSettings withPathTracingEnabled(boolean value) {
        return value == this.pathTracingEnabled
                ? this
                : new PrimeSettings(
                        value,
                        this.voxelTextureSurfaces,
                        this.voxelTextureSurfaceStrengthSteps,
                        this.postProcessingMode,
                        this.reconstructionQuality,
                        this.astronomy,
                        this.sunQuarterSteps,
                        this.starQuarterSteps,
                        this.blockLightQuarterSteps,
                        this.finalExposureQuarterSteps,
                        this.oklabOverexposureSteps,
                        this.curveExponentSteps,
                        this.autoExposureCompensationSteps,
                        this.defaultRoughnessSteps,
                        this.seamlessGlass,
                        this.airGap,
                        this.lightingRevision,
                        this.materialRevision,
                        this.sharcEnabled);
    }

    public PrimeSettings withSharcEnabled(boolean value) {
        return value == this.sharcEnabled
                ? this
                : new PrimeSettings(
                        this.pathTracingEnabled,
                        this.voxelTextureSurfaces,
                        this.voxelTextureSurfaceStrengthSteps,
                        this.postProcessingMode,
                        this.reconstructionQuality,
                        this.astronomy,
                        this.sunQuarterSteps,
                        this.starQuarterSteps,
                        this.blockLightQuarterSteps,
                        this.finalExposureQuarterSteps,
                        this.oklabOverexposureSteps,
                        this.curveExponentSteps,
                        this.autoExposureCompensationSteps,
                        this.defaultRoughnessSteps,
                        this.seamlessGlass,
                        this.airGap,
                        this.lightingRevision,
                        this.materialRevision,
                        value);
    }

    public PrimeSettings withVoxelTextureSurfaces(boolean value) {
        return value == this.voxelTextureSurfaces
                ? this
                : new PrimeSettings(
                        this.pathTracingEnabled,
                        value,
                        this.voxelTextureSurfaceStrengthSteps,
                        this.postProcessingMode,
                        this.reconstructionQuality,
                        this.astronomy,
                        this.sunQuarterSteps,
                        this.starQuarterSteps,
                        this.blockLightQuarterSteps,
                        this.finalExposureQuarterSteps,
                        this.oklabOverexposureSteps,
                        this.curveExponentSteps,
                        this.autoExposureCompensationSteps,
                        this.defaultRoughnessSteps,
                        this.seamlessGlass,
                        this.airGap,
                        this.lightingRevision,
                        this.materialRevision,
                        this.sharcEnabled);
    }

    public PrimeSettings withVoxelTextureSurfaceStrengthSteps(int value) {
        VoxelSurfaceSettings.maximumHeight(value);
        return value == this.voxelTextureSurfaceStrengthSteps
                ? this
                : new PrimeSettings(
                        this.pathTracingEnabled,
                        this.voxelTextureSurfaces,
                        value,
                        this.postProcessingMode,
                        this.reconstructionQuality,
                        this.astronomy,
                        this.sunQuarterSteps,
                        this.starQuarterSteps,
                        this.blockLightQuarterSteps,
                        this.finalExposureQuarterSteps,
                        this.oklabOverexposureSteps,
                        this.curveExponentSteps,
                        this.autoExposureCompensationSteps,
                        this.defaultRoughnessSteps,
                        this.seamlessGlass,
                        this.airGap,
                        this.lightingRevision,
                        this.materialRevision,
                        this.sharcEnabled);
    }

    public PrimeSettings withPostProcessingMode(PostProcessingMode value) {
        Objects.requireNonNull(value, "value");
        return value == this.postProcessingMode
                ? this
                : new PrimeSettings(
                        this.pathTracingEnabled,
                        this.voxelTextureSurfaces,
                        this.voxelTextureSurfaceStrengthSteps,
                        value,
                        this.reconstructionQuality,
                        this.astronomy,
                        this.sunQuarterSteps,
                        this.starQuarterSteps,
                        this.blockLightQuarterSteps,
                        this.finalExposureQuarterSteps,
                        this.oklabOverexposureSteps,
                        this.curveExponentSteps,
                        this.autoExposureCompensationSteps,
                        this.defaultRoughnessSteps,
                        this.seamlessGlass,
                        this.airGap,
                        this.lightingRevision,
                        this.materialRevision,
                        this.sharcEnabled);
    }

    public PrimeSettings withReconstructionQuality(ReconstructionQualityMode value) {
        Objects.requireNonNull(value, "value");
        return value == this.reconstructionQuality
                ? this
                : new PrimeSettings(
                        this.pathTracingEnabled,
                        this.voxelTextureSurfaces,
                        this.voxelTextureSurfaceStrengthSteps,
                        this.postProcessingMode,
                        value,
                        this.astronomy,
                        this.sunQuarterSteps,
                        this.starQuarterSteps,
                        this.blockLightQuarterSteps,
                        this.finalExposureQuarterSteps,
                        this.oklabOverexposureSteps,
                        this.curveExponentSteps,
                        this.autoExposureCompensationSteps,
                        this.defaultRoughnessSteps,
                        this.seamlessGlass,
                        this.airGap,
                        this.lightingRevision,
                        this.materialRevision,
                        this.sharcEnabled);
    }

    public PrimeSettings withLatitudeDegrees(int value) {
        AstronomySettings replacement = this.astronomy.withLatitudeDegrees(value);
        return replacement == this.astronomy
                ? this
                : new PrimeSettings(
                        this.pathTracingEnabled,
                        this.voxelTextureSurfaces,
                        this.voxelTextureSurfaceStrengthSteps,
                        this.postProcessingMode,
                        this.reconstructionQuality,
                        replacement,
                        this.sunQuarterSteps,
                        this.starQuarterSteps,
                        this.blockLightQuarterSteps,
                        this.finalExposureQuarterSteps,
                        this.oklabOverexposureSteps,
                        this.curveExponentSteps,
                        this.autoExposureCompensationSteps,
                        this.defaultRoughnessSteps,
                        this.seamlessGlass,
                        this.airGap,
                        Math.incrementExact(this.lightingRevision),
                        this.materialRevision,
                        this.sharcEnabled);
    }

    public PrimeSettings withSolarLongitudeDegrees(int value) {
        AstronomySettings replacement =
                this.astronomy.withSolarLongitudeDegrees(value);
        return replacement == this.astronomy
                ? this
                : new PrimeSettings(
                        this.pathTracingEnabled,
                        this.voxelTextureSurfaces,
                        this.voxelTextureSurfaceStrengthSteps,
                        this.postProcessingMode,
                        this.reconstructionQuality,
                        replacement,
                        this.sunQuarterSteps,
                        this.starQuarterSteps,
                        this.blockLightQuarterSteps,
                        this.finalExposureQuarterSteps,
                        this.oklabOverexposureSteps,
                        this.curveExponentSteps,
                        this.autoExposureCompensationSteps,
                        this.defaultRoughnessSteps,
                        this.seamlessGlass,
                        this.airGap,
                        Math.incrementExact(this.lightingRevision),
                        this.materialRevision,
                        this.sharcEnabled);
    }

    public PrimeSettings withSunQuarterSteps(int value) {
        LightingSettings.linearMultiplier(value);
        return value == this.sunQuarterSteps
                ? this
                : new PrimeSettings(
                        this.pathTracingEnabled,
                        this.voxelTextureSurfaces,
                        this.voxelTextureSurfaceStrengthSteps,
                        this.postProcessingMode,
                        this.reconstructionQuality,
                        this.astronomy,
                        value,
                        this.starQuarterSteps,
                        this.blockLightQuarterSteps,
                        this.finalExposureQuarterSteps,
                        this.oklabOverexposureSteps,
                        this.curveExponentSteps,
                        this.autoExposureCompensationSteps,
                        this.defaultRoughnessSteps,
                        this.seamlessGlass,
                        this.airGap,
                        Math.incrementExact(this.lightingRevision),
                        this.materialRevision,
                        this.sharcEnabled);
    }

    public PrimeSettings withStarQuarterSteps(int value) {
        LightingSettings.starLinearMultiplier(value);
        return value == this.starQuarterSteps
                ? this
                : new PrimeSettings(
                        this.pathTracingEnabled,
                        this.voxelTextureSurfaces,
                        this.voxelTextureSurfaceStrengthSteps,
                        this.postProcessingMode,
                        this.reconstructionQuality,
                        this.astronomy,
                        this.sunQuarterSteps,
                        value,
                        this.blockLightQuarterSteps,
                        this.finalExposureQuarterSteps,
                        this.oklabOverexposureSteps,
                        this.curveExponentSteps,
                        this.autoExposureCompensationSteps,
                        this.defaultRoughnessSteps,
                        this.seamlessGlass,
                        this.airGap,
                        Math.incrementExact(this.lightingRevision),
                        this.materialRevision,
                        this.sharcEnabled);
    }

    public PrimeSettings withBlockLightQuarterSteps(int value) {
        LightingSettings.linearMultiplier(value);
        return value == this.blockLightQuarterSteps
                ? this
                : new PrimeSettings(
                        this.pathTracingEnabled,
                        this.voxelTextureSurfaces,
                        this.voxelTextureSurfaceStrengthSteps,
                        this.postProcessingMode,
                        this.reconstructionQuality,
                        this.astronomy,
                        this.sunQuarterSteps,
                        this.starQuarterSteps,
                        value,
                        this.finalExposureQuarterSteps,
                        this.oklabOverexposureSteps,
                        this.curveExponentSteps,
                        this.autoExposureCompensationSteps,
                        this.defaultRoughnessSteps,
                        this.seamlessGlass,
                        this.airGap,
                        Math.incrementExact(this.lightingRevision),
                        this.materialRevision,
                        this.sharcEnabled);
    }

    public PrimeSettings withFinalExposureQuarterSteps(int value) {
        DisplaySettings.finalExposureMultiplier(value);
        return value == this.finalExposureQuarterSteps
                ? this
                : new PrimeSettings(
                        this.pathTracingEnabled,
                        this.voxelTextureSurfaces,
                        this.voxelTextureSurfaceStrengthSteps,
                        this.postProcessingMode,
                        this.reconstructionQuality,
                        this.astronomy,
                        this.sunQuarterSteps,
                        this.starQuarterSteps,
                        this.blockLightQuarterSteps,
                        value,
                        this.oklabOverexposureSteps,
                        this.curveExponentSteps,
                        this.autoExposureCompensationSteps,
                        this.defaultRoughnessSteps,
                        this.seamlessGlass,
                        this.airGap,
                        this.lightingRevision,
                        this.materialRevision,
                        this.sharcEnabled);
    }

    public PrimeSettings withOklabOverexposureSteps(int value) {
        DisplaySettings.overexposure(value);
        return value == this.oklabOverexposureSteps
                ? this
                : new PrimeSettings(
                        this.pathTracingEnabled,
                        this.voxelTextureSurfaces,
                        this.voxelTextureSurfaceStrengthSteps,
                        this.postProcessingMode,
                        this.reconstructionQuality,
                        this.astronomy,
                        this.sunQuarterSteps,
                        this.starQuarterSteps,
                        this.blockLightQuarterSteps,
                        this.finalExposureQuarterSteps,
                        value,
                        this.curveExponentSteps,
                        this.autoExposureCompensationSteps,
                        this.defaultRoughnessSteps,
                        this.seamlessGlass,
                        this.airGap,
                        this.lightingRevision,
                        this.materialRevision,
                        this.sharcEnabled);
    }

    public PrimeSettings withCurveExponentSteps(int value) {
        DisplaySettings.curveExponent(value);
        return value == this.curveExponentSteps
                ? this
                : new PrimeSettings(
                        this.pathTracingEnabled,
                        this.voxelTextureSurfaces,
                        this.voxelTextureSurfaceStrengthSteps,
                        this.postProcessingMode,
                        this.reconstructionQuality,
                        this.astronomy,
                        this.sunQuarterSteps,
                        this.starQuarterSteps,
                        this.blockLightQuarterSteps,
                        this.finalExposureQuarterSteps,
                        this.oklabOverexposureSteps,
                        value,
                        this.autoExposureCompensationSteps,
                        this.defaultRoughnessSteps,
                        this.seamlessGlass,
                        this.airGap,
                        this.lightingRevision,
                        this.materialRevision,
                        this.sharcEnabled);
    }

    public PrimeSettings withAutoExposureCompensationSteps(int value) {
        DisplaySettings.autoExposureCompensation(value);
        return value == this.autoExposureCompensationSteps
                ? this
                : new PrimeSettings(
                        this.pathTracingEnabled,
                        this.voxelTextureSurfaces,
                        this.voxelTextureSurfaceStrengthSteps,
                        this.postProcessingMode,
                        this.reconstructionQuality,
                        this.astronomy,
                        this.sunQuarterSteps,
                        this.starQuarterSteps,
                        this.blockLightQuarterSteps,
                        this.finalExposureQuarterSteps,
                        this.oklabOverexposureSteps,
                        this.curveExponentSteps,
                        value,
                        this.defaultRoughnessSteps,
                        this.seamlessGlass,
                        this.airGap,
                        this.lightingRevision,
                        this.materialRevision,
                        this.sharcEnabled);
    }

    public PrimeSettings withDefaultRoughnessSteps(int value) {
        MaterialSettings.linearRoughness(value);
        return value == this.defaultRoughnessSteps
                ? this
                : new PrimeSettings(
                        this.pathTracingEnabled,
                        this.voxelTextureSurfaces,
                        this.voxelTextureSurfaceStrengthSteps,
                        this.postProcessingMode,
                        this.reconstructionQuality,
                        this.astronomy,
                        this.sunQuarterSteps,
                        this.starQuarterSteps,
                        this.blockLightQuarterSteps,
                        this.finalExposureQuarterSteps,
                        this.oklabOverexposureSteps,
                        this.curveExponentSteps,
                        this.autoExposureCompensationSteps,
                        value,
                        this.seamlessGlass,
                        this.airGap,
                        this.lightingRevision,
                        Math.incrementExact(this.materialRevision),
                        this.sharcEnabled);
    }

    public PrimeSettings withSeamlessGlass(boolean value) {
        return value == this.seamlessGlass
                ? this
                : new PrimeSettings(
                        this.pathTracingEnabled,
                        this.voxelTextureSurfaces,
                        this.voxelTextureSurfaceStrengthSteps,
                        this.postProcessingMode,
                        this.reconstructionQuality,
                        this.astronomy,
                        this.sunQuarterSteps,
                        this.starQuarterSteps,
                        this.blockLightQuarterSteps,
                        this.finalExposureQuarterSteps,
                        this.oklabOverexposureSteps,
                        this.curveExponentSteps,
                        this.autoExposureCompensationSteps,
                        this.defaultRoughnessSteps,
                        value,
                        this.airGap,
                        this.lightingRevision,
                        Math.incrementExact(this.materialRevision),
                        this.sharcEnabled);
    }

    public PrimeSettings withAirGap(boolean value) {
        return value == this.airGap
                ? this
                : new PrimeSettings(
                        this.pathTracingEnabled,
                        this.voxelTextureSurfaces,
                        this.voxelTextureSurfaceStrengthSteps,
                        this.postProcessingMode,
                        this.reconstructionQuality,
                        this.astronomy,
                        this.sunQuarterSteps,
                        this.starQuarterSteps,
                        this.blockLightQuarterSteps,
                        this.finalExposureQuarterSteps,
                        this.oklabOverexposureSteps,
                        this.curveExponentSteps,
                        this.autoExposureCompensationSteps,
                        this.defaultRoughnessSteps,
                        this.seamlessGlass,
                        value,
                        this.lightingRevision,
                        Math.incrementExact(this.materialRevision),
                        this.sharcEnabled);
    }

    public LightingSettings.Snapshot lighting() {
        return new LightingSettings.Snapshot(
                this.sunQuarterSteps,
                this.starQuarterSteps,
                this.blockLightQuarterSteps,
                this.lightingRevision);
    }

    public MaterialSettings.Snapshot material() {
        return new MaterialSettings.Snapshot(
                this.defaultRoughnessSteps,
                this.seamlessGlass,
                this.airGap,
                this.materialRevision);
    }

    public DisplaySettings.Snapshot display() {
        return new DisplaySettings.Snapshot(
                this.finalExposureQuarterSteps,
                this.oklabOverexposureSteps,
                this.curveExponentSteps,
                this.autoExposureCompensationSteps);
    }
}
