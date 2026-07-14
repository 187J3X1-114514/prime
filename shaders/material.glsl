#ifndef PRIME_MATERIAL_GLSL
#define PRIME_MATERIAL_GLSL

#include "color_space.glsl"

// Minimal Minecraft adapter. The integrator consumes only this result, so future material
// models can replace atlas/tint decoding without changing path scheduling or traversal.
struct MaterialEvaluation {
    vec3 baseColor;
    float opacity;
    uint flags;
};

MaterialEvaluation primeEvaluateMaterial(PrimitiveRecord primitive, vec2 uv) {
    vec4 textureSample = textureLod(primeBlockAtlas, uv, 0.0);
    vec4 tint = primeUnpackTint(primitive.tint);
    MaterialEvaluation result;
    // Minecraft stores both atlas texels and tint RGB as display-encoded sRGB in UNORM values.
    // Decode both before multiplication, then cross the single material boundary into the
    // integrator's linear Rec.2020 working space. Alpha is coverage and is never color-decoded.
    vec3 linearSrgbAlbedo = primeDecodeSrgb(textureSample.rgb) * primeDecodeSrgb(tint.rgb);
    result.baseColor = primeLinearSrgbToLinearRec2020(linearSrgbAlbedo);
    result.opacity = textureSample.a;
    result.flags = primitive.flags;
    return result;
}

float primeEvaluateOpacity(vec2 uv) {
    return textureLod(primeBlockAtlas, uv, 0.0).a;
}

#endif
