package dev.prime.config;

import dev.prime.render.AstronomySettings;
import dev.prime.render.DisplaySettings;
import dev.prime.render.LightingSettings;
import dev.prime.render.MaterialSettings;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.terrain.VoxelSurfaceSettings;
import java.util.Objects;

/** Immutable product settings grouped by renderer concern. */
public record PrimeSettings(
        boolean pathTracingEnabled,
        boolean sharcEnabled,
        boolean voxelTextureSurfaces,
        int voxelTextureSurfaceStrengthSteps,
        PostProcessingMode postProcessingMode,
        ReconstructionQualityMode reconstructionQuality,
        AstronomySettings astronomy,
        LightingSettings.Snapshot lighting,
        DisplaySettings.Snapshot display,
        MaterialSettings.Snapshot material) {
    public PrimeSettings {
        postProcessingMode = Objects.requireNonNull(
                postProcessingMode, "postProcessingMode");
        if (postProcessingMode == PostProcessingMode.DISABLED) {
            throw new IllegalArgumentException(
                    "Raw output is a non-persistent session diagnostic");
        }
        reconstructionQuality = Objects.requireNonNull(
                reconstructionQuality, "reconstructionQuality");
        astronomy = Objects.requireNonNull(astronomy, "astronomy");
        lighting = Objects.requireNonNull(lighting, "lighting");
        display = Objects.requireNonNull(display, "display");
        material = Objects.requireNonNull(material, "material");
        VoxelSurfaceSettings.maximumHeight(voxelTextureSurfaceStrengthSteps);
    }

    public static PrimeSettings defaults() {
        return new PrimeSettings(
                true,
                true,
                false,
                VoxelSurfaceSettings.DEFAULT_STEPS,
                PostProcessingMode.DEFAULT,
                ReconstructionQualityMode.DEFAULT,
                AstronomySettings.defaults(),
                new LightingSettings.Snapshot(
                        LightingSettings.DEFAULT_SUN_QUARTER_STEPS,
                        LightingSettings.DEFAULT_STAR_QUARTER_STEPS,
                        LightingSettings.DEFAULT_BLOCK_LIGHT_QUARTER_STEPS,
                        0L),
                new DisplaySettings.Snapshot(
                        DisplaySettings.DEFAULT_FINAL_EXPOSURE_QUARTER_STEPS,
                        DisplaySettings.DEFAULT_AUTO_EXPOSURE_COMPENSATION_STEPS),
                new MaterialSettings.Snapshot(
                        MaterialSettings.DEFAULT_ROUGHNESS_STEPS,
                        MaterialSettings.DEFAULT_SEAMLESS_GLASS,
                        MaterialSettings.DEFAULT_AIR_GAP,
                        MaterialSettings.DEFAULT_VANILLA_PBR_PRESETS,
                        0L));
    }

    public PrimeSettings withPathTracingEnabled(boolean value) {
        return value == this.pathTracingEnabled
                ? this
                : new PrimeSettings(
                        value,
                        this.sharcEnabled,
                        this.voxelTextureSurfaces,
                        this.voxelTextureSurfaceStrengthSteps,
                        this.postProcessingMode,
                        this.reconstructionQuality,
                        this.astronomy,
                        this.lighting,
                        this.display,
                        this.material);
    }

    public PrimeSettings withSharcEnabled(boolean value) {
        return value == this.sharcEnabled
                ? this
                : new PrimeSettings(
                        this.pathTracingEnabled,
                        value,
                        this.voxelTextureSurfaces,
                        this.voxelTextureSurfaceStrengthSteps,
                        this.postProcessingMode,
                        this.reconstructionQuality,
                        this.astronomy,
                        this.lighting,
                        this.display,
                        this.material);
    }

    public PrimeSettings withVoxelTextureSurfaces(boolean value) {
        return value == this.voxelTextureSurfaces
                ? this
                : new PrimeSettings(
                        this.pathTracingEnabled,
                        this.sharcEnabled,
                        value,
                        this.voxelTextureSurfaceStrengthSteps,
                        this.postProcessingMode,
                        this.reconstructionQuality,
                        this.astronomy,
                        this.lighting,
                        this.display,
                        this.material);
    }

    public PrimeSettings withVoxelTextureSurfaceStrengthSteps(int value) {
        VoxelSurfaceSettings.maximumHeight(value);
        return value == this.voxelTextureSurfaceStrengthSteps
                ? this
                : new PrimeSettings(
                        this.pathTracingEnabled,
                        this.sharcEnabled,
                        this.voxelTextureSurfaces,
                        value,
                        this.postProcessingMode,
                        this.reconstructionQuality,
                        this.astronomy,
                        this.lighting,
                        this.display,
                        this.material);
    }

    public PrimeSettings withPostProcessingMode(PostProcessingMode value) {
        Objects.requireNonNull(value, "value");
        return value == this.postProcessingMode
                ? this
                : new PrimeSettings(
                        this.pathTracingEnabled,
                        this.sharcEnabled,
                        this.voxelTextureSurfaces,
                        this.voxelTextureSurfaceStrengthSteps,
                        value,
                        this.reconstructionQuality,
                        this.astronomy,
                        this.lighting,
                        this.display,
                        this.material);
    }

    public PrimeSettings withReconstructionQuality(ReconstructionQualityMode value) {
        Objects.requireNonNull(value, "value");
        return value == this.reconstructionQuality
                ? this
                : new PrimeSettings(
                        this.pathTracingEnabled,
                        this.sharcEnabled,
                        this.voxelTextureSurfaces,
                        this.voxelTextureSurfaceStrengthSteps,
                        this.postProcessingMode,
                        value,
                        this.astronomy,
                        this.lighting,
                        this.display,
                        this.material);
    }

    public PrimeSettings withLatitudeDegrees(int value) {
        return withAstronomy(this.astronomy.withLatitudeDegrees(value));
    }

    public PrimeSettings withSolarLongitudeDegrees(int value) {
        return withAstronomy(this.astronomy.withSolarLongitudeDegrees(value));
    }

    public PrimeSettings withSunQuarterSteps(int value) {
        LightingSettings.linearMultiplier(value);
        return value == sunQuarterSteps()
                ? this
                : withLighting(new LightingSettings.Snapshot(
                        value,
                        starQuarterSteps(),
                        blockLightQuarterSteps(),
                        Math.incrementExact(lightingRevision())));
    }

    public PrimeSettings withStarQuarterSteps(int value) {
        LightingSettings.starLinearMultiplier(value);
        return value == starQuarterSteps()
                ? this
                : withLighting(new LightingSettings.Snapshot(
                        sunQuarterSteps(),
                        value,
                        blockLightQuarterSteps(),
                        Math.incrementExact(lightingRevision())));
    }

    public PrimeSettings withBlockLightQuarterSteps(int value) {
        LightingSettings.linearMultiplier(value);
        return value == blockLightQuarterSteps()
                ? this
                : withLighting(new LightingSettings.Snapshot(
                        sunQuarterSteps(),
                        starQuarterSteps(),
                        value,
                        Math.incrementExact(lightingRevision())));
    }

    public PrimeSettings withFinalExposureQuarterSteps(int value) {
        return value == finalExposureQuarterSteps()
                ? this
                : withDisplay(new DisplaySettings.Snapshot(
                        value, autoExposureCompensationSteps()));
    }

    public PrimeSettings withAutoExposureCompensationSteps(int value) {
        return value == autoExposureCompensationSteps()
                ? this
                : withDisplay(new DisplaySettings.Snapshot(
                        finalExposureQuarterSteps(), value));
    }

    public PrimeSettings withDefaultRoughnessSteps(int value) {
        MaterialSettings.linearRoughness(value);
        return value == defaultRoughnessSteps()
                ? this
                : withMaterial(new MaterialSettings.Snapshot(
                        value,
                        seamlessGlass(),
                        airGap(),
                        vanillaPbrPresets(),
                        Math.incrementExact(materialRevision())));
    }

    public PrimeSettings withSeamlessGlass(boolean value) {
        return value == seamlessGlass()
                ? this
                : withMaterial(new MaterialSettings.Snapshot(
                        defaultRoughnessSteps(),
                        value,
                        airGap(),
                        vanillaPbrPresets(),
                        Math.incrementExact(materialRevision())));
    }

    public PrimeSettings withAirGap(boolean value) {
        return value == airGap()
                ? this
                : withMaterial(new MaterialSettings.Snapshot(
                        defaultRoughnessSteps(),
                        seamlessGlass(),
                        value,
                        vanillaPbrPresets(),
                        Math.incrementExact(materialRevision())));
    }

    public PrimeSettings withVanillaPbrPresets(boolean value) {
        return value == vanillaPbrPresets()
                ? this
                : withMaterial(new MaterialSettings.Snapshot(
                        defaultRoughnessSteps(),
                        seamlessGlass(),
                        airGap(),
                        value,
                        Math.incrementExact(materialRevision())));
    }

    public int sunQuarterSteps() {
        return this.lighting.sunQuarterSteps();
    }

    public int starQuarterSteps() {
        return this.lighting.starQuarterSteps();
    }

    public int blockLightQuarterSteps() {
        return this.lighting.blockLightQuarterSteps();
    }

    public long lightingRevision() {
        return this.lighting.revision();
    }

    public int finalExposureQuarterSteps() {
        return this.display.finalExposureQuarterSteps();
    }

    public int autoExposureCompensationSteps() {
        return this.display.autoExposureCompensationSteps();
    }

    public int defaultRoughnessSteps() {
        return this.material.roughnessSteps();
    }

    public boolean seamlessGlass() {
        return this.material.seamlessGlass();
    }

    public boolean airGap() {
        return this.material.airGap();
    }

    public boolean vanillaPbrPresets() {
        return this.material.vanillaPbrPresets();
    }

    public long materialRevision() {
        return this.material.revision();
    }

    private PrimeSettings withAstronomy(AstronomySettings value) {
        if (value == this.astronomy) {
            return this;
        }
        return new PrimeSettings(
                this.pathTracingEnabled,
                this.sharcEnabled,
                this.voxelTextureSurfaces,
                this.voxelTextureSurfaceStrengthSteps,
                this.postProcessingMode,
                this.reconstructionQuality,
                value,
                new LightingSettings.Snapshot(
                        sunQuarterSteps(),
                        starQuarterSteps(),
                        blockLightQuarterSteps(),
                        Math.incrementExact(lightingRevision())),
                this.display,
                this.material);
    }

    private PrimeSettings withLighting(LightingSettings.Snapshot value) {
        return new PrimeSettings(
                this.pathTracingEnabled,
                this.sharcEnabled,
                this.voxelTextureSurfaces,
                this.voxelTextureSurfaceStrengthSteps,
                this.postProcessingMode,
                this.reconstructionQuality,
                this.astronomy,
                value,
                this.display,
                this.material);
    }

    private PrimeSettings withDisplay(DisplaySettings.Snapshot value) {
        return new PrimeSettings(
                this.pathTracingEnabled,
                this.sharcEnabled,
                this.voxelTextureSurfaces,
                this.voxelTextureSurfaceStrengthSteps,
                this.postProcessingMode,
                this.reconstructionQuality,
                this.astronomy,
                this.lighting,
                value,
                this.material);
    }

    private PrimeSettings withMaterial(MaterialSettings.Snapshot value) {
        return new PrimeSettings(
                this.pathTracingEnabled,
                this.sharcEnabled,
                this.voxelTextureSurfaces,
                this.voxelTextureSurfaceStrengthSteps,
                this.postProcessingMode,
                this.reconstructionQuality,
                this.astronomy,
                this.lighting,
                this.display,
                value);
    }
}
