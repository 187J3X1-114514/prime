package dev.prime.mixin;

import dev.prime.client.PrimeVideoOptions;
import dev.prime.config.PrimeConfig;
import dev.prime.render.DisplaySettings;
import dev.prime.render.LightingSettings;
import dev.prime.render.fsr.FsrDebugView;
import dev.prime.render.fsr.FsrQualityMode;
import dev.prime.render.fsr.FsrSettings;
import dev.prime.render.vulkan.nrd.NrdDiagnostics;
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

/** Adds Prime's live, persisted controls to the vanilla Video Settings screen. */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin {
    private static final Component PRIME$HEADER =
            Component.translatable("prime.options.header");
    @Unique private OptionInstance<FsrQualityMode> prime$qualityMode;
    @Unique private OptionInstance<Integer> prime$sunExposure;
    @Unique private OptionInstance<Integer> prime$blockLightExposure;
    @Unique private OptionInstance<Integer> prime$oklabOverexposure;
    @Unique private OptionInstance<NrdDiagnostics.Mode> prime$nrdDebugView;
    @Unique private OptionInstance<FsrDebugView> prime$fsrDebugView;

    @Inject(method = "addOptions", at = @At("TAIL"))
    private void prime$addOptions(CallbackInfo callbackInfo) {
        OptionsList list = ((OptionsSubScreenAccessor) this).prime$getList();
        if (list != null) {
            this.prime$qualityMode = PrimeVideoOptions.qualityMode();
            this.prime$sunExposure = PrimeVideoOptions.sunExposure();
            this.prime$blockLightExposure = PrimeVideoOptions.blockLightExposure();
            this.prime$oklabOverexposure = PrimeVideoOptions.oklabOverexposure();
            this.prime$nrdDebugView = PrimeVideoOptions.nrdDebugView();
            this.prime$fsrDebugView = PrimeVideoOptions.fsrDebugView();
            list.addHeader(PRIME$HEADER);
            list.addBig(this.prime$qualityMode);
            list.addBig(this.prime$sunExposure);
            list.addBig(this.prime$blockLightExposure);
            list.addBig(this.prime$oklabOverexposure);
            list.addSmall(this.prime$nrdDebugView, this.prime$fsrDebugView);
            list.addBig(Button.builder(
                            Component.translatable("prime.options.restore_defaults"),
                            button -> this.prime$restoreDefaults())
                    .build());
        }
    }

    @Unique
    private void prime$restoreDefaults() {
        PrimeConfig.restoreDefaults();
        this.prime$refresh(this.prime$qualityMode, FsrSettings.DEFAULT_QUALITY_MODE);
        this.prime$refresh(
                this.prime$sunExposure,
                LightingSettings.DEFAULT_SUN_QUARTER_STEPS);
        this.prime$refresh(
                this.prime$blockLightExposure,
                LightingSettings.DEFAULT_BLOCK_LIGHT_QUARTER_STEPS);
        this.prime$refresh(
                this.prime$oklabOverexposure,
                DisplaySettings.DEFAULT_OVEREXPOSURE_STEPS);
        this.prime$refresh(this.prime$nrdDebugView, NrdDiagnostics.Mode.OFF);
        this.prime$refresh(this.prime$fsrDebugView, FsrDebugView.OFF);
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
