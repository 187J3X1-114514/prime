package dev.prime.render.terrain;

import dev.prime.render.scene.SpriteId;
import java.util.Map;
import java.util.Set;

/** Immutable resource-pack material availability captured by terrain build jobs. */
public record LabPbrMaterialSet(
        Set<SpriteId> normalSprites,
        Set<SpriteId> specularSprites,
        Map<SpriteId, LabPbrEmissionMap> emissionMaps,
        Map<SpriteId, LabPbrHeightMap> heightMaps,
        Map<SpriteId, LabPbrMaterialMap> materialMaps) {
    public static final LabPbrMaterialSet EMPTY = new LabPbrMaterialSet(
            Set.of(), Set.of(), Map.of(), Map.of(), Map.of());

    public LabPbrMaterialSet(
            Set<SpriteId> normalSprites,
            Set<SpriteId> specularSprites,
            Map<SpriteId, LabPbrEmissionMap> emissionMaps) {
        this(normalSprites, specularSprites, emissionMaps, Map.of(), Map.of());
    }

    public LabPbrMaterialSet(
            Set<SpriteId> normalSprites,
            Set<SpriteId> specularSprites,
            Map<SpriteId, LabPbrEmissionMap> emissionMaps,
            Map<SpriteId, LabPbrHeightMap> heightMaps) {
        this(normalSprites, specularSprites, emissionMaps, heightMaps, Map.of());
    }

    public LabPbrMaterialSet {
        normalSprites = Set.copyOf(normalSprites);
        specularSprites = Set.copyOf(specularSprites);
        emissionMaps = Map.copyOf(emissionMaps);
        heightMaps = Map.copyOf(heightMaps);
        materialMaps = Map.copyOf(materialMaps);
    }

    public boolean hasNormal(SpriteId sprite) {
        return this.normalSprites.contains(sprite);
    }

    public boolean hasSpecular(SpriteId sprite) {
        return this.specularSprites.contains(sprite);
    }

    public LabPbrEmissionMap emissionMap(SpriteId sprite) {
        return this.emissionMaps.get(sprite);
    }

    public LabPbrHeightMap heightMap(SpriteId sprite) {
        return this.heightMaps.get(sprite);
    }

    public LabPbrMaterialMap materialMap(SpriteId sprite) {
        return this.materialMaps.get(sprite);
    }
}
