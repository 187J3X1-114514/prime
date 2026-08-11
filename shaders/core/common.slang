#ifndef PRIME_COMMON_SLANG
#define PRIME_COMMON_SLANG

#include "prime_abi.slang"
#include "core/numerical.slang"
#include "core/transport_math.slang"

static const uint PRIME_HIT_KIND_MASK = 0xffu;
static const uint PRIME_SURFACE_MOTION_FLAG = 0x100u;

float2 primeUnpackUv(uint packedValue) {
    uint2 fixedUv = uint2(packedValue & 0xffffu, packedValue >> 16u);
    float2 uv = float2(fixedUv) * (1.0 / 65536.0);
    return float2(
            fixedUv.x == 0xffffu ? 1.0 : uv.x,
            fixedUv.y == 0xffffu ? 1.0 : uv.y);
}

float4 primeUnpackTint(uint packedValue) {
    return unpackUnorm4x8ToFloat(packedValue);
}

bool primeSurfaceHasMotion(SurfaceInteraction surface) {
    return (surface.motionZFlags & PRIME_SURFACE_MOTION_FLAG) != 0u;
}

uint primeSurfaceEmitterIndex(SurfaceInteraction surface) {
    // Dynamic geometry cannot be a light-tree emitter. Its payload emitter lane therefore carries
    // two FP16 motion components until this semantic accessor restores the no-emitter contract.
    return primeSurfaceHasMotion(surface) ? 0xffffffffu : surface.emitterIndex;
}

float3 primeSurfacePreviousPosition(SurfaceInteraction surface) {
    if (!primeSurfaceHasMotion(surface)) {
        return surface.position;
    }
    float2 xy = unpackHalf2x16ToFloat(surface.emitterIndex);
    float z = unpackHalf2x16ToFloat(surface.motionZFlags).y;
    return surface.position + float3(xy, z);
}

uint primeSampleIndex() {
    return primePush.path.x & PRIME_PATH_SAMPLE_INDEX_MASK;
}

float primeSolarLongitudeRadians() {
    uint encoded = (primePush.path.x >> PRIME_PATH_SOLAR_LONGITUDE_SHIFT)
            & PRIME_PATH_SOLAR_LONGITUDE_MASK;
    return radians(float(encoded));
}

bool primeUsesSeamlessGlass() {
    return (primePush.path.x & PRIME_PATH_SEAMLESS_GLASS_MASK) != 0u;
}

bool primeUsesAirGap() {
    return (primePush.path.x & PRIME_PATH_AIR_GAP_MASK) != 0u;
}

bool primeUsesVanillaPbrPresets() {
    return (primePush.path.x & PRIME_PATH_VANILLA_PBR_PRESETS_MASK) != 0u;
}

float primeObserverLatitudeRadians() {
    uint encoded = (primePush.path.z >> PRIME_PATH_LATITUDE_SHIFT)
            & PRIME_PATH_LATITUDE_MASK;
    return radians(float(int(encoded) - PRIME_PATH_LATITUDE_BIAS));
}

uint primeMaximumBounces() {
    return min(
            primePush.path.z & PRIME_PATH_MAXIMUM_BOUNCES_MASK,
            PRIME_MAXIMUM_BOUNCES);
}

int primeDecodeEvQuarterSteps(uint shift) {
    uint encoded = (primePush.path.w >> shift) & PRIME_PATH_EV_QUARTER_MASK;
    return int(encoded) - PRIME_PATH_EV_QUARTER_BIAS;
}

float primeSunRadianceMultiplier() {
    return exp2(0.25 * float(primeDecodeEvQuarterSteps(PRIME_PATH_SUN_EV_QUARTER_SHIFT)));
}

float primeBlockLightRadianceMultiplier() {
    return exp2(0.25 * float(primeDecodeEvQuarterSteps(
            PRIME_PATH_BLOCK_LIGHT_EV_QUARTER_SHIFT)));
}

float primeStarRadianceMultiplier() {
    uint encoded = (primePush.path.w >> PRIME_PATH_STAR_EV_QUARTER_SHIFT)
            & PRIME_PATH_STAR_EV_QUARTER_MASK;
    int quarterSteps = int(encoded) - PRIME_PATH_STAR_EV_QUARTER_BIAS;
    return exp2(0.25 * float(quarterSteps));
}

bool primeWritesNrdShInputs() {
    return (primePush.path.w & PRIME_PATH_SH_INPUT_MASK) != 0u;
}

bool primeWritesRawNumericalDiagnostic() {
    return (primePush.path.w & PRIME_PATH_RAW_NUMERICAL_MASK) != 0u;
}

uint primeSampleEpoch() {
    return primePush.path.y & PRIME_PATH_SAMPLE_EPOCH_MASK;
}

bool primeVisualizesTriangles() {
    return (primePush.path.y & PRIME_PATH_TRIANGLE_DEBUG_MASK) != 0u;
}

struct PrimeNumericalDiagnostics
{
    uint flags;
    uint context;
    uint firstContext;
};

PrimeNumericalDiagnostics primeNumericalDiagnosticsCreate()
{
    PrimeNumericalDiagnostics result;
    result.flags = 0;
    result.context = 0;
    result.firstContext = 0;
    return result;
}

void primeSetNumericalContext(
        inout PrimeNumericalDiagnostics diagnostics, uint stage, uint bounce)
{
    if (primeWritesRawNumericalDiagnostic()) {
        diagnostics.context = (stage & 0x0f) | (min(bounce, 255) << 4);
    }
}

void primeRecordNumerical(
        inout PrimeNumericalDiagnostics diagnostics, uint flags, uint field)
{
    if (flags == 0) return;
    if (diagnostics.firstContext == 0) {
        diagnostics.firstContext =
                1 + diagnostics.context + ((field & 0x0f) << 12);
    }
    diagnostics.flags |= flags;
}

float4 primeRawNumericalMetadata(PrimeNumericalDiagnostics diagnostics)
{
    if (diagnostics.flags == 0) return float4(0.0);
    uint context = diagnostics.firstContext - 1;
    return float4(
            float(diagnostics.flags),
            float(context & 0x0f),
            float((context >> 4) & 0xff),
            float((context >> 12) & 0x0f));
}

void primeRecordNonFinite(inout PrimeNumericalDiagnostics diagnostics, float value)
{
    if (primeWritesRawNumericalDiagnostic()) {
        primeRecordNumerical(
                diagnostics,
                primeClassifyNonFinite(value),
                PRIME_NUMERICAL_FIELD_NON_FINITE);
    }
}

void primeRecordNonFinite(inout PrimeNumericalDiagnostics diagnostics, float3 value)
{
    if (primeWritesRawNumericalDiagnostic()) {
        primeRecordNumerical(
                diagnostics,
                primeClassifyNonFinite(value),
                PRIME_NUMERICAL_FIELD_NON_FINITE);
    }
}

void primeRecordNonnegative(inout PrimeNumericalDiagnostics diagnostics, float value)
{
    if (primeWritesRawNumericalDiagnostic()) {
        primeRecordNumerical(
                diagnostics,
                primeClassifyNonnegative(value),
                PRIME_NUMERICAL_FIELD_NONNEGATIVE);
    }
}

void primeRecordNonnegative(inout PrimeNumericalDiagnostics diagnostics, float3 value)
{
    if (primeWritesRawNumericalDiagnostic()) {
        primeRecordNumerical(
                diagnostics,
                primeClassifyNonnegative(value),
                PRIME_NUMERICAL_FIELD_NONNEGATIVE);
    }
}

void primeRecordRadiance(inout PrimeNumericalDiagnostics diagnostics, float3 value)
{
    if (primeWritesRawNumericalDiagnostic()) {
        primeRecordNumerical(
                diagnostics,
                primeClassifyRadiance(value),
                PRIME_NUMERICAL_FIELD_RADIANCE);
    }
}

void primeRecordUnit(inout PrimeNumericalDiagnostics diagnostics, float value)
{
    if (primeWritesRawNumericalDiagnostic()) {
        primeRecordNumerical(
                diagnostics,
                primeClassifyUnit(value),
                PRIME_NUMERICAL_FIELD_UNIT);
    }
}

void primeRecordUnit(inout PrimeNumericalDiagnostics diagnostics, float3 value)
{
    if (primeWritesRawNumericalDiagnostic()) {
        primeRecordNumerical(
                diagnostics,
                primeClassifyUnit(value),
                PRIME_NUMERICAL_FIELD_UNIT);
    }
}

void primeRecordDirection(inout PrimeNumericalDiagnostics diagnostics, float3 value)
{
    if (primeWritesRawNumericalDiagnostic()) {
        primeRecordNumerical(
                diagnostics,
                primeClassifyDirection(value),
                PRIME_NUMERICAL_FIELD_DIRECTION);
    }
}
float2 primeSignNotZero(float2 value) {
    return float2(value.x >= 0.0 ? 1.0 : -1.0, value.y >= 0.0 ? 1.0 : -1.0);
}

float3 primeUnpackOctahedralNormal(uint packedValue) {
    float2 encoded = unpackSnorm2x16ToFloat(packedValue);
    float3 normal = float3(encoded, 1.0 - abs(encoded.x) - abs(encoded.y));
    if (normal.z < 0.0) {
        normal.xy = (1.0 - abs(normal.yx)) * primeSignNotZero(normal.xy);
    }
    return normalize(normal);
}

float primeConfiguredDefaultLinearRoughness() {
    uint encoded = (primePush.path.w >> PRIME_PATH_MATERIAL_ROUGHNESS_SHIFT)
            & PRIME_PATH_MATERIAL_ROUGHNESS_MASK;
    return float(encoded) / PRIME_PATH_MATERIAL_ROUGHNESS_STEPS_PER_UNIT;
}

// default_material.slang is also compiled by standalone NRD compute passes that have a different
// push-constant ABI. Ray-tracing stages include common.slang first and therefore replace the
// compile-time reference only where PrimePushConstants is actually available.
#define PRIME_RUNTIME_DEFAULT_LINEAR_ROUGHNESS primeConfiguredDefaultLinearRoughness()

uint primePackOctahedralNormal(float3 value) {
    float3 normal = normalize(value);
    normal /= max(abs(normal.x) + abs(normal.y) + abs(normal.z), 1.0e-20);
    if (normal.z < 0.0) {
        normal.xy = (1.0 - abs(normal.yx)) * primeSignNotZero(normal.xy);
    }
    return packSnorm2x16(clamp(normal.xy, float2(-1.0), float2(1.0)));
}

#endif
