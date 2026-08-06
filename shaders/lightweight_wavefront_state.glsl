#ifndef PRIME_LIGHTWEIGHT_WAVEFRONT_STATE_GLSL
#define PRIME_LIGHTWEIGHT_WAVEFRONT_STATE_GLSL

#include "wavefront_medium.glsl"

// The lightweight scheduler owns exactly one 80-byte transport record per pixel. Reconstruction
// observations remain in the existing image-backed scratch contract between wavefront passes.
struct PrimeLightweightPathRecord {
    vec4 physicalOriginAndPreviousBsdfPdf;
    vec4 traceOriginAndPathControl;
    vec4 rayDirectionAndDenoiserControl;
    vec4 throughputAndNumericalFlags;
    uvec2 medium0;
    uvec2 medium1;
};

layout(
        set = PRIME_RENDERER_DESCRIPTOR_SET,
        binding = PRIME_DESCRIPTOR_WAVEFRONT_PATHS,
        std430) buffer PrimeLightweightPathBuffer {
    PrimeLightweightPathRecord records[];
} primeLightweightPaths;

layout(
        set = PRIME_RENDERER_DESCRIPTOR_SET,
        binding = PRIME_DESCRIPTOR_WAVEFRONT_QUEUE,
        std430) buffer PrimeLightweightQueueBuffer {
    uint words[];
} primeLightweightQueue;

const uint PRIME_LIGHTWEIGHT_ACTIVE = 1u;
const uint PRIME_LIGHTWEIGHT_DIFFUSE_PATH = 2u;
const uint PRIME_LIGHTWEIGHT_PREVIOUS_SUN_NEE = 4u;
const uint PRIME_LIGHTWEIGHT_BOUNCE_MASK = 0xffu;
const uint PRIME_LIGHTWEIGHT_PATH_FLAGS_SHIFT = 8u;
const uint PRIME_LIGHTWEIGHT_MEDIUM_COUNT_SHIFT = 16u;
const uint PRIME_LIGHTWEIGHT_MEDIUM_COUNT_MASK = 0x3u;
const uint PRIME_LIGHTWEIGHT_PRIMARY_FLAGS_SHIFT = 18u;
const uint PRIME_LIGHTWEIGHT_PRIMARY_FLAGS_MASK = 0x3fffu;
const uint PRIME_LIGHTWEIGHT_NUMERICAL_FLAGS_MASK = 0x1ffu;
const uint PRIME_LIGHTWEIGHT_NUMERICAL_CONTEXT_SHIFT = 9u;
const uint PRIME_LIGHTWEIGHT_NUMERICAL_CONTEXT_MASK = 0x7fffu;

uint primeLightweightIndex(uvec2 pixel) {
    return pixel.y * primePush.outputExtent.x + pixel.x;
}

uint primeLightweightPixelCount() {
    return primePush.outputExtent.x * primePush.outputExtent.y;
}

uvec2 primeLightweightPixel(uint pathIndex) {
    return uvec2(
            pathIndex % primePush.outputExtent.x,
            pathIndex / primePush.outputExtent.x);
}

uint primeLightweightQueueCommandWord(uint queue) {
    return queue * (PRIME_WAVEFRONT_QUEUE_COMMAND_STRIDE / 4u);
}

uint primeLightweightQueueWord(uint queue, uint entry) {
    uint indexWords = PRIME_WAVEFRONT_QUEUE_COUNT
            * (PRIME_WAVEFRONT_QUEUE_COMMAND_STRIDE / 4u);
    return indexWords + queue * primeLightweightPixelCount() + entry;
}

uint primeLightweightQueuedPath(uint queue, uint entry) {
    return primeLightweightQueue.words[primeLightweightQueueWord(queue, entry)];
}

void primeAppendLightweightPath(uint queue, uint pathIndex) {
    uint commandWord = primeLightweightQueueCommandWord(queue);
    uint entry = atomicAdd(primeLightweightQueue.words[commandWord], 1u);
    uint capacity = primeLightweightPixelCount();
    if (entry < capacity) {
        primeLightweightQueue.words[
                primeLightweightQueueWord(queue, entry)] = pathIndex;
    } else {
        atomicMin(primeLightweightQueue.words[commandWord], capacity);
        atomicOr(primeLightweightQueue.words[commandWord + 3u], 1u);
    }
}

void primeAppendLightweightContinuation(
        uint queue,
        uint pathIndex,
        bool continuation) {
#if defined(PRIME_ENABLE_SUBGROUP_QUEUE)
    uvec4 activeMask = subgroupBallot(continuation);
    uint activeCount = subgroupBallotBitCount(activeMask);
    uint firstEntry = 0u;
    if (subgroupElect() && activeCount != 0u) {
        firstEntry = atomicAdd(
                primeLightweightQueue.words[
                        primeLightweightQueueCommandWord(queue)],
                activeCount);
    }
    firstEntry = subgroupBroadcastFirst(firstEntry);
    uint capacity = primeLightweightPixelCount();
    bool overflow = activeCount > capacity
            || firstEntry > capacity - min(activeCount, capacity);
    if (subgroupElect() && overflow) {
        uint commandWord = primeLightweightQueueCommandWord(queue);
        atomicMin(primeLightweightQueue.words[commandWord], capacity);
        atomicOr(primeLightweightQueue.words[commandWord + 3u], 1u);
    }
    if (!continuation) {
        return;
    }
    uint entry = firstEntry + subgroupBallotExclusiveBitCount(activeMask);
    if (entry < capacity) {
        primeLightweightQueue.words[
                primeLightweightQueueWord(queue, entry)] = pathIndex;
    }
#else
    if (continuation) {
        primeAppendLightweightPath(queue, pathIndex);
    }
#endif
}

uint primePackLightweightDiagnostic() {
    return (primeRawNumericalFlags & PRIME_LIGHTWEIGHT_NUMERICAL_FLAGS_MASK)
            | ((primeRawNumericalFirstContext
                    & PRIME_LIGHTWEIGHT_NUMERICAL_CONTEXT_MASK)
                    << PRIME_LIGHTWEIGHT_NUMERICAL_CONTEXT_SHIFT);
}

PrimeLightweightPathRecord primeMakeLightweightRecord(
        PathState path,
        PrimeRcVolumeStack volumeStack,
        PrimeDenoiserState denoiserState,
        uint primaryMaterialFlags,
        bool previousSunNee,
        bool enabled) {
    PrimeLightweightPathRecord record;
    record.physicalOriginAndPreviousBsdfPdf =
            vec4(path.physicalOrigin, path.previousBsdfPdf);
    uint pathControl = min(path.bounce, PRIME_LIGHTWEIGHT_BOUNCE_MASK)
            | ((path.flags & PRIME_PATH_PREVIOUS_DELTA)
                    << PRIME_LIGHTWEIGHT_PATH_FLAGS_SHIFT);
    record.traceOriginAndPathControl =
            vec4(path.traceOrigin, uintBitsToFloat(pathControl));
    uint denoiserControl = enabled ? PRIME_LIGHTWEIGHT_ACTIVE : 0u;
    if (denoiserState.diffusePath) {
        denoiserControl |= PRIME_LIGHTWEIGHT_DIFFUSE_PATH;
    }
    if (previousSunNee) {
        denoiserControl |= PRIME_LIGHTWEIGHT_PREVIOUS_SUN_NEE;
    }
    denoiserControl |= min(volumeStack.count, 2u)
            << PRIME_LIGHTWEIGHT_MEDIUM_COUNT_SHIFT;
    denoiserControl |= (primaryMaterialFlags
            & PRIME_LIGHTWEIGHT_PRIMARY_FLAGS_MASK)
            << PRIME_LIGHTWEIGHT_PRIMARY_FLAGS_SHIFT;
    record.rayDirectionAndDenoiserControl =
            vec4(path.rayDirection, uintBitsToFloat(denoiserControl));
    record.throughputAndNumericalFlags = vec4(
            path.throughput, uintBitsToFloat(primePackLightweightDiagnostic()));
    record.medium0 = primePackWavefrontMedium(volumeStack.values[0]);
    record.medium1 = primePackWavefrontMedium(volumeStack.values[1]);
    return record;
}

PathState primeLightweightPath(
        uvec2 pixel,
        PrimeLightweightPathRecord record) {
    uint pathControl = floatBitsToUint(record.traceOriginAndPathControl.w);
    PathState path;
    path.physicalOrigin = record.physicalOriginAndPreviousBsdfPdf.xyz;
    path.bounce = pathControl & PRIME_LIGHTWEIGHT_BOUNCE_MASK;
    path.traceOrigin = record.traceOriginAndPathControl.xyz;
    path.sampleDimension = 0u;
    path.rayDirection = record.rayDirectionAndDenoiserControl.xyz;
    path.flags = (pathControl >> PRIME_LIGHTWEIGHT_PATH_FLAGS_SHIFT)
            & PRIME_PATH_PREVIOUS_DELTA;
    path.throughput = record.throughputAndNumericalFlags.xyz;
    path.previousBsdfPdf = record.physicalOriginAndPreviousBsdfPdf.w;
    path.rrDepth = 0u;
    path.previousLightNormal = 0u;
    path.pixel = pixel;
    path.sampleIndex = primeSampleIndex();
    path.sampleEpoch = primeSampleEpoch();
    return path;
}

PrimeRcVolumeStack primeLightweightVolumeStack(
        PrimeLightweightPathRecord record) {
    PrimeRcVolumeStack stack;
    stack.values[0] = primeUnpackWavefrontMedium(record.medium0);
    stack.values[1] = primeUnpackWavefrontMedium(record.medium1);
    uint control = floatBitsToUint(record.rayDirectionAndDenoiserControl.w);
    stack.count = min(
            (control >> PRIME_LIGHTWEIGHT_MEDIUM_COUNT_SHIFT)
                    & PRIME_LIGHTWEIGHT_MEDIUM_COUNT_MASK,
            2u);
    return stack;
}

PrimeDenoiserState primeLightweightDenoiserState(
        PrimeLightweightPathRecord record,
        PrimeIntegrationResult result) {
    uint control = floatBitsToUint(record.rayDirectionAndDenoiserControl.w);
    PrimeDenoiserState state;
    state.hasPrimarySurface = true;
    state.reachedNonDelta = true;
    state.diffuseAlbedoProduct = result.guides.primaryAlbedo;
    state.specularAlbedoProduct = result.guides.primarySpecularAlbedo;
    state.diffusePath = (control & PRIME_LIGHTWEIGHT_DIFFUSE_PATH) != 0u;
    state.primaryBounce = 0u;
    return state;
}

bool primeLightweightPreviousSunNee(PrimeLightweightPathRecord record) {
    uint control = floatBitsToUint(record.rayDirectionAndDenoiserControl.w);
    return (control & PRIME_LIGHTWEIGHT_PREVIOUS_SUN_NEE) != 0u;
}

uint primeLightweightPrimaryMaterialFlags(PrimeLightweightPathRecord record) {
    uint control = floatBitsToUint(record.rayDirectionAndDenoiserControl.w);
    return (control >> PRIME_LIGHTWEIGHT_PRIMARY_FLAGS_SHIFT)
            & PRIME_LIGHTWEIGHT_PRIMARY_FLAGS_MASK;
}

void primeRestoreLightweightDiagnostic(PrimeLightweightPathRecord record) {
    if (primeWritesRawNumericalDiagnostic()) {
        uint diagnostic = floatBitsToUint(record.throughputAndNumericalFlags.w);
        primeRawNumericalFlags =
                diagnostic & PRIME_LIGHTWEIGHT_NUMERICAL_FLAGS_MASK;
        primeRawNumericalFirstContext =
                (diagnostic >> PRIME_LIGHTWEIGHT_NUMERICAL_CONTEXT_SHIFT)
                        & PRIME_LIGHTWEIGHT_NUMERICAL_CONTEXT_MASK;
    }
}

bool primeLightweightDiffusePath(PrimeLightweightPathRecord record) {
    uint control = floatBitsToUint(record.rayDirectionAndDenoiserControl.w);
    return (control & PRIME_LIGHTWEIGHT_DIFFUSE_PATH) != 0u;
}

void primePrepareLightweightTransparentOutput(
        PrimeLightweightPathRecord record,
        PrimeTransparentGuideProbeResult probes,
        inout PrimeIntegrationResult result) {
    if (!primeMaterialIsTransmissive(result.guides.primaryMaterialFlags)) {
        return;
    }
    result.transparentPrimary = true;
    result.transmissionGuides = result.guides;
    primeApplyProbeSurfaceGuide(
            result.transmissionGuides, probes.transmission);
    result.transmissionAnchorDistance =
            probes.transmission.primaryHitKind == PRIME_HIT_SURFACE
            ? probes.transmissionAnchorDistance
            : -1.0;
    result.reflectionGuides = result.guides;
    result.reflectionDirectionalGuide = false;
    primeApplyProbeSurfaceGuide(
            result.reflectionGuides, probes.reflection);
    if (probes.reflection.primaryHitKind == PRIME_HIT_SURFACE) {
        result.reflectionDirectionalGuide = false;
    } else if (probes.reflectionCurrentVirtualPosition.w > 0.5) {
        result.reflectionGuides.primaryPosition =
                probes.reflectionCurrentVirtualPosition.xyz;
        result.reflectionDirectionalGuide = true;
    }
    // The main lightweight lanes own transmission. A sampled primary reflection moves to the
    // existing reflection signal only at resolve, keeping every queued transport record unchanged.
    if (!primeLightweightDiffusePath(record)) {
        result.reflectionSpecularRadiance = result.radiance.specular;
        result.radiance.specular = vec3(0.0);
    }
}

void primeStoreLightweightIntermediate(
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
    vec2 packedNormal = unpackSnorm2x16(
            primePackOctahedralNormal(result.guides.primaryNormal));
    imageStore(
            primeNrdSpecularMaterial,
            coordinate,
            vec4(
                    result.guides.primarySpecularAlbedo,
                    packedNormal.y + (result.guides.primaryHasMotion ? 4.0 : 0.0)));
    imageStore(
            primeNrdViewZ,
            coordinate,
            vec4(uintBitsToFloat(packHalf2x16(vec2(
                    packedNormal.x,
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

PrimeIntegrationResult primeLoadLightweightIntermediate(
        uvec2 pixel,
        PrimeLightweightPathRecord record) {
    ivec2 coordinate = ivec2(pixel);
    vec4 diffuse = imageLoad(primeNrdNoisyDiffuse, coordinate);
    vec4 specular = imageLoad(primeNrdNoisySpecular, coordinate);
    vec4 stable = imageLoad(primeStableRadiance, coordinate);
    vec4 sun = imageLoad(primeNrdSunLighting, coordinate);
    vec4 position = imageLoad(primeNrdPrimaryPosition, coordinate);
    vec4 material = imageLoad(primeNrdMaterial, coordinate);
    vec4 specularMaterial = imageLoad(primeNrdSpecularMaterial, coordinate);
    vec2 packedNormalMotion = unpackHalf2x16(floatBitsToUint(
            imageLoad(primeNrdViewZ, coordinate).r));
    bool primaryHasMotion = specularMaterial.a > 2.0;
    float primaryNormalY = specularMaterial.a
            - (primaryHasMotion ? 4.0 : 0.0);

    PrimeIntegrationResult result = primeEmptyIntegrationResult();
    result.radiance.diffuse = diffuse.rgb;
    result.radiance.specular = specular.rgb;
    result.radiance.stable = stable.rgb;
    result.radiance.unshadowedSun = sun.rgb;
    result.radiance.sunVisibility = sun.a;
    result.guides.primaryDistance = position.w;
    result.guides.specularHitDistance = specular.a;
    result.guides.diffuseHitDistance = diffuse.a;
    result.guides.sunPenumbra = imageLoad(primeNrdSunPenumbra, coordinate).r;
    result.guides.primaryAlbedo = material.rgb;
    result.guides.primaryHitKind = position.w >= 0.0
            ? PRIME_HIT_SURFACE
            : PRIME_HIT_NONE;
    result.guides.primaryNormal = primeUnpackOctahedralNormal(
            packSnorm2x16(vec2(packedNormalMotion.x, primaryNormalY)));
    result.guides.primaryMaterialFlags =
            primeLightweightPrimaryMaterialFlags(record);
    result.guides.primarySpecularAlbedo = specularMaterial.rgb;
    result.guides.primaryLinearRoughness = material.w;
    result.guides.primaryPosition = position.xyz;
    vec2 primaryMotionXY = unpackHalf2x16(floatBitsToUint(stable.a));
    result.guides.primaryPreviousPosition = position.xyz
            + vec3(primaryMotionXY, packedNormalMotion.y);
    result.guides.primaryHasMotion = primaryHasMotion;
    if (primeWritesNrdShInputs()) {
        result.guides.diffuseDirection = primeRestoreFp16Direction(
                imageLoad(primeNrdDiffuseDirection, coordinate).xyz);
        result.guides.specularDirection = primeRestoreFp16Direction(
                imageLoad(primeNrdSpecularDirection, coordinate).xyz);
    }
    result.guides.primaryAreaDiffuse = vec3(0.0);
    result.guides.primaryAreaSpecular = vec3(0.0);
    result.guides.primaryAreaDirection = vec3(0.0);
    return result;
}

#endif
