package dev.prime.mixin;

import dev.prime.render.scene.vanilla.PrimeEntityRenderState;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @Inject(method = "extractEntity", at = @At("RETURN"))
    private <E extends Entity> void prime$attachEntityId(
            E entity,
            float partialTick,
            CallbackInfoReturnable<EntityRenderState> callback) {
        ((PrimeEntityRenderState) callback.getReturnValue())
                .prime$entityId(entity.getId());
    }
}
