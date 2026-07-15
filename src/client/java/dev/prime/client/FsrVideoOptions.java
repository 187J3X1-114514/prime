package dev.prime.client;

import com.mojang.serialization.Codec;
import dev.prime.config.PrimeConfig;
import dev.prime.render.fsr.FsrQualityMode;
import dev.prime.render.fsr.FsrDebugView;
import dev.prime.render.fsr.FsrSettings;
import java.util.List;
import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;

/** Builds the runtime-safe Prime controls shown in Minecraft's Video Settings screen. */
public final class FsrVideoOptions {
    private static final List<FsrQualityMode> QUALITY_MODES = List.of(FsrQualityMode.values());
    private static final List<FsrDebugView> DEBUG_VIEWS = List.of(FsrDebugView.values());

    private FsrVideoOptions() {
    }

    public static OptionInstance<FsrQualityMode> qualityMode() {
        return new OptionInstance<>(
                "prime.options.fsr.quality",
                OptionInstance.cachedConstantTooltip(
                        Component.translatable("prime.options.fsr.quality.tooltip")),
                (caption, mode) -> Component.translatable(
                        "prime.options.fsr.quality." + mode.id()),
                new OptionInstance.Enum<>(
                        QUALITY_MODES,
                        Codec.STRING.xmap(FsrQualityMode::fromId, FsrQualityMode::id)),
                FsrSettings.qualityMode(),
                PrimeConfig::setFsrQualityMode);
    }

    public static OptionInstance<FsrDebugView> debugView() {
        return new OptionInstance<>(
                "prime.options.fsr.debug_view",
                OptionInstance.cachedConstantTooltip(
                        Component.translatable("prime.options.fsr.debug_view.tooltip")),
                (caption, mode) -> Component.translatable(
                        "prime.options.fsr.debug_view." + mode.id()),
                new OptionInstance.Enum<>(
                        DEBUG_VIEWS,
                        Codec.STRING.xmap(FsrDebugView::fromId, FsrDebugView::id)),
                FsrSettings.debugView(),
                PrimeConfig::setFsrDebugView);
    }
}
