package dev.prime.mixin;

import com.mojang.blaze3d.vulkan.VulkanInstance;
import dev.prime.render.vulkan.dlss.DlssRrBootstrap;
import java.util.Set;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanInstance.class)
public abstract class VulkanInstanceMixin {
    @Shadow @Final private Set<String> enabledExtensions;

    @Inject(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vulkan/VulkanDebug;create(IZLjava/util/Set;Ljava/util/Set;)Lcom/mojang/blaze3d/vulkan/VulkanDebug;"))
    private void prime$enableNgxInstanceExtensions(
            int debugVerbosity,
            boolean enableDebugLabels,
            boolean enableValidation,
            CallbackInfo ci) {
        DlssRrBootstrap.enableRequiredInstanceExtensions(this.enabledExtensions);
    }
}
