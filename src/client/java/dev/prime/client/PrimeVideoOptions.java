package dev.prime.client;

import com.mojang.serialization.Codec;
import dev.prime.config.PrimeConfig;
import dev.prime.render.AstronomySettings;
import dev.prime.render.DisplaySettings;
import dev.prime.render.LightingSettings;
import dev.prime.render.PerformanceIntegratorSettings;
import dev.prime.render.MaterialSettings;
import dev.prime.client.PrimeRuntime;
import dev.prime.render.RealtimeIntegratorMode;
import dev.prime.render.RendererSettings;
import dev.prime.render.fsr.FsrDebugView;
import dev.prime.render.fsr.FsrQualityMode;
import dev.prime.render.post.DlssRrDebugView;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.terrain.VoxelSurfaceSettings;
import dev.prime.render.post.nrd.NrdDiagnostics;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;

/** Builds Prime's live controls shown in Minecraft's Video Settings screen. */
public final class PrimeVideoOptions {
    private static final List<PostProcessingMode> POST_PROCESSING_MODES =
            List.of(PostProcessingMode.values());
    private static final List<RealtimeIntegratorMode> REALTIME_INTEGRATORS =
            List.of(RealtimeIntegratorMode.values());
    private static final List<ReconstructionQualityMode> QUALITY_MODES =
            List.of(ReconstructionQualityMode.values());
    private static final List<DlssRrDebugView> RR_DEBUG_VIEWS = List.of(DlssRrDebugView.values());
    private static final List<FsrDebugView> FSR_DEBUG_VIEWS = List.of(FsrDebugView.values());
    private static final List<NrdDiagnostics.Mode> NRD_DEBUG_VIEWS =
            List.of(NrdDiagnostics.Mode.values());

    private PrimeVideoOptions() {
    }

    public static OptionInstance<Boolean> pathTracingEnabled() {
        return OptionInstance.createBoolean(
                "prime.options.path_tracing",
                OptionInstance.cachedConstantTooltip(
                        Component.translatable("prime.options.path_tracing.tooltip")),
                PrimeConfig.settings().pathTracingEnabled(),
                PrimeVideoOptions::setPathTracingEnabled);
    }

    public static OptionInstance<RealtimeIntegratorMode> realtimeIntegrator() {
        return new OptionInstance<>(
                "prime.options.realtime_integrator",
                OptionInstance.cachedConstantTooltip(
                        Component.translatable("prime.options.realtime_integrator.tooltip")),
                (caption, mode) -> Component.translatable(
                        "prime.options.realtime_integrator." + mode.id()),
                new OptionInstance.Enum<>(
                        REALTIME_INTEGRATORS,
                        Codec.STRING.xmap(
                                RealtimeIntegratorMode::fromId,
                                RealtimeIntegratorMode::id)),
                PrimeConfig.settings().realtimeIntegrator(),
                PrimeConfig::setRealtimeIntegrator);
    }

    public static OptionInstance<Integer> performanceMaximumScatters() {
        return new OptionInstance<>(
                "prime.options.performance_maximum_bounces",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "prime.options.performance_maximum_bounces.tooltip")),
                (caption, scatters) -> Options.genericValueLabel(
                        caption,
                        Component.literal(Integer.toString(scatters))),
                new OptionInstance.IntRange(
                        PerformanceIntegratorSettings.MINIMUM_SCATTERS,
                        PerformanceIntegratorSettings.MAXIMUM_SCATTERS),
                PrimeConfig.settings().performanceMaximumScatters(),
                PrimeConfig::setPerformanceMaximumScatters);
    }

    public static OptionInstance<Boolean> voxelTextureSurfaces() {
        return OptionInstance.createBoolean(
                "prime.options.experimental.voxel_texture_surfaces",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "prime.options.experimental.voxel_texture_surfaces.tooltip")),
                PrimeConfig.settings().voxelTextureSurfaces(),
                PrimeVideoOptions::setVoxelTextureSurfaces);
    }

    public static OptionInstance<Integer> voxelTextureSurfaceStrength() {
        return new OptionInstance<>(
                "prime.options.experimental.voxel_texture_surface_strength",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "prime.options.experimental.voxel_texture_surface_strength.tooltip")),
                (caption, steps) -> Options.genericValueLabel(
                        caption,
                        Component.literal(steps + "%")),
                new OptionInstance.IntRange(
                        VoxelSurfaceSettings.MINIMUM_STEPS,
                        VoxelSurfaceSettings.MAXIMUM_STEPS),
                PrimeConfig.settings().voxelTextureSurfaceStrengthSteps(),
                PrimeVideoOptions::setVoxelTextureSurfaceStrengthSteps);
    }

    public static OptionInstance<Boolean> screenshotMode() {
        PrimeRuntime runtime = PrimeRuntime.instance();
        return OptionInstance.createBoolean(
                "prime.options.screenshot_mode",
                OptionInstance.cachedConstantTooltip(
                        Component.translatable("prime.options.screenshot_mode.tooltip")),
                runtime.screenshotRequested(),
                runtime::requestScreenshot);
    }

    public static OptionInstance<PostProcessingMode> postProcessingMode() {
        return new OptionInstance<>(
                "prime.options.post_processing.mode",
                OptionInstance.cachedConstantTooltip(
                        Component.translatable("prime.options.post_processing.mode.tooltip")),
                (caption, mode) -> Component.translatable(
                        "prime.options.post_processing.mode." + mode.id()),
                new OptionInstance.Enum<>(
                        POST_PROCESSING_MODES,
                        Codec.STRING.xmap(PostProcessingMode::fromId, PostProcessingMode::id)),
                PrimeConfig.settings().postProcessingMode(),
                PrimeConfig::setPostProcessingMode);
    }

    public static OptionInstance<Boolean> triangleDebug() {
        PrimeRuntime runtime = PrimeRuntime.instance();
        return OptionInstance.createBoolean(
                "prime.options.debug.triangle_distribution",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "prime.options.debug.triangle_distribution.tooltip")),
                runtime.triangleDebug(),
                runtime::setTriangleDebug);
    }

    public static OptionInstance<Boolean> rendererDiagnostics() {
        PrimeRuntime runtime = PrimeRuntime.instance();
        return OptionInstance.createBoolean(
                "prime.options.debug.renderer_diagnostics",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "prime.options.debug.renderer_diagnostics.tooltip")),
                runtime.rendererDiagnostics(),
                runtime::setRendererDiagnostics);
    }

    public static OptionInstance<ReconstructionQualityMode> qualityMode() {
        return new OptionInstance<>(
                "prime.options.post_processing.quality",
                OptionInstance.cachedConstantTooltip(
                        Component.translatable("prime.options.post_processing.quality.tooltip")),
                (caption, mode) -> Options.genericValueLabel(
                        caption,
                        Component.translatable("prime.options.post_processing.quality." + mode.id())),
                new OptionInstance.SliderableEnum<>(
                        QUALITY_MODES,
                        Codec.STRING.xmap(
                                ReconstructionQualityMode::fromId,
                                ReconstructionQualityMode::id)),
                PrimeConfig.settings().reconstructionQuality(),
                PrimeConfig::setReconstructionQualityMode);
    }

    public static OptionInstance<Integer> latitude() {
        return new OptionInstance<>(
                "prime.options.astronomy.latitude",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "prime.options.astronomy.latitude.tooltip")),
                (caption, degrees) -> Options.genericValueLabel(
                        caption, formatLatitude(degrees)),
                new OptionInstance.IntRange(
                        AstronomySettings.MINIMUM_LATITUDE_DEGREES,
                        AstronomySettings.MAXIMUM_LATITUDE_DEGREES),
                PrimeConfig.settings().astronomy().latitudeDegrees(),
                PrimeConfig::setLatitudeDegrees);
    }

    public static OptionInstance<Integer> season() {
        return new OptionInstance<>(
                "prime.options.astronomy.season",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "prime.options.astronomy.season.tooltip")),
                (caption, degrees) -> Options.genericValueLabel(
                        caption, formatSolarLongitude(degrees)),
                new OptionInstance.IntRange(
                        AstronomySettings.MINIMUM_SOLAR_LONGITUDE_DEGREES,
                        AstronomySettings.MAXIMUM_SOLAR_LONGITUDE_DEGREES),
                PrimeConfig.settings().astronomy().solarLongitudeDegrees(),
                PrimeConfig::setSolarLongitudeDegrees);
    }

    public static OptionInstance<DlssRrDebugView> dlssRrDebugView() {
        PrimeRuntime runtime = PrimeRuntime.instance();
        return new OptionInstance<>(
                "prime.options.dlss_rr.debug_view",
                OptionInstance.cachedConstantTooltip(
                        Component.translatable("prime.options.dlss_rr.debug_view.tooltip")),
                (caption, mode) -> Component.translatable(
                        "prime.options.dlss_rr.debug_view." + mode.id()),
                new OptionInstance.Enum<>(
                        RR_DEBUG_VIEWS,
                        Codec.STRING.xmap(DlssRrDebugView::fromId, DlssRrDebugView::id)),
                runtime.rrDebugView(),
                runtime::setRrDebugView);
    }

    public static OptionInstance<Boolean> dlssRrDebugFullscreen() {
        PrimeRuntime runtime = PrimeRuntime.instance();
        return OptionInstance.createBoolean(
                "prime.options.dlss_rr.debug_fullscreen",
                OptionInstance.cachedConstantTooltip(
                        Component.translatable("prime.options.dlss_rr.debug_fullscreen.tooltip")),
                runtime.rrDebugFullscreen(),
                runtime::setRrDebugFullscreen);
    }

    public static OptionInstance<Integer> sunExposure() {
        return exposureOption(
                "prime.options.lighting.sun_ev",
                "prime.options.lighting.sun_ev.tooltip",
                PrimeConfig.settings().sunQuarterSteps(),
                LightingSettings.MINIMUM_QUARTER_STEPS,
                LightingSettings.MAXIMUM_QUARTER_STEPS,
                PrimeConfig::setSunQuarterSteps);
    }

    public static OptionInstance<Integer> starExposure() {
        return exposureOption(
                "prime.options.lighting.star_ev",
                "prime.options.lighting.star_ev.tooltip",
                PrimeConfig.settings().starQuarterSteps(),
                LightingSettings.MINIMUM_STAR_QUARTER_STEPS,
                LightingSettings.MAXIMUM_STAR_QUARTER_STEPS,
                PrimeConfig::setStarQuarterSteps);
    }

    public static OptionInstance<Integer> blockLightExposure() {
        return exposureOption(
                "prime.options.lighting.block_light_ev",
                "prime.options.lighting.block_light_ev.tooltip",
                PrimeConfig.settings().blockLightQuarterSteps(),
                LightingSettings.MINIMUM_QUARTER_STEPS,
                LightingSettings.MAXIMUM_QUARTER_STEPS,
                PrimeConfig::setBlockLightQuarterSteps);
    }

    public static OptionInstance<Integer> finalExposure() {
        return exposureOption(
                "prime.options.display.final_exposure_ev",
                "prime.options.display.final_exposure_ev.tooltip",
                PrimeConfig.settings().finalExposureQuarterSteps(),
                DisplaySettings.MINIMUM_FINAL_EXPOSURE_QUARTER_STEPS,
                DisplaySettings.MAXIMUM_FINAL_EXPOSURE_QUARTER_STEPS,
                PrimeConfig::setFinalExposureQuarterSteps);
    }

    public static OptionInstance<Integer> oklabOverexposure() {
        return new OptionInstance<>(
                "prime.options.display.oklab_overexposure",
                OptionInstance.cachedConstantTooltip(
                        Component.translatable("prime.options.display.oklab_overexposure.tooltip")),
                (caption, steps) -> Options.genericValueLabel(
                        caption,
                        Component.literal(formatOverexposure(steps))),
                new OptionInstance.IntRange(
                        DisplaySettings.MINIMUM_OVEREXPOSURE_STEPS,
                        DisplaySettings.MAXIMUM_OVEREXPOSURE_STEPS),
                PrimeConfig.settings().oklabOverexposureSteps(),
                PrimeConfig::setOklabOverexposureSteps);
    }

    public static OptionInstance<Integer> curveExponent() {
        return decimalOption(
                "prime.options.display.oklab_curve_exponent",
                "prime.options.display.oklab_curve_exponent.tooltip",
                PrimeConfig.settings().curveExponentSteps(),
                DisplaySettings.MINIMUM_CURVE_EXPONENT_STEPS,
                DisplaySettings.MAXIMUM_CURVE_EXPONENT_STEPS,
                PrimeConfig::setCurveExponentSteps);
    }

    public static OptionInstance<Integer> autoExposureCompensation() {
        return new OptionInstance<>(
                "prime.options.display.auto_exposure_compensation",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "prime.options.display.auto_exposure_compensation.tooltip")),
                (caption, steps) -> Options.genericValueLabel(
                        caption,
                        Component.literal(steps + "%")),
                new OptionInstance.IntRange(
                        DisplaySettings.MINIMUM_AUTO_EXPOSURE_COMPENSATION_STEPS,
                        DisplaySettings.MAXIMUM_AUTO_EXPOSURE_COMPENSATION_STEPS),
                PrimeConfig.settings().autoExposureCompensationSteps(),
                PrimeConfig::setAutoExposureCompensationSteps);
    }

    public static OptionInstance<Integer> defaultRoughness() {
        return new OptionInstance<>(
                "prime.options.material.default_roughness",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "prime.options.material.default_roughness.tooltip")),
                (caption, steps) -> Options.genericValueLabel(
                        caption,
                        Component.literal(formatRoughness(steps))),
                new OptionInstance.IntRange(
                        MaterialSettings.MINIMUM_ROUGHNESS_STEPS,
                        MaterialSettings.MAXIMUM_ROUGHNESS_STEPS),
                PrimeConfig.settings().defaultRoughnessSteps(),
                PrimeConfig::setDefaultRoughnessSteps);
    }

    public static OptionInstance<Boolean> seamlessGlass() {
        return OptionInstance.createBoolean(
                "prime.options.material.seamless_glass",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "prime.options.material.seamless_glass.tooltip")),
                PrimeConfig.settings().seamlessGlass(),
                PrimeConfig::setSeamlessGlass);
    }

    public static OptionInstance<NrdDiagnostics.Mode> nrdDebugView() {
        PrimeRuntime runtime = PrimeRuntime.instance();
        return new OptionInstance<>(
                "prime.options.nrd.debug_view",
                OptionInstance.cachedConstantTooltip(
                        Component.translatable("prime.options.nrd.debug_view.tooltip")),
                (caption, mode) -> Component.translatable(
                        "prime.options.nrd.debug_view." + mode.id()),
                new OptionInstance.Enum<>(
                        NRD_DEBUG_VIEWS,
                        Codec.STRING.xmap(NrdDiagnostics.Mode::fromId, NrdDiagnostics.Mode::id)),
                runtime.nrdDebugView(),
                runtime::setNrdDebugView);
    }

    public static OptionInstance<FsrDebugView> fsrDebugView() {
        PrimeRuntime runtime = PrimeRuntime.instance();
        return new OptionInstance<>(
                "prime.options.fsr.debug_view",
                OptionInstance.cachedConstantTooltip(
                        Component.translatable("prime.options.fsr.debug_view.tooltip")),
                (caption, mode) -> Component.translatable(
                        "prime.options.fsr.debug_view." + mode.id()),
                new OptionInstance.Enum<>(
                        FSR_DEBUG_VIEWS,
                        Codec.STRING.xmap(FsrDebugView::fromId, FsrDebugView::id)),
                runtime.fsrDebugView(),
                runtime::setFsrDebugView);
    }

    private static OptionInstance<Integer> exposureOption(
            String captionKey,
            String tooltipKey,
            int initialQuarterSteps,
            int minimumQuarterSteps,
            int maximumQuarterSteps,
            OptionInstance.ValueUpdateListener<Integer> listener) {
        return new OptionInstance<>(
                captionKey,
                OptionInstance.cachedConstantTooltip(Component.translatable(tooltipKey)),
                (caption, quarterSteps) -> Options.genericValueLabel(
                        caption,
                        Component.literal(formatExposure(quarterSteps))),
                new OptionInstance.IntRange(
                        minimumQuarterSteps,
                        maximumQuarterSteps),
                initialQuarterSteps,
                listener);
    }

    private static void setPathTracingEnabled(boolean enabled) {
        RendererSettings previous = PrimeConfig.rendererSettings();
        PrimeConfig.setPathTracingEnabled(enabled);
        RendererSettings current = PrimeConfig.rendererSettings();
        if (previous.pathTracingEnabled() != current.pathTracingEnabled()) {
            PrimeRuntime.instance().pathTracingChanged(current.pathTracingEnabled());
        }
    }

    private static void setVoxelTextureSurfaces(boolean enabled) {
        RendererSettings previous = PrimeConfig.rendererSettings();
        PrimeConfig.setVoxelTextureSurfaces(enabled);
        RendererSettings current = PrimeConfig.rendererSettings();
        if (previous.voxelTextureSurfaces() != current.voxelTextureSurfaces()) {
            PrimeRuntime.instance().voxelTextureSurfacesChanged(
                    current.voxelTextureSurfaces());
        }
    }

    private static void setVoxelTextureSurfaceStrengthSteps(int steps) {
        RendererSettings previous = PrimeConfig.rendererSettings();
        PrimeConfig.setVoxelTextureSurfaceStrengthSteps(steps);
        RendererSettings current = PrimeConfig.rendererSettings();
        if (previous.voxelTextureSurfaceStrengthSteps()
                != current.voxelTextureSurfaceStrengthSteps()) {
            PrimeRuntime.instance().voxelTextureSurfaceStrengthChanged(
                    current.voxelTextureSurfaces(),
                    current.voxelTextureSurfaceStrengthSteps());
        }
    }

    private static OptionInstance<Integer> decimalOption(
            String captionKey,
            String tooltipKey,
            int initialSteps,
            int minimumSteps,
            int maximumSteps,
            OptionInstance.ValueUpdateListener<Integer> listener) {
        return new OptionInstance<>(
                captionKey,
                OptionInstance.cachedConstantTooltip(Component.translatable(tooltipKey)),
                (caption, steps) -> Options.genericValueLabel(
                        caption,
                        Component.literal(String.format(
                                Locale.ROOT,
                                "%.2f",
                                steps / (float) DisplaySettings.HUNDREDTH_STEPS_PER_UNIT))),
                new OptionInstance.IntRange(minimumSteps, maximumSteps),
                initialSteps,
                listener);
    }

    static String formatExposure(int quarterSteps) {
        float ev = LightingSettings.exposureValue(quarterSteps);
        if (quarterSteps == 0) {
            return "0 EV";
        }
        return String.format(Locale.ROOT, "%+.2f EV", ev);
    }

    private static Component formatLatitude(int degrees) {
        if (degrees == 0) {
            return Component.translatable("prime.options.astronomy.latitude.equator");
        }
        return Component.translatable(
                degrees > 0
                        ? "prime.options.astronomy.latitude.north"
                        : "prime.options.astronomy.latitude.south",
                Math.abs(degrees));
    }

    private static Component formatSolarLongitude(int degrees) {
        String event = switch (degrees) {
            case 0 -> "march_equinox";
            case 90 -> "june_solstice";
            case 180 -> "september_equinox";
            case 270 -> "december_solstice";
            default -> null;
        };
        if (event != null) {
            return Component.translatable(
                    "prime.options.astronomy.season." + event);
        }
        String interval = switch (degrees / 90) {
            case 0 -> "march_to_june";
            case 1 -> "june_to_september";
            case 2 -> "september_to_december";
            default -> "december_to_march";
        };
        return Component.translatable(
                "prime.options.astronomy.season.progress",
                degrees,
                Component.translatable(
                        "prime.options.astronomy.season." + interval));
    }

    static String formatOverexposure(int steps) {
        return String.format(Locale.ROOT, "%.5f×", DisplaySettings.overexposure(steps));
    }

    static String formatRoughness(int steps) {
        return String.format(Locale.ROOT, "%.2f", MaterialSettings.linearRoughness(steps));
    }
}
