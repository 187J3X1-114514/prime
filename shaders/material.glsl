#ifndef PRIME_MATERIAL_GLSL
#define PRIME_MATERIAL_GLSL

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

uint primePrimitiveFlags(PrimitiveRecord primitive) {
    return (primitive.tint >> 24u) | ((primitive.flagsEmitter & 1u) << 8u);
}

vec3 primeAtlasBaseColor(uint packedTint, vec2 uv, float textureLodValue, out float opacity) {
    vec4 textureSample = textureLod(primeBlockAtlas, uv, textureLodValue);
    vec4 tint = primeUnpackTint(packedTint);
    opacity = textureSample.a;
    vec3 linearSrgbAlbedo = primeDecodeSrgb(textureSample.rgb) * primeDecodeSrgb(tint.rgb);
    return primeLinearSrgbToLinearRec2020(linearSrgbAlbedo);
}

MaterialEvaluation primeEvaluateMaterial(PrimitiveRecord primitive, vec2 uv, float textureLodValue) {
    MaterialEvaluation result;
    // Minecraft stores both atlas texels and tint RGB as display-encoded sRGB in UNORM values.
    // Decode both before multiplication, then cross the single material boundary into the
    // integrator's linear Rec.2020 working space. Alpha is coverage and is never color-decoded.
    result.baseColor = primeAtlasBaseColor(primitive.tint, uv, textureLodValue, result.opacity);
    uint primitiveFlags = primePrimitiveFlags(primitive);
    result.flags = primitiveFlags;
    vec4 normalSample = primeHasLabPbrNormal(primitiveFlags)
            ? textureLod(primeLabPbrNormalAtlas, uv, textureLodValue)
            : vec4(0.5, 0.5, 1.0, 1.0);
    vec4 specularSample = primeHasLabPbrSpecular(primitiveFlags)
            ? textureLod(primeLabPbrSpecularAtlas, uv, textureLodValue)
            : vec4(0.0, 4.0 / 255.0, 0.0, 1.0);
    result.labPbrNormal = packUnorm4x8(normalSample);
    result.labPbrSpecular = packUnorm4x8(specularSample);
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

vec3 primeEvaluateEmitterRadiance(LightEmitter emitter, vec2 uv, float textureLodValue) {
    float opacity;
    vec3 color = primeAtlasBaseColor(emitter.uvsTint.w, uv, textureLodValue, opacity);
    bool cutout = (emitter.metadata.z & PRIME_EMITTER_FLAG_TWO_SIDED) != 0u;
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
    if ((emitter.metadata.z & PRIME_EMITTER_FLAG_LABPBR_EMISSION) != 0u) {
        authoredEmission = primeDecodeLabPbrEmission(
                textureLod(primeLabPbrSpecularAtlas, uv, textureLodValue).a);
    }
    // LabPBR defines only a normalized emissiveness, not absolute radiometry. Prime maps 100%
    // to the same calibrated radiance as a white vanilla level-15 source. Once authored, this
    // channel replaces Minecraft's block-light value; alpha 0 can intentionally turn it off.
    float radianceScale = (emitter.metadata.z & PRIME_EMITTER_FLAG_LABPBR_EMISSION) != 0u
            ? authoredEmission * PRIME_LEVEL_15_BLOCK_INTENSITY
            : max(emitter.edgeOneScale.w, 0.0);
    return color * radianceScale * primeBlockLightRadianceMultiplier();
}

vec3 primeEvaluateEmitterRadiance(LightEmitter emitter, vec2 uv) {
    // A sampled light point has no screen-space ray footprint. The distribution was constructed
    // from mip 0, so evaluating that same radiometric function preserves its PDF contract.
    return primeEvaluateEmitterRadiance(emitter, uv, 0.0);
}

float primeEvaluateOpacity(vec2 uv, float textureLodValue) {
    return textureLod(primeBlockAtlas, uv, textureLodValue).a;
}

#endif
