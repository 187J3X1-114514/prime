package dev.prime.mixin;

import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Preserves player-ticket distances above 127.
 *
 * <p>Vanilla bounds this tracker to 32 and stores its levels in a byte. Prime extends the bound to
 * 128, so the same byte must be interpreted as the unsigned value that the graph wrote. Changing
 * the representation is unnecessary: all required levels, including the graph's 130 sentinel,
 * still fit exactly in an unsigned byte.
 */
@Mixin(targets = "net.minecraft.server.level.DistanceManager$FixedPlayerDistanceChunkTracker")
public abstract class FixedPlayerDistanceChunkTrackerMixin {
    @Inject(method = "getLevel", at = @At("RETURN"), cancellable = true)
    private void prime$readUnsignedDistance(long node, CallbackInfoReturnable<Integer> callback) {
        callback.setReturnValue(Byte.toUnsignedInt(callback.getReturnValue().byteValue()));
    }
}
