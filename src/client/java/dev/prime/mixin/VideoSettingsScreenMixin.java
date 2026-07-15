package dev.prime.mixin;

import dev.prime.client.FsrVideoOptions;
import dev.prime.config.PrimeConfig;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds Prime's live, persisted controls to the vanilla Video Settings screen. */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin {
    private static final Component PRIME$HEADER =
            Component.translatable("prime.options.header");

    @Inject(method = "addOptions", at = @At("TAIL"))
    private void prime$addOptions(CallbackInfo callbackInfo) {
        OptionsList list = ((OptionsSubScreenAccessor) this).prime$getList();
        if (list != null) {
            list.addHeader(PRIME$HEADER);
            list.addSmall(FsrVideoOptions.qualityMode(), FsrVideoOptions.debugView());
        }
    }

    @Inject(method = "removed", at = @At("TAIL"))
    private void prime$saveOptions(CallbackInfo callbackInfo) {
        PrimeConfig.save();
    }
}
