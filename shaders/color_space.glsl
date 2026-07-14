#ifndef PRIME_COLOR_SPACE_GLSL
#define PRIME_COLOR_SPACE_GLSL

#if !defined(PRIME_COLOR_WORKING_SPACE_LINEAR_REC2020_D65) \
        || !defined(PRIME_COLOR_TEXTURE_ENCODING_SRGB) \
        || !defined(PRIME_COLOR_DISPLAY_ENCODING_SRGB)
#error "Prime shader ABI does not declare the required color contract"
#endif

// Prime's estimator contract is linear-light Rec.2020 with a D65 white point. Texture decoding
// must happen before values enter MaterialEvaluation, and display encoding must happen only after
// accumulation. Do not remove, bypass, or locally replace these boundaries: PathState throughput,
// radiance, BSDF/light adapters, accumulation history, and future spectral conversion all share
// this working-space meaning. A different working space requires a coordinated ABI migration.

float primeDecodeSrgbChannel(float encoded) {
    return encoded <= 0.04045
            ? encoded / 12.92
            : pow((encoded + 0.055) / 1.055, 2.4);
}

vec3 primeDecodeSrgb(vec3 encoded) {
    return vec3(
            primeDecodeSrgbChannel(encoded.r),
            primeDecodeSrgbChannel(encoded.g),
            primeDecodeSrgbChannel(encoded.b));
}

float primeEncodeSrgbChannel(float linearValue) {
    float nonNegative = max(linearValue, 0.0);
    return nonNegative <= 0.0031308
            ? 12.92 * nonNegative
            : 1.055 * pow(nonNegative, 1.0 / 2.4) - 0.055;
}

vec3 primeEncodeSrgb(vec3 linearValue) {
    return vec3(
            primeEncodeSrgbChannel(linearValue.r),
            primeEncodeSrgbChannel(linearValue.g),
            primeEncodeSrgbChannel(linearValue.b));
}

vec3 primeLinearSrgbToLinearRec2020(vec3 color) {
    // Explicit row dot-products avoid GLSL mat3 column-major ambiguity.
    return vec3(
            dot(vec3(0.6274039, 0.3292830, 0.0433131), color),
            dot(vec3(0.0690973, 0.9195404, 0.0113623), color),
            dot(vec3(0.0163914, 0.0880133, 0.8955953), color));
}

vec3 primeLinearRec2020ToLinearSrgb(vec3 color) {
    return vec3(
            dot(vec3(1.6604910, -0.5876411, -0.0728499), color),
            dot(vec3(-0.1245505, 1.1328999, -0.0083494), color),
            dot(vec3(-0.0181508, -0.1005789, 1.1187297), color));
}

vec3 primeWorkingToDisplaySrgb(vec3 linearRec2020) {
    return primeEncodeSrgb(primeLinearRec2020ToLinearSrgb(linearRec2020));
}

#endif
