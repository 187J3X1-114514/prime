package dev.prime.mixin;

import dev.prime.render.RayTracingRuntime;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "close()V", at = @At("HEAD"))
    private void prime$shutdown(CallbackInfo ci) {
        RayTracingRuntime.instance().shutdown();
    }
}
