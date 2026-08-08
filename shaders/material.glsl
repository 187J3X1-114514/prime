#ifndef PRIME_MATERIAL_GLSL
#define PRIME_MATERIAL_GLSL

#extension GL_EXT_nonuniform_qualifier : require

#include "color_space.glsl"
#include "default_material.glsl"
#include "labpbr.glsl"
#include "material_translation.glsl"
#include "transparent_material.glsl"

// Minimal Minecraft adapter. The integrator consumes only this result, so future material
// models can replace atlas/tint decoding without changing path scheduling or traversal.
struct MaterialEvaluation {
    vec3 baseColor;
    float opacity;
    uint flags;
    vec3 shadingNormal;
    uint labPbrNormal;
    uint labPbrSpecular;
};

const uint PRIME_EMITTER_FLAG_TWO_SIDED = 1u;
const uint PRIME_EMITTER_FLAG_LABPBR_EMISSION = 2u;
const uint PRIME_CONSTANT_UV_DENSITY = 0x80000000u;
const uint PRIME_CONSTANT_UV_OWN_TINT = 1u;
const uint PRIME_CONSTANT_UV_BAKED_MATERIAL = 2u;
const uint PRIME_PACKED_FLAG_FRONT_FACE_ONLY = 1u << 9u;
const uint PRIME_PACKED_FLAG_RASTER_COMPOSITE = 1u << 10u;

uint primePrimitiveFlags(PrimitiveRecord primitive) {
    uint packedFlags =
            (primitive.tint >> 24u) | ((primitive.flagsEmitter & 7u) << 8u);
    // Traversal and raster-composition bits are primitive translation properties, not material
    // model inputs. Keep them out of MaterialEvaluation, where bit 9 and above are reserved for
    // translated LabPBR state.
    return packedFlags & (PRIME_PACKED_FLAG_FRONT_FACE_ONLY - 1u);
}

bool primePrimitiveIsFrontFaceOnly(PrimitiveRecord primitive) {
    return (primitive.flagsEmitter & 2u) != 0u;
}

bool primeUsesRasterComposite(PrimitiveRecord primitive) {
    return (primitive.flagsEmitter & 4u) != 0u;
}

uint primeRasterCompositeTint(PrimitiveRecord primitive) {
    return (primitive.flagsEmitter >> 3u) & 0x00ffffffu;
}

const uint PRIME_DYNAMIC_TEXTURE_FLAG = 0x80000000u;
const uint PRIME_VISIBLE_EMISSION_FLAG = 0x40000000u;
const uint PRIME_DYNAMIC_RED_ALPHA_FLAG = 0x20000000u;
const uint PRIME_DYNAMIC_TEXTURE_INDEX_MASK = 0x03ffffffu;

uint primePrimitiveTextureIndex(PrimitiveRecord primitive) {
    return (primitive.flagsEmitter & PRIME_DYNAMIC_TEXTURE_FLAG) != 0u
            ? ((primitive.flagsEmitter >> 3u) & PRIME_DYNAMIC_TEXTURE_INDEX_MASK)
            : 0u;
}

bool primeUsesDynamicRedAlpha(PrimitiveRecord primitive) {
    return (primitive.flagsEmitter
            & (PRIME_DYNAMIC_TEXTURE_FLAG | PRIME_DYNAMIC_RED_ALPHA_FLAG))
            == (PRIME_DYNAMIC_TEXTURE_FLAG | PRIME_DYNAMIC_RED_ALPHA_FLAG);
}

vec2 primeRasterCompositeUvOffset(PrimitiveRecord primitive) {
    int x = int(primitive.uvDensity << 16u) >> 16;
    int y = int(primitive.uvDensity) >> 16;
    ivec2 atlasExtent = textureSize(primeSceneTextures[0], 0);
    return vec2(x, y) / vec2(max(atlasExtent, ivec2(1)));
}

vec4 primeSamplePrimitiveTexture(
        PrimitiveRecord primitive, vec2 uv, float textureLodValue) {
    uint textureIndex = min(
            primePrimitiveTextureIndex(primitive), PRIME_SCENE_TEXTURE_COUNT - 1u);
    return textureLod(
            primeSceneTextures[nonuniformEXT(textureIndex)], uv, textureLodValue);
}

bool primeUsesBakedMaterial(PrimitiveRecord primitive) {
    return primitive.uvDensity == PRIME_CONSTANT_UV_DENSITY
            && (primitive.uv2 & PRIME_CONSTANT_UV_BAKED_MATERIAL) != 0u;
}

vec3 primeAtlasBaseColor(uint packedTint, vec2 uv, float textureLodValue, out float opacity) {
    vec4 textureSample = textureLod(primeSceneTextures[0], uv, textureLodValue);
    vec4 tint = primeUnpackTint(packedTint);
    opacity = textureSample.a;
    vec3 linearSrgbAlbedo = primeDecodeSrgb(textureSample.rgb) * primeDecodeSrgb(tint.rgb);
    return primeLinearSrgbToLinearRec2020(linearSrgbAlbedo);
}

vec2 primePrimitiveMaterialReferenceUv(PrimitiveRecord primitive) {
    if (primitive.uvDensity == PRIME_CONSTANT_UV_DENSITY) {
        return vec2(uintBitsToFloat(primitive.uv0), uintBitsToFloat(primitive.uv1));
    }
    vec2 first = primeUnpackUv(primitive.uv0);
    vec2 second = primeUnpackUv(primitive.uv1);
    vec2 third = primeUnpackUv(primitive.uv2);
    return 0.5 * (min(first, min(second, third)) + max(first, max(second, third)));
}

MaterialEvaluation primeEvaluateMaterial(
        PrimitiveRecord primitive, vec2 uv, float textureLodValue, uint instanceTint) {
    MaterialEvaluation result;
    bool bakedMaterial = primeUsesBakedMaterial(primitive);
    // Minecraft stores both atlas texels and tint RGB as display-encoded sRGB in UNORM values.
    // Decode both before multiplication, then cross the single material boundary into the
    // integrator's linear Rec.2020 working space. Alpha is coverage and is never color-decoded.
    if (bakedMaterial) {
        vec3 linearSrgbAlbedo = primeDecodeSrgb(primeUnpackTint(primitive.tint).rgb);
        bool ownsTint = (primitive.uv2 & PRIME_CONSTANT_UV_OWN_TINT) != 0u;
        if (!ownsTint && (instanceTint & 0x80000000u) != 0u) {
            linearSrgbAlbedo *= primeDecodeSrgb(primeUnpackTint(instanceTint).rgb);
        }
        result.baseColor = primeLinearSrgbToLinearRec2020(linearSrgbAlbedo);
        result.opacity = 1.0;
    } else if (primeUsesRasterComposite(primitive)) {
        vec4 baseSample =
                primeSamplePrimitiveTexture(primitive, uv, textureLodValue);
        vec2 overlayUv = uv + primeRasterCompositeUvOffset(primitive);
        vec4 overlaySample = primeSamplePrimitiveTexture(
                primitive, overlayUv, textureLodValue);
        bool overlayCovered =
                overlaySample.a >= PRIME_CUTOUT_ALPHA_THRESHOLD;
        vec4 textureSample = overlayCovered ? overlaySample : baseSample;
        uint packedTint = overlayCovered
                ? primeRasterCompositeTint(primitive)
                : primitive.tint;
        result.opacity = 1.0;
        result.baseColor = primeLinearSrgbToLinearRec2020(
                primeDecodeSrgb(textureSample.rgb)
                        * primeDecodeSrgb(primeUnpackTint(packedTint).rgb));
        uv = overlayCovered ? overlayUv : uv;
    } else {
        vec4 textureSample =
                primeSamplePrimitiveTexture(primitive, uv, textureLodValue);
        vec4 tint = primeUnpackTint(primitive.tint);
        if (primeUsesDynamicRedAlpha(primitive)) {
            result.opacity = textureSample.r;
            result.baseColor = primeLinearSrgbToLinearRec2020(
                    primeDecodeSrgb(tint.rgb));
        } else {
            result.opacity = textureSample.a;
            result.baseColor = primeLinearSrgbToLinearRec2020(
                    primeDecodeSrgb(textureSample.rgb)
                            * primeDecodeSrgb(tint.rgb));
        }
    }
    uint primitiveFlags = primePrimitiveFlags(primitive);
    if (primeMaterialIsTransmissive(primitiveFlags)
            && (primitiveFlags & PRIME_MATERIAL_FLAG_WATER) == 0u
            && !bakedMaterial
            && !primeUsesRasterComposite(primitive)) {
        float referenceOpacity;
        vec3 referenceBaseColor = primeAtlasBaseColor(
                primitive.tint,
                primePrimitiveMaterialReferenceUv(primitive),
                0.0,
                referenceOpacity);
        bool stained = primeGlassReferenceIsStained(referenceOpacity);
        bool rough = primeGlassTexelIsRough(
                result.opacity, primeUsesSeamlessGlass());
        if (stained) {
            // Both boundaries of a closed medium must agree on its color even when they hit
            // different decorative texels. Shadow rays use this same stable material reference.
            result.baseColor = referenceBaseColor;
        } else {
            primitiveFlags |= PRIME_MATERIAL_FLAG_COLORLESS_GLASS;
        }
        if (rough) {
            primitiveFlags |= PRIME_MATERIAL_FLAG_ROUGH_GLASS;
        }
        if (!stained && rough) {
            // Vanilla clear-glass borders are opaque blue-grey paint, not a second dielectric
            // volume. Keep the primitive in the transmissive BLAS but shade this texel as rough
            // diffuse so the transparent body remains a closed colorless medium.
            primitiveFlags &= ~PRIME_MATERIAL_FLAG_TRANSMISSIVE;
        }
    }
    if ((primitive.flagsEmitter
            & (PRIME_DYNAMIC_TEXTURE_FLAG | PRIME_VISIBLE_EMISSION_FLAG))
            == (PRIME_DYNAMIC_TEXTURE_FLAG | PRIME_VISIBLE_EMISSION_FLAG)) {
        primitiveFlags |= PRIME_MATERIAL_FLAG_VISIBLE_EMISSION;
    }
    result.flags = primitiveFlags;
    if (bakedMaterial) {
        result.labPbrNormal = primitive.uv0;
        result.labPbrSpecular = primitive.uv1;
    } else {
        vec4 normalSample = primeHasLabPbrNormal(primitiveFlags)
                ? textureLod(primeLabPbrNormalAtlas, uv, textureLodValue)
                : vec4(0.5, 0.5, 1.0, 1.0);
        vec4 specularSample = primeHasLabPbrSpecular(primitiveFlags)
                ? textureLod(primeLabPbrSpecularAtlas, uv, textureLodValue)
                : vec4(0.0, 4.0 / 255.0, 0.0, 1.0);
        result.labPbrNormal = packUnorm4x8(normalSample);
        result.labPbrSpecular = packUnorm4x8(specularSample);
    }
    PrimeTranslatedLabPbrMaterial translated = primeDecodeAndTranslateLabPbr(
            result.labPbrNormal, result.labPbrSpecular, primitiveFlags);
    if (primeTranslatedLabPbrIsMetal(translated)) {
        result.flags |= PRIME_MATERIAL_FLAG_LABPBR_METAL;
    }
    vec3 geometricNormal = primeUnpackOctahedralNormal(primitive.normal);
    // The normal atlas is intentionally transported and decoded but not applied yet. Normal-map
    // filtering, tangent continuity and NRD's world-normal guide must be introduced as one
    // validated contract; partially connecting it here would make BSDF and temporal geometry
    // disagree. This assignment is therefore deliberate, not dead code.
    result.shadingNormal = geometricNormal;
    return result;
}

vec3 primeEvaluateEmitterRadiance(
        uint atlasTint,
        uint emitterFlags,
        float fallbackRadiance,
        vec2 uv,
        float textureLodValue) {
    float opacity;
    vec3 color = primeAtlasBaseColor(atlasTint, uv, textureLodValue, opacity);
    bool cutout = (emitterFlags & PRIME_EMITTER_FLAG_TWO_SIDED) != 0u;
    if (cutout && opacity < PRIME_CUTOUT_ALPHA_THRESHOLD) {
        return vec3(0.0);
    }
    // Minecraft's 0..15 block-light value is an ordinal influence radius, not radiometry. Prime's
    // documented fallback maps its squared normalized level to the ABI source calibration, so a
    // white level-15 texel has radiance PRIME_LEVEL_15_BLOCK_INTENSITY. The
    // path geometry term supplies the actual inverse-square falloff; never add it here as well.
    // This global radiometric scale is uniform over every emitter. It therefore changes neither
    // light-tree selection probabilities nor their PDFs and does not require rebuilding the tree.
    float authoredEmission = 0.0;
    if ((emitterFlags & PRIME_EMITTER_FLAG_LABPBR_EMISSION) != 0u) {
        authoredEmission = primeDecodeLabPbrEmission(
                textureLod(primeLabPbrSpecularAtlas, uv, textureLodValue).a);
    }
    // LabPBR defines only a normalized emissiveness, not absolute radiometry. Prime maps 100%
    // to the same calibrated radiance as a white vanilla level-15 source. Once authored, this
    // channel replaces Minecraft's block-light value; alpha 0 can intentionally turn it off.
    float radianceScale = (emitterFlags & PRIME_EMITTER_FLAG_LABPBR_EMISSION) != 0u
            ? authoredEmission * PRIME_LEVEL_15_BLOCK_INTENSITY
            : max(fallbackRadiance, 0.0);
    return color * radianceScale * primeBlockLightRadianceMultiplier();
}

vec3 primeEvaluateEmitterRadiance(
        uint atlasTint,
        uint emitterFlags,
        float fallbackRadiance,
        vec2 uv) {
    // A sampled light point has no screen-space ray footprint. The distribution was constructed
    // from mip 0, so evaluating that same radiometric function preserves its PDF contract.
    return primeEvaluateEmitterRadiance(
            atlasTint, emitterFlags, fallbackRadiance, uv, 0.0);
}

float primeEvaluateOpacity(
        PrimitiveRecord primitive, vec2 uv, float textureLodValue) {
    uint textureIndex = min(
            primePrimitiveTextureIndex(primitive), PRIME_SCENE_TEXTURE_COUNT - 1u);
    ivec2 extent = textureSize(
            primeSceneTextures[nonuniformEXT(textureIndex)], 0);
    ivec2 texel = clamp(
            ivec2(floor(uv * vec2(extent))),
            ivec2(0),
            max(extent - 1, ivec2(0)));
    // OMMs are baked from mip-0 alpha. Exact texel fetches keep unknown any-hit decisions and
    // known OMM states on the same coverage function, independent of ray-cone LOD and filtering.
    return texelFetch(
            primeSceneTextures[nonuniformEXT(textureIndex)], texel, 0).a;
}

#endif
