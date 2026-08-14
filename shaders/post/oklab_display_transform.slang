#language slang 2026
module "post/oklab_display_transform.slang";

// Ported from C:/WorkSpace/drt/shaders/oklab_drt.slang at commit
// 39871cd2b82b2456dc528c3c406eb10ef173c352. The source DRT is authored by this
// project; Prime supplies its own color-space boundary and keeps authoring controls internal.
public struct PrimeOklabDrtParameters
{
    public float overexposure;
    public float whiteHueCompression;
    public float hueCompressionOnset;
};

public PrimeOklabDrtParameters primeDefaultOklabDrtParameters()
{
    PrimeOklabDrtParameters parameters;
    parameters.overexposure = 1.0;
    parameters.whiteHueCompression = 1.0;
    parameters.hueCompressionOnset = 0.6;
    return parameters;
}

static const float PRIME_OKLAB_DRT_RGB_HEADROOM = 0.99999;
static const float PRIME_OKLAB_DRT_MIDDLE_GRAY = 0.18;
static const float PRIME_OKLAB_DRT_PI = 3.1415926535897932;
static const float PRIME_OKLAB_DRT_TWO_PI = 6.2831853071795865;
// Oklab hue angles of the linear-sRGB gamut vertices. Secondary axes are attractors;
// primary axes remain fixed and separate adjacent basins.
static const float PRIME_OKLAB_DRT_YELLOW_HUE = 1.9158345171;
static const float PRIME_OKLAB_DRT_GREEN_HUE = 2.4870128323;
static const float PRIME_OKLAB_DRT_CYAN_HUE = 3.3993594221;
static const float PRIME_OKLAB_DRT_BLUE_HUE = 4.6085771567;
static const float PRIME_OKLAB_DRT_MAGENTA_HUE = 5.7310227859;
static const float PRIME_OKLAB_DRT_RED_HUE_WRAPPED = 6.7934128569;
static const float PRIME_OKLAB_DRT_YELLOW_HUE_WRAPPED = 8.1990198243;
static const float3 PRIME_OKLAB_DRT_RED_ROW = float3(
        4.0767416621, -3.3077115913, 0.2309699292);
static const float3 PRIME_OKLAB_DRT_GREEN_ROW = float3(
        -1.2684380046, 2.6097574011, -0.3413193965);
static const float3 PRIME_OKLAB_DRT_BLUE_ROW = float3(
        -0.0041960863, -0.7034186147, 1.7076147010);

float3 primeOklabDrtRgbToOklab(float3 color)
{
    float l = 0.4122214708 * color.r + 0.5363325363 * color.g + 0.0514459929 * color.b;
    float m = 0.2119034982 * color.r + 0.6806995451 * color.g + 0.1073969566 * color.b;
    float s = 0.0883024619 * color.r + 0.2817188376 * color.g + 0.6299787005 * color.b;
    float3 lms = pow(float3(l, m, s), 1.0 / 3.0);
    return float3(
        0.2104542553 * lms.x + 0.7936177850 * lms.y - 0.0040720468 * lms.z,
        1.9779984951 * lms.x - 2.4285922050 * lms.y + 0.4505937099 * lms.z,
        0.0259040371 * lms.x + 0.7827717662 * lms.y - 0.8086757660 * lms.z);
}

float3 primeOklabDrtOklabToRgb(float3 color)
{
    float lRoot = color.x + 0.3963377774 * color.y + 0.2158037573 * color.z;
    float mRoot = color.x - 0.1055613458 * color.y - 0.0638541728 * color.z;
    float sRoot = color.x - 0.0894841775 * color.y - 1.2914855480 * color.z;
    float3 lms = float3(
            lRoot * lRoot * lRoot,
            mRoot * mRoot * mRoot,
            sRoot * sRoot * sRoot);
    return float3(
        dot(PRIME_OKLAB_DRT_RED_ROW, lms),
        dot(PRIME_OKLAB_DRT_GREEN_ROW, lms),
        dot(PRIME_OKLAB_DRT_BLUE_ROW, lms));
}

float primeOklabDrtShoulderCoefficient(float overexposure)
{
    float scale = overexposure / (overexposure - PRIME_OKLAB_DRT_MIDDLE_GRAY);
    return (scale * scale - 1.0) / PRIME_OKLAB_DRT_MIDDLE_GRAY;
}

float primeOklabDrtMapLightness(float lightness, float overexposure)
{
    float brightness = lightness * lightness * lightness;
    float x = primeOklabDrtShoulderCoefficient(overexposure) * brightness;
    float inverseRoot = rsqrt(1.0 + x);
    float mappedBrightness = overexposure * x * inverseRoot * inverseRoot
            / (1.0 + inverseRoot);
    return pow(mappedBrightness, 1.0 / 3.0);
}

float3 primeOklabDrtRootDirection(float2 hue)
{
    return float3(
        0.3963377774 * hue.x + 0.2158037573 * hue.y,
        -0.1055613458 * hue.x - 0.0638541728 * hue.y,
        -0.0894841775 * hue.x - 1.2914855480 * hue.y);
}

float primeOklabDrtWarpHueTowardLower(
        float angle, float lower, float upper, float amount)
{
    float t = (angle - lower) / (upper - lower);
    t -= amount * sin(PRIME_OKLAB_DRT_PI * t) / PRIME_OKLAB_DRT_PI;
    return lerp(lower, upper, t);
}

float primeOklabDrtWarpHueTowardUpper(
        float angle, float lower, float upper, float amount)
{
    float t = (angle - lower) / (upper - lower);
    t += amount * sin(PRIME_OKLAB_DRT_PI * t) / PRIME_OKLAB_DRT_PI;
    return lerp(lower, upper, t);
}

public float2 primeOklabDrtCompressHighlightHue(
        float2 hue,
        float outputLightness,
        PrimeOklabDrtParameters parameters)
{
    float onset = parameters.hueCompressionOnset;
    float t = clamp((outputLightness - onset) / max(1.0 - onset, 1.0e-6), 0.0, 1.0);
    float smoothT = t * t * (3.0 - 2.0 * t);
    float amount = parameters.whiteHueCompression * smoothT;
    if (amount <= 0.0) return hue;

    float angle = atan2(hue.y, hue.x);
    if (angle < PRIME_OKLAB_DRT_YELLOW_HUE) angle += PRIME_OKLAB_DRT_TWO_PI;

    if (angle < PRIME_OKLAB_DRT_GREEN_HUE)
        angle = primeOklabDrtWarpHueTowardLower(
                angle, PRIME_OKLAB_DRT_YELLOW_HUE, PRIME_OKLAB_DRT_GREEN_HUE, amount);
    else if (angle < PRIME_OKLAB_DRT_CYAN_HUE)
        angle = primeOklabDrtWarpHueTowardUpper(
                angle, PRIME_OKLAB_DRT_GREEN_HUE, PRIME_OKLAB_DRT_CYAN_HUE, amount);
    else if (angle < PRIME_OKLAB_DRT_BLUE_HUE)
        angle = primeOklabDrtWarpHueTowardLower(
                angle, PRIME_OKLAB_DRT_CYAN_HUE, PRIME_OKLAB_DRT_BLUE_HUE, amount);
    else if (angle < PRIME_OKLAB_DRT_MAGENTA_HUE)
        angle = primeOklabDrtWarpHueTowardUpper(
                angle, PRIME_OKLAB_DRT_BLUE_HUE, PRIME_OKLAB_DRT_MAGENTA_HUE, amount);
    else if (angle < PRIME_OKLAB_DRT_RED_HUE_WRAPPED)
        angle = primeOklabDrtWarpHueTowardLower(
                angle,
                PRIME_OKLAB_DRT_MAGENTA_HUE,
                PRIME_OKLAB_DRT_RED_HUE_WRAPPED,
                amount);
    else
        angle = primeOklabDrtWarpHueTowardUpper(
                angle,
                PRIME_OKLAB_DRT_RED_HUE_WRAPPED,
                PRIME_OKLAB_DRT_YELLOW_HUE_WRAPPED,
                amount);

    return float2(cos(angle), sin(angle));
}

float primeOklabDrtMaximumSaturation(float2 hue, float3 direction)
{
    float k0;
    float k1;
    float k2;
    float k3;
    float k4;
    float3 rgbRow;
    if (-1.88170328 * hue.x - 0.80936493 * hue.y > 1.0)
    {
        k0 = 1.19086277;
        k1 = 1.76576728;
        k2 = 0.59662641;
        k3 = 0.75515197;
        k4 = 0.56771245;
        rgbRow = PRIME_OKLAB_DRT_RED_ROW;
    }
    else if (1.81444104 * hue.x - 1.19445276 * hue.y > 1.0)
    {
        k0 = 0.73956515;
        k1 = -0.45954404;
        k2 = 0.08285427;
        k3 = 0.12541070;
        k4 = 0.14503204;
        rgbRow = PRIME_OKLAB_DRT_GREEN_ROW;
    }
    else
    {
        k0 = 1.35733652;
        k1 = -0.00915799;
        k2 = -1.15130210;
        k3 = -0.50559606;
        k4 = 0.00692167;
        rgbRow = PRIME_OKLAB_DRT_BLUE_ROW;
    }

    float saturation = k0 + k1 * hue.x + k2 * hue.y
            + k3 * hue.x * hue.x + k4 * hue.x * hue.y;
    float3 roots = 1.0 + saturation * direction;
    float3 lms = roots * roots * roots;
    float3 firstLms = 3.0 * direction * roots * roots;
    float3 secondLms = 6.0 * direction * direction * roots;
    float f = dot(rgbRow, lms);
    float f1 = dot(rgbRow, firstLms);
    float f2 = dot(rgbRow, secondLms);
    return saturation - f * f1 / (f1 * f1 - 0.5 * f * f2);
}

float primeOklabDrtConnectedSaturation(float2 hue, float saturation)
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

float primeOklabDrtCuspLightness(float saturation, float3 direction)
{
    float3 roots = 1.0 + saturation * direction;
    float3 lms = roots * roots * roots;
    float3 rgb = float3(
        dot(PRIME_OKLAB_DRT_RED_ROW, lms),
        dot(PRIME_OKLAB_DRT_GREEN_ROW, lms),
        dot(PRIME_OKLAB_DRT_BLUE_ROW, lms));
    return pow(1.0 / max(rgb.r, max(rgb.g, rgb.b)), 1.0 / 3.0);
}

float primeOklabDrtRefineUpperChroma(
        float chroma, float lightness, float3 direction)
{
    float3 roots = lightness + chroma * direction;
    float3 lms = roots * roots * roots;
    float3 firstLms = 3.0 * direction * roots * roots;
    float3 secondLms = 6.0 * direction * direction * roots;
    float3 rgb = float3(
        dot(PRIME_OKLAB_DRT_RED_ROW, lms),
        dot(PRIME_OKLAB_DRT_GREEN_ROW, lms),
        dot(PRIME_OKLAB_DRT_BLUE_ROW, lms));
    float3 firstRgb = float3(
        dot(PRIME_OKLAB_DRT_RED_ROW, firstLms),
        dot(PRIME_OKLAB_DRT_GREEN_ROW, firstLms),
        dot(PRIME_OKLAB_DRT_BLUE_ROW, firstLms));
    float3 secondRgb = float3(
        dot(PRIME_OKLAB_DRT_RED_ROW, secondLms),
        dot(PRIME_OKLAB_DRT_GREEN_ROW, secondLms),
        dot(PRIME_OKLAB_DRT_BLUE_ROW, secondLms));
    float3 f = rgb - 1.0;
    float3 denominator = firstRgb * firstRgb - 0.5 * f * secondRgb;
    float3 reciprocalStep = firstRgb / denominator;
    float3 step = -f * reciprocalStep;
    step = float3(
        reciprocalStep.x >= 0.0 ? step.x : 1.0e20,
        reciprocalStep.y >= 0.0 ? step.y : 1.0e20,
        reciprocalStep.z >= 0.0 ? step.z : 1.0e20);
    return chroma + min(step.r, min(step.g, step.b));
}

float primeOklabDrtSoftMinimum(float value, float limit, float power)
{
    if (value <= 0.0 || limit <= 0.0) return 0.0;
    float lower = min(value, limit);
    float higher = max(value, limit);
    float ratio = lower / higher;
    return lower * pow(1.0 + pow(ratio, power), -1.0 / power);
}

float primeOklabDrtSoftMinimum4(float value, float limit)
{
    if (value <= 0.0 || limit <= 0.0) return 0.0;
    float lower = min(value, limit);
    float higher = max(value, limit);
    float ratio = lower / higher;
    float ratio2 = ratio * ratio;
    float root = sqrt(1.0 + ratio2 * ratio2);
    return lower * rsqrt(root);
}

float primeOklabDrtSaturationCap(
        float lightness, float maximumSaturation, float3 direction)
{
    if (lightness <= 0.0) return maximumSaturation;
    if (lightness >= 1.0) return 0.0;
    float cusp = primeOklabDrtCuspLightness(maximumSaturation, direction);
    float blackChroma = lightness * maximumSaturation;
    float whiteChroma = cusp * maximumSaturation * (1.0 - lightness) / (1.0 - cusp);
    whiteChroma = primeOklabDrtRefineUpperChroma(whiteChroma, lightness, direction);
    float t = clamp((lightness - cusp) / (1.0 - cusp), 0.0, 1.0);
    float shoulder = t * (1.0 - t);
    whiteChroma *= 1.0 - 0.0035 * 16.0 * shoulder * shoulder;
    float roundedChroma = primeOklabDrtSoftMinimum4(blackChroma, whiteChroma);
    return max(roundedChroma / lightness, 0.0);
}

float primeOklabDrtChromaRetention(float lightness)
{
    float lightness2 = lightness * lightness;
    float lightness4 = lightness2 * lightness2;
    float lightness8 = lightness4 * lightness4;
    return 1.0 - lightness8 * lightness4;
}

float primeOklabDrtRoundingPower(float lightness)
{
    float endpointDistance = lightness * (1.0 - lightness);
    return 32.0 - 256.0 * endpointDistance * endpointDistance;
}

public float3 primeOklabDrtTonemap(
        float3 linearBt709,
        PrimeOklabDrtParameters parameters)
{
    float3 color = max(linearBt709, float3(0.0));
    float3 oklab = primeOklabDrtRgbToOklab(color);
    if (oklab.x <= 0.0) return float3(0.0);
    float outputLightness = primeOklabDrtMapLightness(
            oklab.x, parameters.overexposure);
    float inputChroma = length(oklab.yz);
    if (inputChroma <= 1.0e-8)
    {
        return PRIME_OKLAB_DRT_RGB_HEADROOM
                * primeOklabDrtOklabToRgb(float3(outputLightness, 0.0, 0.0));
    }

    float2 hue = primeOklabDrtCompressHighlightHue(
            oklab.yz / inputChroma, outputLightness, parameters);
    float3 direction = primeOklabDrtRootDirection(hue);
    float inputSaturation = inputChroma / oklab.x;
    float maximumSaturation = primeOklabDrtConnectedSaturation(
            hue, primeOklabDrtMaximumSaturation(hue, direction));
    float desiredSaturation = inputSaturation
            * primeOklabDrtChromaRetention(outputLightness);
    float cap = primeOklabDrtSaturationCap(
            outputLightness, maximumSaturation, direction);
    float outputSaturation = primeOklabDrtSoftMinimum(
            desiredSaturation, cap, primeOklabDrtRoundingPower(outputLightness));
    return PRIME_OKLAB_DRT_RGB_HEADROOM * primeOklabDrtOklabToRgb(float3(
            outputLightness, outputLightness * outputSaturation * hue));
}
