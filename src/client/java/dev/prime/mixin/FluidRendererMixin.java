package dev.prime.mixin;

import dev.prime.render.scene.vanilla.VanillaSectionCapture;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FluidRenderer.class)
public abstract class FluidRendererMixin {
    @Shadow
    @Final
    public FluidStateModelSet fluidModels;

    @Inject(method = "tesselate", at = @At("HEAD"))
    private void prime$beginVanillaFluidCapture(
            BlockAndTintGetter level,
            BlockPos position,
            FluidRenderer.Output output,
            BlockState blockState,
            FluidState fluidState,
            CallbackInfo ci) {
        FluidModel model = this.fluidModels.get(fluidState);
        VanillaSectionCapture.beginFluid(level, position, blockState, fluidState, model);
    }

    @Inject(method = "tesselate", at = @At("RETURN"))
    private void prime$endVanillaFluidCapture(
            BlockAndTintGetter level,
            BlockPos position,
            FluidRenderer.Output output,
            BlockState blockState,
            FluidState fluidState,
            CallbackInfo ci) {
        VanillaSectionCapture.endFluid();
    }
}
