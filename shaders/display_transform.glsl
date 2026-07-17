#ifndef PRIME_DISPLAY_TRANSFORM_GLSL
#define PRIME_DISPLAY_TRANSFORM_GLSL

#include "prime_color_contract.glsl"
#include "color_space.glsl"

#if !defined(PRIME_COLOR_DISPLAY_TRANSFORM_OKLAB_DRT)
#error "Prime shader ABI does not select the Oklab display transform"
#endif

// This is the only global brightness adjustment at the display boundary. It maps the
// integrator's linear Rec.2020 HDR result to the configured sRGB Rec.709 display and must never
// be applied to PathState, light transport, or the RGBA32F accumulation history.
const float PRIME_OKLAB_EPSILON = 0.000001;

vec3 primeLinearBt709ToOklab(vec3 color) {
    float l = 0.4122214708 * color.r + 0.5363325363 * color.g + 0.0514459929 * color.b;
    float m = 0.2119034982 * color.r + 0.6806995451 * color.g + 0.1073969566 * color.b;
    float s = 0.0883024619 * color.r + 0.2817188376 * color.g + 0.6299787005 * color.b;
    float lRoot = sign(l) * pow(abs(l), 1.0 / 3.0);
    float mRoot = sign(m) * pow(abs(m), 1.0 / 3.0);
    float sRoot = sign(s) * pow(abs(s), 1.0 / 3.0);
    return vec3(
            0.2104542553 * lRoot + 0.7936177850 * mRoot - 0.0040720468 * sRoot,
            1.9779984951 * lRoot - 2.4285922050 * mRoot + 0.4505937099 * sRoot,
            0.0259040371 * lRoot + 0.7827717662 * mRoot - 0.8086757660 * sRoot);
}

vec3 primeOklabToLinearBt709(vec3 color) {
    float lRoot = color.x + 0.3963377774 * color.y + 0.2158037573 * color.z;
    float mRoot = color.x - 0.1055613458 * color.y - 0.0638541728 * color.z;
    float sRoot = color.x - 0.0894841775 * color.y - 1.2914855480 * color.z;
    float l = lRoot * lRoot * lRoot;
    float m = mRoot * mRoot * mRoot;
    float s = sRoot * sRoot * sRoot;
    return vec3(
            4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
            -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
            -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s);
}

float primeOklabEnhancedReinhard(float value, float overexposure) {
    return overexposure * value / (1.0 + value);
}

float primeOklabTonemapLightness(float lightness, float overexposure) {
    float brightness = lightness * lightness * lightness;
    return pow(primeOklabEnhancedReinhard(brightness, overexposure), 1.0 / 3.0);
}

vec3 primeOklabTonemapCurve(vec3 color, float overexposure) {
    const float startCompression = 0.875;
    const vec3 oklabWhite = vec3(1.0, 0.0, 0.0);
    float maxChannel = max(color.r, max(color.g, color.b));
    if (maxChannel <= PRIME_OKLAB_EPSILON) {
        return color;
    }

    vec3 oklab = primeLinearBt709ToOklab(color);
    float targetLightness = primeOklabTonemapLightness(oklab.x, overexposure);
    vec3 rgbTruncate = color / maxChannel;
    vec3 oklabTruncate = primeLinearBt709ToOklab(rgbTruncate);
    vec3 oklabStartCompress = oklabTruncate * startCompression;

    float l0 = oklabStartCompress.x;
    if (targetLightness <= l0) {
        return primeOklabToLinearBt709(oklab * (targetLightness / oklab.x));
    }

    float l1 = oklabTruncate.x;
    float deltaLightness = clamp(targetLightness, l0, oklabWhite.x) - l0;
    float a = l0 - 2.0 * l1 + oklabWhite.x;
    float b = 2.0 * (l1 - l0);
    float discriminant = max(b * b + 4.0 * a * deltaLightness, 0.0);
    float denominator = b + sqrt(discriminant);
    float t = denominator > PRIME_OKLAB_EPSILON
            ? clamp(2.0 * deltaLightness / denominator, 0.0, 1.0)
            : 0.0;
    float oneMinusT = 1.0 - t;
    vec3 faded = oneMinusT * oneMinusT * oklabStartCompress
            + 2.0 * t * oneMinusT * oklabTruncate
            + t * t * oklabWhite;
    return primeOklabToLinearBt709(faded);
}

vec3 primeDisplayTransformToSrgb(vec3 hdrRec2020, float overexposure) {
    vec3 exposedRec2020 = max(hdrRec2020, vec3(0.0)) * PRIME_DISPLAY_EXPOSURE;
    vec3 linearBt709 = max(primeLinearRec2020ToLinearBt709(exposedRec2020), vec3(0.0));
    vec3 encodedSrgb = primeEncodeSrgb(primeOklabTonemapCurve(linearBt709, overexposure));
    return clamp(encodedSrgb, vec3(0.0), vec3(1.0));
}

#endif
