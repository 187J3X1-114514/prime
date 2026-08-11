#ifndef PRIME_DISPLAY_TRANSFORM_SLANG
#define PRIME_DISPLAY_TRANSFORM_SLANG

#include "prime_abi_types.slang"
import "core/color_space.slang";
import "core/oklab.slang";

static const float PRIME_OKLAB_RGB_HEADROOM = 0.99999;

float primeOklabReciprocalPowerShoulder(float x, float curveExponent)
{
    if (x < 0.01) {
        float nextExponent = curveExponent + 1.0;
        return curveExponent * x * (
                1.0 - 0.5 * nextExponent * x
                        + nextExponent * (curveExponent + 2.0) * x * x / 6.0);
    }
    return 1.0 - pow(1.0 + x, -curveExponent);
}

float primeOklabMapLightness(
        float lightness,
        float overexposure,
        float curveExponent,
        float curveCoefficient)
{
    float brightness = lightness * lightness * lightness;
    float x = curveCoefficient * brightness;
    float mappedBrightness = overexposure
            * primeOklabReciprocalPowerShoulder(x, curveExponent);
    return pow(mappedBrightness, 1.0 / 3.0);
}

float3 primeOklabRootDirection(float2 hue)
{
    return float3(
            0.3963377774 * hue.x + 0.2158037573 * hue.y,
            -0.1055613458 * hue.x - 0.0638541728 * hue.y,
            -0.0894841775 * hue.x - 1.2914855480 * hue.y);
}

float primeOklabMaxSaturation(float2 hue, float3 direction)
{
    float k0;
    float k1;
    float k2;
    float k3;
    float k4;
    float3 rgbRow;

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
    float3 roots = float3(1.0) + saturation * direction;
    float3 lms = roots * roots * roots;
    float3 firstLms = 3.0 * direction * roots * roots;
    float3 secondLms = 6.0 * direction * direction * roots;
    float f = dot(rgbRow, lms);
    float f1 = dot(rgbRow, firstLms);
    float f2 = dot(rgbRow, secondLms);
    return saturation - f * f1 / (f1 * f1 - 0.5 * f * f2);
}

float primeOklabConnectedSaturation(float2 hue, float saturation)
{
    const float2 blueNotchAxis = float2(-0.10362546, -0.99461639);
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

float primeOklabCuspLightness(float saturation, float3 direction)
{
    float3 roots = float3(1.0) + saturation * direction;
    float3 lms = roots * roots * roots;
    float3 rgb = float3(
            dot(PRIME_OKLAB_RED_ROW, lms),
            dot(PRIME_OKLAB_GREEN_ROW, lms),
            dot(PRIME_OKLAB_BLUE_ROW, lms));
    return pow(1.0 / max(rgb.r, max(rgb.g, rgb.b)), 1.0 / 3.0);
}

float primeOklabRefineUpperChroma(
        float chroma,
        float lightness,
        float3 direction)
{
    float3 roots = float3(lightness) + chroma * direction;
    float3 lms = roots * roots * roots;
    float3 firstLms = 3.0 * direction * roots * roots;
    float3 secondLms = 6.0 * direction * direction * roots;
    float3 rgb = float3(
            dot(PRIME_OKLAB_RED_ROW, lms),
            dot(PRIME_OKLAB_GREEN_ROW, lms),
            dot(PRIME_OKLAB_BLUE_ROW, lms));
    float3 firstRgb = float3(
            dot(PRIME_OKLAB_RED_ROW, firstLms),
            dot(PRIME_OKLAB_GREEN_ROW, firstLms),
            dot(PRIME_OKLAB_BLUE_ROW, firstLms));
    float3 secondRgb = float3(
            dot(PRIME_OKLAB_RED_ROW, secondLms),
            dot(PRIME_OKLAB_GREEN_ROW, secondLms),
            dot(PRIME_OKLAB_BLUE_ROW, secondLms));
    float3 f = rgb - float3(1.0);
    float3 denominator = firstRgb * firstRgb - 0.5 * f * secondRgb;
    float3 reciprocalStep = firstRgb / denominator;
    float3 step = -f * reciprocalStep;
    step.x = reciprocalStep.x >= 0.0 ? step.x : 1.0e20;
    step.y = reciprocalStep.y >= 0.0 ? step.y : 1.0e20;
    step.z = reciprocalStep.z >= 0.0 ? step.z : 1.0e20;
    return chroma + min(step.r, min(step.g, step.b));
}

float primeOklabSoftMin(float value, float limit, float power)
{
    if (value <= 0.0 || limit <= 0.0) return 0.0;
    float lower = min(value, limit);
    float higher = max(value, limit);
    float ratio = lower / higher;
    return lower * pow(1.0 + pow(ratio, power), -1.0 / power);
}

float primeOklabSoftMin4(float value, float limit)
{
    if (value <= 0.0 || limit <= 0.0) return 0.0;
    float lower = min(value, limit);
    float higher = max(value, limit);
    float ratio = lower / higher;
    float ratio2 = ratio * ratio;
    float root = sqrt(1.0 + ratio2 * ratio2);
    return lower * rsqrt(root);
}

float primeOklabSaturationCap(
        float lightness,
        float maximumSaturation,
        float3 direction)
{
    if (lightness <= 0.0) return maximumSaturation;
    if (lightness >= 1.0) return 0.0;

    float cuspLightness = primeOklabCuspLightness(maximumSaturation, direction);
    float blackChroma = lightness * maximumSaturation;
    float whiteChroma = cuspLightness * maximumSaturation
            * (1.0 - lightness) / (1.0 - cuspLightness);
    whiteChroma = primeOklabRefineUpperChroma(whiteChroma, lightness, direction);

    float t = clamp(
            (lightness - cuspLightness) / (1.0 - cuspLightness), 0.0, 1.0);
    float shoulder = t * (1.0 - t);
    whiteChroma *= 1.0 - 0.0035 * 16.0 * shoulder * shoulder;

    float roundedChroma = primeOklabSoftMin4(blackChroma, whiteChroma);
    return max(roundedChroma / lightness, 0.0);
}

float primeOklabChromaRetention(float lightness)
{
    float lightness2 = lightness * lightness;
    float lightness4 = lightness2 * lightness2;
    float lightness8 = lightness4 * lightness4;
    return 1.0 - lightness8 * lightness4;
}

float primeOklabRoundingPower(float lightness)
{
    float endpointDistance = lightness * (1.0 - lightness);
    return 32.0 - 256.0 * endpointDistance * endpointDistance;
}

float3 primeOklabTonemapCurve(
        float3 color,
        float overexposure,
        float curveExponent,
        float curveCoefficient)
{
    float3 oklab = primeLinearBt709ToOklab(color);
    if (oklab.x <= 0.0) return float3(0.0);

    float outputLightness = primeOklabMapLightness(
            oklab.x, overexposure, curveExponent, curveCoefficient);
    float inputChroma = length(oklab.yz);
    if (inputChroma <= 1.0e-8) {
        return PRIME_OKLAB_RGB_HEADROOM
                * primeOklabToLinearBt709(float3(outputLightness, 0.0, 0.0));
    }

    float2 hue = oklab.yz / inputChroma;
    float3 direction = primeOklabRootDirection(hue);
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
    return PRIME_OKLAB_RGB_HEADROOM * primeOklabToLinearBt709(float3(
            outputLightness,
            outputLightness * outputSaturation * hue));
}

float3 primeDisplayTransformToSrgb(
        float3 hdrRec2020,
        float exposureMultiplier,
        float overexposure,
        float curveExponent,
        float curveCoefficient)
{
    float3 exposedRec2020 = max(hdrRec2020, float3(0.0))
            * (PRIME_DISPLAY_EXPOSURE * exposureMultiplier);
    float3 linearBt709 = max(
            primeLinearRec2020ToLinearBt709(exposedRec2020), float3(0.0));
    float3 encodedSrgb = primeEncodeSrgb(primeOklabTonemapCurve(
            linearBt709, overexposure, curveExponent, curveCoefficient));
    return clamp(encodedSrgb, float3(0.0), float3(1.0));
}

#endif
