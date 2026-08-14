#language slang 2026
module "post/display_transform.slang";

import "prime_abi_types.slang";
import "core/color_space.slang";
import "post/oklab_display_transform.slang";

public static const uint PRIME_DISPLAY_TRANSFORM_OKLAB = 0u;
public static const uint PRIME_DISPLAY_TRANSFORM_AGX_HSV = 1u;

// Ported from C:/WorkSpace/drt/shaders/agx_hsv.slang at commit
// ac48c20c6a2412a2fad13f53ac37bc82c3f1ac0b.
//
// These are derived runtime coefficients, not the authoring controls. Keeping them in one
// structure makes future tuning explicit without adding per-frame parameters or UI surface area.
public struct PrimeAgxHsvParameters
{
    public float minimumLog2;
    public float inverseDynamicRange;
    public float inputPivot;
    public float outputPivot;
    public float pivotSlope;
    public float toePower;
    public float shoulderPower;
    public float gamutCompression;
    public float toeCoefficient;
    public float shoulderCoefficient;
    public float blackHueRetention;
    public float whiteHueRetention;
    public float blackGamutOnset;
};

public PrimeAgxHsvParameters primeDefaultAgxHsvParameters()
{
    PrimeAgxHsvParameters parameters;
    // Authoring defaults: -10/+6.5 EV around 18% gray, output pivot 0.455, slope 3,
    // toe/shoulder powers 1.5/3, gamut compression 0.05, hue retention 1/0.5.
    parameters.minimumLog2 = -12.473931;
    parameters.inverseDynamicRange = 1.0 / 16.5;
    parameters.inputPivot = 10.0 / 16.5;
    parameters.outputPivot = 0.455;
    parameters.pivotSlope = 3.0;
    parameters.toePower = 1.5;
    parameters.shoulderPower = 3.0;
    parameters.gamutCompression = 0.05;
    parameters.toeCoefficient = 14.810842;
    parameters.shoulderCoefficient = 150.43433;
    parameters.blackHueRetention = 1.0;
    parameters.whiteHueRetention = 0.5;
    parameters.blackGamutOnset = 0.95;
    return parameters;
}

static const float3 PRIME_AGX_NEUTRAL_WEIGHTS = float3(
        0.2120053547549465,
        0.3921825078090138,
        0.3958121374360396);

public float3 primeAgxRgbToHsv(float3 color)
{
    float maximum = max(color.x, max(color.y, color.z));
    float minimum = min(color.x, min(color.y, color.z));
    float chroma = maximum - minimum;
    float hue = 0.0;
    if (chroma > 1.0e-7)
    {
        if (maximum == color.x)
        {
            hue = (color.y - color.z) / chroma;
        }
        else if (maximum == color.y)
        {
            hue = (color.z - color.x) / chroma + 2.0;
        }
        else
        {
            hue = (color.x - color.y) / chroma + 4.0;
        }
        hue = frac(hue / 6.0);
    }
    float saturation = maximum > 1.0e-7 ? chroma / maximum : 0.0;
    return float3(hue, saturation, maximum);
}

public float3 primeAgxHsvToRgb(float3 hsv)
{
    float3 primary = clamp(
            abs(frac(hsv.x + float3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0,
            0.0,
            1.0);
    return hsv.z * lerp(1.0.xxx, primary, hsv.y);
}

float3 primeAgxInset(float3 color, PrimeAgxHsvParameters parameters)
{
    float neutral = dot(color, PRIME_AGX_NEUTRAL_WEIGHTS);
    return lerp(color, neutral.xxx, parameters.gamutCompression);
}

float3 primeAgxOutset(float3 color, PrimeAgxHsvParameters parameters)
{
    float neutral = dot(color, PRIME_AGX_NEUTRAL_WEIGHTS);
    return (color - parameters.gamutCompression * neutral.xxx)
            / (1.0 - parameters.gamutCompression);
}

public float primeAgxCurveComponent(float value, PrimeAgxHsvParameters parameters)
{
    bool toe = value <= parameters.inputPivot;
    float power = toe ? parameters.toePower : parameters.shoulderPower;
    float coefficient = toe
            ? parameters.toeCoefficient
            : parameters.shoulderCoefficient;
    float distance = value - parameters.inputPivot;
    return parameters.outputPivot
            + parameters.pivotSlope * distance
                    * pow(
                            1.0 + coefficient * pow(abs(distance), power),
                            -1.0 / power);
}

float3 primeAgxCurve(float3 value, PrimeAgxHsvParameters parameters)
{
    return float3(
            primeAgxCurveComponent(value.x, parameters),
            primeAgxCurveComponent(value.y, parameters),
            primeAgxCurveComponent(value.z, parameters));
}

float3 primeAgxS2O3(float3 linearBt709, PrimeAgxHsvParameters parameters)
{
    float3 inset = primeAgxInset(linearBt709, parameters);
    float3 normalizedLog = clamp(
            (log2(inset) - parameters.minimumLog2)
                    * parameters.inverseDynamicRange,
            0.0,
            1.0);
    return primeAgxOutset(primeAgxCurve(normalizedLog, parameters), parameters);
}

public float primeAgxCompressHsvSaturation(float saturation, float onset)
{
    if (saturation <= onset) return saturation;
    float headroom = 1.0 - onset;
    float excess = saturation - onset;
    return onset + headroom * excess / (headroom + excess);
}

float3 primeAgxAdjustHsv(
        float3 originalLinear,
        float3 mapped,
        PrimeAgxHsvParameters parameters)
{
    float3 originalDisplay = pow(originalLinear, 1.0 / 2.2);
    float3 originalHsv = primeAgxRgbToHsv(originalDisplay);
    float3 mappedHsv = primeAgxRgbToHsv(mapped);
    float normalizedValue = clamp(mappedHsv.z, 0.0, 1.0);

    if (originalHsv.y > 1.0e-7 && mappedHsv.y > 1.0e-7)
    {
        float retention = lerp(
                parameters.blackHueRetention,
                parameters.whiteHueRetention,
                normalizedValue);
        float hueOffset = originalHsv.x - mappedHsv.x;
        hueOffset -= floor(hueOffset + 0.5);
        mappedHsv.x = frac(mappedHsv.x + retention * hueOffset);
    }

    float gamutOnset = lerp(
            parameters.blackGamutOnset,
            1.0,
            normalizedValue);
    mappedHsv.y = primeAgxCompressHsvSaturation(mappedHsv.y, gamutOnset);
    return primeAgxHsvToRgb(mappedHsv);
}

public float3 primeAgxHsvTonemap(
        float3 linearBt709,
        PrimeAgxHsvParameters parameters)
{
    float3 nonnegative = max(linearBt709, float3(0.0));
    if (all(nonnegative == float3(0.0))) return float3(0.0);
    return primeAgxAdjustHsv(
            nonnegative,
            primeAgxS2O3(nonnegative, parameters),
            parameters);
}

public float3 primeDisplayTransformToSrgb(
        float3 hdrRec2020,
        float exposureMultiplier,
        uint displayTransformMode)
{
    float3 exposedRec2020 = max(hdrRec2020, float3(0.0))
            * (PRIME_DISPLAY_EXPOSURE * exposureMultiplier);
    float3 linearBt709 = max(
            primeLinearRec2020ToLinearBt709(exposedRec2020),
            float3(0.0));
    if (displayTransformMode == PRIME_DISPLAY_TRANSFORM_AGX_HSV)
    {
        // AgX-S2O3 produces a display-encoded signal; applying an sRGB OETF again is incorrect.
        return clamp(
                primeAgxHsvTonemap(linearBt709, primeDefaultAgxHsvParameters()),
                float3(0.0),
                float3(1.0));
    }
    float3 mapped = primeOklabDrtTonemap(
            linearBt709, primeDefaultOklabDrtParameters());
    return clamp(
            primeEncodeSrgb(clamp(mapped, float3(0.0), float3(1.0))),
            float3(0.0),
            float3(1.0));
}

public float3 primeDisplayTransformToSrgb(
        float3 hdrRec2020,
        float exposureMultiplier)
{
    return primeDisplayTransformToSrgb(
            hdrRec2020,
            exposureMultiplier,
            PRIME_DISPLAY_TRANSFORM_OKLAB);
}
