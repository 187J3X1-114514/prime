package dev.prime.mixin;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Corrects the one extended distance whose byte representation is negative. */
@Mixin(targets = "net.minecraft.server.level.DistanceManager$PlayerTicketTracker")
public abstract class PlayerTicketTrackerMixin {
    @Shadow
    private int viewDistance;

    @Shadow
    private void onLevelChange(long key, int level, boolean saw, boolean sees) {
        throw new AssertionError();
    }

    @Inject(method = "updateViewDistance", at = @At("HEAD"))
    private void prime$bridgeSignedByteBoundary(int newViewDistance, CallbackInfo callback) {
        boolean previouslyIncluded = this.viewDistance >= ViewDistanceLimits.MAXIMUM_RENDER_DISTANCE;
        boolean nowIncluded = newViewDistance >= ViewDistanceLimits.MAXIMUM_RENDER_DISTANCE;
        if (previouslyIncluded == nowIncluded) {
            return;
        }

        Long2ByteMap levels = ((FixedPlayerDistanceChunkTrackerAccessor) this).prime$distanceLevels();
        for (Long2ByteMap.Entry entry : levels.long2ByteEntrySet()) {
            int level = Byte.toUnsignedInt(entry.getByteValue());
            if (level == ViewDistanceLimits.MAXIMUM_RENDER_DISTANCE) {
                // Vanilla's following loop observes -128 and therefore considers this ring
                // included on both sides of the transition. Apply the missing edge explicitly;
                // the vanilla pass then remains a harmless no-op for this ring.
                this.onLevelChange(entry.getLongKey(), level, previouslyIncluded, nowIncluded);
            }
        }
    }
}
