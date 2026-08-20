#include "realtime/realtime_wavefront_common.slang"

static const int PRIME_REALTIME_SUN_SHADOW_RESOLUTION = 512;
static const int PRIME_REALTIME_SUN_SHADOW_CASCADE_COUNT = 5;
static const float PRIME_REALTIME_SUN_SHADOW_SAFE_RADIUS_TEXELS = 254.0;
static const float PRIME_REALTIME_SUN_SHADOW_DEPTH_BIAS = 0.02;
static const float PRIME_REALTIME_SUN_SHADOW_OCCLUDED_IMPORTANCE = 0.25;

float primeRealtimeSunShadowDepth(int imageIndex, int2 coordinate) {
    if (imageIndex == 0) return primeSunShadowDepth0.Load(coordinate);
    if (imageIndex == 1) return primeSunShadowDepth1.Load(coordinate);
    if (imageIndex == 2) return primeSunShadowDepth2.Load(coordinate);
    if (imageIndex == 3) return primeSunShadowDepth3.Load(coordinate);
    if (imageIndex == 4) return primeSunShadowDepth4.Load(coordinate);
    if (imageIndex == 5) return primeSunShadowDepth5.Load(coordinate);
    if (imageIndex == 6) return primeSunShadowDepth6.Load(coordinate);
    if (imageIndex == 7) return primeSunShadowDepth7.Load(coordinate);
    if (imageIndex == 8) return primeSunShadowDepth8.Load(coordinate);
    return primeSunShadowDepth9.Load(coordinate);
}

float primeRealtimeSunShadowTexelSize(int cascade) {
    return 0.5 * float(1 << cascade);
}

int primeRealtimeSunShadowCascade(float3 position) {
    float3 relative = position - primePush.cameraPosition;
    float projectedDistance = max(
            abs(dot(relative, primeSunShadowQuery.basisU)),
            abs(dot(relative, primeSunShadowQuery.basisV)));
    for (int cascade = 0;
            cascade < PRIME_REALTIME_SUN_SHADOW_CASCADE_COUNT;
            cascade++) {
        if (projectedDistance
                <= PRIME_REALTIME_SUN_SHADOW_SAFE_RADIUS_TEXELS
                        * primeRealtimeSunShadowTexelSize(cascade)) {
            return cascade;
        }
    }
    return -1;
}

// This is an importance hint, not a visibility result. One raw texel is deliberately soft so
// clipmap lag, sub-texel blockers, and the finite sun disk cannot remove either light proposal.
float primeRealtimeSunShadowImportance(float3 position) {
    if (primeSunShadowQuery.valid == 0u
            || dot(primeSunShadowQuery.directionToSun, primePush.sunDirection)
                    < cos(ATM_SUN_ANGULAR_RADIUS_RADIANS)) {
        return 1.0;
    }
    int cascade = primeRealtimeSunShadowCascade(position);
    if (cascade < 0) {
        return 1.0;
    }
    float texelSize = primeRealtimeSunShadowTexelSize(cascade);
    float2 projected = float2(
            dot(position, primeSunShadowQuery.basisU),
            dot(position, primeSunShadowQuery.basisV));
    int2 coordinate = int2(floor(projected / texelSize))
            & (PRIME_REALTIME_SUN_SHADOW_RESOLUTION - 1);
    int imageIndex = int(primeSunShadowQuery.bank)
            * PRIME_REALTIME_SUN_SHADOW_CASCADE_COUNT
            + cascade;
    float blockerDepth = primeRealtimeSunShadowDepth(imageIndex, coordinate);
    float pointDepth = dot(position, primeSunShadowQuery.directionToSun);
    return pointDepth + PRIME_REALTIME_SUN_SHADOW_DEPTH_BIAS >= blockerDepth
            ? 1.0
            : PRIME_REALTIME_SUN_SHADOW_OCCLUDED_IMPORTANCE;
}

bool primeSecondarySamplesSunLight(
        PrimePreparedSampleBase preparedSample, float sunProbability) {
    return primeSobolSample1D(
            preparedSample,
            PRIME_SAMPLE_EFFECT_DIRECT_LIGHT_FAMILY,
            PRIME_SAMPLE_DIMENSION_PRIMARY)
            < sunProbability;
}

interface IPrimeWavefrontLightNee {
    float sunProbability(
            IntegratorRecord integrator,
            SurfaceInteraction surface,
            float3 viewDirection);

    float3 estimate(
            inout PrimeIntegratorContext context,
            IntegratorRecord integrator,
            SurfaceInteraction surface,
            float3 viewDirection,
            PrimePreparedSampleBase preparedSample,
            PrimeOpenPbrVolumeStack volumeStack,
            float sunProbability);
}

struct PrimeMixedWavefrontLightNee : IPrimeWavefrontLightNee {
    float sunProbability(
            IntegratorRecord integrator,
            SurfaceInteraction surface,
            float3 viewDirection) {
        float2 importance = primeSecondaryLightFamilyImportance(
                integrator, surface, viewDirection);
        float probability = primeQuantizedSunFamilyProbability(
                importance.x, importance.y);
        float minimumProbability =
                float(PRIME_SUN_FAMILY_MINIMUM_MIXED_CODE)
                / float(PRIME_SUN_FAMILY_PROBABILITY_STEPS);
        if (importance.x > 0.0
                && importance.y > 0.0
                && probability > minimumProbability) {
            importance.x *= primeRealtimeSunShadowImportance(surface.position);
            probability = primeQuantizedSunFamilyProbability(
                    importance.x, importance.y);
        }
        return probability;
    }

    float3 estimate(
            inout PrimeIntegratorContext context,
            IntegratorRecord integrator,
            SurfaceInteraction surface,
            float3 viewDirection,
            PrimePreparedSampleBase preparedSample,
            PrimeOpenPbrVolumeStack volumeStack,
            float sunProbability) {
        if (!primeSecondarySamplesSunLight(
                preparedSample, sunProbability)) {
            return primeEstimateDirectAreaLight(
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
                    1.0 - sunProbability);
        }
        return primeEstimateDirectSun(
                context,
                integrator,
                surface,
                viewDirection,
                primeSobolSample2D(
                        preparedSample,
                        PRIME_SAMPLE_EFFECT_DIRECT_SUN,
                        PRIME_SAMPLE_DIMENSION_PRIMARY),
                volumeStack,
                sunProbability);
    }
};

struct PrimeSunOnlyWavefrontLightNee : IPrimeWavefrontLightNee {
    float sunProbability(
            IntegratorRecord integrator,
            SurfaceInteraction surface,
            float3 viewDirection) {
        return 1.0;
    }

    float3 estimate(
            inout PrimeIntegratorContext context,
            IntegratorRecord integrator,
            SurfaceInteraction surface,
            float3 viewDirection,
            PrimePreparedSampleBase preparedSample,
            PrimeOpenPbrVolumeStack volumeStack,
            float sunProbability) {
        return primeEstimateDirectSun(
                context,
                integrator,
                surface,
                viewDirection,
                primeSobolSample2D(
                        preparedSample,
                        PRIME_SAMPLE_EFFECT_DIRECT_SUN,
                        PRIME_SAMPLE_DIMENSION_PRIMARY),
                volumeStack,
                1.0);
    }
};

void primeWavefrontLight<LightNee, IndirectReuse, SharcTraining>(
        LightNee lightNee,
        IndirectReuse indirectReuse,
        SharcTraining sharcTraining,
        inout PrimeIntegratorContext context,
        uint launchIndex)
    where LightNee : IPrimeWavefrontLightNee
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
    PrimeOpenPbrVolumeStack volumeStack = primeWavefrontVolumeStack(record);
    if (!primeApplySegmentMedium(context, path, surface, volumeStack)) {
        return;
    }
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    if (!primeWavefrontTransparentBranch(control)) {
        PrimeIndirectReuseResult reuse = indirectReuse.query(
                context, path, surface, true);
        if (reuse.hit) {
            if (any(reuse.contribution > float3(0.0))) {
                primeAccumulateDeferredDirectLight(
                        context, pixel, control, reuse.contribution);
            }
            sharcTraining.finish(path);
            record = primeWavefrontWithActive(record, false);
            primeStoreWavefrontTransportRecord(recordIndex, record);
            primeStoreWavefrontDiagnostic(recordIndex, context.diagnostics);
            return;
        }
    }
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
    }
    IntegratorRecord integrator;
    integrator.sunDirectionIntensity = float4(
            primePush.sunDirection,
            ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
    integrator.environmentRadiance = float4(0.0);
    float sunProbability = lightNee.sunProbability(
            integrator, surface, viewDirection);
    primeStoreWavefrontSunFamilyProbability(
            recordIndex,
            primeStoredSunFamilyProbability(sunProbability));

    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
    primeSetNumericalContext(context.diagnostics, PRIME_NUMERICAL_STAGE_DIRECT_LIGHT, path.bounce);
    float3 direct = lightNee.estimate(
            context,
            integrator,
            surface,
            viewDirection,
            preparedSample,
            volumeStack,
            sunProbability);
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
