package dev.prime.mixin;

import net.minecraft.client.Options;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Slice;

/** Extends only the client render-distance control; simulation distance keeps Minecraft's limit. */
@Mixin(Options.class)
public abstract class OptionsMixin {
    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/OptionInstance$IntRange;<init>(IIZ)V"),
            slice = @Slice(
                    from = @At(value = "CONSTANT", args = "stringValue=options.renderDistance"),
                    to = @At(
                            value = "FIELD",
                            target = "Lnet/minecraft/client/Options;renderDistance:Lnet/minecraft/client/OptionInstance;",
                            opcode = Opcodes.PUTFIELD)),
            index = 1)
    private static int prime$maximumRenderDistance(int vanillaMaximum) {
        return Math.max(vanillaMaximum, ViewDistanceLimits.MAXIMUM_RENDER_DISTANCE);
    }
}
