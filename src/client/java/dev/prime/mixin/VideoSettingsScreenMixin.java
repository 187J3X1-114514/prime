package dev.prime.mixin;

import dev.prime.client.PrimeVideoOptions;
import dev.prime.config.PrimeConfig;
import dev.prime.render.DisplaySettings;
import dev.prime.render.LightingSettings;
import dev.prime.render.MaterialSettings;
import dev.prime.render.ScreenshotMode;
import dev.prime.render.fsr.FsrDebugView;
import dev.prime.render.fsr.FsrQualityMode;
import dev.prime.render.fsr.FsrSettings;
import dev.prime.render.vulkan.nrd.NrdDiagnostics;
import dev.prime.render.post.DlssRrDebugView;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.PostProcessingSettings;
import dev.prime.render.post.ReconstructionQualityMode;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.OptionsList;
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
    private static final Component PRIME$HEADER =
            Component.translatable("prime.options.header");
    @Unique private OptionInstance<Boolean> prime$screenshotMode;
    @Unique private OptionInstance<PostProcessingMode> prime$postProcessingMode;
    @Unique private OptionInstance<ReconstructionQualityMode> prime$qualityMode;
    @Unique private OptionInstance<Integer> prime$sunExposure;
    @Unique private OptionInstance<Integer> prime$blockLightExposure;
    @Unique private OptionInstance<Integer> prime$oklabOverexposure;
    @Unique private OptionInstance<Integer> prime$defaultRoughness;
    @Unique private OptionInstance<NrdDiagnostics.Mode> prime$nrdDebugView;
    @Unique private OptionInstance<FsrDebugView> prime$fsrDebugView;
    @Unique private OptionInstance<DlssRrDebugView> prime$rrDebugView;
    @Unique private OptionInstance<Boolean> prime$rrDebugFullscreen;

    @Inject(method = "addOptions", at = @At("TAIL"))
    private void prime$addOptions(CallbackInfo callbackInfo) {
        OptionsList list = ((OptionsSubScreenAccessor) this).prime$getList();
        if (list != null) {
            this.prime$screenshotMode = PrimeVideoOptions.screenshotMode();
            this.prime$postProcessingMode = PrimeVideoOptions.postProcessingMode();
            this.prime$qualityMode = PrimeVideoOptions.qualityMode();
            this.prime$sunExposure = PrimeVideoOptions.sunExposure();
            this.prime$blockLightExposure = PrimeVideoOptions.blockLightExposure();
            this.prime$oklabOverexposure = PrimeVideoOptions.oklabOverexposure();
            this.prime$defaultRoughness = PrimeVideoOptions.defaultRoughness();
            this.prime$nrdDebugView = PrimeVideoOptions.nrdDebugView();
            this.prime$fsrDebugView = PrimeVideoOptions.fsrDebugView();
            this.prime$rrDebugView = PrimeVideoOptions.dlssRrDebugView();
            this.prime$rrDebugFullscreen = PrimeVideoOptions.dlssRrDebugFullscreen();
            list.addHeader(PRIME$HEADER);
            list.addBig(Button.builder(
                            Component.translatable("prime.options.restore_defaults"),
                            button -> this.prime$restoreDefaults())
                    .build());
            list.addBig(this.prime$screenshotMode);
            list.addBig(this.prime$postProcessingMode);
            list.addBig(this.prime$qualityMode);
            list.addBig(this.prime$sunExposure);
            list.addBig(this.prime$blockLightExposure);
            list.addBig(this.prime$oklabOverexposure);
            list.addBig(this.prime$defaultRoughness);
            list.addSmall(this.prime$nrdDebugView, this.prime$fsrDebugView);
            list.addSmall(this.prime$rrDebugView, this.prime$rrDebugFullscreen);
        }
    }

    @Unique
    private void prime$restoreDefaults() {
        PrimeConfig.restoreDefaults();
        ScreenshotMode.request(false);
        this.prime$refresh(this.prime$screenshotMode, false);
        this.prime$refresh(this.prime$postProcessingMode, PostProcessingMode.DEFAULT);
        this.prime$refresh(this.prime$qualityMode, ReconstructionQualityMode.DEFAULT);
        this.prime$refresh(
                this.prime$sunExposure,
                LightingSettings.DEFAULT_SUN_QUARTER_STEPS);
        this.prime$refresh(
                this.prime$blockLightExposure,
                LightingSettings.DEFAULT_BLOCK_LIGHT_QUARTER_STEPS);
        this.prime$refresh(
                this.prime$oklabOverexposure,
                DisplaySettings.DEFAULT_OVEREXPOSURE_STEPS);
        this.prime$refresh(
                this.prime$defaultRoughness,
                MaterialSettings.DEFAULT_ROUGHNESS_STEPS);
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
