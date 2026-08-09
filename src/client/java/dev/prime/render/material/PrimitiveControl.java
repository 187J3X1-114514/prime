package dev.prime.render.material;

import java.util.Objects;

/** Material recipe plus geometry-only controls encoded by one primitive. */
public record PrimitiveControl(
        MaterialRecipe material,
        boolean animated,
        boolean tangentNegative,
        boolean frontFaceOnly,
        boolean rasterComposite) {
    public PrimitiveControl {
        Objects.requireNonNull(material, "material");
        if (tangentNegative && !material.hasDetail(MaterialDetail.NORMAL_TEXTURE)) {
            throw new IllegalArgumentException(
                    "Negative tangent handedness requires a normal texture");
        }
        if (rasterComposite
                && (material.coverage() != CoverageMode.OPAQUE
                        || material.scattering() != ScatteringFamily.OPAQUE
                        || animated
                        || frontFaceOnly)) {
            throw new IllegalArgumentException(
                    "Raster composites must be static, opaque, and two-sided");
        }
    }
}
