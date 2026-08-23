package dev.prime.render.vulkan.reconstruction;

import dev.prime.render.diagnostic.ImageDiagnosticSelection;
import java.util.Objects;

/** Runtime-to-backend debug side channel excluded from semantic frame plans. */
public record ReconstructionDebugSettings(ImageDiagnosticSelection images) {
    public ReconstructionDebugSettings {
        images = Objects.requireNonNull(images, "images");
    }
}
