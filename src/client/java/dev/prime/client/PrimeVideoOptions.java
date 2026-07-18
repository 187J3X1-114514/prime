package dev.prime.client;

import com.mojang.serialization.Codec;
import dev.prime.config.PrimeConfig;
import dev.prime.render.DisplaySettings;
import dev.prime.render.LightingSettings;
import dev.prime.render.MaterialSettings;
import dev.prime.render.ScreenshotMode;
import dev.prime.render.fsr.FsrDebugView;
import dev.prime.render.fsr.FsrQualityMode;
import dev.prime.render.fsr.FsrSettings;
import dev.prime.render.vulkan.nrd.NrdDiagnostics;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;

/** Builds Prime's live controls shown in Minecraft's Video Settings screen. */
public final class PrimeVideoOptions {
    private static final List<FsrQualityMode> QUALITY_MODES = List.of(FsrQualityMode.values());
    private static final List<FsrDebugView> FSR_DEBUG_VIEWS = List.of(FsrDebugView.values());
    private static final List<NrdDiagnostics.Mode> NRD_DEBUG_VIEWS =
            List.of(NrdDiagnostics.Mode.values());

    private PrimeVideoOptions() {
    }

    public static OptionInstance<Boolean> screenshotMode() {
        return OptionInstance.createBoolean(
                "prime.options.screenshot_mode",
                OptionInstance.cachedConstantTooltip(
                        Component.translatable("prime.options.screenshot_mode.tooltip")),
                ScreenshotMode.requested(),
                ScreenshotMode::request);
    }

    public static OptionInstance<FsrQualityMode> qualityMode() {
        return new OptionInstance<>(
                "prime.options.fsr.quality",
                OptionInstance.cachedConstantTooltip(
                        Component.translatable("prime.options.fsr.quality.tooltip")),
                (caption, mode) -> Options.genericValueLabel(
                        caption,
                        Component.translatable("prime.options.fsr.quality." + mode.id())),
                new OptionInstance.SliderableEnum<>(
                        QUALITY_MODES,
                        Codec.STRING.xmap(FsrQualityMode::fromId, FsrQualityMode::id)),
                FsrSettings.qualityMode(),
                PrimeConfig::setFsrQualityMode);
    }

    public static OptionInstance<Integer> sunExposure() {
        return exposureOption(
                "prime.options.lighting.sun_ev",
                "prime.options.lighting.sun_ev.tooltip",
                LightingSettings.sunQuarterSteps(),
                PrimeConfig::setSunQuarterSteps);
    }

    public static OptionInstance<Integer> blockLightExposure() {
        return exposureOption(
                "prime.options.lighting.block_light_ev",
                "prime.options.lighting.block_light_ev.tooltip",
                LightingSettings.blockLightQuarterSteps(),
                PrimeConfig::setBlockLightQuarterSteps);
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
                DisplaySettings.overexposureSteps(),
                PrimeConfig::setOklabOverexposureSteps);
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
                MaterialSettings.roughnessSteps(),
                PrimeConfig::setDefaultRoughnessSteps);
    }

    public static OptionInstance<NrdDiagnostics.Mode> nrdDebugView() {
        return new OptionInstance<>(
                "prime.options.nrd.debug_view",
                OptionInstance.cachedConstantTooltip(
                        Component.translatable("prime.options.nrd.debug_view.tooltip")),
                (caption, mode) -> Component.translatable(
                        "prime.options.nrd.debug_view." + mode.id()),
                new OptionInstance.Enum<>(
                        NRD_DEBUG_VIEWS,
                        Codec.STRING.xmap(NrdDiagnostics.Mode::fromId, NrdDiagnostics.Mode::id)),
                NrdDiagnostics.mode(),
                PrimeConfig::setNrdDebugView);
    }

    public static OptionInstance<FsrDebugView> fsrDebugView() {
        return new OptionInstance<>(
                "prime.options.fsr.debug_view",
                OptionInstance.cachedConstantTooltip(
                        Component.translatable("prime.options.fsr.debug_view.tooltip")),
                (caption, mode) -> Component.translatable(
                        "prime.options.fsr.debug_view." + mode.id()),
                new OptionInstance.Enum<>(
                        FSR_DEBUG_VIEWS,
                        Codec.STRING.xmap(FsrDebugView::fromId, FsrDebugView::id)),
                FsrSettings.debugView(),
                PrimeConfig::setFsrDebugView);
    }

    private static OptionInstance<Integer> exposureOption(
            String captionKey,
            String tooltipKey,
            int initialQuarterSteps,
            OptionInstance.ValueUpdateListener<Integer> listener) {
        return new OptionInstance<>(
                captionKey,
                OptionInstance.cachedConstantTooltip(Component.translatable(tooltipKey)),
                (caption, quarterSteps) -> Options.genericValueLabel(
                        caption,
                        Component.literal(formatExposure(quarterSteps))),
                new OptionInstance.IntRange(
                        LightingSettings.MINIMUM_QUARTER_STEPS,
                        LightingSettings.MAXIMUM_QUARTER_STEPS),
                initialQuarterSteps,
                listener);
    }

    static String formatExposure(int quarterSteps) {
        float ev = LightingSettings.exposureValue(quarterSteps);
        if (quarterSteps == 0) {
            return "0 EV";
        }
        return String.format(Locale.ROOT, "%+.2f EV", ev);
    }

    static String formatOverexposure(int steps) {
        return String.format(Locale.ROOT, "%.5f×", DisplaySettings.overexposure(steps));
    }

    static String formatRoughness(int steps) {
        return String.format(Locale.ROOT, "%.2f", MaterialSettings.linearRoughness(steps));
    }
}
