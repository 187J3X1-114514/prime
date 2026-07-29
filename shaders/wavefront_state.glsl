#ifndef PRIME_WAVEFRONT_STATE_GLSL
#define PRIME_WAVEFRONT_STATE_GLSL

// Two fixed slots belong to each realtime pixel so a primary transparent interface can enqueue
// reflection and transmission as independent invocations. Nine aligned 16-byte lanes keep hot
// transport in f32, bounded medium/guide state in f16, and a compact queue-resident PSR transform.
// The 144-byte std430 stride is a CPU/GPU ABI and needs no scalar-block-layout feature.
struct PrimeWavefrontPathRecord {
    vec4 physicalOriginAndPreviousBsdfPdf;
    vec4 traceOriginAndPathControl;
    vec4 rayDirectionAndDenoiserControl;
    vec4 throughputAndNumericalFlags;
    uvec4 medium0;
    uvec4 medium1;
    uvec4 primaryAreaRadianceAndDirection;
    vec4 psrLastPositionControl;
    uvec4 psrPacked;
};

layout(
        set = 0,
        binding = PRIME_DESCRIPTOR_WAVEFRONT_PATHS,
        std430) buffer PrimeWavefrontPathBuffer {
    PrimeWavefrontPathRecord records[];
} primeWavefrontPaths;

layout(
        set = 0,
        binding = PRIME_DESCRIPTOR_WAVEFRONT_QUEUE,
        std430) buffer PrimeWavefrontQueueBuffer {
    uint words[];
} primeWavefrontQueue;

// Active rounds only need the six transport/medium lanes. Area-light moments are immutable after
// head, while queued PSR is cold state used only by transparent paths before a guide is found.
// Member-wise access prevents the SPIR-V front end from emitting a 144-byte aggregate load/store
// for every ordinary continuation.
PrimeWavefrontPathRecord primeLoadWavefrontTransportRecord(uint pathIndex) {
    PrimeWavefrontPathRecord record;
    record.physicalOriginAndPreviousBsdfPdf =
            primeWavefrontPaths.records[pathIndex].physicalOriginAndPreviousBsdfPdf;
    record.traceOriginAndPathControl =
            primeWavefrontPaths.records[pathIndex].traceOriginAndPathControl;
    record.rayDirectionAndDenoiserControl =
            primeWavefrontPaths.records[pathIndex].rayDirectionAndDenoiserControl;
    record.throughputAndNumericalFlags =
            primeWavefrontPaths.records[pathIndex].throughputAndNumericalFlags;
    record.medium0 = primeWavefrontPaths.records[pathIndex].medium0;
    record.medium1 = primeWavefrontPaths.records[pathIndex].medium1;
    record.primaryAreaRadianceAndDirection = uvec4(0u);
    record.psrLastPositionControl = vec4(0.0);
    record.psrPacked = uvec4(0u);
    return record;
}

void primeLoadWavefrontPsrRecord(
        uint pathIndex,
        inout PrimeWavefrontPathRecord record) {
    record.psrLastPositionControl =
            primeWavefrontPaths.records[pathIndex].psrLastPositionControl;
    record.psrPacked = primeWavefrontPaths.records[pathIndex].psrPacked;
}

void primeStoreWavefrontTransportRecord(
        uint pathIndex,
        PrimeWavefrontPathRecord record) {
    primeWavefrontPaths.records[pathIndex].physicalOriginAndPreviousBsdfPdf =
            record.physicalOriginAndPreviousBsdfPdf;
    primeWavefrontPaths.records[pathIndex].traceOriginAndPathControl =
            record.traceOriginAndPathControl;
    primeWavefrontPaths.records[pathIndex].rayDirectionAndDenoiserControl =
            record.rayDirectionAndDenoiserControl;
    primeWavefrontPaths.records[pathIndex].throughputAndNumericalFlags =
            record.throughputAndNumericalFlags;
    primeWavefrontPaths.records[pathIndex].medium0 = record.medium0;
    primeWavefrontPaths.records[pathIndex].medium1 = record.medium1;
}

void primeStoreWavefrontPsrRecord(
        uint pathIndex,
        PrimeWavefrontPathRecord record) {
    primeWavefrontPaths.records[pathIndex].psrLastPositionControl =
            record.psrLastPositionControl;
    primeWavefrontPaths.records[pathIndex].psrPacked = record.psrPacked;
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
    return queue * (PRIME_WAVEFRONT_QUEUE_COMMAND_STRIDE / 4u);
}

uint primeWavefrontQueueWord(uint queue, uint entry) {
    uint commandWords = PRIME_WAVEFRONT_QUEUE_COUNT
            * (PRIME_WAVEFRONT_QUEUE_COMMAND_STRIDE / 4u);
    return commandWords + queue * primeWavefrontPathCapacity() + entry;
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

uvec4 primePackWavefrontMedium(PrimeRcVolume medium) {
    return uvec4(
            packHalf2x16(medium.extinction.xy),
            packHalf2x16(vec2(medium.extinction.z, medium.ior)),
            packHalf2x16(medium.albedo.xy),
            packHalf2x16(vec2(medium.albedo.z, medium.anisotropy)));
}

PrimeRcVolume primeUnpackWavefrontMedium(uvec4 packedMedium) {
    vec2 extinction01 = unpackHalf2x16(packedMedium.x);
    vec2 extinction2Ior = unpackHalf2x16(packedMedium.y);
    vec2 albedo01 = unpackHalf2x16(packedMedium.z);
    vec2 albedo2Anisotropy = unpackHalf2x16(packedMedium.w);
    PrimeRcVolume medium;
    medium.extinction = vec3(extinction01, extinction2Ior.x);
    medium.ior = extinction2Ior.y;
    medium.albedo = vec3(albedo01, albedo2Anisotropy.x);
    medium.anisotropy = albedo2Anisotropy.y;
    return medium;
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

bool primeWavefrontActive(PrimeWavefrontPathRecord record) {
    uint denoiserControl =
            floatBitsToUint(record.rayDirectionAndDenoiserControl.w);
    return (denoiserControl & PRIME_WAVEFRONT_ACTIVE_MASK) != 0u;
}

void primeSetWavefrontActive(inout PrimeWavefrontPathRecord record, bool enabled) {
    uint denoiserControl =
            floatBitsToUint(record.rayDirectionAndDenoiserControl.w);
    if (enabled) {
        denoiserControl |= PRIME_WAVEFRONT_ACTIVE_MASK;
    } else {
        denoiserControl &= ~PRIME_WAVEFRONT_ACTIVE_MASK;
    }
    record.rayDirectionAndDenoiserControl.w = uintBitsToFloat(denoiserControl);
}

void primeSetWavefrontQueuedPsrState(
        inout PrimeWavefrontPathRecord record,
        PrimeQueuedPsrState state) {
    record.psrLastPositionControl = state.lastPositionControl;
    vec3 firstDirection = state.firstDirectionLength.xyz;
    if (!(dot(firstDirection, firstDirection) > 0.0)) {
        firstDirection = vec3(0.0, 0.0, 1.0);
    }
    record.psrPacked = uvec4(
            primePackOctahedralNormal(firstDirection),
            floatBitsToUint(state.firstDirectionLength.w),
            packSnorm2x16(clamp(state.rotation.xy, vec2(-1.0), vec2(1.0))),
            packSnorm2x16(clamp(state.rotation.zw, vec2(-1.0), vec2(1.0))));
}

PrimeWavefrontPathRecord primeMakeWavefrontRecord(
        PathState path,
        PrimeRcVolumeStack volumeStack,
        PrimeDenoiserState denoiserState,
        PrimeIntegrationResult result,
        bool enabled) {
    PrimeWavefrontPathRecord record;
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
            | ((result.guides.primaryMaterialFlags
                    & PRIME_WAVEFRONT_PRIMARY_FLAGS_MASK)
                    << PRIME_WAVEFRONT_PRIMARY_FLAGS_SHIFT);
    record.rayDirectionAndDenoiserControl =
            vec4(path.rayDirection, uintBitsToFloat(denoiserControl));
    record.throughputAndNumericalFlags =
            vec4(path.throughput, uintBitsToFloat(primePackWavefrontDiagnostic()));
    uvec3 primaryAreaRadiance = primePackWavefrontPair(
            primeNrdSanitizeRadiance(result.guides.primaryAreaDiffuse),
            primeNrdSanitizeRadiance(result.guides.primaryAreaSpecular));
    vec3 primaryAreaDirection = result.guides.primaryAreaDirection;
    if (!(dot(primaryAreaDirection, primaryAreaDirection) > 0.0)) {
        primaryAreaDirection = vec3(0.0, 1.0, 0.0);
    }
    record.primaryAreaRadianceAndDirection = uvec4(
            primaryAreaRadiance,
            primePackOctahedralNormal(primaryAreaDirection));
    primeSetWavefrontQueuedPsrState(record, primeEmptyQueuedPsrState());
    return record;
}

PathState primeWavefrontPath(
        uvec2 pixel, PrimeWavefrontPathRecord record) {
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
    path.pixel = pixel;
    path.sampleIndex = primePush.path.x;
    path.sampleEpoch = primeSampleEpoch();
    return path;
}

PrimeRcVolumeStack primeWavefrontVolumeStack(PrimeWavefrontPathRecord record) {
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
        PrimeWavefrontPathRecord record,
        PrimeIntegrationResult result) {
    PrimeDenoiserState state;
    uint denoiserControl =
            floatBitsToUint(record.rayDirectionAndDenoiserControl.w);
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

void primeRestoreWavefrontDiagnostic(PrimeWavefrontPathRecord record) {
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

void primeUpdateWavefrontRecord(
        inout PrimeWavefrontPathRecord record,
        PathState path,
        PrimeRcVolumeStack volumeStack,
        PrimeDenoiserState denoiserState,
        bool enabled) {
    record.physicalOriginAndPreviousBsdfPdf =
            vec4(path.physicalOrigin, path.previousBsdfPdf);
    record.traceOriginAndPathControl =
            vec4(path.traceOrigin, uintBitsToFloat(primePackWavefrontPathControl(path)));
    record.medium0 = primePackWavefrontMedium(volumeStack.values[0]);
    record.medium1 = primePackWavefrontMedium(volumeStack.values[1]);
    uint previousDenoiserControl =
            floatBitsToUint(record.rayDirectionAndDenoiserControl.w);
    uint persistedControl =
            previousDenoiserControl & PRIME_WAVEFRONT_BRANCH_CONTROL_MASK;
    uint persistedPrimaryFlags =
            previousDenoiserControl & (PRIME_WAVEFRONT_PRIMARY_FLAGS_MASK
                            << PRIME_WAVEFRONT_PRIMARY_FLAGS_SHIFT);
    uint denoiserControl =
            (enabled ? PRIME_WAVEFRONT_ACTIVE_MASK : 0u) | persistedControl;
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
            | persistedPrimaryFlags;
    record.rayDirectionAndDenoiserControl =
            vec4(path.rayDirection, uintBitsToFloat(denoiserControl));
    record.throughputAndNumericalFlags =
            vec4(path.throughput, uintBitsToFloat(primePackWavefrontDiagnostic()));
}

PrimeQueuedPsrState primeWavefrontQueuedPsrState(
        PrimeWavefrontPathRecord record) {
    PrimeQueuedPsrState state;
    state.firstDirectionLength = vec4(
            primeUnpackOctahedralNormal(record.psrPacked.x),
            uintBitsToFloat(record.psrPacked.y));
    state.lastPositionControl = record.psrLastPositionControl;
    state.rotation = vec4(
            unpackSnorm2x16(record.psrPacked.z),
            unpackSnorm2x16(record.psrPacked.w));
    float rotationLengthSquared = dot(state.rotation, state.rotation);
    state.rotation = rotationLengthSquared > 0.0
            ? state.rotation * inversesqrt(rotationLengthSquared)
            : vec4(0.0, 0.0, 0.0, 1.0);
    return state;
}

bool primeWavefrontTransparentBranch(PrimeWavefrontPathRecord record) {
    return (floatBitsToUint(record.rayDirectionAndDenoiserControl.w)
            & PRIME_WAVEFRONT_TRANSPARENT_BRANCH) != 0u;
}

bool primeWavefrontTransmissionBranch(PrimeWavefrontPathRecord record) {
    return (floatBitsToUint(record.rayDirectionAndDenoiserControl.w)
            & PRIME_WAVEFRONT_TRANSMISSION_BRANCH) != 0u;
}

bool primeWavefrontGuideEnabled(PrimeWavefrontPathRecord record) {
    return (floatBitsToUint(record.rayDirectionAndDenoiserControl.w)
            & PRIME_WAVEFRONT_GUIDE_ENABLED) != 0u;
}

bool primeWavefrontDirectionalGuide(PrimeWavefrontPathRecord record) {
    return (floatBitsToUint(record.rayDirectionAndDenoiserControl.w)
            & PRIME_WAVEFRONT_DIRECTIONAL_GUIDE) != 0u;
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
    PrimeIntegrationResult placeholder = primeEmptyIntegrationResult();
    placeholder.guides = branchResult.guides;
    PrimeWavefrontPathRecord record = primeMakeWavefrontRecord(
            path, volumeStack, denoiserState, placeholder, enabled);
    uint control = floatBitsToUint(record.rayDirectionAndDenoiserControl.w)
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
    record.rayDirectionAndDenoiserControl.w = uintBitsToFloat(control);
    primeSetWavefrontQueuedPsrState(record, psrState);
    return record;
}

void primeUpdateTransparentWavefrontRecord(
        inout PrimeWavefrontPathRecord record,
        PathState path,
        PrimeRcVolumeStack volumeStack,
        PrimeTransparentBranchResult branchResult,
        PrimeQueuedPsrState psrState,
        bool hasGuide,
        bool diffusePath,
        uint guideBounce,
        bool directionalGuide,
        bool enabled) {
    PrimeDenoiserState denoiserState;
    denoiserState.hasPrimarySurface = true;
    denoiserState.reachedNonDelta = hasGuide;
    denoiserState.diffuseAlbedoProduct = branchResult.guides.primaryAlbedo;
    denoiserState.specularAlbedoProduct =
            branchResult.guides.primarySpecularAlbedo;
    denoiserState.diffusePath = diffusePath;
    denoiserState.primaryBounce = guideBounce;
    primeUpdateWavefrontRecord(
            record, path, volumeStack, denoiserState, enabled);
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
    primeSetWavefrontQueuedPsrState(record, psrState);
    // The final transparent topology uses the visible interface's direct-light SH moment. Preserve
    // the value installed by head; later virtual-guide surfaces only contribute radiance.
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
    return primeWavefrontTransparentBranch(
            primeWavefrontPaths.records[primeWavefrontIndex(pixel, 0u)]);
}

PrimeDenoiserGuides primeLoadWavefrontVisibleGuides(
        uvec2 pixel,
        PrimeWavefrontPathRecord transmissionRecord,
        PrimeWavefrontPathRecord reflectionRecord,
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
                pixel, primeCameraSample()) * guides.primaryDistance;
        vec4 visibleMaterial = imageLoad(primeNrdSunLighting, coordinate);
        guides.primarySpecularAlbedo = visibleMaterial.rgb;
        guides.primaryLinearRoughness = visibleMaterial.a;
    }
    guides.diffuseDirection = transmission.guides.diffuseDirection;
    guides.specularDirection = reflection.guides.specularDirection;
    vec3 transmissionAreaSpecular;
    vec3 reflectionAreaDiffuse;
    primeUnpackWavefrontPair(
            transmissionRecord.primaryAreaRadianceAndDirection.xyz,
            guides.primaryAreaDiffuse,
            transmissionAreaSpecular);
    primeUnpackWavefrontPair(
            reflectionRecord.primaryAreaRadianceAndDirection.xyz,
            reflectionAreaDiffuse,
            guides.primaryAreaSpecular);
    guides.primaryAreaDirection = primeUnpackOctahedralNormal(
            transmissionRecord.primaryAreaRadianceAndDirection.w);
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
    imageStore(
            primeStableRadiance,
            coordinate,
            vec4(primeNrdSanitizeRadiance(result.radiance.stable), 1.0));
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
            vec4(result.guides.primarySpecularAlbedo, primaryNormalOctahedral.y));
    imageStore(
            primeNrdViewZ,
            coordinate,
            vec4(primaryNormalOctahedral.x));
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
        PrimeWavefrontPathRecord record) {
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
    result.guides.primaryNormal = primeUnpackOctahedralNormal(
            packSnorm2x16(vec2(
                    imageLoad(primeNrdViewZ, coordinate).r,
                    specularMaterial.a)));
    result.guides.primaryMaterialFlags =
            (denoiserControl >> PRIME_WAVEFRONT_PRIMARY_FLAGS_SHIFT)
                    & PRIME_WAVEFRONT_PRIMARY_FLAGS_MASK;
    result.guides.primarySpecularAlbedo = specularMaterial.rgb;
    result.guides.primaryLinearRoughness = material.w;
    result.guides.primaryPosition = position.xyz;
    if (primeWritesNrdShInputs()) {
        result.guides.diffuseDirection =
                primeRestoreFp16Direction(
                        imageLoad(primeNrdDiffuseDirection, coordinate).xyz);
        result.guides.specularDirection =
                primeRestoreFp16Direction(
                        imageLoad(primeNrdSpecularDirection, coordinate).xyz);
    }
    primeUnpackWavefrontPair(
            record.primaryAreaRadianceAndDirection.xyz,
            result.guides.primaryAreaDiffuse,
            result.guides.primaryAreaSpecular);
    result.guides.primaryAreaDirection =
            primeUnpackOctahedralNormal(
                    record.primaryAreaRadianceAndDirection.w);
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
        PrimeWavefrontPathRecord record) {
    ivec2 coordinate = ivec2(pixel);
    vec4 diffuse = imageLoad(primeNrdNoisyDiffuse, coordinate);
    vec4 specular = imageLoad(primeNrdNoisySpecular, coordinate);
    uint control = floatBitsToUint(record.rayDirectionAndDenoiserControl.w);

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
        PrimeWavefrontPathRecord record) {
    ivec2 coordinate = ivec2(pixel);
    bool transmissionBranch = primeWavefrontTransmissionBranch(record);
    bool nrdShInputs = primeWritesNrdShInputs();
    uint control = floatBitsToUint(record.rayDirectionAndDenoiserControl.w);
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
    result.directionalGuide = primeWavefrontDirectionalGuide(record);
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
        PrimeWavefrontPathRecord record) {
    ivec2 coordinate = ivec2(pixel);
    bool transmissionBranch = primeWavefrontTransmissionBranch(record);
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
    uint control = floatBitsToUint(record.rayDirectionAndDenoiserControl.w);

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
    primeUnpackWavefrontPair(
            record.primaryAreaRadianceAndDirection.xyz,
            result.guides.primaryAreaDiffuse,
            result.guides.primaryAreaSpecular);
    result.guides.primaryAreaDirection = primeUnpackOctahedralNormal(
            record.primaryAreaRadianceAndDirection.w);
    result.anchorDistance = transmissionBranch ? branchMetadata.x : -1.0;
    result.firstHitDistance = transmissionBranch
            ? branchMetadata.y
            : imageLoad(primeNrdSunPenumbra, coordinate).r;
    result.directionalGuide = primeWavefrontDirectionalGuide(record);
    return result;
}

void primeFinalizeTransparentBranchGuide(
        PrimeWavefrontPathRecord record,
        inout PrimeTransparentBranchResult result) {
    uint control = floatBitsToUint(record.rayDirectionAndDenoiserControl.w);
    bool hasGuide =
            (control & PRIME_WAVEFRONT_REACHED_NON_DELTA) != 0u;
    if (!primeWavefrontGuideEnabled(record) || hasGuide) {
        return;
    }
    vec3 branchDirection = primeWavefrontTransmissionBranch(record)
            ? result.guides.diffuseDirection
            : result.guides.specularDirection;
    result.guides.primaryAreaDiffuse = vec3(0.0);
    result.guides.primaryAreaSpecular = vec3(0.0);
    result.guides.primaryAreaDirection = vec3(0.0);
    if (primeWavefrontTransmissionBranch(record)) {
        result.guides.diffuseDirection = branchDirection;
        result.guides.diffuseHitDistance = PRIME_NRD_FP16_MAX;
    } else {
        result.guides.specularDirection = branchDirection;
        result.guides.specularHitDistance = PRIME_NRD_FP16_MAX;
    }
}

PrimeIntegrationResult primeResolveTransparentWavefrontResult(uvec2 pixel) {
    PrimeWavefrontPathRecord transmissionRecord =
            primeWavefrontPaths.records[primeWavefrontIndex(pixel, 0u)];
    PrimeWavefrontPathRecord reflectionRecord =
            primeWavefrontPaths.records[primeWavefrontIndex(pixel, 1u)];
    PrimeTransparentBranchResult transmission =
            primeLoadTransparentBranchIntermediate(pixel, transmissionRecord);
    PrimeTransparentBranchResult reflection =
            primeLoadTransparentBranchIntermediate(pixel, reflectionRecord);
    PrimeDenoiserGuides visibleGuides =
            primeLoadWavefrontVisibleGuides(
                    pixel,
                    transmissionRecord,
                    reflectionRecord,
                    transmission,
                    reflection);
    primeFinalizeTransparentBranchGuide(
            transmissionRecord, transmission);
    primeFinalizeTransparentBranchGuide(
            reflectionRecord, reflection);

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
