package dev.prime.render.terrain;

import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;

/** Immutable resource-pack material availability captured by terrain build jobs. */
public record LabPbrMaterialSet(
        Set<Identifier> normalSprites,
        Set<Identifier> specularSprites,
        Map<Identifier, LabPbrEmissionMap> emissionMaps) {
    public static final LabPbrMaterialSet EMPTY = new LabPbrMaterialSet(
            Set.of(), Set.of(), Map.of());

    public LabPbrMaterialSet {
        normalSprites = Set.copyOf(normalSprites);
        specularSprites = Set.copyOf(specularSprites);
        emissionMaps = Map.copyOf(emissionMaps);
    }

    public boolean hasNormal(Identifier sprite) {
        return this.normalSprites.contains(sprite);
    }

    public boolean hasSpecular(Identifier sprite) {
        return this.specularSprites.contains(sprite);
    }

    public LabPbrEmissionMap emissionMap(Identifier sprite) {
        return this.emissionMaps.get(sprite);
    }
}
