#ifndef PRIME_MATERIAL_GLSL
#define PRIME_MATERIAL_GLSL

#include "color_space.glsl"
#include "default_material.glsl"

// Minimal Minecraft adapter. The integrator consumes only this result, so future material
// models can replace atlas/tint decoding without changing path scheduling or traversal.
struct MaterialEvaluation {
    vec3 baseColor;
    float opacity;
    uint flags;
};

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
    result.flags = primitive.flags;
    return result;
}

vec3 primeEvaluateEmitterRadiance(LightEmitter emitter, vec2 uv, float textureLodValue) {
    float opacity;
    vec3 color = primeAtlasBaseColor(emitter.uvsTint.w, uv, textureLodValue, opacity);
    bool cutout = (emitter.metadata.z & 1u) != 0u;
    if (cutout && opacity < PRIME_CUTOUT_ALPHA_THRESHOLD) {
        return vec3(0.0);
    }
    // Minecraft's 0..15 block-light value is an ordinal influence radius, not radiometry. Prime's
    // documented fallback maps it to level^2/15 so a white level-15 texel has radiance 15. The
    // path geometry term supplies the actual inverse-square falloff; never add it here as well.
    return color * max(emitter.edgeOneScale.w, 0.0);
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
