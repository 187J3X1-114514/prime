#ifndef PRIME_OFFLINE_WAVEFRONT_STATE_GLSL
#define PRIME_OFFLINE_WAVEFRONT_STATE_GLSL

#include "wavefront_medium.glsl"

struct PrimeOfflineTransportRecord {
    vec4 physicalOriginAndPreviousBsdfPdf;
    vec4 traceOriginAndPathControl;
    vec4 rayDirectionAndControl;
    vec4 throughput;
    uvec2 medium0;
    uvec2 medium1;
};

// 80 bytes of transport and two-entry medium state, 32 bytes of beauty/primary metadata, and
// one full-f32 32-byte deferred Area request. The record is exactly 144-byte std430 ABI.
struct PrimeOfflinePathRecord {
    PrimeOfflineTransportRecord transport;
    vec4 radianceAndPrimaryDistance;
    vec4 primaryAlbedoAndMaterialFlags;
    vec4 areaDirectionAndDistance;
    vec4 areaContributionAndValid;
};

layout(
        set = PRIME_RENDERER_DESCRIPTOR_SET,
        binding = PRIME_DESCRIPTOR_WAVEFRONT_PATHS,
        std430) buffer PrimeOfflinePathBuffer {
    PrimeOfflinePathRecord records[];
} primeOfflinePaths;

layout(
        set = PRIME_RENDERER_DESCRIPTOR_SET,
        binding = PRIME_DESCRIPTOR_WAVEFRONT_QUEUE,
        std430) buffer PrimeOfflineQueueBuffer {
    uint words[];
} primeOfflineQueue;

const uint PRIME_OFFLINE_PATH_PREVIOUS_AREA_NEE = 2u;
const uint PRIME_OFFLINE_CONTROL_ACTIVE = PRIME_WAVEFRONT_ACTIVE_MASK;
const uint PRIME_OFFLINE_CONTROL_MEDIUM_SHIFT = 1u;
const uint PRIME_OFFLINE_CONTROL_MEDIUM_MASK = 0x3u;
const uint PRIME_OFFLINE_PATH_BOUNCE_SHIFT = 0u;
const uint PRIME_OFFLINE_PATH_RR_DEPTH_SHIFT = 8u;
const uint PRIME_OFFLINE_PATH_FLAGS_SHIFT = 16u;
const uint PRIME_OFFLINE_PATH_BYTE_MASK = 0xffu;
const uint PRIME_OFFLINE_PATH_FLAGS_MASK = 0x3u;

uint primeOfflinePixelCount() {
    return primePush.outputExtent.x * primePush.outputExtent.y;
}

uint primeOfflineIndex(uvec2 pixel) {
    return pixel.y * primePush.outputExtent.x + pixel.x;
}

uvec2 primeOfflinePixel(uint pathIndex) {
    return uvec2(
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
    return primeOfflineQueue.words[primeOfflineQueueWord(queue, entry)];
}

void primeAppendOfflinePath(uint queue, uint pathIndex) {
    uint commandWord = primeOfflineQueueCommandWord(queue);
    uint entry = atomicAdd(primeOfflineQueue.words[commandWord], 1u);
    uint capacity = primeOfflinePixelCount();
    if (entry < capacity) {
        primeOfflineQueue.words[primeOfflineQueueWord(queue, entry)] = pathIndex;
    } else {
        atomicMin(primeOfflineQueue.words[commandWord], capacity);
        atomicOr(primeOfflineQueue.words[commandWord + 3u], 1u);
    }
}

void primeAppendOfflineContinuation(
        uint queue, uint pathIndex, bool continuation) {
#if defined(PRIME_ENABLE_SUBGROUP_QUEUE)
    uvec4 activeMask = subgroupBallot(continuation);
    uint activeCount = subgroupBallotBitCount(activeMask);
    uint firstEntry = 0u;
    if (subgroupElect() && activeCount != 0u) {
        firstEntry = atomicAdd(
                primeOfflineQueue.words[primeOfflineQueueCommandWord(queue)],
                activeCount);
    }
    firstEntry = subgroupBroadcastFirst(firstEntry);
    uint capacity = primeOfflinePixelCount();
    bool overflow = activeCount > capacity
            || firstEntry > capacity - min(activeCount, capacity);
    if (subgroupElect() && overflow) {
        uint commandWord = primeOfflineQueueCommandWord(queue);
        atomicMin(primeOfflineQueue.words[commandWord], capacity);
        atomicOr(primeOfflineQueue.words[commandWord + 3u], 1u);
    }
    if (continuation) {
        uint entry = firstEntry + subgroupBallotExclusiveBitCount(activeMask);
        if (entry < capacity) {
            primeOfflineQueue.words[primeOfflineQueueWord(queue, entry)] = pathIndex;
        }
    }
#else
    if (continuation) {
        primeAppendOfflinePath(queue, pathIndex);
    }
#endif
}

uint primePackOfflinePathControl(PathState path) {
    return (min(path.bounce, PRIME_OFFLINE_PATH_BYTE_MASK)
                    << PRIME_OFFLINE_PATH_BOUNCE_SHIFT)
            | (min(path.rrDepth, PRIME_OFFLINE_PATH_BYTE_MASK)
                    << PRIME_OFFLINE_PATH_RR_DEPTH_SHIFT)
            | ((path.flags & PRIME_OFFLINE_PATH_FLAGS_MASK)
                    << PRIME_OFFLINE_PATH_FLAGS_SHIFT);
}

PrimeOfflineTransportRecord primeMakeOfflineTransport(
        PathState path,
        PrimeRcVolumeStack volumeStack,
        bool enabled) {
    PrimeOfflineTransportRecord record;
    record.physicalOriginAndPreviousBsdfPdf =
            vec4(path.physicalOrigin, path.previousBsdfPdf);
    record.traceOriginAndPathControl =
            vec4(path.traceOrigin, uintBitsToFloat(primePackOfflinePathControl(path)));
    uint control = enabled ? PRIME_OFFLINE_CONTROL_ACTIVE : 0u;
    control |= min(volumeStack.count, 2u) << PRIME_OFFLINE_CONTROL_MEDIUM_SHIFT;
    record.rayDirectionAndControl =
            vec4(path.rayDirection, uintBitsToFloat(control));
    record.throughput = vec4(path.throughput, 0.0);
    record.medium0 = primePackWavefrontMedium(volumeStack.values[0]);
    record.medium1 = primePackWavefrontMedium(volumeStack.values[1]);
    return record;
}

PathState primeOfflinePath(uvec2 pixel, PrimeOfflineTransportRecord record) {
    PathState path;
    uint pathControl = floatBitsToUint(record.traceOriginAndPathControl.w);
    path.physicalOrigin = record.physicalOriginAndPreviousBsdfPdf.xyz;
    path.bounce = (pathControl >> PRIME_OFFLINE_PATH_BOUNCE_SHIFT)
            & PRIME_OFFLINE_PATH_BYTE_MASK;
    path.traceOrigin = record.traceOriginAndPathControl.xyz;
    path.sampleDimension = 0u;
    path.rayDirection = record.rayDirectionAndControl.xyz;
    path.flags = (pathControl >> PRIME_OFFLINE_PATH_FLAGS_SHIFT)
            & PRIME_OFFLINE_PATH_FLAGS_MASK;
    path.throughput = record.throughput.xyz;
    path.previousBsdfPdf = record.physicalOriginAndPreviousBsdfPdf.w;
    path.rrDepth = (pathControl >> PRIME_OFFLINE_PATH_RR_DEPTH_SHIFT)
            & PRIME_OFFLINE_PATH_BYTE_MASK;
    path.pixel = pixel;
    path.sampleIndex = primeSampleIndex();
    path.sampleEpoch = primeSampleEpoch();
    return path;
}

PrimeRcVolumeStack primeOfflineVolumeStack(PrimeOfflineTransportRecord record) {
    PrimeRcVolumeStack stack;
    stack.values[0] = primeUnpackWavefrontMedium(record.medium0);
    stack.values[1] = primeUnpackWavefrontMedium(record.medium1);
    uint control = floatBitsToUint(record.rayDirectionAndControl.w);
    stack.count = min(
            (control >> PRIME_OFFLINE_CONTROL_MEDIUM_SHIFT)
                    & PRIME_OFFLINE_CONTROL_MEDIUM_MASK,
            2u);
    return stack;
}

bool primeOfflineIsActive(PrimeOfflinePathRecord record) {
    return (floatBitsToUint(record.transport.rayDirectionAndControl.w)
            & PRIME_OFFLINE_CONTROL_ACTIVE) != 0u;
}

void primeClearOfflineAreaRequest(inout PrimeOfflinePathRecord record) {
    record.areaDirectionAndDistance = vec4(0.0);
    record.areaContributionAndValid = vec4(0.0);
}

#endif
