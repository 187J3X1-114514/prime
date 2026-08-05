#ifndef PRIME_WAVEFRONT_STATE_GLSL
#define PRIME_WAVEFRONT_STATE_GLSL

#include "wavefront_medium.glsl"

// Two fixed slots belong to each realtime pixel so a primary transparent interface can enqueue
// reflection and transmission as independent invocations. The execution-local type contains only
// the six hot transport lanes; the storage record adds one cold lane shared by queued PSR, deferred
// Area data and the previous receiver normal. Primary area-light moments are immutable pixel state
// and live once per pixel in the queue-buffer prefix.
struct PrimeWavefrontTransportRecord {
    vec4 physicalOriginAndPreviousBsdfPdf;
    vec4 traceOriginAndPathControl;
    vec4 rayDirectionAndDenoiserControl;
    vec4 throughputAndNumericalFlags;
    uvec2 medium0;
    uvec2 medium1;
};

// Four f32 transport lanes, two compact medium lanes and one cold lane give this record its
// explicit 96-byte std430 CPU/GPU ABI.
struct PrimeWavefrontPathRecord {
    PrimeWavefrontTransportRecord transport;
    uvec4 psrPacked;
};

layout(
        set = PRIME_RENDERER_DESCRIPTOR_SET,
        binding = PRIME_DESCRIPTOR_WAVEFRONT_PATHS,
        std430) buffer PrimeWavefrontPathBuffer {
    PrimeWavefrontPathRecord records[];
} primeWavefrontPaths;

layout(
        set = PRIME_RENDERER_DESCRIPTOR_SET,
        binding = PRIME_DESCRIPTOR_WAVEFRONT_QUEUE,
        std430) buffer PrimeWavefrontQueueBuffer {
    uint words[];
} primeWavefrontQueue;

// Member-wise access keeps cold PSR state out of ordinary continuations and prevents a storage
// record aggregate from remaining live across the integrator call tree.
PrimeWavefrontTransportRecord primeLoadWavefrontTransportRecord(uint pathIndex) {
    PrimeWavefrontTransportRecord record;
    record.physicalOriginAndPreviousBsdfPdf =
            primeWavefrontPaths.records[pathIndex]
                    .transport.physicalOriginAndPreviousBsdfPdf;
    record.traceOriginAndPathControl =
            primeWavefrontPaths.records[pathIndex].transport.traceOriginAndPathControl;
    record.rayDirectionAndDenoiserControl =
            primeWavefrontPaths.records[pathIndex]
                    .transport.rayDirectionAndDenoiserControl;
    record.throughputAndNumericalFlags =
            primeWavefrontPaths.records[pathIndex]
                    .transport.throughputAndNumericalFlags;
    record.medium0 = primeWavefrontPaths.records[pathIndex].transport.medium0;
    record.medium1 = primeWavefrontPaths.records[pathIndex].transport.medium1;
    return record;
}

void primeStoreWavefrontTransportRecord(
        uint pathIndex,
        PrimeWavefrontTransportRecord record) {
    primeWavefrontPaths.records[pathIndex]
            .transport.physicalOriginAndPreviousBsdfPdf =
            record.physicalOriginAndPreviousBsdfPdf;
    primeWavefrontPaths.records[pathIndex].transport.traceOriginAndPathControl =
            record.traceOriginAndPathControl;
    primeWavefrontPaths.records[pathIndex]
            .transport.rayDirectionAndDenoiserControl =
            record.rayDirectionAndDenoiserControl;
    primeWavefrontPaths.records[pathIndex]
            .transport.throughputAndNumericalFlags =
            record.throughputAndNumericalFlags;
    primeWavefrontPaths.records[pathIndex].transport.medium0 = record.medium0;
    primeWavefrontPaths.records[pathIndex].transport.medium1 = record.medium1;
}

const uint PRIME_WAVEFRONT_REACHED_NON_DELTA = 2u;
const uint PRIME_WAVEFRONT_DIFFUSE_PATH = 4u;
const uint PRIME_WAVEFRONT_TRANSPARENT_BRANCH = 8u;
const uint PRIME_WAVEFRONT_TRANSMISSION_BRANCH = 16u;
const uint PRIME_WAVEFRONT_GUIDE_ENABLED = 32u;
const uint PRIME_WAVEFRONT_DIRECTIONAL_GUIDE = 64u;
const uint PRIME_WAVEFRONT_BRANCH_CONTROL_MASK = 0x78u;
const uint PRIME_WAVEFRONT_BOUNCE_SHIFT = 0u;
const uint PRIME_WAVEFRONT_RR_DEPTH_SHIFT = 8u;
const uint PRIME_WAVEFRONT_PATH_FLAGS_SHIFT = 17u;
const uint PRIME_WAVEFRONT_PRIMARY_BOUNCE_SHIFT = 8u;
const uint PRIME_WAVEFRONT_MEDIUM_COUNT_SHIFT = 16u;
// Bits 18..31 are the complete current material-flag domain. Extending material flags beyond
// bit 13 requires a queue ABI migration rather than silent truncation here.
const uint PRIME_WAVEFRONT_PRIMARY_FLAGS_SHIFT = 18u;
const uint PRIME_WAVEFRONT_BYTE_MASK = 0xffu;
const uint PRIME_WAVEFRONT_MEDIUM_COUNT_MASK = 0x3u;
const uint PRIME_WAVEFRONT_PRIMARY_FLAGS_MASK = 0x3fffu;
const uint PRIME_WAVEFRONT_NUMERICAL_FLAGS_MASK = 0x1ffu;
const uint PRIME_WAVEFRONT_NUMERICAL_CONTEXT_SHIFT = 9u;
const uint PRIME_WAVEFRONT_NUMERICAL_CONTEXT_MASK = 0x7fffu;
const uint PRIME_WAVEFRONT_PSR_CONTROL_SHIFT = 18u;
const uint PRIME_WAVEFRONT_PSR_CONTROL_MASK = 0x3fu;
const uint PRIME_WAVEFRONT_LIGHT_NORMAL_SHIFT = 16u;
const uint PRIME_WAVEFRONT_LIGHT_NORMAL_MASK = 0xffffu;

uint primeWavefrontIndex(uvec2 pixel) {
    return pixel.y * primePush.outputExtent.x + pixel.x;
}

uint primeWavefrontPixelCount() {
    return primePush.outputExtent.x * primePush.outputExtent.y;
}

uint primeWavefrontPathCapacity() {
    return primeWavefrontPixelCount() * PRIME_WAVEFRONT_PATH_SLOTS_PER_PIXEL;
}

uint primeWavefrontIndex(uvec2 pixel, uint branch) {
    return primeWavefrontIndex(pixel) + branch * primeWavefrontPixelCount();
}

uvec2 primeWavefrontPixel(uint pathIndex) {
    uint pixelIndex = pathIndex % primeWavefrontPixelCount();
    return uvec2(
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
    return indexWords + queue * primeWavefrontPathCapacity() + entry;
}

uint primeWavefrontAreaWord(uvec2 pixel, uint component) {
    return primeWavefrontIndex(pixel)
            * (PRIME_WAVEFRONT_AREA_RECORD_SIZE / 4u) + component;
}

uint primeWavefrontQueuedPath(uint queue, uint entry) {
    return primeWavefrontQueue.words[primeWavefrontQueueWord(queue, entry)];
}

void primeAppendWavefrontPath(uint queue, uint pathIndex) {
    uint commandWord = primeWavefrontQueueCommandWord(queue);
    uint entry = atomicAdd(primeWavefrontQueue.words[commandWord], 1u);
    uint capacity = primeWavefrontPathCapacity();
    if (entry < capacity) {
        primeWavefrontQueue.words[
                primeWavefrontQueueWord(queue, entry)] = pathIndex;
    } else {
        atomicMin(primeWavefrontQueue.words[commandWord], capacity);
        atomicOr(primeWavefrontQueue.words[commandWord + 3u], 1u);
    }
}

// Must be called by every invocation in the subgroup from uniform control flow. The elected lane
// reserves one contiguous range, reducing global atomics and preserving subgroup locality.
void primeAppendWavefrontContinuation(
        uint queue,
        uint pathIndex,
        bool continuation) {
#if defined(PRIME_ENABLE_SUBGROUP_QUEUE)
    uvec4 activeMask = subgroupBallot(continuation);
    uint activeCount = subgroupBallotBitCount(activeMask);
    uint firstEntry = 0u;
    if (subgroupElect() && activeCount != 0u) {
        firstEntry = atomicAdd(
                primeWavefrontQueue.words[primeWavefrontQueueCommandWord(queue)],
                activeCount);
    }
    firstEntry = subgroupBroadcastFirst(firstEntry);
    uint capacity = primeWavefrontPathCapacity();
    bool overflow = activeCount > capacity
            || firstEntry > capacity - min(activeCount, capacity);
    if (subgroupElect() && overflow) {
        uint commandWord = primeWavefrontQueueCommandWord(queue);
        atomicMin(primeWavefrontQueue.words[commandWord], capacity);
        atomicOr(primeWavefrontQueue.words[commandWord + 3u], 1u);
    }
    if (!continuation) {
        return;
    }

    uint entry = firstEntry + subgroupBallotExclusiveBitCount(activeMask);
    if (entry < capacity) {
        primeWavefrontQueue.words[primeWavefrontQueueWord(queue, entry)] = pathIndex;
    }
#else
    if (continuation) {
        primeAppendWavefrontPath(queue, pathIndex);
    }
#endif
}

uvec3 primePackWavefrontPair(vec3 first, vec3 second) {
    return uvec3(
            packHalf2x16(first.xy),
            packHalf2x16(vec2(first.z, second.x)),
            packHalf2x16(second.yz));
}

void primeUnpackWavefrontPair(uvec3 packedPair, out vec3 first, out vec3 second) {
    vec2 first01 = unpackHalf2x16(packedPair.x);
    vec2 first2Second0 = unpackHalf2x16(packedPair.y);
    vec2 second12 = unpackHalf2x16(packedPair.z);
    first = vec3(first01, first2Second0.x);
    second = vec3(first2Second0.y, second12);
}

void primeStoreWavefrontArea(uvec2 pixel, PrimeDenoiserGuides guides) {
    uvec3 radiance = primePackWavefrontPair(
            primeNrdSanitizeRadiance(guides.primaryAreaDiffuse),
            primeNrdSanitizeRadiance(guides.primaryAreaSpecular));
    vec3 direction = guides.primaryAreaDirection;
    if (!(dot(direction, direction) > 0.0)) {
        direction = vec3(0.0, 1.0, 0.0);
    }
    primeWavefrontQueue.words[primeWavefrontAreaWord(pixel, 0u)] = radiance.x;
    primeWavefrontQueue.words[primeWavefrontAreaWord(pixel, 1u)] = radiance.y;
    primeWavefrontQueue.words[primeWavefrontAreaWord(pixel, 2u)] = radiance.z;
    primeWavefrontQueue.words[primeWavefrontAreaWord(pixel, 3u)] =
            primePackOctahedralNormal(direction);
}

void primeLoadWavefrontArea(
        uvec2 pixel,
        out vec3 diffuse,
        out vec3 specular,
        out vec3 direction) {
    uvec3 radiance = uvec3(
            primeWavefrontQueue.words[primeWavefrontAreaWord(pixel, 0u)],
            primeWavefrontQueue.words[primeWavefrontAreaWord(pixel, 1u)],
            primeWavefrontQueue.words[primeWavefrontAreaWord(pixel, 2u)]);
    primeUnpackWavefrontPair(radiance, diffuse, specular);
    direction = primeUnpackOctahedralNormal(
            primeWavefrontQueue.words[primeWavefrontAreaWord(pixel, 3u)]);
}

uint primePackWavefrontPathControl(PathState path) {
    return (min(path.bounce, PRIME_WAVEFRONT_BYTE_MASK)
                    << PRIME_WAVEFRONT_BOUNCE_SHIFT)
            | (min(path.rrDepth, PRIME_WAVEFRONT_BYTE_MASK)
                    << PRIME_WAVEFRONT_RR_DEPTH_SHIFT)
            | ((path.flags & PRIME_PATH_PREVIOUS_DELTA)
                    << PRIME_WAVEFRONT_PATH_FLAGS_SHIFT);
}

uint primePackWavefrontDiagnostic() {
    // The current classifier occupies bits 0..8 and first-context metadata fits in 15 bits.
    // Extending either domain requires a queue ABI migration.
    return (primeRawNumericalFlags & PRIME_WAVEFRONT_NUMERICAL_FLAGS_MASK)
            | ((primeRawNumericalFirstContext
                    & PRIME_WAVEFRONT_NUMERICAL_CONTEXT_MASK)
                    << PRIME_WAVEFRONT_NUMERICAL_CONTEXT_SHIFT);
}

bool primeWavefrontActive(PrimeWavefrontTransportRecord record) {
    uint denoiserControl =
            floatBitsToUint(record.rayDirectionAndDenoiserControl.w);
    return (denoiserControl & PRIME_WAVEFRONT_ACTIVE_MASK) != 0u;
}

void primeSetWavefrontActive(
        inout PrimeWavefrontTransportRecord record, bool enabled) {
    uint denoiserControl =
            floatBitsToUint(record.rayDirectionAndDenoiserControl.w);
    if (enabled) {
        denoiserControl |= PRIME_WAVEFRONT_ACTIVE_MASK;
    } else {
        denoiserControl &= ~PRIME_WAVEFRONT_ACTIVE_MASK;
    }
    record.rayDirectionAndDenoiserControl.w = uintBitsToFloat(denoiserControl);
}

void primeSetWavefrontQueuedPsrControl(
        inout PrimeWavefrontTransportRecord record,
        uint control) {
    uint pathControl = floatBitsToUint(record.traceOriginAndPathControl.w);
    pathControl &= ~(PRIME_WAVEFRONT_PSR_CONTROL_MASK
            << PRIME_WAVEFRONT_PSR_CONTROL_SHIFT);
    pathControl |= (control & PRIME_WAVEFRONT_PSR_CONTROL_MASK)
            << PRIME_WAVEFRONT_PSR_CONTROL_SHIFT;
    record.traceOriginAndPathControl.w = uintBitsToFloat(pathControl);
}

uvec4 primePackWavefrontPsrState(PrimeQueuedPsrState state) {
    vec3 firstDirection = state.firstDirectionLength.xyz;
    if (!(dot(firstDirection, firstDirection) > 0.0)) {
        firstDirection = vec3(0.0, 0.0, 1.0);
    }
    return uvec4(
            primePackOctahedralNormal(firstDirection),
            floatBitsToUint(state.firstDirectionLength.w),
            packSnorm2x16(clamp(state.rotation.xy, vec2(-1.0), vec2(1.0))),
            packSnorm2x16(clamp(state.rotation.zw, vec2(-1.0), vec2(1.0))));
}

void primeSetWavefrontQueuedPsrState(
        inout PrimeWavefrontPathRecord record,
        PrimeQueuedPsrState state) {
    primeSetWavefrontQueuedPsrControl(record.transport, state.control);
    record.psrPacked = primePackWavefrontPsrState(state);
}

uint primeLoadWavefrontPreviousLightNormal(uint pathIndex) {
    return (primeWavefrontPaths.records[pathIndex].psrPacked.w
            >> PRIME_WAVEFRONT_LIGHT_NORMAL_SHIFT)
            & PRIME_WAVEFRONT_LIGHT_NORMAL_MASK;
}

void primeSetWavefrontPreviousLightNormal(
        inout PrimeWavefrontPathRecord record, uint packedNormal) {
    record.psrPacked.w = (record.psrPacked.w & PRIME_WAVEFRONT_LIGHT_NORMAL_MASK)
            | ((packedNormal & PRIME_WAVEFRONT_LIGHT_NORMAL_MASK)
                    << PRIME_WAVEFRONT_LIGHT_NORMAL_SHIFT);
}

void primeStoreWavefrontPreviousLightNormal(
        uint pathIndex, uint packedNormal) {
    uint packed = primeWavefrontPaths.records[pathIndex].psrPacked.w;
    primeWavefrontPaths.records[pathIndex].psrPacked.w =
            (packed & PRIME_WAVEFRONT_LIGHT_NORMAL_MASK)
            | ((packedNormal & PRIME_WAVEFRONT_LIGHT_NORMAL_MASK)
                    << PRIME_WAVEFRONT_LIGHT_NORMAL_SHIFT);
}

PrimeQueuedPsrState primeLoadWavefrontPsrState(
        uint pathIndex,
        PrimeWavefrontTransportRecord record) {
    uvec4 packed = primeWavefrontPaths.records[pathIndex].psrPacked;
    PrimeQueuedPsrState state;
    state.firstDirectionLength = vec4(
            primeUnpackOctahedralNormal(packed.x),
            uintBitsToFloat(packed.y));
    state.rotation = vec4(
            unpackSnorm2x16(packed.z),
            unpackSnorm2x16(packed.w));
    float rotationLengthSquared = dot(state.rotation, state.rotation);
    state.rotation = rotationLengthSquared > 0.0
            ? state.rotation * inversesqrt(rotationLengthSquared)
            : vec4(0.0, 0.0, 0.0, 1.0);
    uint pathControl = floatBitsToUint(record.traceOriginAndPathControl.w);
    state.control = (pathControl >> PRIME_WAVEFRONT_PSR_CONTROL_SHIFT)
            & PRIME_WAVEFRONT_PSR_CONTROL_MASK;
    return state;
}

void primeStoreWavefrontPsrState(
        uint pathIndex,
        PrimeQueuedPsrState state) {
    primeWavefrontPaths.records[pathIndex].psrPacked =
            primePackWavefrontPsrState(state);
}

#if defined(PRIME_DEFER_SECONDARY_AREA_NEE)
struct PrimeDeferredAreaLightRequest {
    vec3 direction;
    float distance;
    vec3 contribution;
    bool valid;
};

void primeClearDeferredAreaLightRequest(uint pathIndex) {
    uint packedNormal = primeWavefrontPaths.records[pathIndex].psrPacked.w
            & (PRIME_WAVEFRONT_LIGHT_NORMAL_MASK
                    << PRIME_WAVEFRONT_LIGHT_NORMAL_SHIFT);
    primeWavefrontPaths.records[pathIndex].psrPacked =
            uvec4(0u, 0u, 0u, packedNormal);
}

void primeStoreDeferredAreaLightRequest(
        uint pathIndex,
        vec3 direction,
        float distance,
        vec3 contribution) {
    // A guide-pending delta path and a secondary Area request are mutually exclusive. Reusing the
    // cold PSR lane keeps the 96-byte realtime path ABI and memory footprint fixed.
    vec3 packedContribution = primeNrdSanitizeRadiance(contribution);
    uint packedNormal = primeWavefrontPaths.records[pathIndex].psrPacked.w
            & (PRIME_WAVEFRONT_LIGHT_NORMAL_MASK
                    << PRIME_WAVEFRONT_LIGHT_NORMAL_SHIFT);
    primeWavefrontPaths.records[pathIndex].psrPacked = uvec4(
            primePackOctahedralNormal(direction),
            floatBitsToUint(distance),
            packHalf2x16(packedContribution.xy),
            packedNormal | (packHalf2x16(vec2(packedContribution.z, 0.0))
                    & PRIME_WAVEFRONT_LIGHT_NORMAL_MASK));
}

PrimeDeferredAreaLightRequest primeLoadDeferredAreaLightRequest(
        uint pathIndex) {
    uvec4 packed = primeWavefrontPaths.records[pathIndex].psrPacked;
    PrimeDeferredAreaLightRequest request;
    request.direction = primeUnpackOctahedralNormal(packed.x);
    request.distance = uintBitsToFloat(packed.y);
    vec2 contribution01 = unpackHalf2x16(packed.z);
    request.contribution = vec3(
            contribution01,
            unpackHalf2x16(packed.w).x);
    request.valid = (packed.z != 0u
            || (packed.w & PRIME_WAVEFRONT_LIGHT_NORMAL_MASK) != 0u)
            && primeNrdIsFinite(request.distance)
            && request.distance > 0.0;
    return request;
}

void primeAccumulateDeferredAreaLight(
        uvec2 pixel,
        uint control,
        vec3 contribution) {
    ivec2 coordinate = ivec2(pixel);
    bool transparent = (control & PRIME_WAVEFRONT_TRANSPARENT_BRANCH) != 0u;
    bool transmission = (control & PRIME_WAVEFRONT_TRANSMISSION_BRANCH) != 0u;
    bool guideEnabled = (control & PRIME_WAVEFRONT_GUIDE_ENABLED) != 0u;
    bool diffuse = transparent && !guideEnabled
            ? transmission
            : (control & PRIME_WAVEFRONT_DIFFUSE_PATH) != 0u;
    vec4 current;
    if (!transparent || transmission) {
        current = diffuse
                ? imageLoad(primeNrdNoisyDiffuse, coordinate)
                : imageLoad(primeNrdNoisySpecular, coordinate);
    } else if (primeWritesNrdShInputs()) {
        current = diffuse
                ? imageLoad(primeNrdReflectionNoisyDiffuse, coordinate)
                : imageLoad(primeNrdReflectionNoisySpecular, coordinate);
    } else {
        current = imageLoad(primeStableRadiance, coordinate);
    }

    primeAccumulate(current.rgb, contribution);
    current.rgb = primeNrdSanitizeRadiance(current.rgb);
    if (!transparent || transmission) {
        if (diffuse) {
            imageStore(primeNrdNoisyDiffuse, coordinate, current);
        } else {
            imageStore(primeNrdNoisySpecular, coordinate, current);
        }
    } else if (primeWritesNrdShInputs()) {
        if (diffuse) {
            imageStore(primeNrdReflectionNoisyDiffuse, coordinate, current);
        } else {
            imageStore(primeNrdReflectionNoisySpecular, coordinate, current);
        }
    } else {
        imageStore(primeStableRadiance, coordinate, current);
    }
}
#endif

void primeStoreWavefrontDiagnostic(uint pathIndex) {
    if (primeWritesRawNumericalDiagnostic()) {
        primeWavefrontPaths.records[pathIndex]
                .transport.throughputAndNumericalFlags.w =
                uintBitsToFloat(primePackWavefrontDiagnostic());
    }
}

PrimeWavefrontTransportRecord primeMakeWavefrontTransportRecord(
        PathState path,
        PrimeRcVolumeStack volumeStack,
        PrimeDenoiserState denoiserState,
        uint primaryMaterialFlags,
        bool enabled) {
    PrimeWavefrontTransportRecord record;
    record.physicalOriginAndPreviousBsdfPdf =
            vec4(path.physicalOrigin, path.previousBsdfPdf);
    record.traceOriginAndPathControl =
            vec4(path.traceOrigin, uintBitsToFloat(primePackWavefrontPathControl(path)));
    record.medium0 = primePackWavefrontMedium(volumeStack.values[0]);
    record.medium1 = primePackWavefrontMedium(volumeStack.values[1]);
    uint denoiserControl = enabled ? PRIME_WAVEFRONT_ACTIVE_MASK : 0u;
    if (denoiserState.reachedNonDelta) {
        denoiserControl |= PRIME_WAVEFRONT_REACHED_NON_DELTA;
    }
    if (denoiserState.diffusePath) {
        denoiserControl |= PRIME_WAVEFRONT_DIFFUSE_PATH;
    }
    denoiserControl = denoiserControl
            | (min(denoiserState.primaryBounce, PRIME_WAVEFRONT_BYTE_MASK)
                    << PRIME_WAVEFRONT_PRIMARY_BOUNCE_SHIFT)
            | (min(volumeStack.count, 2u)
                    << PRIME_WAVEFRONT_MEDIUM_COUNT_SHIFT)
            | ((primaryMaterialFlags & PRIME_WAVEFRONT_PRIMARY_FLAGS_MASK)
                    << PRIME_WAVEFRONT_PRIMARY_FLAGS_SHIFT);
    record.rayDirectionAndDenoiserControl =
            vec4(path.rayDirection, uintBitsToFloat(denoiserControl));
    record.throughputAndNumericalFlags =
            vec4(path.throughput, uintBitsToFloat(primePackWavefrontDiagnostic()));
    return record;
}

PrimeWavefrontPathRecord primeMakeWavefrontRecord(
        PathState path,
        PrimeRcVolumeStack volumeStack,
        PrimeDenoiserState denoiserState,
        uint primaryMaterialFlags,
        bool enabled) {
    PrimeWavefrontPathRecord record;
    record.transport = primeMakeWavefrontTransportRecord(
            path,
            volumeStack,
            denoiserState,
            primaryMaterialFlags,
            enabled);
    primeSetWavefrontQueuedPsrState(record, primeEmptyQueuedPsrState());
    primeSetWavefrontPreviousLightNormal(record, path.previousLightNormal);
    return record;
}

PathState primeWavefrontPath(
        uvec2 pixel, PrimeWavefrontTransportRecord record) {
    PathState path;
    uint pathControl = floatBitsToUint(record.traceOriginAndPathControl.w);
    path.physicalOrigin = record.physicalOriginAndPreviousBsdfPdf.xyz;
    path.bounce = (pathControl >> PRIME_WAVEFRONT_BOUNCE_SHIFT)
            & PRIME_WAVEFRONT_BYTE_MASK;
    path.traceOrigin = record.traceOriginAndPathControl.xyz;
    uint denoiserControl =
            floatBitsToUint(record.rayDirectionAndDenoiserControl.w);
    path.sampleDimension =
            (denoiserControl & PRIME_WAVEFRONT_TRANSMISSION_BRANCH) != 0u
            ? 1u
            : 0u;
    path.rayDirection = record.rayDirectionAndDenoiserControl.xyz;
    path.flags = (pathControl >> PRIME_WAVEFRONT_PATH_FLAGS_SHIFT)
            & PRIME_PATH_PREVIOUS_DELTA;
    path.throughput = record.throughputAndNumericalFlags.xyz;
    path.previousBsdfPdf = record.physicalOriginAndPreviousBsdfPdf.w;
    path.rrDepth = (pathControl >> PRIME_WAVEFRONT_RR_DEPTH_SHIFT)
            & PRIME_WAVEFRONT_BYTE_MASK;
    path.previousLightNormal = 0u;
    path.pixel = pixel;
    path.sampleIndex = primeSampleIndex();
    path.sampleEpoch = primeSampleEpoch();
    return path;
}

PrimeRcVolumeStack primeWavefrontVolumeStack(
        PrimeWavefrontTransportRecord record) {
    PrimeRcVolumeStack volumeStack;
    volumeStack.values[0] = primeUnpackWavefrontMedium(record.medium0);
    volumeStack.values[1] = primeUnpackWavefrontMedium(record.medium1);
    uint denoiserControl =
            floatBitsToUint(record.rayDirectionAndDenoiserControl.w);
    volumeStack.count = min(
            (denoiserControl >> PRIME_WAVEFRONT_MEDIUM_COUNT_SHIFT)
                    & PRIME_WAVEFRONT_MEDIUM_COUNT_MASK,
            2u);
    return volumeStack;
}

PrimeDenoiserState primeWavefrontDenoiserState(
        uint denoiserControl,
        PrimeIntegrationResult result) {
    PrimeDenoiserState state;
    state.hasPrimarySurface = true;
    state.reachedNonDelta =
            (denoiserControl & PRIME_WAVEFRONT_REACHED_NON_DELTA) != 0u;
    state.diffuseAlbedoProduct = result.guides.primaryAlbedo;
    state.specularAlbedoProduct = result.guides.primarySpecularAlbedo;
    state.diffusePath =
            (denoiserControl & PRIME_WAVEFRONT_DIFFUSE_PATH) != 0u;
    state.primaryBounce =
            (denoiserControl >> PRIME_WAVEFRONT_PRIMARY_BOUNCE_SHIFT)
            & PRIME_WAVEFRONT_BYTE_MASK;
    return state;
}

void primeRestoreWavefrontDiagnostic(PrimeWavefrontTransportRecord record) {
    if (primeWritesRawNumericalDiagnostic()) {
        uint packedDiagnostic =
                floatBitsToUint(record.throughputAndNumericalFlags.w);
        primeRawNumericalFlags =
                packedDiagnostic & PRIME_WAVEFRONT_NUMERICAL_FLAGS_MASK;
        primeRawNumericalFirstContext =
                (packedDiagnostic >> PRIME_WAVEFRONT_NUMERICAL_CONTEXT_SHIFT)
                        & PRIME_WAVEFRONT_NUMERICAL_CONTEXT_MASK;
    }
}

PrimeWavefrontTransportRecord primeMakeUpdatedWavefrontRecord(
        PathState path,
        PrimeRcVolumeStack volumeStack,
        PrimeDenoiserState denoiserState,
        uint previousDenoiserControl,
        uint psrControl,
        bool enabled) {
    uint persistedControl =
            previousDenoiserControl & PRIME_WAVEFRONT_BRANCH_CONTROL_MASK;
    uint primaryMaterialFlags = (previousDenoiserControl
            >> PRIME_WAVEFRONT_PRIMARY_FLAGS_SHIFT)
            & PRIME_WAVEFRONT_PRIMARY_FLAGS_MASK;
    PrimeWavefrontTransportRecord record = primeMakeWavefrontTransportRecord(
            path,
            volumeStack,
            denoiserState,
            primaryMaterialFlags,
            enabled);
    uint denoiserControl =
            floatBitsToUint(record.rayDirectionAndDenoiserControl.w)
                    | persistedControl;
    record.rayDirectionAndDenoiserControl.w = uintBitsToFloat(denoiserControl);
    primeSetWavefrontQueuedPsrControl(record, psrControl);
    return record;
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

PrimeWavefrontPathRecord primeMakeTransparentWavefrontRecord(
        PathState path,
        PrimeRcVolumeStack volumeStack,
        PrimeTransparentBranchResult branchResult,
        PrimeQueuedPsrState psrState,
        bool transmissionBranch,
        bool guideEnabled,
        bool hasGuide,
        bool diffusePath,
        uint guideBounce,
        bool enabled) {
    PrimeDenoiserState denoiserState;
    denoiserState.hasPrimarySurface = true;
    denoiserState.reachedNonDelta = hasGuide;
    denoiserState.diffuseAlbedoProduct = branchResult.guides.primaryAlbedo;
    denoiserState.specularAlbedoProduct =
            branchResult.guides.primarySpecularAlbedo;
    denoiserState.diffusePath = diffusePath;
    denoiserState.primaryBounce = guideBounce;
    PrimeWavefrontPathRecord record = primeMakeWavefrontRecord(
            path,
            volumeStack,
            denoiserState,
            branchResult.guides.primaryMaterialFlags,
            enabled);
    uint control = floatBitsToUint(
            record.transport.rayDirectionAndDenoiserControl.w)
            | PRIME_WAVEFRONT_TRANSPARENT_BRANCH;
    if (transmissionBranch) {
        control |= PRIME_WAVEFRONT_TRANSMISSION_BRANCH;
    }
    if (guideEnabled) {
        control |= PRIME_WAVEFRONT_GUIDE_ENABLED;
    }
    if (branchResult.directionalGuide) {
        control |= PRIME_WAVEFRONT_DIRECTIONAL_GUIDE;
    }
    record.transport.rayDirectionAndDenoiserControl.w = uintBitsToFloat(control);
    primeSetWavefrontQueuedPsrState(record, psrState);
    return record;
}

PrimeWavefrontTransportRecord primeMakeUpdatedTransparentWavefrontRecord(
        PathState path,
        PrimeRcVolumeStack volumeStack,
        PrimeTransparentBranchResult branchResult,
        PrimeQueuedPsrState psrState,
        bool hasGuide,
        bool diffusePath,
        uint guideBounce,
        bool directionalGuide,
        uint previousDenoiserControl,
        bool enabled) {
    PrimeDenoiserState denoiserState;
    denoiserState.hasPrimarySurface = true;
    denoiserState.reachedNonDelta = hasGuide;
    denoiserState.diffuseAlbedoProduct = branchResult.guides.primaryAlbedo;
    denoiserState.specularAlbedoProduct =
            branchResult.guides.primarySpecularAlbedo;
    denoiserState.diffusePath = diffusePath;
    denoiserState.primaryBounce = guideBounce;
    PrimeWavefrontTransportRecord record = primeMakeUpdatedWavefrontRecord(
            path,
            volumeStack,
            denoiserState,
            previousDenoiserControl,
            psrState.control,
            enabled);
    uint control = floatBitsToUint(record.rayDirectionAndDenoiserControl.w);
    control = directionalGuide
            ? control | PRIME_WAVEFRONT_DIRECTIONAL_GUIDE
            : control & ~PRIME_WAVEFRONT_DIRECTIONAL_GUIDE;
    control &= ~(PRIME_WAVEFRONT_PRIMARY_FLAGS_MASK
            << PRIME_WAVEFRONT_PRIMARY_FLAGS_SHIFT);
    control |= (branchResult.guides.primaryMaterialFlags
            & PRIME_WAVEFRONT_PRIMARY_FLAGS_MASK)
            << PRIME_WAVEFRONT_PRIMARY_FLAGS_SHIFT;
    record.rayDirectionAndDenoiserControl.w = uintBitsToFloat(control);
    return record;
}

void primeStoreWavefrontVisibleScratch(
        uvec2 pixel, PrimeDenoiserGuides visibleGuides) {
    ivec2 coordinate = ivec2(pixel);
    imageStore(
            primeNrdNormalRoughness,
            coordinate,
            primeNrdPackNormalRoughness(
                    visibleGuides.primaryNormal,
                    visibleGuides.primaryLinearRoughness,
                    primeNrdMaterialId(visibleGuides.primaryMaterialFlags)));
    imageStore(
            primeStableRadiance,
            coordinate,
            vec4(0.0, 0.0, 0.0, visibleGuides.primaryDistance));
    imageStore(primeNrdSunPenumbra, coordinate, vec4(0.0));
    if (primeWritesNrdShInputs()) {
        imageStore(
                primeNrdDisplayPosition,
                coordinate,
                vec4(
                        visibleGuides.primaryPosition,
                        uintBitsToFloat(visibleGuides.primaryMaterialFlags)));
        imageStore(primeNrdSunLighting, coordinate, vec4(0.0));
    } else {
        // A transparent primary has no SIGMA sun lane. Before resolve this otherwise-unused image
        // preserves the visible interface's specular albedo for RR/no-post output.
        imageStore(
                primeNrdSunLighting,
                coordinate,
                vec4(
                        visibleGuides.primarySpecularAlbedo,
                        visibleGuides.primaryLinearRoughness));
    }
}

bool primeWavefrontPixelTransparent(uvec2 pixel) {
    uint control = floatBitsToUint(
            primeWavefrontPaths.records[primeWavefrontIndex(pixel, 0u)]
                    .transport.rayDirectionAndDenoiserControl.w);
    return primeWavefrontTransparentBranch(control);
}

PrimeDenoiserGuides primeLoadWavefrontVisibleGuides(
        uvec2 pixel,
        PrimeTransparentBranchResult transmission,
        PrimeTransparentBranchResult reflection) {
    ivec2 coordinate = ivec2(pixel);
    PrimeDenoiserGuides guides = transmission.guides;
    guides.primaryDistance = imageLoad(primeStableRadiance, coordinate).a;
    guides.primaryHitKind = guides.primaryDistance >= 0.0
            ? PRIME_HIT_SURFACE
            : PRIME_HIT_NONE;
    vec3 normal;
    float roughness;
    primeNrdUnpackNormalRoughness(
            imageLoad(primeNrdNormalRoughness, coordinate),
            normal,
            roughness);
    guides.primaryNormal = normal;
    guides.primaryLinearRoughness = roughness;
    if (primeWritesNrdShInputs()) {
        vec4 displayPosition =
                imageLoad(primeNrdDisplayPosition, coordinate);
        guides.primaryPosition = displayPosition.xyz;
        guides.primaryMaterialFlags = floatBitsToUint(displayPosition.w);
    } else {
        guides.primaryPosition = primeCameraRayDirection(
                pixel, primeRealtimeCameraSample()) * guides.primaryDistance;
        vec4 visibleMaterial = imageLoad(primeNrdSunLighting, coordinate);
        guides.primarySpecularAlbedo = visibleMaterial.rgb;
        guides.primaryLinearRoughness = visibleMaterial.a;
    }
    guides.diffuseDirection = transmission.guides.diffuseDirection;
    guides.specularDirection = reflection.guides.specularDirection;
    primeLoadWavefrontArea(
            pixel,
            guides.primaryAreaDiffuse,
            guides.primaryAreaSpecular,
            guides.primaryAreaDirection);
    return guides;
}

void primeStoreWavefrontIntermediate(
        uvec2 pixel,
        PrimeIntegrationResult result) {
    ivec2 coordinate = ivec2(pixel);
    imageStore(
            primeNrdNoisyDiffuse,
            coordinate,
            vec4(
                    primeNrdSanitizeRadiance(result.radiance.diffuse),
                    primeNrdSanitizeHitDistance(result.guides.diffuseHitDistance)));
    imageStore(
            primeNrdNoisySpecular,
            coordinate,
            vec4(
                    primeNrdSanitizeRadiance(result.radiance.specular),
                    primeNrdSanitizeHitDistance(result.guides.specularHitDistance)));
    // Stable alpha and the pre-NRD normal scratch preserve three FP16 motion components without
    // adding a full-resolution image; both are overwritten by final output/preparation.
    vec3 primaryMotion = result.guides.primaryHasMotion
            ? result.guides.primaryPreviousPosition - result.guides.primaryPosition
            : vec3(0.0);
    imageStore(
            primeStableRadiance,
            coordinate,
            vec4(
                    primeNrdSanitizeRadiance(result.radiance.stable),
                    uintBitsToFloat(packHalf2x16(primaryMotion.xy))));
    imageStore(
            primeNrdSunLighting,
            coordinate,
            vec4(
                    primeNrdSanitizeRadiance(result.radiance.unshadowedSun),
                    primeNrdSanitizeUnit(result.radiance.sunVisibility, 0.0)));
    imageStore(
            primeNrdSunPenumbra,
            coordinate,
            vec4(primeNrdSanitizeHitDistance(result.guides.sunPenumbra)));
    imageStore(
            primeNrdPrimaryPosition,
            coordinate,
            vec4(result.guides.primaryPosition, result.guides.primaryDistance));
    imageStore(
            primeNrdMaterial,
            coordinate,
            vec4(result.guides.primaryAlbedo, result.guides.primaryLinearRoughness));
    vec2 primaryNormalOctahedral = unpackSnorm2x16(
            primePackOctahedralNormal(result.guides.primaryNormal));
    imageStore(
            primeNrdSpecularMaterial,
            coordinate,
            vec4(
                    result.guides.primarySpecularAlbedo,
                    primaryNormalOctahedral.y
                            + (result.guides.primaryHasMotion ? 4.0 : 0.0)));
    imageStore(
            primeNrdViewZ,
            coordinate,
            vec4(uintBitsToFloat(packHalf2x16(vec2(
                    primaryNormalOctahedral.x,
                    primaryMotion.z)))));
    if (primeWritesNrdShInputs()) {
        imageStore(
                primeNrdDiffuseDirection,
                coordinate,
                vec4(result.guides.diffuseDirection, 0.0));
        imageStore(
                primeNrdSpecularDirection,
                coordinate,
                vec4(result.guides.specularDirection, 0.0));
    }
}

PrimeIntegrationResult primeLoadWavefrontIntermediate(
        uvec2 pixel,
        PrimeWavefrontTransportRecord record) {
    ivec2 coordinate = ivec2(pixel);
    vec4 diffuse = imageLoad(primeNrdNoisyDiffuse, coordinate);
    vec4 specular = imageLoad(primeNrdNoisySpecular, coordinate);
    vec4 stable = imageLoad(primeStableRadiance, coordinate);
    vec4 sun = imageLoad(primeNrdSunLighting, coordinate);
    vec4 position = imageLoad(primeNrdPrimaryPosition, coordinate);
    vec4 material = imageLoad(primeNrdMaterial, coordinate);
    vec4 specularMaterial = imageLoad(primeNrdSpecularMaterial, coordinate);
    uint denoiserControl =
            floatBitsToUint(record.rayDirectionAndDenoiserControl.w);

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
    result.guides.sunPenumbra = imageLoad(primeNrdSunPenumbra, coordinate).r;
    result.guides.primaryAlbedo = material.rgb;
    result.guides.primaryHitKind = position.w >= 0.0
            ? PRIME_HIT_SURFACE
            : PRIME_HIT_NONE;
    vec2 packedNormalMotion = unpackHalf2x16(floatBitsToUint(
            imageLoad(primeNrdViewZ, coordinate).r));
    bool primaryHasMotion = specularMaterial.a > 2.0;
    float primaryNormalY = specularMaterial.a
            - (primaryHasMotion ? 4.0 : 0.0);
    result.guides.primaryNormal = primeUnpackOctahedralNormal(
            packSnorm2x16(vec2(packedNormalMotion.x, primaryNormalY)));
    result.guides.primaryMaterialFlags =
            (denoiserControl >> PRIME_WAVEFRONT_PRIMARY_FLAGS_SHIFT)
                    & PRIME_WAVEFRONT_PRIMARY_FLAGS_MASK;
    result.guides.primarySpecularAlbedo = specularMaterial.rgb;
    result.guides.primaryLinearRoughness = material.w;
    result.guides.primaryPosition = position.xyz;
    vec2 primaryMotionXY = unpackHalf2x16(floatBitsToUint(stable.a));
    result.guides.primaryPreviousPosition = position.xyz
            + vec3(primaryMotionXY, packedNormalMotion.y);
    result.guides.primaryHasMotion = primaryHasMotion;
    if (primeWritesNrdShInputs()) {
        result.guides.diffuseDirection =
                primeRestoreFp16Direction(
                        imageLoad(primeNrdDiffuseDirection, coordinate).xyz);
        result.guides.specularDirection =
                primeRestoreFp16Direction(
                        imageLoad(primeNrdSpecularDirection, coordinate).xyz);
    }
    primeLoadWavefrontArea(
            pixel,
            result.guides.primaryAreaDiffuse,
            result.guides.primaryAreaSpecular,
            result.guides.primaryAreaDirection);
    result.reflectionDiffuseRadiance = vec3(0.0);
    result.reflectionSpecularRadiance = vec3(0.0);
    result.transmissionGuides = primeEmptyDenoiserGuides();
    result.reflectionGuides = primeEmptyDenoiserGuides();
    result.transmissionAnchorDistance = -1.0;
    result.reflectionDirectionalGuide = false;
    result.transparentPrimary = false;
    return result;
}

// A queued ordinary continuation already owns a primary guide. Only radiance and the two
// first-bounce hit distances evolve on every vertex. The pre-guide delta chain additionally needs
// its albedo products, but no other immutable guide field participates in transport.
PrimeIntegrationResult primeLoadWavefrontActiveIntermediate(
        uvec2 pixel,
        uint control) {
    ivec2 coordinate = ivec2(pixel);
    vec4 diffuse = imageLoad(primeNrdNoisyDiffuse, coordinate);
    vec4 specular = imageLoad(primeNrdNoisySpecular, coordinate);
    PrimeIntegrationResult result = primeEmptyIntegrationResult();
    result.radiance.diffuse = diffuse.rgb;
    result.radiance.specular = specular.rgb;
    result.guides.diffuseHitDistance = diffuse.a;
    result.guides.specularHitDistance = specular.a;
    if ((control & PRIME_WAVEFRONT_REACHED_NON_DELTA) == 0u) {
        result.guides.primaryAlbedo =
                imageLoad(primeNrdMaterial, coordinate).rgb;
        result.guides.primarySpecularAlbedo =
                imageLoad(primeNrdSpecularMaterial, coordinate).rgb;
    }
    return result;
}

void primeStoreWavefrontActiveIntermediate(
        uvec2 pixel,
        bool guideWasPending,
        PrimeIntegrationResult result) {
    ivec2 coordinate = ivec2(pixel);
    imageStore(
            primeNrdNoisyDiffuse,
            coordinate,
            vec4(
                    primeNrdSanitizeRadiance(result.radiance.diffuse),
                    primeNrdSanitizeHitDistance(
                            result.guides.diffuseHitDistance)));
    imageStore(
            primeNrdNoisySpecular,
            coordinate,
            vec4(
                    primeNrdSanitizeRadiance(result.radiance.specular),
                    primeNrdSanitizeHitDistance(
                            result.guides.specularHitDistance)));
    if (guideWasPending) {
        vec4 encoded = imageLoad(primeNrdSpecularMaterial, coordinate);
        imageStore(
                primeNrdSpecularMaterial,
                coordinate,
                vec4(
                        result.guides.primarySpecularAlbedo,
                        encoded.a));
    }
}

void primeStoreTransparentBranchIntermediate(
        uvec2 pixel,
        bool transmissionBranch,
        PrimeTransparentBranchResult result) {
    ivec2 coordinate = ivec2(pixel);
    if (transmissionBranch) {
        imageStore(
                primeNrdNoisyDiffuse,
                coordinate,
                vec4(
                        primeNrdSanitizeRadiance(result.diffuseRadiance),
                        primeNrdSanitizeHitDistance(
                                result.guides.diffuseHitDistance)));
        imageStore(
                primeNrdNoisySpecular,
                coordinate,
                vec4(
                        primeNrdSanitizeRadiance(result.specularRadiance),
                        primeNrdSanitizeHitDistance(
                                result.guides.specularHitDistance)));
        imageStore(
                primeNrdPrimaryPosition,
                coordinate,
                vec4(result.guides.primaryPosition, result.guides.primaryDistance));
        imageStore(
                primeNrdMaterial,
                coordinate,
                vec4(
                        result.guides.primaryAlbedo,
                        result.guides.primaryLinearRoughness));
        vec2 normalOctahedral = unpackSnorm2x16(
                primePackOctahedralNormal(result.guides.primaryNormal));
        imageStore(
                primeNrdSpecularMaterial,
                coordinate,
                vec4(result.guides.primarySpecularAlbedo, normalOctahedral.y));
        imageStore(primeNrdViewZ, coordinate, vec4(normalOctahedral.x));
        if (primeWritesNrdShInputs()) {
            imageStore(
                    primeNrdDiffuseDirection,
                    coordinate,
                    vec4(result.guides.diffuseDirection, 0.0));
            imageStore(
                    primeNrdSpecularDirection,
                    coordinate,
                    vec4(result.guides.specularDirection, 0.0));
        }
        imageStore(
                primeWavefrontTransportMetadata,
                coordinate,
                vec4(
                        result.anchorDistance,
                        result.firstHitDistance,
                        0.0,
                        0.0));
        return;
    }

    imageStore(
            primeNrdSunPenumbra,
            coordinate,
            vec4(primeNrdSanitizeHitDistance(result.firstHitDistance)));
    if (!primeWritesNrdShInputs()) {
        vec4 visibleScratch = imageLoad(primeStableRadiance, coordinate);
        imageStore(
                primeStableRadiance,
                coordinate,
                vec4(
                        primeNrdSanitizeRadiance(
                                result.diffuseRadiance
                                        + result.specularRadiance),
                        visibleScratch.a));
        return;
    }
    imageStore(
            primeNrdReflectionNoisyDiffuse,
            coordinate,
            vec4(
                    primeNrdSanitizeRadiance(result.diffuseRadiance),
                    primeNrdSanitizeHitDistance(
                            result.guides.diffuseHitDistance)));
    imageStore(
            primeNrdReflectionNoisySpecular,
            coordinate,
            vec4(
                    primeNrdSanitizeRadiance(result.specularRadiance),
                    primeNrdSanitizeHitDistance(
                            result.guides.specularHitDistance)));
    imageStore(
            primeNrdReflectionPosition,
            coordinate,
            vec4(result.guides.primaryPosition, result.firstHitDistance));
    imageStore(
            primeNrdReflectionMaterial,
            coordinate,
            vec4(result.guides.primaryAlbedo, result.guides.primaryDistance));
    imageStore(
            primeNrdReflectionSpecularMaterial,
            coordinate,
            vec4(result.guides.primarySpecularAlbedo, 0.0));
    imageStore(
            primeNrdReflectionNormalRoughness,
            coordinate,
            primeNrdPackNormalRoughness(
                    result.guides.primaryNormal,
                    result.guides.primaryLinearRoughness,
                    primeNrdMaterialId(result.guides.primaryMaterialFlags)));
    if (primeWritesNrdShInputs()) {
        imageStore(
                primeNrdReflectionDiffuseDirection,
                coordinate,
                vec4(result.guides.diffuseDirection, 0.0));
        imageStore(
                primeNrdReflectionSpecularDirection,
                coordinate,
                vec4(result.guides.specularDirection, 0.0));
    }
}

// Active transparent branches keep their immutable guide images resident. Per-vertex transport
// reads only radiance, hit metadata and the two fallback directions required until PSR resolves.
PrimeTransparentBranchResult primeLoadTransparentBranchActiveIntermediate(
        uvec2 pixel,
        bool transmissionBranch,
        uint control) {
    ivec2 coordinate = ivec2(pixel);
    bool nrdShInputs = primeWritesNrdShInputs();
    bool hasGuide = (control & PRIME_WAVEFRONT_REACHED_NON_DELTA) != 0u;
    vec4 diffuse = transmissionBranch
            ? imageLoad(primeNrdNoisyDiffuse, coordinate)
            : nrdShInputs
                    ? imageLoad(primeNrdReflectionNoisyDiffuse, coordinate)
                    : vec4(imageLoad(primeStableRadiance, coordinate).rgb, 0.0);
    vec4 specular = transmissionBranch
            ? imageLoad(primeNrdNoisySpecular, coordinate)
            : nrdShInputs
                    ? imageLoad(primeNrdReflectionNoisySpecular, coordinate)
                    : vec4(0.0);

    PrimeTransparentBranchResult result;
    result.diffuseRadiance = diffuse.rgb;
    result.specularRadiance = specular.rgb;
    result.guides = primeEmptyDenoiserGuides();
    result.guides.diffuseHitDistance = diffuse.a;
    result.guides.specularHitDistance = specular.a;
    result.guides.primaryMaterialFlags =
            (control >> PRIME_WAVEFRONT_PRIMARY_FLAGS_SHIFT)
                    & PRIME_WAVEFRONT_PRIMARY_FLAGS_MASK;
    if (!hasGuide && nrdShInputs) {
        if (transmissionBranch) {
            result.guides.diffuseDirection = primeRestoreFp16Direction(
                    imageLoad(primeNrdDiffuseDirection, coordinate).xyz);
            result.guides.specularDirection = primeRestoreFp16Direction(
                    imageLoad(primeNrdSpecularDirection, coordinate).xyz);
        } else {
            result.guides.diffuseDirection = primeRestoreFp16Direction(
                    imageLoad(
                            primeNrdReflectionDiffuseDirection,
                            coordinate).xyz);
            result.guides.specularDirection = primeRestoreFp16Direction(
                    imageLoad(
                            primeNrdReflectionSpecularDirection,
                            coordinate).xyz);
        }
    }
    if (transmissionBranch) {
        vec4 metadata = imageLoad(primeWavefrontTransportMetadata, coordinate);
        result.anchorDistance = metadata.x;
        result.firstHitDistance = metadata.y;
    } else {
        result.anchorDistance = -1.0;
        result.firstHitDistance =
                imageLoad(primeNrdSunPenumbra, coordinate).r;
    }
    result.directionalGuide = primeWavefrontDirectionalGuide(control);
    return result;
}

void primeStoreTransparentBranchGuide(
        uvec2 pixel,
        bool transmissionBranch,
        PrimeTransparentBranchResult result) {
    ivec2 coordinate = ivec2(pixel);
    if (transmissionBranch) {
        imageStore(
                primeNrdPrimaryPosition,
                coordinate,
                vec4(result.guides.primaryPosition, result.guides.primaryDistance));
        imageStore(
                primeNrdMaterial,
                coordinate,
                vec4(
                        result.guides.primaryAlbedo,
                        result.guides.primaryLinearRoughness));
        vec2 normalOctahedral = unpackSnorm2x16(
                primePackOctahedralNormal(result.guides.primaryNormal));
        imageStore(
                primeNrdSpecularMaterial,
                coordinate,
                vec4(result.guides.primarySpecularAlbedo, normalOctahedral.y));
        imageStore(primeNrdViewZ, coordinate, vec4(normalOctahedral.x));
        if (primeWritesNrdShInputs()) {
            imageStore(
                    primeNrdDiffuseDirection,
                    coordinate,
                    vec4(result.guides.diffuseDirection, 0.0));
            imageStore(
                    primeNrdSpecularDirection,
                    coordinate,
                    vec4(result.guides.specularDirection, 0.0));
        }
        return;
    }
    if (!primeWritesNrdShInputs()) {
        return;
    }

    imageStore(
            primeNrdReflectionPosition,
            coordinate,
            vec4(result.guides.primaryPosition, result.firstHitDistance));
    imageStore(
            primeNrdReflectionMaterial,
            coordinate,
            vec4(result.guides.primaryAlbedo, result.guides.primaryDistance));
    imageStore(
            primeNrdReflectionSpecularMaterial,
            coordinate,
            vec4(result.guides.primarySpecularAlbedo, 0.0));
    imageStore(
            primeNrdReflectionNormalRoughness,
            coordinate,
            primeNrdPackNormalRoughness(
                    result.guides.primaryNormal,
                    result.guides.primaryLinearRoughness,
                    primeNrdMaterialId(result.guides.primaryMaterialFlags)));
    imageStore(
            primeNrdReflectionDiffuseDirection,
            coordinate,
            vec4(result.guides.diffuseDirection, 0.0));
    imageStore(
            primeNrdReflectionSpecularDirection,
            coordinate,
            vec4(result.guides.specularDirection, 0.0));
}

void primeStoreTransparentBranchActiveIntermediate(
        uvec2 pixel,
        bool transmissionBranch,
        bool storeGuide,
        bool storeDirectionalPosition,
        PrimeTransparentBranchResult result) {
    ivec2 coordinate = ivec2(pixel);
    if (transmissionBranch) {
        imageStore(
                primeNrdNoisyDiffuse,
                coordinate,
                vec4(
                        primeNrdSanitizeRadiance(result.diffuseRadiance),
                        primeNrdSanitizeHitDistance(
                                result.guides.diffuseHitDistance)));
        imageStore(
                primeNrdNoisySpecular,
                coordinate,
                vec4(
                        primeNrdSanitizeRadiance(result.specularRadiance),
                        primeNrdSanitizeHitDistance(
                                result.guides.specularHitDistance)));
        imageStore(
                primeWavefrontTransportMetadata,
                coordinate,
                vec4(
                        result.anchorDistance,
                        result.firstHitDistance,
                        0.0,
                        0.0));
    } else {
        imageStore(
                primeNrdSunPenumbra,
                coordinate,
                vec4(primeNrdSanitizeHitDistance(result.firstHitDistance)));
        if (primeWritesNrdShInputs()) {
            imageStore(
                    primeNrdReflectionNoisyDiffuse,
                    coordinate,
                    vec4(
                            primeNrdSanitizeRadiance(result.diffuseRadiance),
                            primeNrdSanitizeHitDistance(
                                    result.guides.diffuseHitDistance)));
            imageStore(
                    primeNrdReflectionNoisySpecular,
                    coordinate,
                    vec4(
                            primeNrdSanitizeRadiance(result.specularRadiance),
                            primeNrdSanitizeHitDistance(
                                    result.guides.specularHitDistance)));
        } else {
            vec4 visibleScratch = imageLoad(primeStableRadiance, coordinate);
            imageStore(
                    primeStableRadiance,
                    coordinate,
                    vec4(
                            primeNrdSanitizeRadiance(
                                    result.diffuseRadiance
                                            + result.specularRadiance),
                            visibleScratch.a));
        }
    }

    if (storeGuide) {
        primeStoreTransparentBranchGuide(
                pixel, transmissionBranch, result);
    } else if (storeDirectionalPosition
            && !transmissionBranch
            && primeWritesNrdShInputs()) {
        imageStore(
                primeNrdReflectionPosition,
                coordinate,
                vec4(result.guides.primaryPosition, result.firstHitDistance));
    }
}

PrimeTransparentBranchResult primeLoadTransparentBranchIntermediate(
        uvec2 pixel,
        bool transmissionBranch,
        uint control) {
    ivec2 coordinate = ivec2(pixel);
    bool nrdShInputs = primeWritesNrdShInputs();
    vec4 diffuse = transmissionBranch
            ? imageLoad(primeNrdNoisyDiffuse, coordinate)
            : nrdShInputs
                    ? imageLoad(primeNrdReflectionNoisyDiffuse, coordinate)
                    : vec4(imageLoad(primeStableRadiance, coordinate).rgb, 0.0);
    vec4 specular = transmissionBranch
            ? imageLoad(primeNrdNoisySpecular, coordinate)
            : nrdShInputs
                    ? imageLoad(primeNrdReflectionNoisySpecular, coordinate)
                    : vec4(0.0);
    vec4 position = transmissionBranch
            ? imageLoad(primeNrdPrimaryPosition, coordinate)
            : nrdShInputs
                    ? imageLoad(primeNrdReflectionPosition, coordinate)
                    : vec4(0.0);
    vec4 material = transmissionBranch
            ? imageLoad(primeNrdMaterial, coordinate)
            : nrdShInputs
                    ? imageLoad(primeNrdReflectionMaterial, coordinate)
                    : vec4(0.0);
    vec4 specularMaterial = transmissionBranch
            ? imageLoad(primeNrdSpecularMaterial, coordinate)
            : nrdShInputs
                    ? imageLoad(primeNrdReflectionSpecularMaterial, coordinate)
                    : vec4(0.0);
    vec4 branchMetadata = transmissionBranch
            ? imageLoad(primeWavefrontTransportMetadata, coordinate)
            : position;
    PrimeTransparentBranchResult result;
    result.diffuseRadiance = diffuse.rgb;
    result.specularRadiance = specular.rgb;
    result.guides = primeEmptyDenoiserGuides();
    result.guides.diffuseHitDistance = diffuse.a;
    result.guides.specularHitDistance = specular.a;
    result.guides.primaryPosition = position.xyz;
    result.guides.primaryDistance =
            transmissionBranch ? position.w : material.w;
    result.guides.primaryHitKind = result.guides.primaryDistance >= 0.0
            ? PRIME_HIT_SURFACE
            : PRIME_HIT_NONE;
    result.guides.primaryAlbedo = material.rgb;
    result.guides.primaryMaterialFlags =
            (control >> PRIME_WAVEFRONT_PRIMARY_FLAGS_SHIFT)
                    & PRIME_WAVEFRONT_PRIMARY_FLAGS_MASK;
    result.guides.primarySpecularAlbedo = specularMaterial.rgb;
    if (transmissionBranch) {
        result.guides.primaryNormal = primeUnpackOctahedralNormal(
                packSnorm2x16(vec2(
                        imageLoad(primeNrdViewZ, coordinate).r,
                        specularMaterial.a)));
        result.guides.primaryLinearRoughness = material.w;
        if (primeWritesNrdShInputs()) {
            result.guides.diffuseDirection = primeRestoreFp16Direction(
                    imageLoad(primeNrdDiffuseDirection, coordinate).xyz);
            result.guides.specularDirection = primeRestoreFp16Direction(
                    imageLoad(primeNrdSpecularDirection, coordinate).xyz);
        }
    } else if (nrdShInputs) {
        vec3 reflectionNormal;
        float reflectionRoughness;
        primeNrdUnpackNormalRoughness(
                imageLoad(primeNrdReflectionNormalRoughness, coordinate),
                reflectionNormal,
                reflectionRoughness);
        result.guides.primaryNormal = reflectionNormal;
        result.guides.primaryLinearRoughness = reflectionRoughness;
        if (primeWritesNrdShInputs()) {
            result.guides.diffuseDirection = primeRestoreFp16Direction(
                    imageLoad(
                            primeNrdReflectionDiffuseDirection,
                            coordinate).xyz);
            result.guides.specularDirection = primeRestoreFp16Direction(
                    imageLoad(
                            primeNrdReflectionSpecularDirection,
                            coordinate).xyz);
        }
    }
    primeLoadWavefrontArea(
            pixel,
            result.guides.primaryAreaDiffuse,
            result.guides.primaryAreaSpecular,
            result.guides.primaryAreaDirection);
    result.anchorDistance = transmissionBranch ? branchMetadata.x : -1.0;
    result.firstHitDistance = transmissionBranch
            ? branchMetadata.y
            : imageLoad(primeNrdSunPenumbra, coordinate).r;
    result.directionalGuide = primeWavefrontDirectionalGuide(control);
    return result;
}

void primeFinalizeTransparentBranchGuide(
        uint control,
        inout PrimeTransparentBranchResult result) {
    bool hasGuide =
            (control & PRIME_WAVEFRONT_REACHED_NON_DELTA) != 0u;
    if (!primeWavefrontGuideEnabled(control) || hasGuide) {
        return;
    }
    vec3 branchDirection = primeWavefrontTransmissionBranch(control)
            ? result.guides.diffuseDirection
            : result.guides.specularDirection;
    result.guides.primaryAreaDiffuse = vec3(0.0);
    result.guides.primaryAreaSpecular = vec3(0.0);
    result.guides.primaryAreaDirection = vec3(0.0);
    if (primeWavefrontTransmissionBranch(control)) {
        result.guides.diffuseDirection = branchDirection;
        result.guides.diffuseHitDistance = PRIME_NRD_FP16_MAX;
    } else {
        result.guides.specularDirection = branchDirection;
        result.guides.specularHitDistance = PRIME_NRD_FP16_MAX;
    }
}

PrimeIntegrationResult primeResolveTransparentWavefrontResult(uvec2 pixel) {
    uint transmissionControl = floatBitsToUint(
            primeWavefrontPaths.records[primeWavefrontIndex(pixel, 0u)]
                    .transport.rayDirectionAndDenoiserControl.w);
    uint reflectionControl = floatBitsToUint(
            primeWavefrontPaths.records[primeWavefrontIndex(pixel, 1u)]
                    .transport.rayDirectionAndDenoiserControl.w);
    PrimeTransparentBranchResult transmission =
            primeLoadTransparentBranchIntermediate(
                    pixel, true, transmissionControl);
    PrimeTransparentBranchResult reflection =
            primeLoadTransparentBranchIntermediate(
                    pixel, false, reflectionControl);
    PrimeDenoiserGuides visibleGuides =
            primeLoadWavefrontVisibleGuides(
                    pixel,
                    transmission,
                    reflection);
    primeFinalizeTransparentBranchGuide(
            transmissionControl, transmission);
    primeFinalizeTransparentBranchGuide(
            reflectionControl, reflection);

    PrimeIntegrationResult result = primeEmptyIntegrationResult();
    result.radiance.diffuse = transmission.diffuseRadiance;
    result.radiance.specular = transmission.specularRadiance;
    result.radiance.stable = vec3(0.0);
    result.radiance.unshadowedSun = vec3(0.0);
    result.radiance.sunVisibility = 0.0;
    result.guides = visibleGuides;
    result.guides.diffuseHitDistance = transmission.firstHitDistance;
    result.guides.specularHitDistance = reflection.firstHitDistance;
    result.guides.sunPenumbra = 0.0;
    result.reflectionDiffuseRadiance = reflection.diffuseRadiance;
    result.reflectionSpecularRadiance = reflection.specularRadiance;
    result.transmissionGuides = transmission.guides;
    result.reflectionGuides = reflection.guides;
    result.transmissionGuides.primaryAreaDiffuse =
            result.guides.primaryAreaDiffuse;
    result.transmissionGuides.primaryAreaDirection =
            result.guides.primaryAreaDirection;
    result.reflectionGuides.primaryAreaSpecular =
            result.guides.primaryAreaSpecular;
    result.reflectionGuides.primaryAreaDirection =
            result.guides.primaryAreaDirection;
    result.transmissionAnchorDistance = transmission.anchorDistance;
    result.reflectionDirectionalGuide = reflection.directionalGuide;
    result.transparentPrimary = true;
    return result;
}

#endif
