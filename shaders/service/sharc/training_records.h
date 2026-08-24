#ifndef PRIME_SHARC_TRAINING_RECORDS_H
#define PRIME_SHARC_TRAINING_RECORDS_H

import "math/packed_geometry.slang";
#include "service/sharc/training_core.h"

// One fully traced carrier path trains its first four distinct cache roots. Adjacent roots own
// consecutive camera-weighted tail segments, so each bounce updates one float3 without
// subtracting large path prefixes or rewriting every root.
static const uint PRIME_SHARC_TRAINING_HEADER_WORDS = 4u;
static const uint PRIME_SHARC_TRAINING_ANCHOR_WORDS = 19u;
static const uint PRIME_SHARC_TRAINING_RECORD_WORDS =
        PRIME_SHARC_TRAINING_HEADER_WORDS
        + PRIME_SHARC_TRAINING_ANCHOR_CAPACITY
                * PRIME_SHARC_TRAINING_ANCHOR_WORDS;
static const uint PRIME_SHARC_TRAINING_RECORD_BYTES =
        PRIME_SHARC_TRAINING_RECORD_WORDS * 4u;

static const uint PRIME_SHARC_TRAINING_CONTROL_WORD = 0u;

static const uint PRIME_SHARC_TRAINING_ANCHOR_CONTROL_WORD = 0u;
static const uint PRIME_SHARC_TRAINING_ROOT_THROUGHPUT_WORD = 1u;
static const uint PRIME_SHARC_TRAINING_POSITION_WORD = 4u;
static const uint PRIME_SHARC_TRAINING_NORMAL_WORD = 7u;
static const uint PRIME_SHARC_TRAINING_RADIANCE_DIRECTION_WORD = 8u;
static const uint PRIME_SHARC_TRAINING_DEMODULATION_WORD = 9u;
static const uint PRIME_SHARC_TRAINING_DIRECT_WORD = 12u;
static const uint PRIME_SHARC_TRAINING_TAIL_WORD = 15u;
static const uint PRIME_SHARC_TRAINING_DIRECTION_WEIGHT_WORD = 18u;
static const uint PRIME_SHARC_TRAINING_PATH_EVENT_DISCRETE = 0u;
static const uint PRIME_SHARC_TRAINING_PATH_EVENT_GLOSSY = 2u;

struct PrimeSharcTrainingRecord {
    uint pathControl;
    uint anchorControl;
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
    return primeSharcFrame.trainingRecordsAddress != uint64_t(0)
            && primeSharcTrainingPixelInPhase(
                    pixel, primeSharcFrame.updatePhase);
}

uint primeSharcTrainingPathIndex(uint2 pixel) {
    uint2 coordinate = primeSharcTrainingCoordinate(pixel);
    return coordinate.y * primeSharcFrame.trainingWidth + coordinate.x;
}

uint primeSharcTrainingRecordBase(uint pathIndex) {
    return pathIndex * PRIME_SHARC_TRAINING_RECORD_WORDS;
}

uint primeSharcTrainingAnchorBase(uint pathBase, uint anchor) {
    return pathBase + PRIME_SHARC_TRAINING_HEADER_WORDS
            + anchor * PRIME_SHARC_TRAINING_ANCHOR_WORDS;
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

uint primeSharcTrainingFinalizePendingDirection(
        uint* words,
        uint pathBase,
        uint control,
        PathState path) {
    if (!primeSharcTrainingHasPendingDirection(control)) {
        return control;
    }
    uint anchor = primeSharcTrainingPendingAnchor(control);
    uint anchorBase = primeSharcTrainingAnchorBase(pathBase, anchor);
    uint anchorControl = words[
            anchorBase + PRIME_SHARC_TRAINING_ANCHOR_CONTROL_WORD];
    if (!primeSharcTrainingActive(anchorControl)
            || path.bounce <= primeSharcTrainingRootBounce(anchorControl)) {
        return control;
    }
    words[anchorBase + PRIME_SHARC_TRAINING_DIRECTION_WEIGHT_WORD] = asuint(
            primeSharcTrainingIncomingDirectionWeight(path));
    words[anchorBase + PRIME_SHARC_TRAINING_ANCHOR_CONTROL_WORD] =
            anchorControl | PRIME_SHARC_TRAINING_ANCHOR_DIRECTION;
    return primeSharcTrainingWithoutPendingAnchor(control);
}

HashGridKey primeSharcTrainingAnchorKey(
        uint* words,
        uint anchorBase) {
    float3 position = primeSharcTrainingLoadFloat3(
            words, anchorBase + PRIME_SHARC_TRAINING_POSITION_WORD);
    float3 normal = primeUnpackOctahedralNormal(
            words[anchorBase + PRIME_SHARC_TRAINING_NORMAL_WORD]);
    return HashGridComputeSpatialHash(
            position,
            normal,
            primeSharcParameters().hashGridParameters);
}

bool primeSharcTrainingDuplicatesAnchor(
        uint* words,
        uint pathBase,
        uint control,
        SurfaceInteraction surface) {
    float3 storedNormal = primeUnpackOctahedralNormal(
            primePackOctahedralNormal(surface.geometricNormal));
    HashGridKey candidate = HashGridComputeSpatialHash(
            surface.position,
            storedNormal,
            primeSharcParameters().hashGridParameters);
    uint storedCount = primeSharcTrainingStoredAnchorCount(control);
    for (uint anchor = 0u; anchor < storedCount; anchor++) {
        uint anchorBase = primeSharcTrainingAnchorBase(pathBase, anchor);
        uint anchorControl = words[
                anchorBase + PRIME_SHARC_TRAINING_ANCHOR_CONTROL_WORD];
        if (primeSharcTrainingActive(anchorControl)
                && candidate == primeSharcTrainingAnchorKey(words, anchorBase)) {
            return true;
        }
    }
    return false;
}

float3 primeSharcTrainingLoadAnchorTail(
        uint* words,
        uint pathBase,
        uint anchor) {
    uint anchorBase = primeSharcTrainingAnchorBase(pathBase, anchor);
    return primeSharcTrainingLoadFloat3(
            words, anchorBase + PRIME_SHARC_TRAINING_TAIL_WORD);
}

void primeSharcTrainingStoreAnchorTail(
        uint* words,
        uint pathBase,
        uint anchor,
        float3 tail) {
    uint anchorBase = primeSharcTrainingAnchorBase(pathBase, anchor);
    primeSharcTrainingStoreFloat3(
            words, anchorBase + PRIME_SHARC_TRAINING_TAIL_WORD, tail);
}

void primeSharcTrainingAccumulateLatestTail(
        uint* words,
        uint pathBase,
        uint control,
        PathState path,
        float3 localRadiance) {
    uint storedCount = primeSharcTrainingStoredAnchorCount(control);
    if (storedCount == 0u || !any(localRadiance != float3(0.0))) {
        return;
    }
    uint latest = storedCount - 1u;
    float3 tail = primeSharcTrainingLoadAnchorTail(
            words, pathBase, latest);
    tail = primeSharcTrainingAccumulateTarget(
            tail, path.throughput, localRadiance);
    primeSharcTrainingStoreAnchorTail(
            words, pathBase, latest, tail);
}

void primeSharcTrainingStoreAnchor(
        uint* words,
        uint pathBase,
        uint anchor,
        PathState path,
        SurfaceInteraction surface,
        float3 direct) {
    uint anchorBase = primeSharcTrainingAnchorBase(pathBase, anchor);
    words[anchorBase + PRIME_SHARC_TRAINING_ANCHOR_CONTROL_WORD] = 0u;
    primeSharcTrainingStoreFloat3(
            words,
            anchorBase + PRIME_SHARC_TRAINING_ROOT_THROUGHPUT_WORD,
            path.throughput);
    primeSharcTrainingStoreFloat3(
            words,
            anchorBase + PRIME_SHARC_TRAINING_POSITION_WORD,
            surface.position);
    words[anchorBase + PRIME_SHARC_TRAINING_NORMAL_WORD] =
            primePackOctahedralNormal(surface.geometricNormal);
    words[anchorBase + PRIME_SHARC_TRAINING_RADIANCE_DIRECTION_WORD] =
            primePackOctahedralNormal(
                    primeSharcTrainingRootRadianceDirection(path.rayDirection));
    primeSharcTrainingStoreFloat3(
            words,
            anchorBase + PRIME_SHARC_TRAINING_DEMODULATION_WORD,
            primeSharcMaterialDemodulation(surface));
    primeSharcTrainingStoreFloat3(
            words,
            anchorBase + PRIME_SHARC_TRAINING_DIRECT_WORD,
            path.throughput * direct);
    primeSharcTrainingStoreFloat3(
            words,
            anchorBase + PRIME_SHARC_TRAINING_TAIL_WORD,
            float3(0.0));
    words[anchorBase + PRIME_SHARC_TRAINING_DIRECTION_WEIGHT_WORD] =
            asuint(0.0);
    // Publish the anchor only after every payload word is ready.
    words[anchorBase + PRIME_SHARC_TRAINING_ANCHOR_CONTROL_WORD] =
            primeSharcTrainingRootControl(path.bounce);
}

uint primeSharcTrainingAppendAnchor(
        uint* words,
        uint pathBase,
        uint control,
        PathState path,
        SurfaceInteraction surface,
        float3 direct) {
    if (!primeSharcTrainingCanAppendAnchor(control)) {
        return control;
    }
    if (primeSharcTrainingDuplicatesAnchor(
            words, pathBase, control, surface)) {
        return control;
    }
    uint anchorIndex = primeSharcTrainingAnchorCount(control);
    uint anchor = primeSharcTrainingAnchorSlot(anchorIndex);
    primeSharcTrainingStoreAnchor(
            words,
            pathBase,
            anchor,
            path,
            surface,
            direct);
    control = primeSharcTrainingWithAnchorCount(control, anchorIndex + 1u);
    return primeSharcTrainingWithPendingAnchor(control, anchor);
}

void primeBeginSharcTraining(
        PathState path,
        SurfaceInteraction surface,
        float3 direct) {
    uint* words = primeSharcTrainingRecordWords();
    uint pathBase = primeSharcTrainingRecordBase(
            primeSharcTrainingPathIndex(path.pixel));
    primeSharcTrainingStoreAnchor(
            words, pathBase, 0u, path, surface, direct);
    uint control = primeSharcTrainingWithAnchorCount(
            PRIME_SHARC_TRAINING_ACTIVE, 1u);
    control = primeSharcTrainingWithPendingAnchor(control, 0u);
    // Publish the path last. Inter-dispatch barriers make completed writes visible to consumers.
    words[pathBase + PRIME_SHARC_TRAINING_CONTROL_WORD] = control;
}

void primeRecordSharcTrainingHit(
        PathState path,
        SurfaceInteraction surface,
        float3 direct,
        float3 localRadiance,
        bool anchorEligible) {
    uint* words = primeSharcTrainingRecordWords();
    uint pathBase = primeSharcTrainingRecordBase(
            primeSharcTrainingPathIndex(path.pixel));
    uint control = words[pathBase + PRIME_SHARC_TRAINING_CONTROL_WORD];
    if (!primeSharcTrainingActive(control)
            || primeSharcTrainingComplete(control)) {
        return;
    }
    control = primeSharcTrainingFinalizePendingDirection(
            words, pathBase, control, path);
    primeSharcTrainingAccumulateLatestTail(
            words, pathBase, control, path, localRadiance);
    if (anchorEligible) {
        control = primeSharcTrainingAppendAnchor(
                words,
                pathBase,
                control,
                path,
                surface,
                direct);
    }
    words[pathBase + PRIME_SHARC_TRAINING_CONTROL_WORD] = control;
}

void primeCompleteSharcTraining(PathState path, float3 localRadiance) {
    if (!primeSharcTrainingPixel(path.pixel)) {
        return;
    }
    uint* words = primeSharcTrainingRecordWords();
    uint pathBase = primeSharcTrainingRecordBase(
            primeSharcTrainingPathIndex(path.pixel));
    uint control = words[pathBase + PRIME_SHARC_TRAINING_CONTROL_WORD];
    if (!primeSharcTrainingActive(control)
            || primeSharcTrainingComplete(control)) {
        return;
    }
    control = primeSharcTrainingFinalizePendingDirection(
            words, pathBase, control, path);
    primeSharcTrainingAccumulateLatestTail(
            words, pathBase, control, path, localRadiance);
    // Publish completion after the final tail and direction writes.
    words[pathBase + PRIME_SHARC_TRAINING_CONTROL_WORD] =
            control | PRIME_SHARC_TRAINING_COMPLETE;
}

PrimeSharcTrainingRecord primeLoadSharcTrainingRecord(
        uint pathIndex,
        uint anchor) {
    uint* words = primeSharcTrainingRecordWords();
    uint pathBase = primeSharcTrainingRecordBase(pathIndex);
    uint anchorBase = primeSharcTrainingAnchorBase(pathBase, anchor);
    PrimeSharcTrainingRecord record;
    record.pathControl = words[pathBase + PRIME_SHARC_TRAINING_CONTROL_WORD];
    record.anchorControl = words[
            anchorBase + PRIME_SHARC_TRAINING_ANCHOR_CONTROL_WORD];
    record.rootThroughput = primeSharcTrainingLoadFloat3(
            words,
            anchorBase + PRIME_SHARC_TRAINING_ROOT_THROUGHPUT_WORD);
    record.position = primeSharcTrainingLoadFloat3(
            words, anchorBase + PRIME_SHARC_TRAINING_POSITION_WORD);
    record.geometricNormal = primeUnpackOctahedralNormal(
            words[anchorBase + PRIME_SHARC_TRAINING_NORMAL_WORD]);
    record.radianceDirection = primeUnpackOctahedralNormal(
            words[anchorBase + PRIME_SHARC_TRAINING_RADIANCE_DIRECTION_WORD]);
    record.materialDemodulation = primeSharcTrainingLoadFloat3(
            words, anchorBase + PRIME_SHARC_TRAINING_DEMODULATION_WORD);
    float3 direct = primeSharcTrainingLoadFloat3(
            words, anchorBase + PRIME_SHARC_TRAINING_DIRECT_WORD);
    float3 weightedTail = float3(0.0);
    uint storedCount = primeSharcTrainingStoredAnchorCount(record.pathControl);
    for (uint segment = anchor; segment < storedCount; segment++) {
        weightedTail += primeSharcTrainingLoadAnchorTail(
                words, pathBase, segment);
    }
    record.direct = primeSharcTrainingRatio(direct, record.rootThroughput);
    record.tail = primeSharcTrainingSuffix(
            weightedTail, record.rootThroughput);
    record.incomingDirectionWeight = asfloat(
            words[anchorBase + PRIME_SHARC_TRAINING_DIRECTION_WEIGHT_WORD]);
    return record;
}

#endif
