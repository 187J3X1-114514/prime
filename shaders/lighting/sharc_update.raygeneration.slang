#define SHARC_UPDATE 1
#define SHARC_QUERY 0

import "core/nrd_common.slang";
import "core/render_config.slang";
import "material/material.slang";
#include "lighting/sharc.slang"
#include "integrator/integrator.slang"
import "core/camera.slang";

float primeSharcSampleDirectionWeight(BsdfSample bsdf, float linearRoughness) {
    if ((bsdf.eventFlags & PRIME_BSDF_EVENT_DISCRETE) != 0u) {
        return 1.0;
    }
    return (bsdf.eventFlags & PRIME_BSDF_EVENT_GLOSSY) != 0u
            ? 1.0 - linearRoughness
            : 0.0;
}

[shader("raygeneration")]
void main() {
    uint2 launchIndex = DispatchRaysIndex().xy;
    uint2 phase = uint2(
            primeSharcFrame.updatePhase % 5u,
            primeSharcFrame.updatePhase / 5u);
    uint2 pixel = launchIndex * 5u + phase;
    if (any(pixel >= primePush.outputExtent)) {
        return;
    }

    PrimeIntegratorContext context = primeIntegratorContextCreate();
    PathState path = primeCameraPath(
            pixel, 0u, primeRealtimeCameraSample(launchIndex));
    PrimeRcVolumeStack volumeStack =
            (primePush.path.z & PRIME_PATH_CAMERA_IN_WATER_MASK) != 0u
            ? primeCameraWaterVolumeStack()
            : primeEmptyVolumeStack();
    IntegratorRecord integrator;
    integrator.sunDirectionIntensity = float4(
            primePush.sunDirection,
            ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
    integrator.environmentRadiance = float4(0.0);

    SharcParameters parameters = primeSharcParameters();
    SharcState state;
    SharcInit(state);
    uint maximumBounces = primeMaximumBounces();
    for (uint bounce = 0u; bounce < maximumBounces; ++bounce) {
        SurfaceInteraction surface = primeTraceSurfaceWithoutReorder(
                path.traceOrigin, path.rayDirection);
        if (surface.hitKind == PRIME_HIT_NONE) {
            SharcUpdateMiss(
                    parameters,
                    state,
                    primeEvaluateEnvironmentContribution(context, path, integrator));
            return;
        }
        if (!primeKnownHitKind(surface)) {
            return;
        }

        if (volumeStack.count > 0u) {
            PrimeRcVolume medium = volumeStack.values[volumeStack.count - 1u];
            float3 transmittance = primeSegmentTransmittance(
                    medium.extinction, surface.t);
            if (all(transmittance == float3(0.0))) {
                return;
            }
            SharcSetThroughput(state, transmittance);
        }

        path.throughput = float3(1.0);
        float3 viewDirection = -path.rayDirection;
        float surfaceLinearRoughness = primeSurfaceLinearRoughness(surface);
        static const float minimumUpdateLinearRoughness = 0.4;
        float updateLinearRoughness = max(
                surfaceLinearRoughness, minimumUpdateLinearRoughness);
        bool discreteOnlyInterface = primeMaterialIsTransmissive(surface.materialControl)
                && primeClosureIsDiscreteOnly(primeSurfaceClosureTraits(
                        surface, viewDirection, volumeStack));
        PrimePreparedSampleBase preparedSample = primePrepareSampleBase(
                primeMakeSampleBase(path, path.bounce + 1u));

        float3 diffuseDirect = float3(0.0);
        float3 specularDirect = float3(0.0);
        if (!discreteOnlyInterface) {
            PrimePrimarySunSample sun = primeEstimatePrimaryDirectSun(
                    context,
                    integrator,
                    surface,
                    viewDirection,
                    primeSobolSample2D(
                            preparedSample,
                            PRIME_SAMPLE_EFFECT_DIRECT_SUN,
                            PRIME_SAMPLE_DIMENSION_PRIMARY),
                    volumeStack,
                    false);
            diffuseDirect = sun.lighting.diffuse * sun.visibility;
            specularDirect = sun.lighting.specular * sun.visibility;
            PrimeDirectLightingSplit nonsun = primeEstimatePrimaryNonsunDirect(
                    context,
                    surface,
                    viewDirection,
                    preparedSample,
                    volumeStack,
                    false);
            diffuseDirect += nonsun.diffuse;
            specularDirect += nonsun.specular;
        }
        float3 emissive = primeEvaluateLocalHitEmission(
                context, path, surface, path.bounce > 0u);
        float3 direct = diffuseDirect + specularDirect;
        float directLuminance = primeSharcLuminance(direct);
        float directionWeight = (1.0 - surfaceLinearRoughness)
                * primeSharcLuminance(specularDirect)
                / max(directLuminance, 1.0e-6);
        SharcHitData hit = primeSharcHitData(
                surface.position,
                surface.geometricNormal,
                viewDirection,
                directionWeight,
                primeSharcMaterialDemodulation(surface),
                emissive);
        float resamplingRandom = primeHashSample1D(
                preparedSample,
                PRIME_SAMPLE_EFFECT_RUSSIAN_ROULETTE,
                PRIME_SAMPLE_DIMENSION_SECONDARY);
        if (!SharcUpdateHit(
                parameters, state, hit, direct, resamplingRandom)) {
            return;
        }

        PrimePathScatter scatter = primeSamplePathSurfaceWithMinimumRoughness(
                surface,
                viewDirection,
                primeSobolSample3D(
                        preparedSample,
                        PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
                        PRIME_SAMPLE_DIMENSION_PRIMARY),
                volumeStack,
                minimumUpdateLinearRoughness);
        BsdfSample bsdf = scatter.bsdf;
        if (!primeHasScatter(bsdf)) {
            return;
        }
        volumeStack = scatter.volumeStack;
        if (!primeAdvancePath(
                context,
                path,
                surface,
                bsdf,
                preparedSample,
                discreteOnlyInterface,
                updateLinearRoughness)) {
            return;
        }
        SharcSetThroughput(state, path.throughput);
        SharcSetRadianceDirectionWeight(
                state,
                primeSharcSampleDirectionWeight(bsdf, updateLinearRoughness));
        path.throughput = float3(1.0);
        path.bounce++;
    }
}
