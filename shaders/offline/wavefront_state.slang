#ifndef PRIME_OFFLINE_WAVEFRONT_STATE_SLANG
#define PRIME_OFFLINE_WAVEFRONT_STATE_SLANG

import "realtime/wavefront_medium.slang";

struct PrimeOfflineTransportRecord {
    float4 physicalOriginAndPreviousBsdfPdf;
    uint4 sourceAndPathControl;
    float4 rayDirectionAndControl;
    float4 throughput;
    uint4 medium0;
    uint4 medium1;
};

// 96 bytes of transport and two-entry medium state, 32 bytes of beauty/primary metadata, and
// one 32-byte deferred Area request whose control lane retains the previous receiver normal and
// pre-scatter medium depth, plus exact target-emitter identity. The record is exactly 176-byte
// std430 ABI.
struct PrimeOfflinePathRecord {
    PrimeOfflineTransportRecord transport;
    float4 radianceAndPrimaryDistance;
    float4 primaryAlbedoAndMaterialFlags;
    float4 areaDirectionAndDistance;
    float4 areaContributionAndValid;
    uint4 areaTargetEmitter;
};

[[vk::binding(PRIME_DESCRIPTOR_WAVEFRONT_PATHS, PRIME_RENDERER_DESCRIPTOR_SET)]]
RWStructuredBuffer<PrimeOfflinePathRecord> primeOfflinePaths;

[[vk::binding(PRIME_DESCRIPTOR_WAVEFRONT_QUEUE, PRIME_RENDERER_DESCRIPTOR_SET)]]
RWStructuredBuffer<uint> primeOfflineQueue;

static const uint PRIME_OFFLINE_PATH_PREVIOUS_AREA_NEE = 2u;
static const uint PRIME_OFFLINE_CONTROL_ACTIVE = PRIME_WAVEFRONT_ACTIVE_MASK;
static const uint PRIME_OFFLINE_CONTROL_MEDIUM_SHIFT = 1u;
static const uint PRIME_OFFLINE_CONTROL_MEDIUM_MASK = 0x3u;
static const uint PRIME_OFFLINE_PATH_BOUNCE_SHIFT = 0u;
static const uint PRIME_OFFLINE_PATH_FLAGS_SHIFT = 16u;
static const uint PRIME_OFFLINE_PATH_BYTE_MASK = 0xffu;
static const uint PRIME_OFFLINE_PATH_FLAGS_MASK = 0x3u;
static const uint PRIME_OFFLINE_AREA_VALID = 1u;
static const uint PRIME_OFFLINE_AREA_MEDIUM_SHIFT = 1u;
static const uint PRIME_OFFLINE_AREA_MEDIUM_MASK = 0x3u;
static const uint PRIME_OFFLINE_LIGHT_NORMAL_SHIFT = 16u;
static const uint PRIME_OFFLINE_LIGHT_NORMAL_MASK = 0xffffu;

uint primeOfflineAreaControl(
        uint packedNormal, bool valid, uint startingMediumCount) {
    uint control = (packedNormal & PRIME_OFFLINE_LIGHT_NORMAL_MASK)
            << PRIME_OFFLINE_LIGHT_NORMAL_SHIFT;
    if (valid) {
        control |= PRIME_OFFLINE_AREA_VALID;
        control |= min(startingMediumCount, PRIME_OPENPBR_MAX_VOLUME_STACK_SIZE)
                << PRIME_OFFLINE_AREA_MEDIUM_SHIFT;
    }
    return control;
}

uint primeOfflineAreaStartingMediumCount(PrimeOfflinePathRecord record) {
    return (asuint(record.areaContributionAndValid.w)
            >> PRIME_OFFLINE_AREA_MEDIUM_SHIFT)
            & PRIME_OFFLINE_AREA_MEDIUM_MASK;
}

uint primeOfflinePreviousLightNormal(PrimeOfflinePathRecord record) {
    return (asuint(record.areaContributionAndValid.w)
            >> PRIME_OFFLINE_LIGHT_NORMAL_SHIFT)
            & PRIME_OFFLINE_LIGHT_NORMAL_MASK;
}

uint primeOfflinePixelCount() {
    return primePush.outputExtent.x * primePush.outputExtent.y;
}

uint primeOfflineIndex(uint2 pixel) {
    return pixel.y * primePush.outputExtent.x + pixel.x;
}

uint2 primeOfflinePixel(uint pathIndex) {
    return uint2(
            pathIndex % primePush.outputExtent.x,
            pathIndex / primePush.outputExtent.x);
}

uint primeOfflineQueueCommandWord(uint queue) {
    return queue * (PRIME_WAVEFRONT_QUEUE_COMMAND_STRIDE / 4u);
}

uint primeOfflineQueueWord(uint queue, uint entry) {
    uint commandWords = PRIME_WAVEFRONT_QUEUE_COUNT
            * (PRIME_WAVEFRONT_QUEUE_COMMAND_STRIDE / 4u);
    return commandWords + queue * primeOfflinePixelCount() + entry;
}

uint primeOfflineQueuedPath(uint queue, uint entry) {
    return primeOfflineQueue[primeOfflineQueueWord(queue, entry)];
}

// Queue consumption owns the counter reset. This removes a transfer fill and its two barriers
// from every round while preserving the indirect command that was fetched for this dispatch.
void primeResetOfflineQueue(uint queue) {
    uint ignored;
    InterlockedExchange(
            primeOfflineQueue[primeOfflineQueueCommandWord(queue)],
            0u,
            ignored);
}

void primeAppendOfflinePath(uint queue, uint pathIndex) {
    uint commandWord = primeOfflineQueueCommandWord(queue);
    uint entry;
    InterlockedAdd(primeOfflineQueue[commandWord], 1u, entry);
    uint capacity = primeOfflinePixelCount();
    if (entry < capacity) {
        primeOfflineQueue[primeOfflineQueueWord(queue, entry)] = pathIndex;
    } else {
        uint ignored;
        InterlockedMin(primeOfflineQueue[commandWord], capacity, ignored);
        InterlockedOr(primeOfflineQueue[commandWord + 3u], 1u, ignored);
    }
}

interface IPrimeOfflineContinuationAppender {
    void append(uint queue, uint pathIndex, bool continuation);
}

struct PrimeScalarOfflineContinuationAppender
        : IPrimeOfflineContinuationAppender {
    void append(uint queue, uint pathIndex, bool continuation) {
        if (continuation) {
            primeAppendOfflinePath(queue, pathIndex);
        }
    }
};

struct PrimeSubgroupOfflineContinuationAppender
        : IPrimeOfflineContinuationAppender {
    // Every invocation must call this from uniform control flow. The elected lane reserves one
    // contiguous range, reducing global atomics and preserving subgroup locality.
    void append(uint queue, uint pathIndex, bool continuation) {
        uint4 activeMask = WaveActiveBallot(continuation);
        uint activeCount = countbits(activeMask.x) + countbits(activeMask.y)
                + countbits(activeMask.z) + countbits(activeMask.w);
        uint firstEntry = 0u;
        if (WaveIsFirstLane() && activeCount != 0u) {
            InterlockedAdd(
                    primeOfflineQueue[primeOfflineQueueCommandWord(queue)],
                    activeCount,
                    firstEntry);
        }
        firstEntry = WaveReadLaneFirst(firstEntry);
        uint capacity = primeOfflinePixelCount();
        bool overflow = activeCount > capacity
                || firstEntry > capacity - min(activeCount, capacity);
        if (WaveIsFirstLane() && overflow) {
            uint commandWord = primeOfflineQueueCommandWord(queue);
            uint ignored;
            InterlockedMin(primeOfflineQueue[commandWord], capacity, ignored);
            InterlockedOr(primeOfflineQueue[commandWord + 3u], 1u, ignored);
        }
        if (continuation) {
            uint entry = firstEntry + WavePrefixCountBits(continuation);
            if (entry < capacity) {
                primeOfflineQueue[primeOfflineQueueWord(queue, entry)] = pathIndex;
            }
        }
    }
};

void primeAppendOfflineContinuation<Appender>(
        Appender appender,
        uint queue,
        uint pathIndex,
        bool continuation)
    where Appender : IPrimeOfflineContinuationAppender
{
    appender.append(queue, pathIndex, continuation);
}

uint primePackOfflinePathControl(PathState path) {
    return (min(path.bounce, PRIME_OFFLINE_PATH_BYTE_MASK)
                    << PRIME_OFFLINE_PATH_BOUNCE_SHIFT)
            | ((path.flags & PRIME_OFFLINE_PATH_FLAGS_MASK)
                    << PRIME_OFFLINE_PATH_FLAGS_SHIFT);
}

PrimeOfflineTransportRecord primeMakeOfflineTransport(
        PathState path,
        PrimeOpenPbrVolumeStack volumeStack,
        bool enabled) {
    PrimeOfflineTransportRecord record;
    record.physicalOriginAndPreviousBsdfPdf =
            float4(path.physicalOrigin, path.previousBsdfPdf);
    record.sourceAndPathControl = uint4(
            path.sourcePrimitive,
            path.sourceReserved,
            primePackOfflinePathControl(path));
    uint control = enabled ? PRIME_OFFLINE_CONTROL_ACTIVE : 0u;
    control |= min(volumeStack.count, 2u) << PRIME_OFFLINE_CONTROL_MEDIUM_SHIFT;
    record.rayDirectionAndControl =
            float4(path.rayDirection, asfloat(control));
    record.throughput = float4(path.throughput, path.etaScale);
    record.medium0 = primePackWavefrontMedium(volumeStack.values[0]);
    record.medium1 = primePackWavefrontMedium(volumeStack.values[1]);
    return record;
}

PathState primeOfflinePath(
        uint2 pixel,
        PrimeOfflineTransportRecord record,
        uint previousLightNormal) {
    PathState path;
    uint pathControl = record.sourceAndPathControl.w;
    path.physicalOrigin = record.physicalOriginAndPreviousBsdfPdf.xyz;
    path.bounce = (pathControl >> PRIME_OFFLINE_PATH_BOUNCE_SHIFT)
            & PRIME_OFFLINE_PATH_BYTE_MASK;
    path.sourcePrimitive = record.sourceAndPathControl.xy;
    path.sourceReserved = record.sourceAndPathControl.z;
    path.sampleDimension = 0u;
    path.rayDirection = record.rayDirectionAndControl.xyz;
    path.flags = (pathControl >> PRIME_OFFLINE_PATH_FLAGS_SHIFT)
            & PRIME_OFFLINE_PATH_FLAGS_MASK;
    path.throughput = record.throughput.xyz;
    path.previousBsdfPdf = record.physicalOriginAndPreviousBsdfPdf.w;
    path.etaScale = record.throughput.w;
    path.previousLightNormal = previousLightNormal;
    path.pixel = pixel;
    path.sampleIndex = primeSampleIndex();
    path.sampleEpoch = primeSampleEpoch();
    path.previousSharcRoughness = 0.0;
    path.previousSharcEvent = 0u;
    return path;
}

PrimeOpenPbrVolumeStack primeOfflineVolumeStack(PrimeOfflineTransportRecord record) {
    PrimeOpenPbrVolumeStack stack;
    stack.values[0] = primeUnpackWavefrontMedium(record.medium0);
    stack.values[1] = primeUnpackWavefrontMedium(record.medium1);
    uint control = asuint(record.rayDirectionAndControl.w);
    stack.count = min(
            (control >> PRIME_OFFLINE_CONTROL_MEDIUM_SHIFT)
                    & PRIME_OFFLINE_CONTROL_MEDIUM_MASK,
            2u);
    return stack;
}

bool primeOfflineIsActive(PrimeOfflinePathRecord record) {
    return (asuint(record.transport.rayDirectionAndControl.w)
            & PRIME_OFFLINE_CONTROL_ACTIVE) != 0u;
}

PrimeOfflinePathRecord primeOfflineWithoutAreaRequest(
        PrimeOfflinePathRecord record) {
    record.areaDirectionAndDistance = float4(0.0);
    uint packedNormal = asuint(record.areaContributionAndValid.w)
            & (PRIME_OFFLINE_LIGHT_NORMAL_MASK
                    << PRIME_OFFLINE_LIGHT_NORMAL_SHIFT);
    record.areaContributionAndValid = float4(
            0.0, 0.0, 0.0, asfloat(packedNormal));
    record.areaTargetEmitter = uint4(PRIME_NO_PRIMITIVE_INDEX);
    return record;
}

#endif
