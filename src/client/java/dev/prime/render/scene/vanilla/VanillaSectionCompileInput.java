package dev.prime.render.scene.vanilla;

import java.util.Objects;

/**
 * Explicit captured input of one vanilla Section compilation.
 *
 * <p>Renderer scratch buffers, scheduling state and live Minecraft singletons are deliberately
 * absent. The two component snapshots state the remaining Minecraft capture gaps directly.
 */
public record VanillaSectionCompileInput(
        VanillaSectionSnapshot section,
        VanillaAssetSnapshot assets) {
    public VanillaSectionCompileInput {
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(assets, "assets");
    }
}
