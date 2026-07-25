#ifndef PRIME_WAVEFRONT_STATE_GLSL
#define PRIME_WAVEFRONT_STATE_GLSL

// One fixed slot belongs to each realtime pixel. Seven aligned 16-byte lanes keep hot transport
// values in f32, store bounded medium/guide state as f16, and exclude the large denoiser result,
// which stays in the already allocated per-pixel images. The 112-byte std430 stride is a CPU/GPU
// ABI and deliberately requires no optional scalar-block-layout feature.
struct PrimeWavefrontPathRecord {
    vec4 physicalOriginAndPreviousBsdfPdf;
    vec4 traceOriginAndPathControl;
    vec4 rayDirectionAndDenoiserControl;
    vec4 throughputAndNumericalFlags;
    uvec4 medium0;
    uvec4 medium1;
    uvec4 primaryAreaRadianceAndDirection;
};

layout(
        set = 0,
        binding = PRIME_DESCRIPTOR_WAVEFRONT_PATHS,
        std430) buffer PrimeWavefrontPathBuffer {
    PrimeWavefrontPathRecord records[];
} primeWavefrontPaths;

const uint PRIME_WAVEFRONT_REACHED_NON_DELTA = 2u;
const uint PRIME_WAVEFRONT_DIFFUSE_PATH = 4u;
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
    // Ordinary queued paths currently have exactly the delta and no-area-NEE flags.
    return (min(path.bounce, PRIME_WAVEFRONT_BYTE_MASK)
                    << PRIME_WAVEFRONT_BOUNCE_SHIFT)
            | (min(path.rrDepth, PRIME_WAVEFRONT_BYTE_MASK)
                    << PRIME_WAVEFRONT_RR_DEPTH_SHIFT)
            | ((path.flags & 0x3u) << PRIME_WAVEFRONT_PATH_FLAGS_SHIFT);
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
    // Ordinary realtime slots never fork, so their path index remains zero.
    path.sampleDimension = 0u;
    path.rayDirection = record.rayDirectionAndDenoiserControl.xyz;
    path.flags = (pathControl >> PRIME_WAVEFRONT_PATH_FLAGS_SHIFT) & 0x3u;
    path.throughput = record.throughputAndNumericalFlags.xyz;
    path.previousBsdfPdf = record.physicalOriginAndPreviousBsdfPdf.w;
    path.rrDepth = (pathControl >> PRIME_WAVEFRONT_RR_DEPTH_SHIFT)
            & PRIME_WAVEFRONT_BYTE_MASK;
    path.pixel = pixel;
    path.sampleIndex = primePush.path.x;
    path.sampleEpoch = primePush.path.y;
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
    uint persistedPrimaryFlags =
            floatBitsToUint(record.rayDirectionAndDenoiserControl.w)
                    & (PRIME_WAVEFRONT_PRIMARY_FLAGS_MASK
                            << PRIME_WAVEFRONT_PRIMARY_FLAGS_SHIFT);
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
            | persistedPrimaryFlags;
    record.rayDirectionAndDenoiserControl =
            vec4(path.rayDirection, uintBitsToFloat(denoiserControl));
    record.throughputAndNumericalFlags =
            vec4(path.throughput, uintBitsToFloat(primePackWavefrontDiagnostic()));
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
            primeAccumulation,
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
    vec4 stable = imageLoad(primeAccumulation, coordinate);
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
    result.guides.primaryHitKind = PRIME_HIT_SURFACE;
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
                imageLoad(primeNrdDiffuseDirection, coordinate).xyz;
        result.guides.specularDirection =
                imageLoad(primeNrdSpecularDirection, coordinate).xyz;
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

#endif
