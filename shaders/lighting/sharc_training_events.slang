#ifndef PRIME_SHARC_TRAINING_EVENTS_SLANG
#define PRIME_SHARC_TRAINING_EVENTS_SLANG

import "core/packed_geometry.slang";

// Four queued vertices match SHARC_PROPAGATION_DEPTH. The main wavefront writes only compact
// transport observations; SHARC's register-heavy propagation state stays in the replay kernel.
static const uint PRIME_SHARC_TRAINING_MAX_EVENTS = 4u;
static const uint PRIME_SHARC_TRAINING_EVENT_WORDS = 20u;
static const uint PRIME_SHARC_TRAINING_EVENT_BYTES =
        PRIME_SHARC_TRAINING_EVENT_WORDS * 4u;
static const uint PRIME_SHARC_TRAINING_EVENT_NONE = 0u;
static const uint PRIME_SHARC_TRAINING_EVENT_HIT = 1u;
static const uint PRIME_SHARC_TRAINING_EVENT_MISS = 2u;
static const uint PRIME_SHARC_TRAINING_PATH_EVENT_DISCRETE = 0u;
static const uint PRIME_SHARC_TRAINING_PATH_EVENT_GLOSSY = 2u;

static const uint PRIME_SHARC_TRAINING_TYPE_WORD = 0u;
static const uint PRIME_SHARC_TRAINING_THROUGHPUT_WORD = 1u;
static const uint PRIME_SHARC_TRAINING_POSITION_WORD = 4u;
static const uint PRIME_SHARC_TRAINING_NORMAL_WORD = 7u;
static const uint PRIME_SHARC_TRAINING_DIRECTION_WORD = 8u;
static const uint PRIME_SHARC_TRAINING_DIRECTION_WEIGHT_WORD = 9u;
static const uint PRIME_SHARC_TRAINING_DEMODULATION_WORD = 10u;
static const uint PRIME_SHARC_TRAINING_EMISSIVE_WORD = 13u;
static const uint PRIME_SHARC_TRAINING_DIRECT_WORD = 16u;
static const uint PRIME_SHARC_TRAINING_INCOMING_WEIGHT_WORD = 19u;

struct PrimeSharcTrainingEvent {
    uint type;
    float3 throughput;
    float3 position;
    float3 geometricNormal;
    float3 radianceDirection;
    float radianceDirectionWeight;
    float3 materialDemodulation;
    float3 emissive;
    float3 direct;
    float incomingDirectionWeight;
};

uint* primeSharcTrainingEventWords() {
    return (uint*)primeSharcFrame.trainingEventsAddress;
}

bool primeSharcTrainingPixel(uint2 pixel) {
    uint2 phase = uint2(
            primeSharcFrame.updatePhase % 5u,
            primeSharcFrame.updatePhase / 5u);
    return primeSharcFrame.trainingEventsAddress != uint64_t(0)
            && all((pixel % 5u) == phase);
}

bool primeSharcTrainingCarrier(PathState path) {
    return path.bounce > 0u
            && path.bounce <= PRIME_SHARC_TRAINING_MAX_EVENTS
            && primeSharcTrainingPixel(path.pixel);
}

uint primeSharcTrainingEventBase(uint2 pixel, uint bounce) {
    uint pathIndex = (pixel.y / 5u) * primeSharcFrame.trainingWidth
            + pixel.x / 5u;
    uint eventIndex = pathIndex * PRIME_SHARC_TRAINING_MAX_EVENTS
            + (bounce - 1u);
    return eventIndex * PRIME_SHARC_TRAINING_EVENT_WORDS;
}

void primeSharcTrainingStoreFloat3(uint* words, uint offset, float3 value) {
    words[offset + 0u] = asuint(value.x);
    words[offset + 1u] = asuint(value.y);
    words[offset + 2u] = asuint(value.z);
}

float3 primeSharcTrainingLoadFloat3(uint* words, uint offset) {
    return float3(
            asfloat(words[offset + 0u]),
            asfloat(words[offset + 1u]),
            asfloat(words[offset + 2u]));
}

float primeSharcTrainingIncomingDirectionWeight(PathState path) {
    if (path.previousSharcEvent == PRIME_SHARC_TRAINING_PATH_EVENT_DISCRETE) {
        return 1.0;
    }
    return path.previousSharcEvent == PRIME_SHARC_TRAINING_PATH_EVENT_GLOSSY
            ? 1.0 - path.previousSharcRoughness
            : 0.0;
}

void primeStoreSharcTrainingHit(
        PathState path,
        SurfaceInteraction surface,
        float3 direct,
        float3 emissive) {
    if (!primeSharcTrainingCarrier(path)) {
        return;
    }
    uint* words = primeSharcTrainingEventWords();
    uint base = primeSharcTrainingEventBase(path.pixel, path.bounce);
    primeSharcTrainingStoreFloat3(
            words, base + PRIME_SHARC_TRAINING_THROUGHPUT_WORD, path.throughput);
    primeSharcTrainingStoreFloat3(
            words, base + PRIME_SHARC_TRAINING_POSITION_WORD, surface.position);
    words[base + PRIME_SHARC_TRAINING_NORMAL_WORD] =
            primePackOctahedralNormal(surface.geometricNormal);
    words[base + PRIME_SHARC_TRAINING_DIRECTION_WORD] =
            primePackOctahedralNormal(-path.rayDirection);
    // The merged one-family NEE estimator does not retain a diffuse/specular split. Diffuse SH
    // encoding is conservative here; subsequent transport still installs its BSDF direction weight.
    words[base + PRIME_SHARC_TRAINING_DIRECTION_WEIGHT_WORD] = asuint(0.0);
    primeSharcTrainingStoreFloat3(
            words,
            base + PRIME_SHARC_TRAINING_DEMODULATION_WORD,
            primeSharcMaterialDemodulation(surface));
    primeSharcTrainingStoreFloat3(
            words, base + PRIME_SHARC_TRAINING_EMISSIVE_WORD, emissive);
    primeSharcTrainingStoreFloat3(
            words, base + PRIME_SHARC_TRAINING_DIRECT_WORD, direct);
    words[base + PRIME_SHARC_TRAINING_INCOMING_WEIGHT_WORD] = asuint(
            primeSharcTrainingIncomingDirectionWeight(path));
    // Publish control last; the final ray-to-compute barrier exposes the completed record.
    words[base + PRIME_SHARC_TRAINING_TYPE_WORD] = PRIME_SHARC_TRAINING_EVENT_HIT;
}

void primeStoreSharcTrainingMiss(PathState path, float3 environmentRadiance) {
    if (!primeSharcTrainingCarrier(path)) {
        return;
    }
    uint* words = primeSharcTrainingEventWords();
    uint base = primeSharcTrainingEventBase(path.pixel, path.bounce);
    primeSharcTrainingStoreFloat3(
            words, base + PRIME_SHARC_TRAINING_THROUGHPUT_WORD, path.throughput);
    primeSharcTrainingStoreFloat3(
            words, base + PRIME_SHARC_TRAINING_POSITION_WORD, environmentRadiance);
    words[base + PRIME_SHARC_TRAINING_INCOMING_WEIGHT_WORD] = asuint(
            primeSharcTrainingIncomingDirectionWeight(path));
    words[base + PRIME_SHARC_TRAINING_TYPE_WORD] = PRIME_SHARC_TRAINING_EVENT_MISS;
}

PrimeSharcTrainingEvent primeLoadSharcTrainingEvent(uint pathIndex, uint eventIndex) {
    uint* words = primeSharcTrainingEventWords();
    uint base = (pathIndex * PRIME_SHARC_TRAINING_MAX_EVENTS + eventIndex)
            * PRIME_SHARC_TRAINING_EVENT_WORDS;
    PrimeSharcTrainingEvent event;
    event.type = words[base + PRIME_SHARC_TRAINING_TYPE_WORD];
    event.throughput = primeSharcTrainingLoadFloat3(
            words, base + PRIME_SHARC_TRAINING_THROUGHPUT_WORD);
    event.position = primeSharcTrainingLoadFloat3(
            words, base + PRIME_SHARC_TRAINING_POSITION_WORD);
    event.geometricNormal = primeUnpackOctahedralNormal(
            words[base + PRIME_SHARC_TRAINING_NORMAL_WORD]);
    event.radianceDirection = primeUnpackOctahedralNormal(
            words[base + PRIME_SHARC_TRAINING_DIRECTION_WORD]);
    event.radianceDirectionWeight = asfloat(
            words[base + PRIME_SHARC_TRAINING_DIRECTION_WEIGHT_WORD]);
    event.materialDemodulation = primeSharcTrainingLoadFloat3(
            words, base + PRIME_SHARC_TRAINING_DEMODULATION_WORD);
    event.emissive = primeSharcTrainingLoadFloat3(
            words, base + PRIME_SHARC_TRAINING_EMISSIVE_WORD);
    event.direct = primeSharcTrainingLoadFloat3(
            words, base + PRIME_SHARC_TRAINING_DIRECT_WORD);
    event.incomingDirectionWeight = asfloat(
            words[base + PRIME_SHARC_TRAINING_INCOMING_WEIGHT_WORD]);
    return event;
}

float primeSharcTrainingThroughputRatio(float current, float previous) {
    return previous > 0.0 ? current / previous : 0.0;
}

float3 primeSharcTrainingThroughputRatio(float3 current, float3 previous) {
    return float3(
            primeSharcTrainingThroughputRatio(current.x, previous.x),
            primeSharcTrainingThroughputRatio(current.y, previous.y),
            primeSharcTrainingThroughputRatio(current.z, previous.z));
}

#endif
