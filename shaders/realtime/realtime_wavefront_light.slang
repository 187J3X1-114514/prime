#include "realtime/realtime_wavefront_common.slang"

bool primeSecondarySamplesAreaLight(PrimePreparedSampleBase preparedSample) {
    return primeSobolSample1D(
            preparedSample,
            PRIME_SAMPLE_EFFECT_DIRECT_LIGHT_FAMILY,
            PRIME_SAMPLE_DIMENSION_PRIMARY)
            < PRIME_SECONDARY_LIGHT_FAMILY_PROBABILITY;
}

void primeWavefrontLight<IndirectReuse, SharcTraining>(
        IndirectReuse indirectReuse,
        SharcTraining sharcTraining,
        inout PrimeIntegratorContext context,
        uint launchIndex)
    where IndirectReuse : IPrimeIndirectReuse
    where SharcTraining : IPrimeWavefrontSharcTraining
{
    uint recordIndex =
            primeWavefrontQueuedPath(
                    PRIME_WAVEFRONT_AREA_QUEUE, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    primeRestoreWavefrontDiagnostic(record, context.diagnostics);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    if (surface.hitKind != PRIME_HIT_SURFACE) {
        return;
    }

    uint2 pixel = primeWavefrontPixel(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
    bool samplesAreaLight = primeSecondarySamplesAreaLight(preparedSample);
    PrimeOpenPbrVolumeStack volumeStack = primeWavefrontVolumeStack(record);
    if (!primeApplySegmentMedium(context, path, surface, volumeStack)) {
        return;
    }
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    float3 viewDirection = -path.rayDirection;
    PrimeClosureTraits closureTraits = primeSurfaceClosureTraits(
            surface, viewDirection, volumeStack);
    if (!primeClosureSupports(closureTraits, PRIME_MEASURE_SOLID_ANGLE)) {
        if (!primeWavefrontTransparentBranch(control)) {
            sharcTraining.recordHit(
                    context, path, surface, float3(0.0));
        }
        return;
    }
    if (primeWavefrontTransparentBranch(control)) {
        bool guideEnabled = primeWavefrontGuideEnabled(control);
        bool hasGuide = (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) != 0u;
        if (guideEnabled && !hasGuide && !primeClosureIsDiscreteOnly(closureTraits)) {
            return;
        }
    } else {
        PrimeIndirectReuseResult reuse = indirectReuse.query(
                context, path, surface, true);
        if (reuse.hit) {
            return;
        }
    }

    primeSetNumericalContext(context.diagnostics, PRIME_NUMERICAL_STAGE_DIRECT_LIGHT, path.bounce);
    float3 direct;
    if (samplesAreaLight) {
        direct = primeEstimateDirectAreaLight(
                context,
                surface,
                viewDirection,
                primeSobolSample3D(
                        preparedSample,
                        PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
                        PRIME_SAMPLE_DIMENSION_PRIMARY).xy,
                primeSobolSample2D(
                        preparedSample,
                        PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
                        PRIME_SAMPLE_DIMENSION_SECONDARY),
                volumeStack,
                PRIME_SECONDARY_LIGHT_FAMILY_PROBABILITY);
    } else {
        IntegratorRecord integrator;
        integrator.sunDirectionIntensity = float4(
                primePush.sunDirection,
                ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
        integrator.environmentRadiance = float4(0.0);
        direct = primeEstimateDirectSun(
                context,
                integrator,
                surface,
                viewDirection,
                primeSobolSample2D(
                        preparedSample,
                        PRIME_SAMPLE_EFFECT_DIRECT_SUN,
                        PRIME_SAMPLE_DIMENSION_PRIMARY),
                volumeStack,
                PRIME_SECONDARY_LIGHT_FAMILY_PROBABILITY);
    }
    if (!primeWavefrontTransparentBranch(control)) {
        sharcTraining.recordHit(context, path, surface, direct);
    }
    float3 contribution = path.throughput * direct;
    if (any(contribution > float3(0.0))) {
        primeAccumulateDeferredDirectLight(
                context, pixel, control, contribution);
    }
    primeStoreWavefrontDiagnostic(recordIndex, context.diagnostics);
}
