#ifndef PRIME_REALTIME_WAVEFRONT_COMMON_SLANG
#define PRIME_REALTIME_WAVEFRONT_COMMON_SLANG

import "prime_realtime_abi.slang";
import "core/atmosphere_rt.slang";
import "core/numerical_diagnostics.slang";
import "core/nrd_common.slang";
import "core/packed_geometry.slang";
import "core/render_config.slang";
import "rt/shadow_payload.slang";

import "core/sampling.slang";
import "core/transparent_checkerboard.slang";
#include "bsdf/adapter/bsdf.slang"
import "lighting/lights.slang";

#include "integrator/integrator.slang"
import "core/camera.slang";
#include "realtime/wavefront_state.slang"

#if defined(PRIME_ENABLE_SUBGROUP_QUEUE)
typealias PrimeWavefrontContinuationAppender =
        PrimeSubgroupWavefrontContinuationAppender;
#else
typealias PrimeWavefrontContinuationAppender =
        PrimeScalarWavefrontContinuationAppender;
#endif

struct PrimeWavefrontShaderRecord {
    uint control;
};

[[vk::shader_record]]
ConstantBuffer<PrimeWavefrontShaderRecord> primeWavefrontShaderRecord;

interface IPrimeWavefrontSharcTraining {
    void recordHit(
            inout PrimeIntegratorContext context,
            PathState path,
            SurfaceInteraction surface,
            float3 direct);
    void recordMiss(
            inout PrimeIntegratorContext context,
            PathState path,
            IntegratorRecord integrator);
}

struct PrimeNoWavefrontSharcTraining : IPrimeWavefrontSharcTraining {
    void recordHit(
            inout PrimeIntegratorContext context,
            PathState path,
            SurfaceInteraction surface,
            float3 direct) {}
    void recordMiss(
            inout PrimeIntegratorContext context,
            PathState path,
            IntegratorRecord integrator) {}
};

struct PrimeStagedSecondarySunNee : IPrimeSecondarySunNee {
    float3 estimate(
            inout PrimeIntegratorContext context,
            IntegratorRecord integrator,
            SurfaceInteraction surface,
            float3 viewDirection,
            PrimePreparedSampleBase preparedSample,
            PrimeOpenPbrVolumeStack volumeStack,
            float3 pathThroughput,
            uint pathIndex) {
        // The merged Light stage consumes the persisted pre-scatter surface and path.
        return float3(0.0);
    }
};

#endif
