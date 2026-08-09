package dev.prime.render.scene.vanilla;

import dev.prime.render.terrain.SectionCluster;
import java.util.Objects;

/**
 * Explicit captured input of one vanilla Section compilation.
 *
 * <p>Renderer scratch buffers, scheduling state and live Minecraft singletons are deliberately
 * absent. The component snapshots expose every Prime-owned input while retaining live vanilla
 * services only inside the Minecraft adapter boundary.
 */
public record VanillaSectionCompileInput(
        VanillaSectionSnapshot section,
        VanillaAssetSnapshot assets,
        int clusterX,
        int clusterY,
        int clusterZ) {
    public VanillaSectionCompileInput {
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(assets, "assets");
    }

    public VanillaSectionCompileInput(
            VanillaSectionSnapshot section,
            VanillaAssetSnapshot assets) {
        this(
                section,
                assets,
                SectionCluster.origin(section.sectionX()),
                SectionCluster.origin(section.sectionY()),
                SectionCluster.origin(section.sectionZ()));
    }
}
