#language slang 2026
module "post/display_transform.slang";

import "prime_abi_types.slang";
import "core/color_space.slang";

// Ported from the C:/WorkSpace/drt working tree at commit
// fa282723f5e1536b37217c10c74437fad054c87c. Prime keeps its Rec.2020 input
// boundary; the source bench's ACES AP0 conversion is therefore intentionally omitted.
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
    public float maximumLogCoordinate;
    public float outputPeak;
};

public PrimeAgxHsvParameters primeDefaultAgxHsvParameters()
{
    PrimeAgxHsvParameters parameters;
    // The calibrated HSV preset preserves IEC sRGB 18% gray and matches the local
    // identity slope and visible-shadow neutral ramp used by the source bench.
    parameters.minimumLog2 = -12.473931;
    parameters.inverseDynamicRange = 1.0 / 16.5;
    parameters.inputPivot = 10.0 / 16.5;
    parameters.outputPivot = 0.46135613;
    parameters.pivotSlope = 2.4606366;
    parameters.toePower = 1.55;
    parameters.shoulderPower = 5.2;
    parameters.gamutCompression = 0.05;
    parameters.toeCoefficient = 11.219474;
    parameters.shoulderCoefficient = 2568.7498;
    parameters.blackHueRetention = 1.0;
    parameters.whiteHueRetention = 0.5;
    parameters.maximumLogCoordinate = 1.0;
    parameters.outputPeak = 1.0;
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
    // A strong per-channel shoulder can band across highlight saturation transitions.
    // The calibrated 5.2 power is the source bench's current artistic compromise.
    float3 inset = primeAgxInset(linearBt709, parameters);
    float3 normalizedLog = clamp(
            (log2(inset) - parameters.minimumLog2)
                    * parameters.inverseDynamicRange,
            0.0,
            parameters.maximumLogCoordinate);
    return primeAgxOutset(primeAgxCurve(normalizedLog, parameters), parameters);
}

float3 primeAgxAdjustHsv(
        float3 originalLinear,
        float3 mapped,
        PrimeAgxHsvParameters parameters)
{
    float3 originalDisplay = pow(originalLinear, 1.0 / 2.2);
    float3 originalHsv = primeAgxRgbToHsv(originalDisplay);
    float3 mappedHsv = primeAgxRgbToHsv(mapped);
    float normalizedValue = clamp(mappedHsv.z / parameters.outputPeak, 0.0, 1.0);

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

    mappedHsv.y = clamp(mappedHsv.y, 0.0, 1.0);
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
        float maximumLogCoordinate,
        float outputPeak,
        float shoulderCoefficient)
{
    float3 exposedRec2020 = max(hdrRec2020, float3(0.0))
            * (PRIME_DISPLAY_EXPOSURE * exposureMultiplier);
    float3 linearBt709 = max(
            primeLinearRec2020ToLinearBt709(exposedRec2020),
            float3(0.0));
    // AgX-S2O3 produces a display-encoded signal; applying an sRGB OETF again is incorrect.
    PrimeAgxHsvParameters parameters = primeDefaultAgxHsvParameters();
    parameters.maximumLogCoordinate = maximumLogCoordinate;
    parameters.outputPeak = outputPeak;
    parameters.shoulderCoefficient = shoulderCoefficient;
    return clamp(
            primeAgxHsvTonemap(linearBt709, parameters),
            float3(0.0),
            outputPeak.xxx);
}

public float3 primeDisplayTransformToSrgb(
        float3 hdrRec2020,
        float exposureMultiplier)
{
    PrimeAgxHsvParameters parameters = primeDefaultAgxHsvParameters();
    return primeDisplayTransformToSrgb(
            hdrRec2020,
            exposureMultiplier,
            parameters.maximumLogCoordinate,
            parameters.outputPeak,
            parameters.shoulderCoefficient);
}
