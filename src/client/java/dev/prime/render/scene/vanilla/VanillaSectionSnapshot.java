package dev.prime.render.scene.vanilla;

import java.util.Objects;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;

/**
 * Captured block-state neighborhood and coordinate identity of one vanilla Section.
 *
 * <p>{@link RenderSectionRegion} owns copied {@code SectionCopy} state and block-entity maps.
 * Biome tint and light-engine queries still delegate to its captured level; those remaining live
 * services are explicit gaps rather than hidden interpreter state.
 */
public record VanillaSectionSnapshot(
        int sectionX,
        int sectionY,
        int sectionZ,
        RenderSectionRegion region) {
    public VanillaSectionSnapshot {
        Objects.requireNonNull(region, "region");
    }
}
