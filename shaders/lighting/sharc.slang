#ifndef PRIME_SHARC_SLANG
#define PRIME_SHARC_SLANG

#define SHARC_ENABLE_SH_ENCODING 1
#define SHARC_ENABLE_GLSL 0
#define SHARC_USE_FP16 1
#define SHARC_ENABLE_FADE_ACCELERATION 1
#define SHARC_ENABLE_RESPONSIVE_LIGHTING 0
// Training targets come only from traced anchor paths. Keep vendor resampling disabled so cached
// radiance cannot re-enter the update stream through an implicit feedback edge.
#define SHARC_ENABLE_CACHE_RESAMPLING 0
#define SHARC_MATERIAL_DEMODULATION 1
#define SHARC_SEPARATE_EMISSIVE 1
#define SHARC_PROPAGATION_DEPTH 1
#define HASH_GRID_ENABLE_64_BIT_ATOMICS 1
#define HASH_GRID_COMPACT 0

#include "lighting/sharc_training_core.slang"
#if SHARC_QUERY
// The vendored query uses `sampleNum > threshold`; one therefore requires two traced anchors.
#define SHARC_SAMPLE_NUM_THRESHOLD PRIME_SHARC_TERMINATION_SAMPLE_THRESHOLD
#endif

// SHaRC's vendored HLSL path normally stores descriptor-backed RWStructuredBuffers. Prime's
// immutable ABI stores Vulkan buffer device addresses, so Slang physical pointers provide the
// same indexed lvalues without modifying the hash-locked third-party headers.
#define RW_STRUCTURED_BUFFER(name, type) type* name
#define BUFFER_AT_OFFSET(name, offset) name[offset]

import "core/sampling.slang";
import "prime_abi_types.slang";
import "prime_sharc_abi.slang";
import "material/default_material.slang";
import "material/material_translation.slang";
// SHaRC has rejected-query exits that intentionally leave HLSL `out` radiance undefined because
// callers consume it only on success. Slang validates every `out` exit. Treating vendored outputs
// as `inout` preserves that conditional contract and leaves the hash-locked header byte-identical.
#define out inout
#include "SharcCommon.h"
#undef out

#if SHARC_UPDATE || SHARC_QUERY
float primeSharcLuminance(float3 value) {
    return dot(value, float3(0.2627, 0.6780, 0.0593));
}

float3 primeSharcMaterialDemodulation(SurfaceInteraction surface) {
    if (primeMaterialIsTransmissive(surface.materialControl)) {
        return float3(1.0);
    }

    PrimeOpticalMaterial translated = primeDecodeOpticalMaterial(
            surface.roughness,
            surface.opticalControl,
            surface.materialControl);
    bool metal = primeOpticalMaterialIsMetal(translated);
    float3 diffuseAlbedo = metal ? float3(0.0) : surface.baseColor;
    float3 specularF0 = metal
            ? primeConductorFresnel(surface.baseColor, translated.fresnelCode).f0
            : float3(translated.dielectricF0);
    float3 averageSpecularFresnel = specularF0
            + (float3(1.0) - specularF0) * (1.0 / 21.0);
    float averageSpecular = primeSharcLuminance(averageSpecularFresnel);
    return max(diffuseAlbedo, float3(0.05))
            + max(specularF0, float3(0.02)) * averageSpecular;
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
            (uint64_t*)primeSharcFrame.hashEntriesAddress;
    parameters.radianceScale = primeSharcFrame.radianceScale;
    parameters.accumulationBuffer =
            (SharcAccumulationData*)primeSharcFrame.accumulationAddress;
    parameters.resolvedBuffer =
            (SharcPackedData*)primeSharcFrame.resolvedAddress;
    return parameters;
}

#if SHARC_UPDATE || SHARC_QUERY
SharcHitData primeSharcHitData(
        float3 position,
        float3 geometricNormal,
        float3 radianceDirection,
        float radianceDirectionWeight,
        float3 materialDemodulation,
        float3 emissive) {
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
float primeSharcVoxelSize(float3 position, float3 geometricNormal) {
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
