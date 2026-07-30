package dev.prime.render.terrain;

import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;

/** Immutable resource-pack material availability captured by terrain build jobs. */
public record LabPbrMaterialSet(
        Set<Identifier> normalSprites,
        Set<Identifier> specularSprites,
        Map<Identifier, LabPbrEmissionMap> emissionMaps,
        Map<Identifier, LabPbrHeightMap> heightMaps) {
    public static final LabPbrMaterialSet EMPTY = new LabPbrMaterialSet(
            Set.of(), Set.of(), Map.of(), Map.of());

    public LabPbrMaterialSet(
            Set<Identifier> normalSprites,
            Set<Identifier> specularSprites,
            Map<Identifier, LabPbrEmissionMap> emissionMaps) {
        this(normalSprites, specularSprites, emissionMaps, Map.of());
    }

    public LabPbrMaterialSet {
        normalSprites = Set.copyOf(normalSprites);
        specularSprites = Set.copyOf(specularSprites);
        emissionMaps = Map.copyOf(emissionMaps);
        heightMaps = Map.copyOf(heightMaps);
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

    public LabPbrHeightMap heightMap(Identifier sprite) {
        return this.heightMaps.get(sprite);
    }
}
