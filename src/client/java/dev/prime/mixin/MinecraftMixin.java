package dev.prime.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import dev.prime.render.RayTracingRuntime;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "handleGlobalKeyPress", at = @At("HEAD"), cancellable = true)
    private void prime$screenshotModeShortcut(
            InputConstants.Key key,
            boolean controlDown,
            CallbackInfoReturnable<Boolean> callbackInfo) {
        Minecraft minecraft = (Minecraft) (Object) this;
        if (RayTracingRuntime.instance().handleScreenshotShortcut(
                minecraft, key, controlDown)) {
            callbackInfo.setReturnValue(true);
        }
    }

    @Inject(method = "close()V", at = @At("HEAD"))
    private void prime$shutdown(CallbackInfo ci) {
        RayTracingRuntime.instance().shutdown();
    }
}
