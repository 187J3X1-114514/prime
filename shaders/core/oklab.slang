#ifndef PRIME_OKLAB_SLANG
#define PRIME_OKLAB_SLANG

static const float3 PRIME_OKLAB_RED_ROW = float3(4.0767416621, -3.3077115913, 0.2309699292);
static const float3 PRIME_OKLAB_GREEN_ROW = float3(-1.2684380046, 2.6097574011, -0.3413193965);
static const float3 PRIME_OKLAB_BLUE_ROW = float3(-0.0041960863, -0.7034186147, 1.7076147010);

float3 primeLinearBt709ToOklab(float3 color)
{
    float l = 0.4122214708 * color.r + 0.5363325363 * color.g + 0.0514459929 * color.b;
    float m = 0.2119034982 * color.r + 0.6806995451 * color.g + 0.1073969566 * color.b;
    float s = 0.0883024619 * color.r + 0.2817188376 * color.g + 0.6299787005 * color.b;
    float3 lms = pow(float3(l, m, s), float3(1.0 / 3.0));
    return float3(
            0.2104542553 * lms.x + 0.7936177850 * lms.y - 0.0040720468 * lms.z,
            1.9779984951 * lms.x - 2.4285922050 * lms.y + 0.4505937099 * lms.z,
            0.0259040371 * lms.x + 0.7827717662 * lms.y - 0.8086757660 * lms.z);
}

float3 primeOklabToLinearBt709(float3 color)
{
    float lRoot = color.x + 0.3963377774 * color.y + 0.2158037573 * color.z;
    float mRoot = color.x - 0.1055613458 * color.y - 0.0638541728 * color.z;
    float sRoot = color.x - 0.0894841775 * color.y - 1.2914855480 * color.z;
    float3 lms = float3(
            lRoot * lRoot * lRoot,
            mRoot * mRoot * mRoot,
            sRoot * sRoot * sRoot);
    return float3(
            dot(PRIME_OKLAB_RED_ROW, lms),
            dot(PRIME_OKLAB_GREEN_ROW, lms),
            dot(PRIME_OKLAB_BLUE_ROW, lms));
}

#endif
