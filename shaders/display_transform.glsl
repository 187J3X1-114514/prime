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
const float PRIME_OKLAB_RGB_HEADROOM = 0.99999;
const float PRIME_OKLAB_MIDDLE_GRAY = 0.18;
const vec3 PRIME_OKLAB_RED_ROW = vec3(4.0767416621, -3.3077115913, 0.2309699292);
const vec3 PRIME_OKLAB_GREEN_ROW = vec3(-1.2684380046, 2.6097574011, -0.3413193965);
const vec3 PRIME_OKLAB_BLUE_ROW = vec3(-0.0041960863, -0.7034186147, 1.7076147010);

vec3 primeLinearBt709ToOklab(vec3 color) {
    float l = 0.4122214708 * color.r + 0.5363325363 * color.g + 0.0514459929 * color.b;
    float m = 0.2119034982 * color.r + 0.6806995451 * color.g + 0.1073969566 * color.b;
    float s = 0.0883024619 * color.r + 0.2817188376 * color.g + 0.6299787005 * color.b;
    // The display boundary clamps Rec.709 to non-negative values before this conversion.
    vec3 lms = pow(vec3(l, m, s), vec3(1.0 / 3.0));
    return vec3(
            0.2104542553 * lms.x + 0.7936177850 * lms.y - 0.0040720468 * lms.z,
            1.9779984951 * lms.x - 2.4285922050 * lms.y + 0.4505937099 * lms.z,
            0.0259040371 * lms.x + 0.7827717662 * lms.y - 0.8086757660 * lms.z);
}

vec3 primeOklabToLinearBt709(vec3 color) {
    float lRoot = color.x + 0.3963377774 * color.y + 0.2158037573 * color.z;
    float mRoot = color.x - 0.1055613458 * color.y - 0.0638541728 * color.z;
    float sRoot = color.x - 0.0894841775 * color.y - 1.2914855480 * color.z;
    vec3 lms = vec3(
            lRoot * lRoot * lRoot,
            mRoot * mRoot * mRoot,
            sRoot * sRoot * sRoot);
    return vec3(
            dot(PRIME_OKLAB_RED_ROW, lms),
            dot(PRIME_OKLAB_GREEN_ROW, lms),
            dot(PRIME_OKLAB_BLUE_ROW, lms));
}

float primeOklabShoulderCoefficient(float overexposure) {
    float scale = overexposure
            / (overexposure - PRIME_OKLAB_MIDDLE_GRAY);
    return (scale * scale - 1.0) / PRIME_OKLAB_MIDDLE_GRAY;
}

float primeOklabMapLightness(float lightness, float overexposure) {
    float brightness = lightness * lightness * lightness;
    float x = primeOklabShoulderCoefficient(overexposure) * brightness;
    float inverseRoot = inversesqrt(1.0 + x);

    // p=1/2 reciprocal-power shoulder. This conjugate form keeps middle gray
    // fixed and avoids cancellation as brightness approaches black.
    float mappedBrightness = overexposure
            * x * inverseRoot * inverseRoot / (1.0 + inverseRoot);
    return pow(mappedBrightness, 1.0 / 3.0);
}

vec3 primeOklabRootDirection(vec2 hue) {
    return vec3(
            0.3963377774 * hue.x + 0.2158037573 * hue.y,
            -0.1055613458 * hue.x - 0.0638541728 * hue.y,
            -0.0894841775 * hue.x - 1.2914855480 * hue.y);
}

// Bjorn Ottosson's lower sRGB hull fit followed by one Halley refinement.
float primeOklabMaxSaturation(vec2 hue, vec3 direction) {
    float k0;
    float k1;
    float k2;
    float k3;
    float k4;
    vec3 rgbRow;

    if (-1.88170328 * hue.x - 0.80936493 * hue.y > 1.0) {
        k0 = 1.19086277;
        k1 = 1.76576728;
        k2 = 0.59662641;
        k3 = 0.75515197;
        k4 = 0.56771245;
        rgbRow = PRIME_OKLAB_RED_ROW;
    } else if (1.81444104 * hue.x - 1.19445276 * hue.y > 1.0) {
        k0 = 0.73956515;
        k1 = -0.45954404;
        k2 = 0.08285427;
        k3 = 0.12541070;
        k4 = 0.14503204;
        rgbRow = PRIME_OKLAB_GREEN_ROW;
    } else {
        k0 = 1.35733652;
        k1 = -0.00915799;
        k2 = -1.15130210;
        k3 = -0.50559606;
        k4 = 0.00692167;
        rgbRow = PRIME_OKLAB_BLUE_ROW;
    }

    float saturation = k0
            + k1 * hue.x
            + k2 * hue.y
            + k3 * hue.x * hue.x
            + k4 * hue.x * hue.y;
    vec3 roots = vec3(1.0) + saturation * direction;
    vec3 lms = roots * roots * roots;
    vec3 firstLms = 3.0 * direction * roots * roots;
    vec3 secondLms = 6.0 * direction * direction * roots;
    float f = dot(rgbRow, lms);
    float f1 = dot(rgbRow, firstLms);
    float f2 = dot(rgbRow, secondLms);
    return saturation - f * f1 / (f1 * f1 - 0.5 * f * f2);
}

// Discard Oklab's narrow, disconnected saturated-blue re-entry shell.
float primeOklabConnectedSaturation(vec2 hue, float saturation) {
    const vec2 blueNotchAxis = vec2(-0.10362546, -0.99461639);
    float alignment = max(dot(hue, blueNotchAxis), 0.0);
    float alignment2 = alignment * alignment;
    float alignment4 = alignment2 * alignment2;
    float alignment8 = alignment4 * alignment4;
    float alignment16 = alignment8 * alignment8;
    float alignment32 = alignment16 * alignment16;
    float alignment64 = alignment32 * alignment32;
    float alignment128 = alignment64 * alignment64;
    float alignment256 = alignment128 * alignment128;
    return min(saturation, 0.57 + (1.0 - alignment256));
}

float primeOklabCuspLightness(float saturation, vec3 direction) {
    vec3 roots = vec3(1.0) + saturation * direction;
    vec3 lms = roots * roots * roots;
    vec3 rgb = vec3(
            dot(PRIME_OKLAB_RED_ROW, lms),
            dot(PRIME_OKLAB_GREEN_ROW, lms),
            dot(PRIME_OKLAB_BLUE_ROW, lms));
    return pow(1.0 / max(rgb.r, max(rgb.g, rgb.b)), 1.0 / 3.0);
}

// One vector Halley step from the cusp-white chord to the first upper RGB face.
float primeOklabRefineUpperChroma(
        float chroma,
        float lightness,
        vec3 direction) {
    vec3 roots = vec3(lightness) + chroma * direction;
    vec3 lms = roots * roots * roots;
    vec3 firstLms = 3.0 * direction * roots * roots;
    vec3 secondLms = 6.0 * direction * direction * roots;
    vec3 rgb = vec3(
            dot(PRIME_OKLAB_RED_ROW, lms),
            dot(PRIME_OKLAB_GREEN_ROW, lms),
            dot(PRIME_OKLAB_BLUE_ROW, lms));
    vec3 firstRgb = vec3(
            dot(PRIME_OKLAB_RED_ROW, firstLms),
            dot(PRIME_OKLAB_GREEN_ROW, firstLms),
            dot(PRIME_OKLAB_BLUE_ROW, firstLms));
    vec3 secondRgb = vec3(
            dot(PRIME_OKLAB_RED_ROW, secondLms),
            dot(PRIME_OKLAB_GREEN_ROW, secondLms),
            dot(PRIME_OKLAB_BLUE_ROW, secondLms));
    vec3 f = rgb - vec3(1.0);
    vec3 denominator = firstRgb * firstRgb - 0.5 * f * secondRgb;
    vec3 reciprocalStep = firstRgb / denominator;
    vec3 step = -f * reciprocalStep;
    step = mix(vec3(1.0e20), step, greaterThanEqual(reciprocalStep, vec3(0.0)));
    return chroma + min(step.r, min(step.g, step.b));
}

float primeOklabSoftMin(float value, float limit, float power) {
    if (value <= 0.0 || limit <= 0.0) {
        return 0.0;
    }
    float lower = min(value, limit);
    float higher = max(value, limit);
    float ratio = lower / higher;
    return lower * pow(1.0 + pow(ratio, power), -1.0 / power);
}

float primeOklabSoftMin4(float value, float limit) {
    if (value <= 0.0 || limit <= 0.0) {
        return 0.0;
    }
    float lower = min(value, limit);
    float higher = max(value, limit);
    float ratio = lower / higher;
    float ratio2 = ratio * ratio;
    float root = sqrt(1.0 + ratio2 * ratio2);
    return lower * inversesqrt(root);
}

float primeOklabSaturationCap(
        float lightness,
        float maximumSaturation,
        vec3 direction) {
    if (lightness <= 0.0) {
        return maximumSaturation;
    }
    if (lightness >= 1.0) {
        return 0.0;
    }

    float cuspLightness = primeOklabCuspLightness(maximumSaturation, direction);
    float blackChroma = lightness * maximumSaturation;
    float whiteChroma = cuspLightness * maximumSaturation
            * (1.0 - lightness) / (1.0 - cuspLightness);
    whiteChroma = primeOklabRefineUpperChroma(whiteChroma, lightness, direction);

    // A zero-slope 0.35% margin absorbs the remaining one-step upper-face error.
    float t = clamp(
            (lightness - cuspLightness) / (1.0 - cuspLightness), 0.0, 1.0);
    float shoulder = t * (1.0 - t);
    whiteChroma *= 1.0 - 0.0035 * 16.0 * shoulder * shoulder;

    float roundedChroma = primeOklabSoftMin4(blackChroma, whiteChroma);
    return max(roundedChroma / lightness, 0.0);
}

float primeOklabChromaRetention(float lightness) {
    float lightness2 = lightness * lightness;
    float lightness4 = lightness2 * lightness2;
    float lightness8 = lightness4 * lightness4;
    return 1.0 - lightness8 * lightness4;
}

float primeOklabRoundingPower(float lightness) {
    float endpointDistance = lightness * (1.0 - lightness);
    return 32.0 - 256.0 * endpointDistance * endpointDistance;
}

vec3 primeOklabTonemapCurve(vec3 color, float overexposure) {
    vec3 oklab = primeLinearBt709ToOklab(color);
    if (oklab.x <= 0.0) {
        return vec3(0.0);
    }

    float outputLightness = primeOklabMapLightness(oklab.x, overexposure);
    float inputChroma = length(oklab.yz);
    if (inputChroma <= 1.0e-8) {
        return PRIME_OKLAB_RGB_HEADROOM
                * primeOklabToLinearBt709(vec3(outputLightness, 0.0, 0.0));
    }

    vec2 hue = oklab.yz / inputChroma;
    vec3 direction = primeOklabRootDirection(hue);
    float inputSaturation = inputChroma / oklab.x;
    float maximumSaturation = primeOklabConnectedSaturation(
            hue, primeOklabMaxSaturation(hue, direction));
    float desiredSaturation = inputSaturation
            * primeOklabChromaRetention(outputLightness);
    float saturationCap = primeOklabSaturationCap(
            outputLightness, maximumSaturation, direction);
    float outputSaturation = primeOklabSoftMin(
            desiredSaturation,
            saturationCap,
            primeOklabRoundingPower(outputLightness));
    return PRIME_OKLAB_RGB_HEADROOM * primeOklabToLinearBt709(vec3(
            outputLightness,
            outputLightness * outputSaturation * hue));
}

vec3 primeDisplayTransformToSrgb(
        vec3 hdrRec2020,
        float exposureMultiplier,
        float overexposure) {
    vec3 exposedRec2020 = max(hdrRec2020, vec3(0.0))
            * (PRIME_DISPLAY_EXPOSURE * exposureMultiplier);
    vec3 linearBt709 = max(primeLinearRec2020ToLinearBt709(exposedRec2020), vec3(0.0));
    vec3 encodedSrgb = primeEncodeSrgb(primeOklabTonemapCurve(linearBt709, overexposure));
    return clamp(encodedSrgb, vec3(0.0), vec3(1.0));
}

#endif
