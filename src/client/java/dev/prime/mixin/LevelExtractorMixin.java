package dev.prime.mixin;

import dev.prime.client.PrimeRuntime;
import dev.prime.render.scene.vanilla.PrimeEntityFrustum;
import dev.prime.render.scene.vanilla.VanillaSceneBoundary;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forwards invalidation events without making vanilla a geometry source or scheduling authority.
 *
 * <p>{@code setSectionDirty} is intentionally not intercepted: chunk light updates call it even
 * though Prime does not consume the vanilla light volume. Treating that signal as geometry would
 * repeatedly rebuild resident BLASes and restart temporal history while chunks stream in.
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
    @Redirect(
            method = "isEntityVisible",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z"))
    private boolean prime$routeEntityFrustum(
            EntityRenderDispatcher dispatcher,
            Entity entity,
            Frustum frustum,
            double cameraX,
            double cameraY,
            double cameraZ) {
        return dispatcher.shouldRender(
                entity,
                PrimeRuntime.instance().shouldReplaceWorld()
                        ? PrimeEntityFrustum.INSTANCE
                        : frustum,
                cameraX,
                cameraY,
                cameraZ);
    }

    @Redirect(
            method = "isEntityVisible",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;isSectionCompiledAndVisible(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean prime$routeEntitySectionVisibility(
            LevelRenderer renderer, BlockPos position) {
        return VanillaSceneBoundary.includesEntitySection(
                PrimeRuntime.instance().shouldReplaceWorld(),
                renderer.isSectionCompiledAndVisible(position));
    }

    @Inject(method = "blockChanged(Lnet/minecraft/core/BlockPos;I)V", at = @At("HEAD"))
    private void prime$markBlockDirty(BlockPos position, int updateFlags, CallbackInfo ci) {
        PrimeRuntime.instance().invalidateBlocks(
                position.getX(),
                position.getY(),
                position.getZ(),
                position.getX(),
                position.getY(),
                position.getZ());
    }

    @Inject(method = "setBlocksDirty(IIIIII)V", at = @At("HEAD"))
    private void prime$markBlocksDirty(
            int minimumX,
            int minimumY,
            int minimumZ,
            int maximumX,
            int maximumY,
            int maximumZ,
            CallbackInfo ci) {
        PrimeRuntime.instance().invalidateBlocks(
                minimumX, minimumY, minimumZ, maximumX, maximumY, maximumZ);
    }

    @Inject(method = "allChanged()V", at = @At("TAIL"))
    private void prime$invalidateTerrain(CallbackInfo ci) {
        PrimeRuntime.instance().invalidateAll();
    }
}
