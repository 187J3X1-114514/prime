#ifndef PRIME_WAVEFRONT_STORAGE_SLANG
#define PRIME_WAVEFRONT_STORAGE_SLANG

// Two signal slots belong to each realtime pixel so transparent reflection and transmission
// keep independent reconstruction histories. The execution-local type contains six hot transport
// lanes; storage adds one cold lane. Transparent paths use it for queued PSR, while ordinary paths
// use the same mutually exclusive lane for the exact previous primary position. Primary area-light
// moments are immutable pixel state and live once per pixel in the queue-buffer prefix.
struct PrimeWavefrontTransportRecord {
    float4 physicalOriginAndPreviousBsdfPdf;
    float4 traceOriginAndPathControl;
    float4 rayDirectionAndDenoiserControl;
    float4 throughputAndNumericalFlags;
    uint4 medium0;
    uint4 medium1;
};

// Four f32 transport lanes, two 16-byte medium lanes and one cold lane give this record its
// explicit 112-byte std430 CPU/GPU ABI. Medium extinction and IOR stay f32 across dispatches.
struct PrimeWavefrontPathRecord {
    PrimeWavefrontTransportRecord transport;
    uint4 psrPacked;
};

[[vk::binding(PRIME_DESCRIPTOR_WAVEFRONT_PATHS, PRIME_RENDERER_DESCRIPTOR_SET)]]
RWStructuredBuffer<PrimeWavefrontPathRecord> primeWavefrontPaths;

[[vk::binding(PRIME_DESCRIPTOR_WAVEFRONT_QUEUE, PRIME_RENDERER_DESCRIPTOR_SET)]]
RWStructuredBuffer<uint> primeWavefrontQueue;

static const uint PRIME_WAVEFRONT_REACHED_SOLID_ANGLE = 2u;
static const uint PRIME_WAVEFRONT_DIFFUSE_PATH = 4u;
static const uint PRIME_WAVEFRONT_TRANSPARENT_BRANCH = 8u;
static const uint PRIME_WAVEFRONT_TRANSMISSION_BRANCH = 16u;
static const uint PRIME_WAVEFRONT_GUIDE_ENABLED = 32u;
static const uint PRIME_WAVEFRONT_DIRECTIONAL_GUIDE = 64u;
static const uint PRIME_WAVEFRONT_BRANCH_CONTROL_MASK = 0x78u;
static const uint PRIME_WAVEFRONT_BOUNCE_SHIFT = 0u;
static const uint PRIME_WAVEFRONT_ETA_SCALE_SHIFT = 8u;
static const uint PRIME_WAVEFRONT_ETA_SCALE_MASK = 0xffffu;
static const uint PRIME_WAVEFRONT_PATH_FLAGS_SHIFT = 24u;
static const uint PRIME_WAVEFRONT_PATH_FLAGS_MASK =
        PRIME_PATH_PREVIOUS_DISCRETE | PRIME_PATH_PREVIOUS_MIXED_LIGHT_NEE;
static const uint PRIME_WAVEFRONT_PRIMARY_BOUNCE_SHIFT = 8u;
static const uint PRIME_WAVEFRONT_MEDIUM_COUNT_SHIFT = 16u;
// Bits 18..31 are the complete current material-flag domain. Extending material flags beyond
// bit 13 requires a queue ABI migration rather than silent truncation here.
static const uint PRIME_WAVEFRONT_PRIMARY_FLAGS_SHIFT = 18u;
static const uint PRIME_WAVEFRONT_BYTE_MASK = 0xffu;
static const uint PRIME_WAVEFRONT_MEDIUM_COUNT_MASK = 0x3u;
static const uint PRIME_WAVEFRONT_PRIMARY_FLAGS_MASK = 0x3fffu;
static const uint PRIME_WAVEFRONT_NUMERICAL_FLAGS_MASK = 0x1ffu;
static const uint PRIME_WAVEFRONT_NUMERICAL_CONTEXT_SHIFT = 9u;
static const uint PRIME_WAVEFRONT_NUMERICAL_CONTEXT_MASK = 0x7fffu;
static const uint PRIME_WAVEFRONT_SHARC_SHIFT = 24u;
static const uint PRIME_WAVEFRONT_SHARC_ROUGHNESS_MASK = 0x3fu;
static const uint PRIME_WAVEFRONT_SHARC_EVENT_SHIFT = 6u;
static const uint PRIME_WAVEFRONT_LOW_DIAGNOSTIC_MASK = 0xffffffu;
static const uint PRIME_WAVEFRONT_PSR_CONTROL_SHIFT = 25u;
static const uint PRIME_WAVEFRONT_PSR_CONTROL_MASK = 0x3fu;
static const uint PRIME_WAVEFRONT_LIGHT_NORMAL_SHIFT = 16u;
static const uint PRIME_WAVEFRONT_LIGHT_NORMAL_MASK = 0xffffu;
static const uint PRIME_WAVEFRONT_PRIMARY_MOTION = 1u;

uint primeWavefrontIndex(uint2 pixel) {
    return pixel.y * primePush.outputExtent.x + pixel.x;
}

uint primeWavefrontPixelCount() {
    return primePush.outputExtent.x * primePush.outputExtent.y;
}

uint primeWavefrontQueueCapacity() {
    return primeWavefrontPixelCount() * PRIME_WAVEFRONT_QUEUE_ENTRIES_PER_PIXEL;
}

uint primeWavefrontIndex(uint2 pixel, uint branch) {
    return primeWavefrontIndex(pixel) + branch * primeWavefrontPixelCount();
}

uint2 primeWavefrontPixel(uint pathIndex) {
    uint pixelIndex = pathIndex % primeWavefrontPixelCount();
    return uint2(
            pixelIndex % primePush.outputExtent.x,
            pixelIndex / primePush.outputExtent.x);
}

uint primeWavefrontQueueCommandWord(uint queue) {
    uint areaWords = primeWavefrontPixelCount()
            * (PRIME_WAVEFRONT_AREA_RECORD_SIZE / 4u);
    return areaWords + queue * (PRIME_WAVEFRONT_QUEUE_COMMAND_STRIDE / 4u);
}

uint primeWavefrontQueueWord(uint queue, uint entry) {
    uint indexWords = primeWavefrontQueueCommandWord(PRIME_WAVEFRONT_QUEUE_COUNT);
    return indexWords + queue * primeWavefrontQueueCapacity() + entry;
}

static const uint PRIME_WAVEFRONT_AREA_GUIDE_WORDS = 4u;

uint primeWavefrontAreaWord(uint2 pixel, uint component) {
    return primeWavefrontIndex(pixel) * PRIME_WAVEFRONT_AREA_GUIDE_WORDS + component;
}

uint3 primePackWavefrontPair(float3 first, float3 second) {
    return uint3(
            packHalf2x16(first.xy),
            packHalf2x16(float2(first.z, second.x)),
            packHalf2x16(second.yz));
}

void primeUnpackWavefrontPair(uint3 packedPair, out float3 first, out float3 second) {
    float2 first01 = unpackHalf2x16ToFloat(packedPair.x);
    float2 first2Second0 = unpackHalf2x16ToFloat(packedPair.y);
    float2 second12 = unpackHalf2x16ToFloat(packedPair.z);
    first = float3(first01, first2Second0.x);
    second = float3(first2Second0.y, second12);
}

void primeStoreWavefrontArea(uint2 pixel, PrimeDenoiserGuides guides) {
    uint3 radiance = primePackWavefrontPair(
            primeNrdSanitizeRadiance(guides.primaryAreaDiffuse),
            primeNrdSanitizeRadiance(guides.primaryAreaSpecular));
    float3 direction = guides.primaryAreaDirection;
    if (!(dot(direction, direction) > 0.0)) {
        direction = float3(0.0, 1.0, 0.0);
    }
    primeWavefrontQueue[primeWavefrontAreaWord(pixel, 0u)] = radiance.x;
    primeWavefrontQueue[primeWavefrontAreaWord(pixel, 1u)] = radiance.y;
    primeWavefrontQueue[primeWavefrontAreaWord(pixel, 2u)] = radiance.z;
    primeWavefrontQueue[primeWavefrontAreaWord(pixel, 3u)] =
            primePackOctahedralNormal(direction);
}

void primeLoadWavefrontArea(
        uint2 pixel,
        out float3 diffuse,
        out float3 specular,
        out float3 direction) {
    uint3 radiance = uint3(
            primeWavefrontQueue[primeWavefrontAreaWord(pixel, 0u)],
            primeWavefrontQueue[primeWavefrontAreaWord(pixel, 1u)],
            primeWavefrontQueue[primeWavefrontAreaWord(pixel, 2u)]);
    primeUnpackWavefrontPair(radiance, diffuse, specular);
    direction = primeUnpackOctahedralNormal(
            primeWavefrontQueue[primeWavefrontAreaWord(pixel, 3u)]);
}

bool primeWavefrontTransparentBranch(uint control) {
    return (control & PRIME_WAVEFRONT_TRANSPARENT_BRANCH) != 0u;
}

bool primeWavefrontTransmissionBranch(uint control) {
    return (control & PRIME_WAVEFRONT_TRANSMISSION_BRANCH) != 0u;
}

bool primeWavefrontGuideEnabled(uint control) {
    return (control & PRIME_WAVEFRONT_GUIDE_ENABLED) != 0u;
}

bool primeWavefrontDirectionalGuide(uint control) {
    return (control & PRIME_WAVEFRONT_DIRECTIONAL_GUIDE) != 0u;
}

bool primeWavefrontPixelTransparent(uint2 pixel) {
    uint control = asuint(
            primeWavefrontPaths[primeWavefrontIndex(pixel, 0u)]
                    .transport.rayDirectionAndDenoiserControl.w);
    return primeWavefrontTransparentBranch(control);
}

PrimeIntegrationResult primeLoadWavefrontIntermediate(
        uint2 pixel,
        uint pathIndex,
        PrimeWavefrontTransportRecord record) {
    int2 coordinate = int2(pixel);
    float4 diffuse = primeImageLoad(primeNrdNoisyDiffuse, coordinate);
    float4 specular = primeImageLoad(primeNrdNoisySpecular, coordinate);
    float4 stable = primeImageLoad(primeStableRadiance, coordinate);
    float4 sun = primeImageLoad(primeNrdSunLighting, coordinate);
    float4 position = primeImageLoad(primeNrdPrimaryPosition, coordinate);
    float4 material = primeImageLoad(primeNrdMaterial, coordinate);
    float4 specularMaterial = primeImageLoad(primeNrdSpecularMaterial, coordinate);
    uint denoiserControl =
            asuint(record.rayDirectionAndDenoiserControl.w);

    PrimeIntegrationResult result;
    result.radiance.diffuse = diffuse.rgb;
    result.radiance.specular = specular.rgb;
    result.radiance.stable = stable.rgb;
    result.radiance.unshadowedSun = sun.rgb;
    result.radiance.sunVisibility = sun.a;
    result.guides = primeEmptyDenoiserGuides();
    result.guides.primaryDistance = position.w;
    result.guides.specularHitDistance = specular.a;
    result.guides.diffuseHitDistance = diffuse.a;
    result.guides.sunPenumbra = primeImageLoad(primeNrdSunPenumbra, coordinate).r;
    result.guides.primaryAlbedo = material.rgb;
    result.guides.primaryHitKind = position.w >= 0.0
            ? PRIME_HIT_SURFACE
            : PRIME_HIT_NONE;
    float primaryNormalX = primeImageLoad(primeNrdViewZ, coordinate).r;
    result.guides.primaryNormal = primeUnpackOctahedralNormal(
            packSnorm2x16(float2(primaryNormalX, specularMaterial.a)));
    result.guides.primaryMaterialFlags =
            (denoiserControl >> PRIME_WAVEFRONT_PRIMARY_FLAGS_SHIFT)
                    & PRIME_WAVEFRONT_PRIMARY_FLAGS_MASK;
    result.guides.primarySpecularAlbedo = specularMaterial.rgb;
    result.guides.primaryLinearRoughness = material.w;
    result.guides.primaryPosition = position.xyz;
    uint4 previous = primeWavefrontPaths[pathIndex].psrPacked;
    result.guides.primaryHasMotion =
            (previous.w & PRIME_WAVEFRONT_PRIMARY_MOTION) != 0u;
    result.guides.primaryPreviousPosition = result.guides.primaryHasMotion
            ? asfloat(previous.xyz)
            : position.xyz;
    if (primeWritesNrdShInputs()) {
        result.guides.diffuseDirection =
                primeRestoreFp16Direction(
                        primeImageLoad(primeNrdDiffuseDirection, coordinate).xyz);
        result.guides.specularDirection =
                primeRestoreFp16Direction(
                        primeImageLoad(primeNrdSpecularDirection, coordinate).xyz);
    }
    primeLoadWavefrontArea(
            pixel,
            result.guides.primaryAreaDiffuse,
            result.guides.primaryAreaSpecular,
            result.guides.primaryAreaDirection);
    result.reflectionDiffuseRadiance = float3(0.0);
    result.reflectionSpecularRadiance = float3(0.0);
    result.transmissionGuides = primeEmptyDenoiserGuides();
    result.reflectionGuides = primeEmptyDenoiserGuides();
    result.transmissionAnchorDistance = -1.0;
    result.reflectionDirectionalGuide = false;
    result.transparentPrimary = false;
    return result;
}

#endif
