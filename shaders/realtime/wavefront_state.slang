#ifndef PRIME_WAVEFRONT_STATE_SLANG
#define PRIME_WAVEFRONT_STATE_SLANG

import "realtime/wavefront_medium.slang";
import "integrator/wavefront_queue.slang";
#include "realtime/wavefront_storage.slang"

// Member-wise access keeps cold PSR state out of ordinary continuations and prevents a storage
// record aggregate from remaining live across the integrator call tree.
PrimeWavefrontTransportRecord primeLoadWavefrontTransportRecord(uint pathIndex) {
    PrimeWavefrontTransportRecord record;
    record.physicalOriginAndPreviousBsdfPdf =
            primeWavefrontPaths[pathIndex]
                    .transport.physicalOriginAndPreviousBsdfPdf;
    record.sourceAndPathControl =
            primeWavefrontPaths[pathIndex].transport.sourceAndPathControl;
    record.rayDirectionAndDenoiserControl =
            primeWavefrontPaths[pathIndex]
                    .transport.rayDirectionAndDenoiserControl;
    record.throughputAndNumericalFlags =
            primeWavefrontPaths[pathIndex]
                    .transport.throughputAndNumericalFlags;
    record.medium0 = primeWavefrontPaths[pathIndex].transport.medium0;
    record.medium1 = primeWavefrontPaths[pathIndex].transport.medium1;
    return record;
}

void primeStoreWavefrontTransportRecord(
        uint pathIndex,
        PrimeWavefrontTransportRecord record) {
    primeWavefrontPaths[pathIndex]
            .transport.physicalOriginAndPreviousBsdfPdf =
            record.physicalOriginAndPreviousBsdfPdf;
    primeWavefrontPaths[pathIndex].transport.sourceAndPathControl =
            record.sourceAndPathControl;
    primeWavefrontPaths[pathIndex]
            .transport.rayDirectionAndDenoiserControl =
            record.rayDirectionAndDenoiserControl;
    primeWavefrontPaths[pathIndex]
            .transport.throughputAndNumericalFlags =
            record.throughputAndNumericalFlags;
    primeWavefrontPaths[pathIndex].transport.medium0 = record.medium0;
    primeWavefrontPaths[pathIndex].transport.medium1 = record.medium1;
}

static const uint PRIME_WAVEFRONT_SURFACE_LANE_WORDS = 4u;

void primeStoreWavefrontSurfaceLane(uint base, uint laneIndex, uint4 lane) {
    uint word = base + laneIndex * PRIME_WAVEFRONT_SURFACE_LANE_WORDS;
    primeWavefrontQueue[word + 0u] = lane.x;
    primeWavefrontQueue[word + 1u] = lane.y;
    primeWavefrontQueue[word + 2u] = lane.z;
    primeWavefrontQueue[word + 3u] = lane.w;
}

uint4 primeLoadWavefrontSurfaceLane(uint base, uint laneIndex) {
    uint word = base + laneIndex * PRIME_WAVEFRONT_SURFACE_LANE_WORDS;
    return uint4(
            primeWavefrontQueue[word + 0u],
            primeWavefrontQueue[word + 1u],
            primeWavefrontQueue[word + 2u],
            primeWavefrontQueue[word + 3u]);
}

// Area guides and the two path-indexed hit arrays occupy separate contiguous regions. Keeping the
// surface arrays branch-major preserves coalesced ordinary-path access while both fixed transparent
// continuations can safely remain in flight. Position is retained as f32 hit output rather than
// reconstructed from ray arithmetic. hitKind occupies the low payload byte while motionZFlags
// occupies the remaining bits, so their original packed word is also lossless.
uint primeWavefrontSurfaceWord(uint pathIndex) {
    uint guideWords = primeWavefrontPixelCount() * PRIME_WAVEFRONT_AREA_GUIDE_WORDS;
    return guideWords
            + pathIndex * (PRIME_WAVEFRONT_SURFACE_RECORD_SIZE / 4u);
}

// The physical surface record owns these high bits between Light and Shade; SurfaceInteraction
// continues to expose only the low-byte hit kind.
void primeStoreWavefrontSunFamilyProbability(
        uint pathIndex,
        uint storedProbability) {
    uint word = primeWavefrontSurfaceWord(pathIndex)
            + PRIME_WAVEFRONT_SURFACE_LANE_WORDS;
    primeWavefrontQueue[word] = primePackWavefrontSurfaceSunProbability(
            primeWavefrontQueue[word], storedProbability);
}

uint primeLoadWavefrontSunFamilyProbability(uint pathIndex) {
    uint word = primeWavefrontSurfaceWord(pathIndex)
            + PRIME_WAVEFRONT_SURFACE_LANE_WORDS;
    return primeWavefrontSurfaceSunProbability(primeWavefrontQueue[word]);
}

void primeClearWavefrontTransparentAreaPending(uint pathIndex) {
    uint word = primeWavefrontSurfaceWord(pathIndex)
            + PRIME_WAVEFRONT_SURFACE_LANE_WORDS;
    primeWavefrontQueue[word] &= ~PRIME_WAVEFRONT_TRANSPARENT_AREA_PENDING;
}

void primeStoreWavefrontTransparentAreaPending(
        uint pathIndex,
        uint storedProbability) {
    uint word = primeWavefrontSurfaceWord(pathIndex)
            + PRIME_WAVEFRONT_SURFACE_LANE_WORDS;
    primeWavefrontQueue[word] = primePackWavefrontSurfaceSunProbability(
            primeWavefrontQueue[word], storedProbability)
            | PRIME_WAVEFRONT_TRANSPARENT_AREA_PENDING;
}

bool primeWavefrontTransparentAreaPending(uint pathIndex) {
    uint word = primeWavefrontSurfaceWord(pathIndex)
            + PRIME_WAVEFRONT_SURFACE_LANE_WORDS;
    return (primeWavefrontQueue[word]
            & PRIME_WAVEFRONT_TRANSPARENT_AREA_PENDING) != 0u;
}

void primeStoreWavefrontSurface(uint pathIndex, SurfaceInteraction surface) {
    uint base = primeWavefrontSurfaceWord(pathIndex);
    if (surface.hitKind == PRIME_HIT_NONE) {
        // Clear the complete packed hit-kind word; Light may reuse its high bits for sun-family
        // probability on surface paths, and stale bits must not survive record reuse.
        primeWavefrontQueue[base + PRIME_WAVEFRONT_SURFACE_LANE_WORDS] =
                PRIME_HIT_NONE;
        return;
    }
    primeStoreWavefrontSurfaceLane(base, 0u, uint4(
            asuint(surface.t),
            asuint(surface.geometricNormal.x),
            asuint(surface.geometricNormal.y),
            asuint(surface.geometricNormal.z)));
    primeStoreWavefrontSurfaceLane(base, 1u, uint4(
            surface.hitKind,
            surface.materialControl,
            surface.sectionIndex,
            surface.emitterIndex));
    primeStoreWavefrontSurfaceLane(base, 2u, uint4(
            asuint(surface.baseColor.x),
            asuint(surface.baseColor.y),
            asuint(surface.baseColor.z),
            asuint(surface.textureLod)));
    primeStoreWavefrontSurfaceLane(base, 3u, uint4(
            asuint(surface.opacity),
            surface.shadingNormal,
            asuint(surface.roughness),
            surface.opticalControl));
    primeStoreWavefrontSurfaceLane(base, 4u, uint4(
            asuint(surface.adjacentBaseColor.x),
            asuint(surface.adjacentBaseColor.y),
            asuint(surface.adjacentBaseColor.z),
            surface.adjacentInterfaceControl));
    primeStoreWavefrontSurfaceLane(base, 5u, uint4(
            asuint(surface.position.x),
            asuint(surface.position.y),
            asuint(surface.position.z),
            surface.motionZFlags));
}

SurfaceInteraction primeLoadWavefrontSurface(uint pathIndex) {
    uint base = primeWavefrontSurfaceWord(pathIndex);
    uint hitKind = primeWavefrontQueue[
            base + PRIME_WAVEFRONT_SURFACE_LANE_WORDS]
            & PRIME_HIT_KIND_MASK;
    if (hitKind == PRIME_HIT_NONE) {
        return primeMissSurfaceInteraction(
                primeWavefrontPaths[pathIndex]
                        .transport.sourceAndPathControl.xy);
    }
    SurfaceInteraction surface;
    uint4 lane = primeLoadWavefrontSurfaceLane(base, 0u);
    surface.t = asfloat(lane.x);
    surface.geometricNormal = float3(
            asfloat(lane.y), asfloat(lane.z), asfloat(lane.w));
    surface.hitKind = hitKind;
    surface.materialControl = primeWavefrontQueue[
            base + PRIME_WAVEFRONT_SURFACE_LANE_WORDS + 1u];
    surface.sectionIndex = primeWavefrontQueue[
            base + PRIME_WAVEFRONT_SURFACE_LANE_WORDS + 2u];
    surface.emitterIndex = primeWavefrontQueue[
            base + PRIME_WAVEFRONT_SURFACE_LANE_WORDS + 3u];
    lane = primeLoadWavefrontSurfaceLane(base, 2u);
    surface.baseColor = float3(
            asfloat(lane.x), asfloat(lane.y), asfloat(lane.z));
    surface.textureLod = asfloat(lane.w);
    lane = primeLoadWavefrontSurfaceLane(base, 3u);
    surface.opacity = asfloat(lane.x);
    surface.shadingNormal = lane.y;
    surface.roughness = asfloat(lane.z);
    surface.opticalControl = lane.w;
    lane = primeLoadWavefrontSurfaceLane(base, 4u);
    surface.adjacentBaseColor = float3(
            asfloat(lane.x), asfloat(lane.y), asfloat(lane.z));
    surface.adjacentInterfaceControl = lane.w;
    lane = primeLoadWavefrontSurfaceLane(base, 5u);
    surface.position = float3(
            asfloat(lane.x), asfloat(lane.y), asfloat(lane.z));
    surface.motionZFlags = lane.w;
    return surface;
}

uint primeWavefrontQueuedPath(uint queue, uint entry) {
    return primeWavefrontQueue[primeWavefrontQueueWord(queue, entry)];
}

// Indirect width is fetched before ray generation begins. The first consumer can therefore clear
// only the counter in-shader; the following wavefront barrier publishes the reset before reuse.
void primeResetWavefrontQueue(uint queue) {
    uint ignored;
    InterlockedExchange(
            primeWavefrontQueue[primeWavefrontQueueCommandWord(queue)],
            0u,
            ignored);
}

struct PrimeRealtimeQueueStorage : IPrimeWavefrontQueueStorage {
    uint capacity(uint queue) {
        return primeWavefrontQueueCapacity(queue);
    }

    uint reserve(uint queue, uint count) {
        uint firstEntry;
        InterlockedAdd(
                primeWavefrontQueue[primeWavefrontQueueCommandWord(queue)],
                count,
                firstEntry);
        return firstEntry;
    }

    void markOverflow(uint queue, uint capacity) {
        uint commandWord = primeWavefrontQueueCommandWord(queue);
        uint ignored;
        InterlockedMin(primeWavefrontQueue[commandWord], capacity, ignored);
        InterlockedOr(primeWavefrontQueue[commandWord + 3u], 1u, ignored);
    }

    void store(uint queue, uint entry, uint pathIndex) {
        primeWavefrontQueue[primeWavefrontQueueWord(queue, entry)] = pathIndex;
    }
};

void primeAppendWavefrontPath(uint queue, uint pathIndex) {
    primeAppendScalarQueueEntry(
            PrimeRealtimeQueueStorage(), queue, pathIndex, true);
}

interface IPrimeWavefrontContinuationAppender {
    void append(uint queue, uint pathIndex, bool continuation);
}

struct PrimeScalarWavefrontContinuationAppender
        : IPrimeWavefrontContinuationAppender {
    void append(uint queue, uint pathIndex, bool continuation) {
        primeAppendScalarQueueEntry(
                PrimeRealtimeQueueStorage(), queue, pathIndex, continuation);
    }
};

struct PrimeSubgroupWavefrontContinuationAppender
        : IPrimeWavefrontContinuationAppender {
    void append(uint queue, uint pathIndex, bool continuation) {
        primeAppendSubgroupQueueEntry(
                PrimeRealtimeQueueStorage(), queue, pathIndex, continuation);
    }
};

void primeAppendWavefrontContinuation<Appender>(
        Appender appender,
        uint queue,
        uint pathIndex,
        bool continuation)
    where Appender : IPrimeWavefrontContinuationAppender
{
    appender.append(queue, pathIndex, continuation);
}

uint primePackWavefrontPathControl(PathState path) {
    // FP16 etaScale only changes the roulette probability; survivor reweighting preserves the
    // estimator's expectation. Medium extinction and IOR remain FP32 in the transport record.
    return (min(path.bounce, PRIME_WAVEFRONT_BYTE_MASK)
                    << PRIME_WAVEFRONT_BOUNCE_SHIFT)
            | ((primePackWavefrontEtaScale(path.etaScale)
                    & PRIME_WAVEFRONT_ETA_SCALE_MASK)
                    << PRIME_WAVEFRONT_ETA_SCALE_SHIFT)
            | ((path.flags & PRIME_WAVEFRONT_PATH_FLAGS_MASK)
                    << PRIME_WAVEFRONT_PATH_FLAGS_SHIFT);
}

uint primePackWavefrontDiagnostic(PrimeNumericalDiagnostics diagnostics) {
    // The current classifier occupies bits 0..8 and first-context metadata fits in 15 bits.
    // Extending either domain requires a queue ABI migration.
    return (diagnostics.flags & PRIME_WAVEFRONT_NUMERICAL_FLAGS_MASK)
            | ((diagnostics.firstContext
                    & PRIME_WAVEFRONT_NUMERICAL_CONTEXT_MASK)
                    << PRIME_WAVEFRONT_NUMERICAL_CONTEXT_SHIFT);
}

uint primePackWavefrontSharcState(PathState path) {
    uint roughness = uint(round(
            clamp(path.previousSharcRoughness, 0.0, 1.0)
                    * float(PRIME_WAVEFRONT_SHARC_ROUGHNESS_MASK)));
    return ((path.previousSharcEvent << PRIME_WAVEFRONT_SHARC_EVENT_SHIFT)
                    | roughness)
            << PRIME_WAVEFRONT_SHARC_SHIFT;
}

bool primeWavefrontActive(PrimeWavefrontTransportRecord record) {
    uint denoiserControl =
            asuint(record.rayDirectionAndDenoiserControl.w);
    return (denoiserControl & PRIME_WAVEFRONT_ACTIVE_MASK) != 0u;
}

PrimeWavefrontTransportRecord primeWavefrontWithActive(
        PrimeWavefrontTransportRecord record, bool enabled) {
    uint denoiserControl =
            asuint(record.rayDirectionAndDenoiserControl.w);
    if (enabled) {
        denoiserControl |= PRIME_WAVEFRONT_ACTIVE_MASK;
    } else {
        denoiserControl &= ~PRIME_WAVEFRONT_ACTIVE_MASK;
    }
    record.rayDirectionAndDenoiserControl.w = asfloat(denoiserControl);
    return record;
}

PrimeWavefrontTransportRecord primeWavefrontWithQueuedPsrControl(
        PrimeWavefrontTransportRecord record,
        uint control) {
    uint pathControl = record.sourceAndPathControl.w;
    pathControl &= ~(PRIME_WAVEFRONT_PSR_CONTROL_MASK
            << PRIME_WAVEFRONT_PSR_CONTROL_SHIFT);
    pathControl |= (control & PRIME_WAVEFRONT_PSR_CONTROL_MASK)
            << PRIME_WAVEFRONT_PSR_CONTROL_SHIFT;
    record.sourceAndPathControl.w = pathControl;
    return record;
}

PrimeWavefrontTransportRecord primeWavefrontWithGuideProbeFallback(
        PrimeWavefrontTransportRecord record,
        bool fallback) {
    if (fallback) {
        record.sourceAndPathControl.w |= PRIME_WAVEFRONT_GUIDE_PROBE_FALLBACK;
    } else {
        record.sourceAndPathControl.w &= ~PRIME_WAVEFRONT_GUIDE_PROBE_FALLBACK;
    }
    return record;
}

uint4 primePackWavefrontPsrState(PrimeQueuedPsrState state) {
    float3 firstDirection = state.firstDirectionLength.xyz;
    if (!(dot(firstDirection, firstDirection) > 0.0)) {
        firstDirection = float3(0.0, 0.0, 1.0);
    }
    return uint4(
            primePackOctahedralNormal(firstDirection),
            asuint(state.firstDirectionLength.w),
            packSnorm2x16(clamp(state.rotation.xy, float2(-1.0), float2(1.0))),
            packSnorm2x16(clamp(state.rotation.zw, float2(-1.0), float2(1.0))));
}

PrimeWavefrontPathRecord primeWavefrontWithQueuedPsrState(
        PrimeWavefrontPathRecord record,
        PrimeQueuedPsrState state) {
    record.transport = primeWavefrontWithQueuedPsrControl(
            record.transport, state.control);
    record.psrPacked = primePackWavefrontPsrState(state);
    return record;
}

uint primeLoadWavefrontPreviousLightNormal(uint pathIndex) {
    return (primeWavefrontPaths[pathIndex].psrPacked.w
            >> PRIME_WAVEFRONT_LIGHT_NORMAL_SHIFT)
            & PRIME_WAVEFRONT_LIGHT_NORMAL_MASK;
}

PrimeWavefrontPathRecord primeWavefrontWithPreviousLightNormal(
        PrimeWavefrontPathRecord record, uint packedNormal) {
    record.psrPacked.w = (record.psrPacked.w & PRIME_WAVEFRONT_LIGHT_NORMAL_MASK)
            | ((packedNormal & PRIME_WAVEFRONT_LIGHT_NORMAL_MASK)
                    << PRIME_WAVEFRONT_LIGHT_NORMAL_SHIFT);
    return record;
}

PrimeWavefrontPathRecord primeWavefrontWithPrimaryPreviousPosition(
        PrimeWavefrontPathRecord record, PrimeDenoiserGuides guides) {
    record.psrPacked.xyz = asuint(guides.primaryHasMotion
            ? guides.primaryPreviousPosition
            : guides.primaryPosition);
    record.psrPacked.w = (record.psrPacked.w & ~PRIME_WAVEFRONT_PRIMARY_MOTION)
            | (guides.primaryHasMotion ? PRIME_WAVEFRONT_PRIMARY_MOTION : 0u);
    return record;
}

void primeStoreWavefrontPreviousLightNormal(
        uint pathIndex, uint packedNormal) {
    uint packed = primeWavefrontPaths[pathIndex].psrPacked.w;
    primeWavefrontPaths[pathIndex].psrPacked.w =
            (packed & PRIME_WAVEFRONT_LIGHT_NORMAL_MASK)
            | ((packedNormal & PRIME_WAVEFRONT_LIGHT_NORMAL_MASK)
                    << PRIME_WAVEFRONT_LIGHT_NORMAL_SHIFT);
}

PrimeQueuedPsrState primeLoadWavefrontPsrState(
        uint pathIndex,
        PrimeWavefrontTransportRecord record) {
    uint4 packed = primeWavefrontPaths[pathIndex].psrPacked;
    PrimeQueuedPsrState state;
    state.firstDirectionLength = float4(
            primeUnpackOctahedralNormal(packed.x),
            asfloat(packed.y));
    state.rotation = float4(
            unpackSnorm2x16ToFloat(packed.z),
            unpackSnorm2x16ToFloat(packed.w));
    float rotationLengthSquared = dot(state.rotation, state.rotation);
    state.rotation = rotationLengthSquared > 0.0
            ? state.rotation * rsqrt(rotationLengthSquared)
            : float4(0.0, 0.0, 0.0, 1.0);
    uint pathControl = record.sourceAndPathControl.w;
    state.control = (pathControl >> PRIME_WAVEFRONT_PSR_CONTROL_SHIFT)
            & PRIME_WAVEFRONT_PSR_CONTROL_MASK;
    return state;
}

void primeStoreWavefrontPsrState(
        uint pathIndex,
        PrimeQueuedPsrState state) {
    primeWavefrontPaths[pathIndex].psrPacked =
            primePackWavefrontPsrState(state);
}

void primeAccumulateDeferredDirectLight(
        inout PrimeIntegratorContext context,
        uint2 pixel,
        uint control,
        float3 contribution) {
    int2 coordinate = int2(pixel);
    bool transparent = (control & PRIME_WAVEFRONT_TRANSPARENT_BRANCH) != 0u;
    bool transmission = (control & PRIME_WAVEFRONT_TRANSMISSION_BRANCH) != 0u;
    bool guideEnabled = (control & PRIME_WAVEFRONT_GUIDE_ENABLED) != 0u;
    bool diffuse = transparent && !guideEnabled
            ? transmission
            : (control & PRIME_WAVEFRONT_DIFFUSE_PATH) != 0u;
    float4 current;
    if (!transparent || transmission) {
        current = diffuse
                ? primeImageLoad(primeNrdNoisyDiffuse, coordinate)
                : primeImageLoad(primeNrdNoisySpecular, coordinate);
    } else if (primeWritesNrdShInputs()) {
        current = diffuse
                ? primeImageLoad(primeNrdReflectionNoisyDiffuse, coordinate)
                : primeImageLoad(primeNrdReflectionNoisySpecular, coordinate);
    } else {
        current = primeImageLoad(primeStableRadiance, coordinate);
    }

    primeAccumulate(context, current.rgb, contribution);
    current.rgb = primeNrdSanitizeRadiance(current.rgb);
    if (!transparent || transmission) {
        if (diffuse) {
            primeImageStore(primeNrdNoisyDiffuse, coordinate, current);
        } else {
            primeImageStore(primeNrdNoisySpecular, coordinate, current);
        }
    } else if (primeWritesNrdShInputs()) {
        if (diffuse) {
            primeImageStore(primeNrdReflectionNoisyDiffuse, coordinate, current);
        } else {
            primeImageStore(primeNrdReflectionNoisySpecular, coordinate, current);
        }
    } else {
        primeImageStore(primeStableRadiance, coordinate, current);
    }
}

void primeAccumulatePrimaryDirectSplit(
        inout PrimeIntegratorContext context,
        uint2 pixel,
        PrimeDirectLightingSplit direct) {
    int2 coordinate = int2(pixel);
    float4 diffuse = primeImageLoad(primeNrdNoisyDiffuse, coordinate);
    float4 specular = primeImageLoad(primeNrdNoisySpecular, coordinate);
    primeAccumulate(context, diffuse.rgb, direct.diffuse);
    primeAccumulate(context, specular.rgb, direct.specular);
    diffuse.rgb = primeNrdSanitizeRadiance(diffuse.rgb);
    specular.rgb = primeNrdSanitizeRadiance(specular.rgb);
    primeImageStore(primeNrdNoisyDiffuse, coordinate, diffuse);
    primeImageStore(primeNrdNoisySpecular, coordinate, specular);
}

// Primary direct light for a deferred transparent guide is accumulated before its Shade pass.
// Keep the intermediate unsanitized so the final branch store preserves the original accumulation
// and sanitization order apart from permitted floating-point reassociation.
void primeAccumulateTransparentPrimaryDirect(
        inout PrimeIntegratorContext context,
        uint2 pixel,
        bool transmissionBranch,
        float3 diffuseContribution,
        float3 specularContribution) {
    int2 coordinate = int2(pixel);
    float4 diffuse = transmissionBranch
            ? primeImageLoad(primeNrdNoisyDiffuse, coordinate)
            : primeImageLoad(primeNrdReflectionNoisyDiffuse, coordinate);
    float4 specular = transmissionBranch
            ? primeImageLoad(primeNrdNoisySpecular, coordinate)
            : primeImageLoad(primeNrdReflectionNoisySpecular, coordinate);
    primeAccumulate(context, diffuse.rgb, diffuseContribution);
    primeAccumulate(context, specular.rgb, specularContribution);
    if (transmissionBranch) {
        primeImageStore(primeNrdNoisyDiffuse, coordinate, diffuse);
        primeImageStore(primeNrdNoisySpecular, coordinate, specular);
    } else {
        primeImageStore(primeNrdReflectionNoisyDiffuse, coordinate, diffuse);
        primeImageStore(primeNrdReflectionNoisySpecular, coordinate, specular);
    }
}

void primeStoreWavefrontPrimarySun(
        uint2 pixel,
        float3 unshadowed,
        float visibility,
        float penumbra) {
    int2 coordinate = int2(pixel);
    primeImageStore(
            primeNrdSunLighting,
            coordinate,
            float4(
                    primeNrdSanitizeRadiance(unshadowed),
                    primeNrdSanitizeUnit(visibility, 0.0)));
    primeImageStore(
            primeNrdSunPenumbra,
            coordinate,
            float4(primeNrdSanitizeHitDistance(penumbra)));
}

void primeStoreWavefrontDiagnostic(
        uint pathIndex, PrimeNumericalDiagnostics diagnostics) {
    if (primeWritesRawNumericalDiagnostic()) {
        uint previous = asuint(primeWavefrontPaths[pathIndex]
                .transport.throughputAndNumericalFlags.w);
        primeWavefrontPaths[pathIndex]
                .transport.throughputAndNumericalFlags.w =
                asfloat(
                        (previous & ~PRIME_WAVEFRONT_LOW_DIAGNOSTIC_MASK)
                        | primePackWavefrontDiagnostic(diagnostics));
    }
}

PrimeWavefrontTransportRecord primeMakeWavefrontTransportRecord(
        PrimeNumericalDiagnostics diagnostics,
        PathState path,
        PrimeOpenPbrVolumeStack volumeStack,
        PrimeDenoiserState denoiserState,
        uint primaryMaterialFlags,
        bool enabled) {
    PrimeWavefrontTransportRecord record;
    record.physicalOriginAndPreviousBsdfPdf =
            float4(path.physicalOrigin, path.previousBsdfPdf);
    record.sourceAndPathControl = uint4(
            path.sourcePrimitive,
            path.sourceReserved,
            primePackWavefrontPathControl(path));
    record.medium0 = primePackWavefrontMedium(volumeStack.values[0]);
    record.medium1 = primePackWavefrontMedium(volumeStack.values[1]);
    uint denoiserControl = enabled ? PRIME_WAVEFRONT_ACTIVE_MASK : 0u;
    if (denoiserState.reachedSolidAngle) {
        denoiserControl |= PRIME_WAVEFRONT_REACHED_SOLID_ANGLE;
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
            float4(path.rayDirection, asfloat(denoiserControl));
    record.throughputAndNumericalFlags =
            float4(
                    path.throughput,
                    asfloat(
                            primePackWavefrontDiagnostic(diagnostics)
                            | primePackWavefrontSharcState(path)));
    return record;
}

PrimeWavefrontPathRecord primeMakeWavefrontRecord(
        PrimeNumericalDiagnostics diagnostics,
        PathState path,
        PrimeOpenPbrVolumeStack volumeStack,
        PrimeDenoiserState denoiserState,
        PrimeDenoiserGuides guides,
        bool enabled) {
    PrimeWavefrontPathRecord record;
    record.transport = primeMakeWavefrontTransportRecord(
            diagnostics,
            path,
            volumeStack,
            denoiserState,
            guides.primaryMaterialFlags,
            enabled);
    record = primeWavefrontWithQueuedPsrState(
            record, primeEmptyQueuedPsrState());
    record = primeWavefrontWithPreviousLightNormal(
            record, path.previousLightNormal);
    record = primeWavefrontWithPrimaryPreviousPosition(record, guides);
    return record;
}

PathState primeWavefrontPath(
        uint2 pixel, PrimeWavefrontTransportRecord record) {
    PathState path;
    uint pathControl = record.sourceAndPathControl.w;
    path.physicalOrigin = record.physicalOriginAndPreviousBsdfPdf.xyz;
    path.bounce = (pathControl >> PRIME_WAVEFRONT_BOUNCE_SHIFT)
            & PRIME_WAVEFRONT_BYTE_MASK;
    path.sourcePrimitive = record.sourceAndPathControl.xy;
    path.sourceReserved = record.sourceAndPathControl.z;
    uint denoiserControl =
            asuint(record.rayDirectionAndDenoiserControl.w);
    path.sampleDimension =
            (denoiserControl & PRIME_WAVEFRONT_TRANSMISSION_BRANCH) != 0u
            ? 1u
            : 0u;
    path.rayDirection = record.rayDirectionAndDenoiserControl.xyz;
    path.flags = (pathControl >> PRIME_WAVEFRONT_PATH_FLAGS_SHIFT)
            & PRIME_WAVEFRONT_PATH_FLAGS_MASK;
    path.throughput = record.throughputAndNumericalFlags.xyz;
    path.previousBsdfPdf = record.physicalOriginAndPreviousBsdfPdf.w;
    path.etaScale = primeUnpackWavefrontEtaScale(
            (pathControl >> PRIME_WAVEFRONT_ETA_SCALE_SHIFT)
                    & PRIME_WAVEFRONT_ETA_SCALE_MASK);
    path.previousLightNormal = 0u;
    path.pixel = pixel;
    path.sampleIndex = primeSampleIndex();
    path.sampleEpoch = primeSampleEpoch();
    uint packedSharc = asuint(record.throughputAndNumericalFlags.w)
            >> PRIME_WAVEFRONT_SHARC_SHIFT;
    path.previousSharcRoughness = float(
            packedSharc & PRIME_WAVEFRONT_SHARC_ROUGHNESS_MASK)
            / float(PRIME_WAVEFRONT_SHARC_ROUGHNESS_MASK);
    path.previousSharcEvent = packedSharc >> PRIME_WAVEFRONT_SHARC_EVENT_SHIFT;
    return path;
}

PrimeOpenPbrVolumeStack primeWavefrontVolumeStack(
        PrimeWavefrontTransportRecord record) {
    PrimeOpenPbrVolumeStack volumeStack;
    volumeStack.values[0] = primeUnpackWavefrontMedium(record.medium0);
    volumeStack.values[1] = primeUnpackWavefrontMedium(record.medium1);
    uint denoiserControl =
            asuint(record.rayDirectionAndDenoiserControl.w);
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
    state.reachedSolidAngle =
            (denoiserControl & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) != 0u;
    state.diffuseAlbedoProduct = result.guides.primaryAlbedo;
    state.specularAlbedoProduct = result.guides.primarySpecularAlbedo;
    state.diffusePath =
            (denoiserControl & PRIME_WAVEFRONT_DIFFUSE_PATH) != 0u;
    state.primaryBounce =
            (denoiserControl >> PRIME_WAVEFRONT_PRIMARY_BOUNCE_SHIFT)
            & PRIME_WAVEFRONT_BYTE_MASK;
    return state;
}

void primeRestoreWavefrontDiagnostic(
        PrimeWavefrontTransportRecord record,
        inout PrimeNumericalDiagnostics diagnostics) {
    if (primeWritesRawNumericalDiagnostic()) {
        uint packedDiagnostic =
                asuint(record.throughputAndNumericalFlags.w);
        diagnostics.flags =
                packedDiagnostic & PRIME_WAVEFRONT_NUMERICAL_FLAGS_MASK;
        diagnostics.firstContext =
                (packedDiagnostic >> PRIME_WAVEFRONT_NUMERICAL_CONTEXT_SHIFT)
                        & PRIME_WAVEFRONT_NUMERICAL_CONTEXT_MASK;
    }
}

PrimeWavefrontTransportRecord primeMakeUpdatedWavefrontRecord(
        PrimeNumericalDiagnostics diagnostics,
        PathState path,
        PrimeOpenPbrVolumeStack volumeStack,
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
            diagnostics,
            path,
            volumeStack,
            denoiserState,
            primaryMaterialFlags,
            enabled);
    uint denoiserControl =
            asuint(record.rayDirectionAndDenoiserControl.w)
                    | persistedControl;
    record.rayDirectionAndDenoiserControl.w = asfloat(denoiserControl);
    record = primeWavefrontWithQueuedPsrControl(record, psrControl);
    return record;
}

PrimeWavefrontPathRecord primeMakeTransparentWavefrontRecord(
        PrimeNumericalDiagnostics diagnostics,
        PathState path,
        PrimeOpenPbrVolumeStack volumeStack,
        PrimeTransparentBranchResult branchResult,
        PrimeQueuedPsrState psrState,
        bool transmissionBranch,
        bool guideEnabled,
        bool hasGuide,
        bool diffusePath,
        uint guideBounce,
        bool primaryReflectionOnly,
        bool guideProbeFallback,
        bool enabled) {
    PrimeDenoiserState denoiserState;
    denoiserState.hasPrimarySurface = true;
    denoiserState.reachedSolidAngle = hasGuide;
    denoiserState.diffuseAlbedoProduct = branchResult.guides.primaryAlbedo;
    denoiserState.specularAlbedoProduct =
            branchResult.guides.primarySpecularAlbedo;
    denoiserState.diffusePath = diffusePath;
    denoiserState.primaryBounce = guideBounce;
    PrimeWavefrontPathRecord record = primeMakeWavefrontRecord(
            diagnostics,
            path,
            volumeStack,
            denoiserState,
            branchResult.guides,
            enabled);
    uint control = asuint(
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
    if (primaryReflectionOnly) {
        control |= PRIME_WAVEFRONT_PRIMARY_REFLECTION_ONLY;
    }
    record.transport.rayDirectionAndDenoiserControl.w = asfloat(control);
    record = primeWavefrontWithQueuedPsrState(record, psrState);
    record.transport = primeWavefrontWithGuideProbeFallback(
            record.transport, guideProbeFallback);
    return record;
}

PrimeWavefrontTransportRecord primeMakeUpdatedTransparentWavefrontRecord(
        PrimeNumericalDiagnostics diagnostics,
        PathState path,
        PrimeOpenPbrVolumeStack volumeStack,
        PrimeTransparentBranchResult branchResult,
        PrimeQueuedPsrState psrState,
        bool hasGuide,
        bool diffusePath,
        uint guideBounce,
        bool directionalGuide,
        uint previousDenoiserControl,
        bool guideProbeFallback,
        bool enabled) {
    PrimeDenoiserState denoiserState;
    denoiserState.hasPrimarySurface = true;
    denoiserState.reachedSolidAngle = hasGuide;
    denoiserState.diffuseAlbedoProduct = branchResult.guides.primaryAlbedo;
    denoiserState.specularAlbedoProduct =
            branchResult.guides.primarySpecularAlbedo;
    denoiserState.diffusePath = diffusePath;
    denoiserState.primaryBounce = guideBounce;
    PrimeWavefrontTransportRecord record = primeMakeUpdatedWavefrontRecord(
            diagnostics,
            path,
            volumeStack,
            denoiserState,
            previousDenoiserControl,
            psrState.control,
            enabled);
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    control = directionalGuide
            ? control | PRIME_WAVEFRONT_DIRECTIONAL_GUIDE
            : control & ~PRIME_WAVEFRONT_DIRECTIONAL_GUIDE;
    control &= ~(PRIME_WAVEFRONT_PRIMARY_FLAGS_MASK
            << PRIME_WAVEFRONT_PRIMARY_FLAGS_SHIFT);
    control |= (branchResult.guides.primaryMaterialFlags
            & PRIME_WAVEFRONT_PRIMARY_FLAGS_MASK)
            << PRIME_WAVEFRONT_PRIMARY_FLAGS_SHIFT;
    record.rayDirectionAndDenoiserControl.w = asfloat(control);
    record = primeWavefrontWithGuideProbeFallback(record, guideProbeFallback);
    return record;
}

void primeStoreWavefrontVisibleScratch(
        uint2 pixel, PrimeDenoiserGuides visibleGuides) {
    int2 coordinate = int2(pixel);
    primeImageStore(
            primeNrdNormalRoughness,
            coordinate,
            primeNrdPackNormalRoughness(
                    visibleGuides.primaryNormal,
                    visibleGuides.primaryLinearRoughness,
                    primeNrdMaterialId(visibleGuides.primaryMaterialFlags)));
    primeImageStore(
            primeStableRadiance,
            coordinate,
            float4(0.0, 0.0, 0.0, visibleGuides.primaryDistance));
    primeImageStore(primeNrdSunPenumbra, coordinate, float4(0.0));
    if (primeWritesNrdShInputs()) {
        primeImageStore(
                primeNrdDisplayPosition,
                coordinate,
                float4(
                        visibleGuides.primaryPosition,
                        asfloat(visibleGuides.primaryMaterialFlags)));
        primeImageStore(primeNrdSunLighting, coordinate, float4(0.0));
    } else {
        // A transparent primary has no SIGMA sun lane. Before resolve this otherwise-unused image
        // preserves the visible interface's specular albedo for RR/no-post output.
        primeImageStore(
                primeNrdSunLighting,
                coordinate,
                float4(
                        visibleGuides.primarySpecularAlbedo,
                        visibleGuides.primaryLinearRoughness));
    }
}

PrimeDenoiserGuides primeLoadWavefrontVisibleGuides(
        uint2 pixel,
        PrimeTransparentBranchResult transmission,
        PrimeTransparentBranchResult reflection) {
    int2 coordinate = int2(pixel);
    PrimeDenoiserGuides guides = transmission.guides;
    guides.primaryDistance = primeImageLoad(primeStableRadiance, coordinate).a;
    guides.primaryHitKind = guides.primaryDistance >= 0.0
            ? PRIME_HIT_SURFACE
            : PRIME_HIT_NONE;
    float3 normal;
    float roughness;
    primeNrdUnpackNormalRoughness(
            primeImageLoad(primeNrdNormalRoughness, coordinate),
            normal,
            roughness);
    guides.primaryNormal = normal;
    guides.primaryLinearRoughness = roughness;
    if (primeWritesNrdShInputs()) {
        float4 displayPosition =
                primeImageLoad(primeNrdDisplayPosition, coordinate);
        guides.primaryPosition = displayPosition.xyz;
        guides.primaryMaterialFlags = asuint(displayPosition.w);
    } else {
        guides.primaryPosition = primeCameraRayDirection(
                pixel, primeRealtimeCameraSample(pixel)) * guides.primaryDistance;
        float4 visibleMaterial = primeImageLoad(primeNrdSunLighting, coordinate);
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
        uint2 pixel,
        PrimeIntegrationResult result) {
    int2 coordinate = int2(pixel);
    primeImageStore(
            primeNrdNoisyDiffuse,
            coordinate,
            float4(
                    primeNrdSanitizeRadiance(result.radiance.diffuse),
                    primeNrdSanitizeHitDistance(result.guides.diffuseHitDistance)));
    primeImageStore(
            primeNrdNoisySpecular,
            coordinate,
            float4(
                    primeNrdSanitizeRadiance(result.radiance.specular),
                    primeNrdSanitizeHitDistance(result.guides.specularHitDistance)));
    primeImageStore(
            primeStableRadiance,
            coordinate,
            float4(
                    primeNrdSanitizeRadiance(result.radiance.stable),
                    0.0));
    primeImageStore(
            primeNrdSunLighting,
            coordinate,
            float4(
                    primeNrdSanitizeRadiance(result.radiance.unshadowedSun),
                    primeNrdSanitizeUnit(result.radiance.sunVisibility, 0.0)));
    primeImageStore(
            primeNrdSunPenumbra,
            coordinate,
            float4(primeNrdSanitizeHitDistance(result.guides.sunPenumbra)));
    primeImageStore(
            primeNrdPrimaryPosition,
            coordinate,
            float4(result.guides.primaryPosition, result.guides.primaryDistance));
    primeImageStore(
            primeNrdMaterial,
            coordinate,
            float4(result.guides.primaryAlbedo, result.guides.primaryLinearRoughness));
    float2 primaryNormalOctahedral = unpackSnorm2x16ToFloat(
            primePackOctahedralNormal(result.guides.primaryNormal));
    primeImageStore(
            primeNrdSpecularMaterial,
            coordinate,
            float4(
                    result.guides.primarySpecularAlbedo,
                    primaryNormalOctahedral.y));
    primeImageStore(
            primeNrdViewZ,
            coordinate,
            float4(primaryNormalOctahedral.x));
    if (primeWritesNrdShInputs()) {
        primeImageStore(
                primeNrdDiffuseDirection,
                coordinate,
                float4(result.guides.diffuseDirection, 0.0));
        primeImageStore(
                primeNrdSpecularDirection,
                coordinate,
                float4(result.guides.specularDirection, 0.0));
    }
}

// A queued ordinary continuation already owns a primary guide. Only radiance and the two
// first-bounce hit distances evolve on every vertex. The pre-guide discrete chain additionally needs
// its albedo products, but no other immutable guide field participates in transport.
PrimeIntegrationResult primeLoadWavefrontActiveIntermediate(
        uint2 pixel,
        uint control) {
    int2 coordinate = int2(pixel);
    float4 diffuse = primeImageLoad(primeNrdNoisyDiffuse, coordinate);
    float4 specular = primeImageLoad(primeNrdNoisySpecular, coordinate);
    PrimeIntegrationResult result = primeEmptyIntegrationResult();
    result.radiance.diffuse = diffuse.rgb;
    result.radiance.specular = specular.rgb;
    result.guides.diffuseHitDistance = diffuse.a;
    result.guides.specularHitDistance = specular.a;
    if ((control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) == 0u) {
        result.guides.primaryAlbedo =
                primeImageLoad(primeNrdMaterial, coordinate).rgb;
        result.guides.primarySpecularAlbedo =
                primeImageLoad(primeNrdSpecularMaterial, coordinate).rgb;
    }
    return result;
}

void primeStoreWavefrontActiveIntermediate(
        uint2 pixel,
        bool guideWasPending,
        PrimeIntegrationResult result) {
    int2 coordinate = int2(pixel);
    primeImageStore(
            primeNrdNoisyDiffuse,
            coordinate,
            float4(
                    primeNrdSanitizeRadiance(result.radiance.diffuse),
                    primeNrdSanitizeHitDistance(
                            result.guides.diffuseHitDistance)));
    primeImageStore(
            primeNrdNoisySpecular,
            coordinate,
            float4(
                    primeNrdSanitizeRadiance(result.radiance.specular),
                    primeNrdSanitizeHitDistance(
                            result.guides.specularHitDistance)));
    if (guideWasPending) {
        float4 encoded = primeImageLoad(primeNrdSpecularMaterial, coordinate);
        primeImageStore(
                primeNrdSpecularMaterial,
                coordinate,
                float4(
                        result.guides.primarySpecularAlbedo,
                        encoded.a));
    }
}

void primeStoreRrReflectionGuide(
        uint2 pixel,
        bool primaryReflectionOnly,
        PrimeTransparentBranchResult result) {
    int2 coordinate = int2(pixel);
    float3 previousPosition = result.guides.primaryHasMotion
            && primeNrdIsFinite(result.guides.primaryPreviousPosition)
            ? result.guides.primaryPreviousPosition
            : result.guides.primaryPosition;
    primeImageStore(
            primeNrdReflectionPosition,
            coordinate,
            float4(previousPosition, 0.0));
    if (!primaryReflectionOnly) {
        return;
    }
    // RR allocates only an independent reflection-position image. Under pure TIR the
    // transmission identity does not exist, so the reflection guide may safely own the otherwise
    // idle primary material, normal and current-position images.
    primeImageStore(
            primeNrdPrimaryPosition,
            coordinate,
            float4(result.guides.primaryPosition, result.guides.primaryDistance));
    primeImageStore(
            primeNrdMaterial,
            coordinate,
            float4(
                    result.guides.primaryAlbedo,
                    result.guides.primaryLinearRoughness));
    float2 normalOctahedral = unpackSnorm2x16ToFloat(
            primePackOctahedralNormal(result.guides.primaryNormal));
    primeImageStore(
            primeNrdSpecularMaterial,
            coordinate,
            float4(
                    result.guides.primarySpecularAlbedo,
                    normalOctahedral.y));
    primeImageStore(
            primeNrdViewZ,
            coordinate,
            float4(normalOctahedral.x));
}

void primeStoreTransparentBranchIntermediate(
        uint2 pixel,
        bool transmissionBranch,
        bool hasGuide,
        bool primaryReflectionOnly,
        PrimeTransparentBranchResult result) {
    int2 coordinate = int2(pixel);
    if (transmissionBranch) {
        primeImageStore(
                primeNrdNoisyDiffuse,
                coordinate,
                float4(
                        primeNrdSanitizeRadiance(result.diffuseRadiance),
                        primeNrdSanitizeHitDistance(
                                result.guides.diffuseHitDistance)));
        primeImageStore(
                primeNrdNoisySpecular,
                coordinate,
                float4(
                        primeNrdSanitizeRadiance(result.specularRadiance),
                        primeNrdSanitizeHitDistance(
                                result.guides.specularHitDistance)));
        primeImageStore(
                primeNrdPrimaryPosition,
                coordinate,
                float4(result.guides.primaryPosition, result.guides.primaryDistance));
        primeImageStore(
                primeNrdMaterial,
                coordinate,
                float4(
                        result.guides.primaryAlbedo,
                        result.guides.primaryLinearRoughness));
        float2 normalOctahedral = unpackSnorm2x16ToFloat(
                primePackOctahedralNormal(result.guides.primaryNormal));
        primeImageStore(
                primeNrdSpecularMaterial,
                coordinate,
                float4(result.guides.primarySpecularAlbedo, normalOctahedral.y));
        primeImageStore(primeNrdViewZ, coordinate, float4(normalOctahedral.x));
        if (primeWritesNrdShInputs()) {
            primeImageStore(
                    primeNrdDiffuseDirection,
                    coordinate,
                    float4(result.guides.diffuseDirection, 0.0));
            primeImageStore(
                    primeNrdSpecularDirection,
                    coordinate,
                    float4(result.guides.specularDirection, 0.0));
        }
        primeImageStore(
                primeWavefrontTransportMetadata,
                coordinate,
                float4(
                        result.anchorDistance,
                        result.firstHitDistance,
                        0.0,
                        0.0));
        return;
    }

    primeImageStore(
            primeNrdSunPenumbra,
            coordinate,
            float4(primeNrdSanitizeHitDistance(result.firstHitDistance)));
    if (!primeWritesNrdShInputs()) {
        float4 visibleScratch = primeImageLoad(primeStableRadiance, coordinate);
        primeImageStore(
                primeStableRadiance,
                coordinate,
                float4(
                        primeNrdSanitizeRadiance(
                                result.diffuseRadiance
                                        + result.specularRadiance),
                        visibleScratch.a));
        if (hasGuide) {
            primeStoreRrReflectionGuide(
                    pixel, primaryReflectionOnly, result);
        } else {
            primeImageStore(
                    primeNrdReflectionPosition,
                    coordinate,
                    float4(0.0, 0.0, 0.0, -1.0));
        }
        return;
    }
    primeImageStore(
            primeNrdReflectionNoisyDiffuse,
            coordinate,
            float4(
                    primeNrdSanitizeRadiance(result.diffuseRadiance),
                    primeNrdSanitizeHitDistance(
                            result.guides.diffuseHitDistance)));
    primeImageStore(
            primeNrdReflectionNoisySpecular,
            coordinate,
            float4(
                    primeNrdSanitizeRadiance(result.specularRadiance),
                    primeNrdSanitizeHitDistance(
                            result.guides.specularHitDistance)));
    primeImageStore(
            primeNrdReflectionPosition,
            coordinate,
            float4(result.guides.primaryPosition, result.firstHitDistance));
    primeImageStore(
            primeNrdReflectionMaterial,
            coordinate,
            float4(result.guides.primaryAlbedo, result.guides.primaryDistance));
    primeImageStore(
            primeNrdReflectionSpecularMaterial,
            coordinate,
            float4(result.guides.primarySpecularAlbedo, 0.0));
    primeImageStore(
            primeNrdReflectionNormalRoughness,
            coordinate,
            primeNrdPackNormalRoughness(
                    result.guides.primaryNormal,
                    result.guides.primaryLinearRoughness,
                    primeNrdMaterialId(result.guides.primaryMaterialFlags)));
    if (primeWritesNrdShInputs()) {
        primeImageStore(
                primeNrdReflectionDiffuseDirection,
                coordinate,
                float4(result.guides.diffuseDirection, 0.0));
        primeImageStore(
                primeNrdReflectionSpecularDirection,
                coordinate,
                float4(result.guides.specularDirection, 0.0));
    }
}

// Active transparent branches keep their immutable guide images resident. Per-vertex transport
// reads only radiance, hit metadata and the two fallback directions required until PSR resolves.
PrimeTransparentBranchResult primeLoadTransparentBranchActiveIntermediate(
        uint2 pixel,
        bool transmissionBranch,
        uint control) {
    int2 coordinate = int2(pixel);
    bool nrdShInputs = primeWritesNrdShInputs();
    bool hasGuide = (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) != 0u;
    float4 diffuse = transmissionBranch
            ? primeImageLoad(primeNrdNoisyDiffuse, coordinate)
            : nrdShInputs
                    ? primeImageLoad(primeNrdReflectionNoisyDiffuse, coordinate)
                    : float4(primeImageLoad(primeStableRadiance, coordinate).rgb, 0.0);
    float4 specular = transmissionBranch
            ? primeImageLoad(primeNrdNoisySpecular, coordinate)
            : nrdShInputs
                    ? primeImageLoad(primeNrdReflectionNoisySpecular, coordinate)
                    : float4(0.0);

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
                    primeImageLoad(primeNrdDiffuseDirection, coordinate).xyz);
            result.guides.specularDirection = primeRestoreFp16Direction(
                    primeImageLoad(primeNrdSpecularDirection, coordinate).xyz);
        } else {
            result.guides.diffuseDirection = primeRestoreFp16Direction(
                    primeImageLoad(
                            primeNrdReflectionDiffuseDirection,
                            coordinate).xyz);
            result.guides.specularDirection = primeRestoreFp16Direction(
                    primeImageLoad(
                            primeNrdReflectionSpecularDirection,
                            coordinate).xyz);
        }
    }
    if (transmissionBranch) {
        float4 metadata = primeImageLoad(primeWavefrontTransportMetadata, coordinate);
        result.anchorDistance = metadata.x;
        result.firstHitDistance = metadata.y;
    } else {
        result.anchorDistance = -1.0;
        result.firstHitDistance =
                primeImageLoad(primeNrdSunPenumbra, coordinate).r;
    }
    result.directionalGuide = primeWavefrontDirectionalGuide(control);
    return result;
}

void primeStoreTransparentBranchGuide(
        uint2 pixel,
        bool transmissionBranch,
        bool primaryReflectionOnly,
        PrimeTransparentBranchResult result) {
    int2 coordinate = int2(pixel);
    if (transmissionBranch) {
        primeImageStore(
                primeNrdPrimaryPosition,
                coordinate,
                float4(result.guides.primaryPosition, result.guides.primaryDistance));
        primeImageStore(
                primeNrdMaterial,
                coordinate,
                float4(
                        result.guides.primaryAlbedo,
                        result.guides.primaryLinearRoughness));
        float2 normalOctahedral = unpackSnorm2x16ToFloat(
                primePackOctahedralNormal(result.guides.primaryNormal));
        primeImageStore(
                primeNrdSpecularMaterial,
                coordinate,
                float4(result.guides.primarySpecularAlbedo, normalOctahedral.y));
        primeImageStore(primeNrdViewZ, coordinate, float4(normalOctahedral.x));
        if (primeWritesNrdShInputs()) {
            primeImageStore(
                    primeNrdDiffuseDirection,
                    coordinate,
                    float4(result.guides.diffuseDirection, 0.0));
            primeImageStore(
                    primeNrdSpecularDirection,
                    coordinate,
                    float4(result.guides.specularDirection, 0.0));
        }
        return;
    }
    if (!primeWritesNrdShInputs()) {
        primeStoreRrReflectionGuide(
                pixel, primaryReflectionOnly, result);
        return;
    }

    primeImageStore(
            primeNrdReflectionPosition,
            coordinate,
            float4(result.guides.primaryPosition, result.firstHitDistance));
    primeImageStore(
            primeNrdReflectionMaterial,
            coordinate,
            float4(result.guides.primaryAlbedo, result.guides.primaryDistance));
    primeImageStore(
            primeNrdReflectionSpecularMaterial,
            coordinate,
            float4(result.guides.primarySpecularAlbedo, 0.0));
    primeImageStore(
            primeNrdReflectionNormalRoughness,
            coordinate,
            primeNrdPackNormalRoughness(
                    result.guides.primaryNormal,
                    result.guides.primaryLinearRoughness,
                    primeNrdMaterialId(result.guides.primaryMaterialFlags)));
    primeImageStore(
            primeNrdReflectionDiffuseDirection,
            coordinate,
            float4(result.guides.diffuseDirection, 0.0));
    primeImageStore(
            primeNrdReflectionSpecularDirection,
            coordinate,
            float4(result.guides.specularDirection, 0.0));
}

void primeStoreTransparentBranchActiveIntermediate(
        uint2 pixel,
        bool transmissionBranch,
        bool storeGuide,
        bool storeDirectionalPosition,
        bool primaryReflectionOnly,
        PrimeTransparentBranchResult result) {
    int2 coordinate = int2(pixel);
    if (transmissionBranch) {
        primeImageStore(
                primeNrdNoisyDiffuse,
                coordinate,
                float4(
                        primeNrdSanitizeRadiance(result.diffuseRadiance),
                        primeNrdSanitizeHitDistance(
                                result.guides.diffuseHitDistance)));
        primeImageStore(
                primeNrdNoisySpecular,
                coordinate,
                float4(
                        primeNrdSanitizeRadiance(result.specularRadiance),
                        primeNrdSanitizeHitDistance(
                                result.guides.specularHitDistance)));
        primeImageStore(
                primeWavefrontTransportMetadata,
                coordinate,
                float4(
                        result.anchorDistance,
                        result.firstHitDistance,
                        0.0,
                        0.0));
    } else {
        primeImageStore(
                primeNrdSunPenumbra,
                coordinate,
                float4(primeNrdSanitizeHitDistance(result.firstHitDistance)));
        if (primeWritesNrdShInputs()) {
            primeImageStore(
                    primeNrdReflectionNoisyDiffuse,
                    coordinate,
                    float4(
                            primeNrdSanitizeRadiance(result.diffuseRadiance),
                            primeNrdSanitizeHitDistance(
                                    result.guides.diffuseHitDistance)));
            primeImageStore(
                    primeNrdReflectionNoisySpecular,
                    coordinate,
                    float4(
                            primeNrdSanitizeRadiance(result.specularRadiance),
                            primeNrdSanitizeHitDistance(
                                    result.guides.specularHitDistance)));
        } else {
            float4 visibleScratch = primeImageLoad(primeStableRadiance, coordinate);
            primeImageStore(
                    primeStableRadiance,
                    coordinate,
                    float4(
                            primeNrdSanitizeRadiance(
                                    result.diffuseRadiance
                                            + result.specularRadiance),
                            visibleScratch.a));
        }
    }

    if (storeGuide) {
        primeStoreTransparentBranchGuide(
                pixel, transmissionBranch, primaryReflectionOnly, result);
    } else if (storeDirectionalPosition
            && !transmissionBranch) {
        primeImageStore(
                primeNrdReflectionPosition,
                coordinate,
                float4(result.guides.primaryPosition, result.firstHitDistance));
    }
}

PrimeTransparentBranchResult primeLoadTransparentBranchIntermediate(
        uint2 pixel,
        bool transmissionBranch,
        uint control) {
    int2 coordinate = int2(pixel);
    bool nrdShInputs = primeWritesNrdShInputs();
    bool rrReflectionPrimaryGuide = !nrdShInputs
            && !transmissionBranch
            && (control & PRIME_WAVEFRONT_PRIMARY_REFLECTION_ONLY) != 0u
            && (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) != 0u;
    float4 diffuse = transmissionBranch
            ? primeImageLoad(primeNrdNoisyDiffuse, coordinate)
            : nrdShInputs
                    ? primeImageLoad(primeNrdReflectionNoisyDiffuse, coordinate)
                    : float4(primeImageLoad(primeStableRadiance, coordinate).rgb, 0.0);
    float4 specular = transmissionBranch
            ? primeImageLoad(primeNrdNoisySpecular, coordinate)
            : nrdShInputs
                    ? primeImageLoad(primeNrdReflectionNoisySpecular, coordinate)
                    : float4(0.0);
    float4 position = transmissionBranch
            || rrReflectionPrimaryGuide
                    ? primeImageLoad(primeNrdPrimaryPosition, coordinate)
                    : primeImageLoad(primeNrdReflectionPosition, coordinate);
    float4 material = transmissionBranch
            || rrReflectionPrimaryGuide
                    ? primeImageLoad(primeNrdMaterial, coordinate)
                    : nrdShInputs
                            ? primeImageLoad(primeNrdReflectionMaterial, coordinate)
                            : float4(0.0, 0.0, 0.0, -1.0);
    float4 specularMaterial = transmissionBranch
            || rrReflectionPrimaryGuide
                    ? primeImageLoad(primeNrdSpecularMaterial, coordinate)
                    : nrdShInputs
                            ? primeImageLoad(primeNrdReflectionSpecularMaterial, coordinate)
                            : float4(0.0);
    float4 branchMetadata = transmissionBranch
            ? primeImageLoad(primeWavefrontTransportMetadata, coordinate)
            : position;
    PrimeTransparentBranchResult result;
    result.diffuseRadiance = diffuse.rgb;
    result.specularRadiance = specular.rgb;
    result.guides = primeEmptyDenoiserGuides();
    result.guides.diffuseHitDistance = diffuse.a;
    result.guides.specularHitDistance = specular.a;
    result.guides.primaryPosition = position.xyz;
    result.guides.primaryDistance =
            transmissionBranch || rrReflectionPrimaryGuide
                    ? position.w
                    : material.w;
    result.guides.primaryHitKind = result.guides.primaryDistance >= 0.0
            ? PRIME_HIT_SURFACE
            : PRIME_HIT_NONE;
    result.guides.primaryAlbedo = material.rgb;
    result.guides.primaryMaterialFlags =
            (control >> PRIME_WAVEFRONT_PRIMARY_FLAGS_SHIFT)
                    & PRIME_WAVEFRONT_PRIMARY_FLAGS_MASK;
    result.guides.primarySpecularAlbedo = specularMaterial.rgb;
    if (transmissionBranch || rrReflectionPrimaryGuide) {
        result.guides.primaryNormal = primeUnpackOctahedralNormal(
                packSnorm2x16(float2(
                        primeImageLoad(primeNrdViewZ, coordinate).r,
                        specularMaterial.a)));
        result.guides.primaryLinearRoughness = material.w;
        if (transmissionBranch && primeWritesNrdShInputs()) {
            result.guides.diffuseDirection = primeRestoreFp16Direction(
                    primeImageLoad(primeNrdDiffuseDirection, coordinate).xyz);
            result.guides.specularDirection = primeRestoreFp16Direction(
                    primeImageLoad(primeNrdSpecularDirection, coordinate).xyz);
        }
    } else if (nrdShInputs) {
        float3 reflectionNormal;
        float reflectionRoughness;
        primeNrdUnpackNormalRoughness(
                primeImageLoad(primeNrdReflectionNormalRoughness, coordinate),
                reflectionNormal,
                reflectionRoughness);
        result.guides.primaryNormal = reflectionNormal;
        result.guides.primaryLinearRoughness = reflectionRoughness;
        if (primeWritesNrdShInputs()) {
            result.guides.diffuseDirection = primeRestoreFp16Direction(
                    primeImageLoad(
                            primeNrdReflectionDiffuseDirection,
                            coordinate).xyz);
            result.guides.specularDirection = primeRestoreFp16Direction(
                    primeImageLoad(
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
            : primeImageLoad(primeNrdSunPenumbra, coordinate).r;
    result.directionalGuide = primeWavefrontDirectionalGuide(control);
    return result;
}

void primeFinalizeTransparentBranchGuide(
        uint control,
        inout PrimeTransparentBranchResult result) {
    bool hasGuide =
            (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) != 0u;
    if (!primeWavefrontGuideEnabled(control) || hasGuide) {
        return;
    }
    float3 branchDirection = primeWavefrontTransmissionBranch(control)
            ? result.guides.diffuseDirection
            : result.guides.specularDirection;
    result.guides.primaryAreaDiffuse = float3(0.0);
    result.guides.primaryAreaSpecular = float3(0.0);
    result.guides.primaryAreaDirection = float3(0.0);
    if (primeWavefrontTransmissionBranch(control)) {
        result.guides.diffuseDirection = branchDirection;
        result.guides.diffuseHitDistance = PRIME_NRD_FP16_MAX;
    } else {
        result.guides.specularDirection = branchDirection;
        result.guides.specularHitDistance = PRIME_NRD_FP16_MAX;
    }
}

PrimeIntegrationResult primeResolveTransparentWavefrontResult(uint2 pixel) {
    uint transmissionControl = asuint(
            primeWavefrontPaths[primeWavefrontIndex(pixel, 0u)]
                    .transport.rayDirectionAndDenoiserControl.w);
    uint reflectionControl = asuint(
            primeWavefrontPaths[primeWavefrontIndex(pixel, 1u)]
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
    result.radiance.stable = float3(0.0);
    result.radiance.unshadowedSun = float3(0.0);
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
    result.transparentReflectionOnly =
            (transmissionControl & PRIME_WAVEFRONT_PRIMARY_REFLECTION_ONLY) != 0u;
    return result;
}

#endif
