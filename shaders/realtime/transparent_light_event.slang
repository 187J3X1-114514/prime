#ifndef PRIME_TRANSPARENT_LIGHT_EVENT_SLANG
#define PRIME_TRANSPARENT_LIGHT_EVENT_SLANG

static const uint PRIME_TRANSPARENT_LIGHT_EVENT_NONE = 0u;
static const uint PRIME_TRANSPARENT_LIGHT_EVENT_PRIMARY = 1u;
static const uint PRIME_TRANSPARENT_LIGHT_EVENT_SUN = 2u;
static const uint PRIME_TRANSPARENT_LIGHT_EVENT_AREA = 3u;

struct PrimeTransparentAreaSampleInput {
    float3 position;
    uint receiverNormal;
    float2 treeSample;
    float2 positionSample;
    uint numericalFlags;
    uint numericalContext;
};

#endif
