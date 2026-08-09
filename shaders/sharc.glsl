#ifndef PRIME_SHARC_GLSL
#define PRIME_SHARC_GLSL

#define SHARC_ENABLE_GLSL 1
#define SHARC_ENABLE_SH_ENCODING 1
#define SHARC_USE_FP16 1
#define SHARC_ENABLE_FADE_ACCELERATION 1
#define SHARC_ENABLE_RESPONSIVE_LIGHTING 0
// Resolved-cache reuse in Update correlates samples and can form a static-view energy feedback loop.
#define SHARC_ENABLE_CACHE_RESAMPLING 0
#define SHARC_MATERIAL_DEMODULATION 1
#define SHARC_SEPARATE_EMISSIVE 1
#define SHARC_PROPAGATION_DEPTH 4
#define HASH_GRID_ENABLE_64_BIT_ATOMICS 1
#define HASH_GRID_COMPACT 0

#include "SharcGlslHelpers.h"
#include "SharcCommon.h"

#if SHARC_QUERY
const uint PRIME_SHARC_DIAGNOSTICS_ENABLED = 1u;
const uint PRIME_SHARC_DIAGNOSTIC_SAMPLE_MASK = 255u;
const uint PRIME_SHARC_DIAGNOSTIC_QUERY = 0u;
const uint PRIME_SHARC_DIAGNOSTIC_DELTA_SKIP = 1u;
const uint PRIME_SHARC_DIAGNOSTIC_SHORT_SKIP = 2u;
const uint PRIME_SHARC_DIAGNOSTIC_GLOSSY_SKIP = 3u;
const uint PRIME_SHARC_DIAGNOSTIC_LOOKUP = 4u;
const uint PRIME_SHARC_DIAGNOSTIC_HIT = 5u;

layout(buffer_reference, std430, buffer_reference_align = 4) buffer
PrimeSharcDiagnosticBuffer {
    uint counters[];
};

bool primeSharcDiagnosticSample(PathState path) {
    if ((primeSharcFrame.flags & PRIME_SHARC_DIAGNOSTICS_ENABLED) == 0u
            || primeSharcFrame.diagnosticsAddress == 0ul) {
        return false;
    }
    uint hash = primeHash32(path.pixel.x);
    hash = primeHashCombine(hash, path.pixel.y);
    hash = primeHashCombine(hash, path.sampleEpoch);
    hash = primeHashCombine(hash, path.sampleIndex);
    hash = primeHashCombine(hash, path.sampleDimension);
    return (hash & PRIME_SHARC_DIAGNOSTIC_SAMPLE_MASK) == 0u;
}

void primeRecordSharcDiagnostic(bool sampled, uint counter) {
    if (sampled) {
        atomicAdd(
                PrimeSharcDiagnosticBuffer(
                        primeSharcFrame.diagnosticsAddress).counters[counter],
                1u);
    }
}
#endif

#if SHARC_UPDATE || SHARC_QUERY
float primeSharcLuminance(vec3 value) {
    return dot(value, vec3(0.2627, 0.6780, 0.0593));
}

vec3 primeSharcMaterialDemodulation(SurfaceInteraction surface) {
    if (primeMaterialIsTransmissive(surface.materialFlags)) {
        return vec3(1.0);
    }

    PrimeTranslatedLabPbrMaterial translated = primeDecodeAndTranslateLabPbr(
            surface.labPbrNormal,
            surface.labPbrSpecular,
            surface.materialFlags);
    bool metal = primeTranslatedLabPbrIsMetal(translated);
    vec3 diffuseAlbedo = metal ? vec3(0.0) : surface.baseColor;
    vec3 specularF0 = metal
            ? primeLabPbrMetalFresnel(surface.baseColor, translated.metalId).f0
            : vec3(translated.dielectricF0);
    vec3 averageSpecularFresnel = specularF0
            + (vec3(1.0) - specularF0) * (1.0 / 21.0);
    float averageSpecular = primeSharcLuminance(averageSpecularFresnel);
    // Floors keep dark diffuse and weak dielectric materials numerically reconstructable.
    return max(diffuseAlbedo, vec3(0.05))
            + max(specularF0, vec3(0.02)) * averageSpecular;
}
#endif

SharcParameters primeSharcParameters() {
    SharcParameters parameters;
    parameters.hashGridParameters.cameraPosition = primeSharcFrame.cameraPosition;
    parameters.hashGridParameters.logarithmBase = primeSharcFrame.logarithmBase;
    parameters.hashGridParameters.sceneScale = primeSharcFrame.sceneScale;
    parameters.hashGridParameters.levelBias = primeSharcFrame.levelBias;
    parameters.hashGridData.capacity = primeSharcFrame.capacity;
    parameters.hashGridData.hashEntriesBuffer =
            RWStructuredBuffer_uint64_t(primeSharcFrame.hashEntriesAddress);
    parameters.radianceScale = primeSharcFrame.radianceScale;
    parameters.accumulationBuffer =
            RWStructuredBuffer_SharcAccumulationData(
                    primeSharcFrame.accumulationAddress);
    parameters.resolvedBuffer = RWStructuredBuffer_SharcPackedData(
            primeSharcFrame.resolvedAddress);
    return parameters;
}

#if SHARC_UPDATE || SHARC_QUERY
SharcHitData primeSharcHitData(
        vec3 position,
        vec3 geometricNormal,
        vec3 radianceDirection,
        float radianceDirectionWeight,
        vec3 materialDemodulation,
        vec3 emissive) {
    SharcHitData hit;
    hit.positionWorld = position;
    hit.normalWorld = geometricNormal;
    hit.radianceDirectionWorld = radianceDirection;
    hit.radianceDirectionWeight = radianceDirectionWeight;
    hit.materialDemodulation = materialDemodulation;
    hit.emissive = emissive;
    return hit;
}
#endif

#if SHARC_QUERY
float primeSharcVoxelSize(vec3 position, vec3 geometricNormal) {
    float voxelSize;
    HashGridComputeSpatialHashWithVoxelSize(
            position,
            geometricNormal,
            primeSharcParameters().hashGridParameters,
            voxelSize);
    return voxelSize;
}
#endif

#endif
