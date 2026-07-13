package dev.prime.mixin;

import dev.prime.render.RayTracingRuntime;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
    private void prime$markSectionDirty(int sectionX, int sectionY, int sectionZ, boolean playerChanged, CallbackInfo ci) {
        RayTracingRuntime.instance().invalidateSection(sectionX, sectionY, sectionZ);
    }

    @Inject(method = "allChanged()V", at = @At("TAIL"))
    private void prime$invalidateTerrain(CallbackInfo ci) {
        RayTracingRuntime.instance().invalidateAll();
    }
}
