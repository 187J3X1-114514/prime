package dev.prime.mixin;

import dev.prime.client.PrimeVideoOptions;
import dev.prime.config.PrimeConfig;
import dev.prime.render.AstronomySettings;
import dev.prime.render.DisplaySettings;
import dev.prime.render.LightingSettings;
import dev.prime.render.MaterialSettings;
import dev.prime.client.PrimeRuntime;
import dev.prime.render.RendererSettings;
import dev.prime.render.fsr.FsrDebugView;
import dev.prime.render.post.nrd.NrdDiagnostics;
import dev.prime.render.post.DlssRrDebugView;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.terrain.VoxelSurfaceSettings;
import java.net.URI;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds Prime's live controls to the vanilla Video Settings screen. */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin {
    private static final URI PRIME$REPOSITORY =
            URI.create("https://github.com/bWFuanVzYWth/prime");
    private static final Component PRIME$HEADER =
            Component.translatable("prime.options.header");
    private static final Component PRIME$RENDERING_HEADER =
            Component.translatable("prime.options.header.rendering");
    private static final Component PRIME$LIGHTING_HEADER =
            Component.translatable("prime.options.header.lighting");
    private static final Component PRIME$DISPLAY_HEADER =
            Component.translatable("prime.options.header.display");
    private static final Component PRIME$MATERIAL_HEADER =
            Component.translatable("prime.options.header.material");
    private static final Component PRIME$DIAGNOSTICS_HEADER =
            Component.translatable("prime.options.header.diagnostics");
    @Unique private OptionInstance<Boolean> prime$pathTracingEnabled;
    @Unique private OptionInstance<Boolean> prime$sharcEnabled;
    @Unique private OptionInstance<Boolean> prime$voxelTextureSurfaces;
    @Unique private OptionInstance<Integer> prime$voxelTextureSurfaceStrength;
    @Unique private OptionInstance<Boolean> prime$screenshotMode;
    @Unique private OptionInstance<PostProcessingMode> prime$postProcessingMode;
    @Unique private OptionInstance<ReconstructionQualityMode> prime$qualityMode;
    @Unique private OptionInstance<Integer> prime$latitude;
    @Unique private OptionInstance<Integer> prime$season;
    @Unique private OptionInstance<Integer> prime$sunExposure;
    @Unique private OptionInstance<Integer> prime$starExposure;
    @Unique private OptionInstance<Integer> prime$blockLightExposure;
    @Unique private OptionInstance<Integer> prime$finalExposure;
    @Unique private OptionInstance<Integer> prime$oklabOverexposure;
    @Unique private OptionInstance<Integer> prime$curveExponent;
    @Unique private OptionInstance<Integer> prime$autoExposureCompensation;
    @Unique private OptionInstance<Integer> prime$defaultRoughness;
    @Unique private OptionInstance<Boolean> prime$seamlessGlass;
    @Unique private OptionInstance<Boolean> prime$triangleDebug;
    @Unique private OptionInstance<Boolean> prime$rendererDiagnostics;
    @Unique private OptionInstance<NrdDiagnostics.Mode> prime$nrdDebugView;
    @Unique private OptionInstance<FsrDebugView> prime$fsrDebugView;
    @Unique private OptionInstance<DlssRrDebugView> prime$rrDebugView;
    @Unique private OptionInstance<Boolean> prime$rrDebugFullscreen;

    @Inject(method = "addOptions", at = @At("TAIL"))
    private void prime$addOptions(CallbackInfo callbackInfo) {
        OptionsList list = ((OptionsSubScreenAccessor) this).prime$getList();
        if (list != null) {
            this.prime$pathTracingEnabled = PrimeVideoOptions.pathTracingEnabled();
            this.prime$sharcEnabled = PrimeVideoOptions.sharcEnabled();
            this.prime$voxelTextureSurfaces = PrimeVideoOptions.voxelTextureSurfaces();
            this.prime$voxelTextureSurfaceStrength =
                    PrimeVideoOptions.voxelTextureSurfaceStrength();
            this.prime$screenshotMode = PrimeVideoOptions.screenshotMode();
            this.prime$postProcessingMode = PrimeVideoOptions.postProcessingMode();
            this.prime$qualityMode = PrimeVideoOptions.qualityMode();
            this.prime$latitude = PrimeVideoOptions.latitude();
            this.prime$season = PrimeVideoOptions.season();
            this.prime$sunExposure = PrimeVideoOptions.sunExposure();
            this.prime$starExposure = PrimeVideoOptions.starExposure();
            this.prime$blockLightExposure = PrimeVideoOptions.blockLightExposure();
            this.prime$finalExposure = PrimeVideoOptions.finalExposure();
            this.prime$oklabOverexposure = PrimeVideoOptions.oklabOverexposure();
            this.prime$curveExponent = PrimeVideoOptions.curveExponent();
            this.prime$autoExposureCompensation =
                    PrimeVideoOptions.autoExposureCompensation();
            this.prime$defaultRoughness = PrimeVideoOptions.defaultRoughness();
            this.prime$seamlessGlass = PrimeVideoOptions.seamlessGlass();
            this.prime$triangleDebug = PrimeVideoOptions.triangleDebug();
            this.prime$rendererDiagnostics = PrimeVideoOptions.rendererDiagnostics();
            this.prime$nrdDebugView = PrimeVideoOptions.nrdDebugView();
            this.prime$fsrDebugView = PrimeVideoOptions.fsrDebugView();
            this.prime$rrDebugView = PrimeVideoOptions.dlssRrDebugView();
            this.prime$rrDebugFullscreen = PrimeVideoOptions.dlssRrDebugFullscreen();
            list.addHeader(PRIME$HEADER);
            list.addBig(Button.builder(
                            Component.translatable("prime.options.restore_defaults"),
                            button -> this.prime$restoreDefaults())
                    .build());
            list.addHeader(PRIME$RENDERING_HEADER);
            list.addBig(this.prime$pathTracingEnabled);
            list.addBig(this.prime$sharcEnabled);
            list.addSmall(
                    this.prime$voxelTextureSurfaces,
                    this.prime$voxelTextureSurfaceStrength);
            list.addBig(this.prime$screenshotMode);
            list.addSmall(this.prime$postProcessingMode, this.prime$qualityMode);
            list.addHeader(PRIME$LIGHTING_HEADER);
            list.addSmall(this.prime$latitude, this.prime$season);
            list.addBig(this.prime$sunExposure);
            list.addBig(this.prime$starExposure);
            list.addBig(this.prime$blockLightExposure);
            list.addHeader(PRIME$DISPLAY_HEADER);
            list.addBig(this.prime$autoExposureCompensation);
            list.addBig(this.prime$finalExposure);
            list.addSmall(
                    this.prime$oklabOverexposure,
                    this.prime$curveExponent);
            list.addHeader(PRIME$MATERIAL_HEADER);
            list.addSmall(this.prime$defaultRoughness, this.prime$seamlessGlass);
            list.addHeader(PRIME$DIAGNOSTICS_HEADER);
            list.addBig(this.prime$triangleDebug);
            list.addBig(this.prime$rendererDiagnostics);
            list.addSmall(this.prime$nrdDebugView, this.prime$fsrDebugView);
            list.addSmall(this.prime$rrDebugView, this.prime$rrDebugFullscreen);
            list.addBig(Button.builder(
                            Component.translatable("prime.options.open_repository"),
                            ConfirmLinkScreen.confirmLink(
                                    (VideoSettingsScreen) (Object) this,
                                    PRIME$REPOSITORY))
                    .build());
        }
    }

    @Unique
    private void prime$restoreDefaults() {
        RendererSettings previous = PrimeConfig.rendererSettings();
        PrimeConfig.restoreDefaults();
        RendererSettings current = PrimeConfig.rendererSettings();
        PrimeRuntime runtime = PrimeRuntime.instance();
        runtime.restoreSessionDefaults();
        if (previous.pathTracingEnabled() != current.pathTracingEnabled()) {
            runtime.pathTracingChanged(current.pathTracingEnabled());
        }
        if (previous.voxelTextureSurfaces() != current.voxelTextureSurfaces()) {
            runtime.voxelTextureSurfacesChanged(
                    current.voxelTextureSurfaces());
        } else if (previous.voxelTextureSurfaceStrengthSteps()
                != current.voxelTextureSurfaceStrengthSteps()) {
            runtime.voxelTextureSurfaceStrengthChanged(
                    current.voxelTextureSurfaces(),
                    current.voxelTextureSurfaceStrengthSteps());
        }
        this.prime$refresh(this.prime$pathTracingEnabled, true);
        this.prime$refresh(this.prime$sharcEnabled, true);
        this.prime$refresh(this.prime$voxelTextureSurfaces, false);
        this.prime$refresh(
                this.prime$voxelTextureSurfaceStrength,
                VoxelSurfaceSettings.DEFAULT_STEPS);
        this.prime$refresh(this.prime$screenshotMode, false);
        this.prime$refresh(this.prime$postProcessingMode, PostProcessingMode.DEFAULT);
        this.prime$refresh(this.prime$qualityMode, ReconstructionQualityMode.DEFAULT);
        this.prime$refresh(
                this.prime$latitude,
                AstronomySettings.DEFAULT_LATITUDE_DEGREES);
        this.prime$refresh(
                this.prime$season,
                AstronomySettings.DEFAULT_SOLAR_LONGITUDE_DEGREES);
        this.prime$refresh(
                this.prime$sunExposure,
                LightingSettings.DEFAULT_SUN_QUARTER_STEPS);
        this.prime$refresh(
                this.prime$starExposure,
                LightingSettings.DEFAULT_STAR_QUARTER_STEPS);
        this.prime$refresh(
                this.prime$blockLightExposure,
                LightingSettings.DEFAULT_BLOCK_LIGHT_QUARTER_STEPS);
        this.prime$refresh(
                this.prime$finalExposure,
                DisplaySettings.DEFAULT_FINAL_EXPOSURE_QUARTER_STEPS);
        this.prime$refresh(
                this.prime$oklabOverexposure,
                DisplaySettings.DEFAULT_OVEREXPOSURE_STEPS);
        this.prime$refresh(
                this.prime$curveExponent,
                DisplaySettings.DEFAULT_CURVE_EXPONENT_STEPS);
        this.prime$refresh(
                this.prime$autoExposureCompensation,
                DisplaySettings.DEFAULT_AUTO_EXPOSURE_COMPENSATION_STEPS);
        this.prime$refresh(
                this.prime$defaultRoughness,
                MaterialSettings.DEFAULT_ROUGHNESS_STEPS);
        this.prime$refresh(
                this.prime$seamlessGlass,
                MaterialSettings.DEFAULT_SEAMLESS_GLASS);
        this.prime$refresh(this.prime$triangleDebug, false);
        this.prime$refresh(this.prime$rendererDiagnostics, false);
        this.prime$refresh(this.prime$nrdDebugView, NrdDiagnostics.Mode.OFF);
        this.prime$refresh(this.prime$fsrDebugView, FsrDebugView.OFF);
        this.prime$refresh(this.prime$rrDebugView, DlssRrDebugView.OFF);
        this.prime$refresh(this.prime$rrDebugFullscreen, false);
    }

    @Unique
    @SuppressWarnings("unchecked")
    private <T> void prime$refresh(OptionInstance<T> option, T value) {
        option.set(value);
        OptionsList list = ((OptionsSubScreenAccessor) this).prime$getList();
        if (list == null) {
            return;
        }
        AbstractWidget widget = list.findOption(option);
        if (widget instanceof CycleButton<?> cycleButton) {
            ((CycleButton<T>) cycleButton).setValue(value);
        } else {
            ((VideoSettingsScreen) (Object) this).resetOption(option);
        }
    }

    @Inject(method = "removed", at = @At("TAIL"))
    private void prime$saveOptions(CallbackInfo callbackInfo) {
        PrimeConfig.save();
    }
}
