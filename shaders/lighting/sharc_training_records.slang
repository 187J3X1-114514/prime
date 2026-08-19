#ifndef PRIME_SHARC_TRAINING_RECORDS_SLANG
#define PRIME_SHARC_TRAINING_RECORDS_SLANG

import "core/packed_geometry.slang";
#include "lighting/sharc_training_core.slang"

// A selected main-wavefront path follows the complete renderer path and trains one collapsed cache
// root. Training never consumes cached radiance, so changing illumination cannot form a temporal
// feedback edge through another voxel.
static const uint PRIME_SHARC_TRAINING_RECORD_WORDS = 20u;
static const uint PRIME_SHARC_TRAINING_RECORD_BYTES =
        PRIME_SHARC_TRAINING_RECORD_WORDS * 4u;

static const uint PRIME_SHARC_TRAINING_CONTROL_WORD = 0u;
static const uint PRIME_SHARC_TRAINING_ROOT_THROUGHPUT_WORD = 1u;
static const uint PRIME_SHARC_TRAINING_POSITION_WORD = 4u;
static const uint PRIME_SHARC_TRAINING_NORMAL_WORD = 7u;
static const uint PRIME_SHARC_TRAINING_RADIANCE_DIRECTION_WORD = 8u;
// Word 9 preserves the existing 80-byte stride after retiring the two-word bootstrap root key.
static const uint PRIME_SHARC_TRAINING_DEMODULATION_WORD = 10u;
static const uint PRIME_SHARC_TRAINING_DIRECT_WORD = 13u;
static const uint PRIME_SHARC_TRAINING_TAIL_WORD = 16u;
static const uint PRIME_SHARC_TRAINING_DIRECTION_WEIGHT_WORD = 19u;
static const uint PRIME_SHARC_TRAINING_PATH_EVENT_DISCRETE = 0u;
static const uint PRIME_SHARC_TRAINING_PATH_EVENT_GLOSSY = 2u;

struct PrimeSharcTrainingRecord {
    uint control;
    float3 rootThroughput;
    float3 position;
    float3 geometricNormal;
    float3 radianceDirection;
    float3 materialDemodulation;
    float3 direct;
    float3 tail;
    float incomingDirectionWeight;
};

uint* primeSharcTrainingRecordWords() {
    return (uint*)primeSharcFrame.trainingRecordsAddress;
}

bool primeSharcTrainingPixel(uint2 pixel) {
    uint2 phase = uint2(
            primeSharcFrame.updatePhase % 5u,
            primeSharcFrame.updatePhase / 5u);
    return primeSharcFrame.trainingRecordsAddress != uint64_t(0)
            && all((pixel % 5u) == phase);
}

uint primeSharcTrainingPathIndex(uint2 pixel) {
    return (pixel.y / 5u) * primeSharcFrame.trainingWidth + pixel.x / 5u;
}

uint primeSharcTrainingRecordBase(uint pathIndex) {
    return pathIndex * PRIME_SHARC_TRAINING_RECORD_WORDS;
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

uint primeSharcTrainingControl(uint2 pixel) {
    uint* words = primeSharcTrainingRecordWords();
    uint base = primeSharcTrainingRecordBase(
            primeSharcTrainingPathIndex(pixel));
    return words[base + PRIME_SHARC_TRAINING_CONTROL_WORD];
}

float primeSharcTrainingIncomingDirectionWeight(PathState path) {
    if (path.previousSharcEvent == PRIME_SHARC_TRAINING_PATH_EVENT_DISCRETE) {
        return 1.0;
    }
    return path.previousSharcEvent == PRIME_SHARC_TRAINING_PATH_EVENT_GLOSSY
            ? 1.0 - path.previousSharcRoughness
            : 0.0;
}

uint primeSharcTrainingEnsureDirection(
        uint* words,
        uint base,
        uint control,
        PathState path) {
    if (path.bounce <= primeSharcTrainingRootBounce(control)
            || (control & PRIME_SHARC_TRAINING_DIRECTION) != 0u) {
        return control;
    }
    words[base + PRIME_SHARC_TRAINING_DIRECTION_WEIGHT_WORD] = asuint(
            primeSharcTrainingIncomingDirectionWeight(path));
    control |= PRIME_SHARC_TRAINING_DIRECTION;
    words[base + PRIME_SHARC_TRAINING_CONTROL_WORD] = control;
    return control;
}

// Selected training paths bypass every cache query until transport completes.
bool primeSharcTrainingBypassesCache(PathState path) {
    bool selected = primeSharcTrainingPixel(path.pixel);
    uint control = selected ? primeSharcTrainingControl(path.pixel) : 0u;
    return primeSharcTrainingBypassesCacheValue(selected, control);
}

void primeBeginSharcTraining(
        PathState path,
        SurfaceInteraction surface,
        float3 direct) {
    uint* words = primeSharcTrainingRecordWords();
    uint base = primeSharcTrainingRecordBase(
            primeSharcTrainingPathIndex(path.pixel));
    primeSharcTrainingStoreFloat3(
            words,
            base + PRIME_SHARC_TRAINING_ROOT_THROUGHPUT_WORD,
            path.throughput);
    primeSharcTrainingStoreFloat3(
            words, base + PRIME_SHARC_TRAINING_POSITION_WORD, surface.position);
    uint packedNormal = primePackOctahedralNormal(surface.geometricNormal);
    words[base + PRIME_SHARC_TRAINING_NORMAL_WORD] = packedNormal;
    words[base + PRIME_SHARC_TRAINING_RADIANCE_DIRECTION_WORD] =
            primePackOctahedralNormal(
                    primeSharcTrainingRootRadianceDirection(path.rayDirection));
    primeSharcTrainingStoreFloat3(
            words,
            base + PRIME_SHARC_TRAINING_DEMODULATION_WORD,
            primeSharcMaterialDemodulation(surface));
    primeSharcTrainingStoreFloat3(
            words,
            base + PRIME_SHARC_TRAINING_DIRECT_WORD,
            path.throughput * direct);
    primeSharcTrainingStoreFloat3(
            words, base + PRIME_SHARC_TRAINING_TAIL_WORD, float3(0.0));
    words[base + PRIME_SHARC_TRAINING_DIRECTION_WEIGHT_WORD] = asuint(0.0);
    uint control = primeSharcTrainingRootControl(path.bounce);
    // Publish control last. Inter-dispatch barriers make the completed root visible to consumers.
    words[base + PRIME_SHARC_TRAINING_CONTROL_WORD] = control;
}

void primeAccumulateSharcTrainingTarget(PathState path, float3 localRadiance) {
    if (!primeSharcTrainingPixel(path.pixel)) {
        return;
    }
    uint* words = primeSharcTrainingRecordWords();
    uint base = primeSharcTrainingRecordBase(
            primeSharcTrainingPathIndex(path.pixel));
    uint control = words[base + PRIME_SHARC_TRAINING_CONTROL_WORD];
    if (!primeSharcTrainingActive(control)
            || primeSharcTrainingComplete(control)
            || path.bounce <= primeSharcTrainingRootBounce(control)) {
        return;
    }
    primeSharcTrainingEnsureDirection(words, base, control, path);
    float3 tail = primeSharcTrainingLoadFloat3(
            words, base + PRIME_SHARC_TRAINING_TAIL_WORD);
    tail = primeSharcTrainingAccumulateTarget(
            tail, path.throughput, localRadiance);
    primeSharcTrainingStoreFloat3(
            words, base + PRIME_SHARC_TRAINING_TAIL_WORD, tail);
}

void primeCompleteSharcTraining(PathState path, float3 localRadiance) {
    if (!primeSharcTrainingPixel(path.pixel)) {
        return;
    }
    uint* words = primeSharcTrainingRecordWords();
    uint base = primeSharcTrainingRecordBase(
            primeSharcTrainingPathIndex(path.pixel));
    uint control = words[base + PRIME_SHARC_TRAINING_CONTROL_WORD];
    if (!primeSharcTrainingActive(control)
            || primeSharcTrainingComplete(control)) {
        return;
    }
    control = primeSharcTrainingEnsureDirection(words, base, control, path);
    if (any(localRadiance != float3(0.0))) {
        float3 tail = primeSharcTrainingLoadFloat3(
                words, base + PRIME_SHARC_TRAINING_TAIL_WORD);
        tail = primeSharcTrainingAccumulateTarget(
                tail, path.throughput, localRadiance);
        primeSharcTrainingStoreFloat3(
                words, base + PRIME_SHARC_TRAINING_TAIL_WORD, tail);
    }
    // Publish completion after the final target write.
    words[base + PRIME_SHARC_TRAINING_CONTROL_WORD] =
            control | PRIME_SHARC_TRAINING_COMPLETE;
}

PrimeSharcTrainingRecord primeLoadSharcTrainingRecord(uint pathIndex) {
    uint* words = primeSharcTrainingRecordWords();
    uint base = primeSharcTrainingRecordBase(pathIndex);
    PrimeSharcTrainingRecord record;
    record.control = words[base + PRIME_SHARC_TRAINING_CONTROL_WORD];
    record.rootThroughput = primeSharcTrainingLoadFloat3(
            words, base + PRIME_SHARC_TRAINING_ROOT_THROUGHPUT_WORD);
    record.position = primeSharcTrainingLoadFloat3(
            words, base + PRIME_SHARC_TRAINING_POSITION_WORD);
    record.geometricNormal = primeUnpackOctahedralNormal(
            words[base + PRIME_SHARC_TRAINING_NORMAL_WORD]);
    record.radianceDirection = primeUnpackOctahedralNormal(
            words[base + PRIME_SHARC_TRAINING_RADIANCE_DIRECTION_WORD]);
    record.materialDemodulation = primeSharcTrainingLoadFloat3(
            words, base + PRIME_SHARC_TRAINING_DEMODULATION_WORD);
    float3 direct = primeSharcTrainingLoadFloat3(
            words, base + PRIME_SHARC_TRAINING_DIRECT_WORD);
    float3 tail = primeSharcTrainingLoadFloat3(
            words, base + PRIME_SHARC_TRAINING_TAIL_WORD);
    record.direct = primeSharcTrainingRatio(direct, record.rootThroughput);
    record.tail = primeSharcTrainingRatio(tail, record.rootThroughput);
    record.incomingDirectionWeight = asfloat(
            words[base + PRIME_SHARC_TRAINING_DIRECTION_WEIGHT_WORD]);
    return record;
}

#endif
