package dev.prime.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.prime.render.RayTracingRuntime;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow
    @Final
    private RenderTarget mainRenderTarget;

    @Shadow
    public abstract GameRenderState gameRenderState();

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"))
    private void prime$beginFrame(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        RayTracingRuntime.instance().beginFrame(net.minecraft.client.Minecraft.getInstance());
    }

    @ModifyArg(
            method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;",
                    ordinal = 0),
            index = 0)
    private Matrix4f prime$captureCamera(Matrix4f projection) {
        var camera = this.gameRenderState().levelRenderState.cameraRenderState;
        RayTracingRuntime.instance().captureCamera(
                projection,
                camera.viewRotationMatrix,
                camera.pos.x,
                camera.pos.y,
                camera.pos.z);
        return projection;
    }

    @Inject(
            method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
                    shift = At.Shift.AFTER))
    private void prime$renderRayTracedWorld(DeltaTracker deltaTracker, CallbackInfo ci) {
        RayTracingRuntime.instance().renderWorld(this.mainRenderTarget);
    }
}
