#ifndef PRIME_INTEGRATOR_SLANG
#define PRIME_INTEGRATOR_SLANG

import glsl;
import "prime_abi.slang";
import "prime_abi_types.slang";

import "core/atmosphere.slang";
import "core/atmosphere_rt.slang";
import "core/light_tree_math.slang";
import "core/numerical_diagnostics.slang";
import "core/numerical.slang";
import "core/packed_geometry.slang";
import "core/render_config.slang";
import "core/sampling.slang";
import "core/starmap.slang";
import "core/transport_math.slang";
import "core/queued_psr_math.slang";
import "core/nrd_common.slang";
import "integrator/integration_result.slang";
import "integrator/path_contract.slang";
import "integrator/transparent_guide_coincidence.slang";

#include "bsdf/adapter/bsdf.slang"
import "lighting/hit_emission.slang";
import "lighting/lights.slang";
import "rt/shadow_payload.slang";

// The entire estimator operates in linear Rec.2020 D65. Scheduling changes may move this state
// between kernels, but they must not reinterpret it as encoded sRGB or another RGB basis.

#include "integrator/transport_core.slang"

uint primeAreaLightReceiverNormal(
        SurfaceInteraction surface, float3 viewDirection) {
    if (primeMaterialIsTransmissive(surface.materialControl)
            || primeMaterialIsFoliage(surface.materialControl)) {
        return 0u;
    }
    return primePackLightReceiverNormal(
            primeSurfaceShadingNormal(surface, viewDirection));
}

float3 primeSunDiskUpperDirection(float3 sunDirection) {
    float3 tangent = float3(0.0, 1.0, 0.0)
            - sunDirection * sunDirection.y;
    float tangentLengthSquared = dot(tangent, tangent);
    tangent = tangentLengthSquared > 0.0
            ? tangent * rsqrt(tangentLengthSquared)
            : float3(1.0, 0.0, 0.0);
    return sunDirection * cos(ATM_SUN_ANGULAR_RADIUS_RADIANS)
            + tangent * sin(ATM_SUN_ANGULAR_RADIUS_RADIANS);
}

float2 primeSecondaryLightFamilyImportance(
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        float3 viewDirection) {
    float3 sunDirection = integrator.sunDirectionIntensity.xyz;
    float3 upperDirection = primeSunDiskUpperDirection(sunDirection);
    uint packedReceiverNormal = primeAreaLightReceiverNormal(
            surface, viewDirection);
    float sunImportance = 0.0;
    if (primeAtmosphereDistantDirectionVisible(upperDirection)
            && integrator.sunDirectionIntensity.w > 0.0) {
        float3 representativeDirection =
                primeAtmosphereDistantDirectionVisible(sunDirection)
                ? sunDirection
                : upperDirection;
        float3 transmittance = primeAtmosphereSunTransmittance(
                surface.position, representativeDirection);
        float3 receiverNormal = primeUnpackLightReceiverNormal(
                packedReceiverNormal);
        float receiverCosine = primeLightReceiverCosineEstimate(
                sunDirection, receiverNormal);
        float angularImportance = PRIME_LIGHT_RECEIVER_COSINE_FLOOR
                + (1.0 - PRIME_LIGHT_RECEIVER_COSINE_FLOOR) * receiverCosine;
        sunImportance = integrator.sunDirectionIntensity.w
                * max(transmittance.x, max(transmittance.y, transmittance.z))
                * angularImportance;
    }
    float areaImportance = primeAreaLightFamilyImportance(
            surface.position, packedReceiverNormal);
    return float2(sunImportance, areaImportance);
}

float primeSecondarySunFamilyProbability(
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        float3 viewDirection) {
    float2 importance = primeSecondaryLightFamilyImportance(
            integrator, surface, viewDirection);
    return primeQuantizedSunFamilyProbability(
            importance.x, importance.y);
}

struct PrimeShadowTrace {
    float3 transmittance;
    float hitDistance;
};

PrimeShadowTrace primeTracePreparedShadow(
        inout PrimeIntegratorContext context,
        float3 physicalOrigin,
        float3 direction,
        float distance,
        uint2 sourcePrimitive,
        uint2 targetEmitter,
        float3 startingExtinction0,
        float3 startingExtinction1,
        uint startingMediumCount) {
    primeShadowPayload.opticalDepthMomentHitDistance = float4(0.0);
    primeShadowPayload.terminalExtinctionRayDistance = float4(
            float3(0.0), distance);
    primeShadowPayload.startingExtinction0Winding = float4(
            startingExtinction0, startingMediumCount > 0u ? 1.0 : 0.0);
    primeShadowPayload.startingExtinction1Winding = float4(
            startingExtinction1, startingMediumCount > 1u ? 1.0 : 0.0);
    primeShadowPayload.startingMediumCount = startingMediumCount;
    primeShadowPayload.sourcePrimitive = sourcePrimitive;
    primeShadowPayload.targetEmitter = targetEmitter;
    // Source and target geometry are rejected by exact identity in any-hit. The physical segment
    // therefore remains open at both endpoints without deleting nearby occluders or media.
    traceRayEXT(
            primeScene,
            gl_RayFlagsNoOpaqueEXT,
            0xff,
            3,
            1,
            1,
            physicalOrigin,
            0.0,
            direction,
            distance,
            1);
    PrimeShadowTrace result;
    result.transmittance = clamp(exp(-primeShadowOpticalDepth(
                    primeShadowPayload.opticalDepthMomentHitDistance.xyz,
                    primeShadowPayload.terminalExtinctionRayDistance.xyz,
                    primeShadowPayload.startingExtinction0Winding,
                    primeShadowPayload.startingExtinction1Winding,
                    primeShadowPayload.startingMediumCount,
                    primeShadowPayload.terminalExtinctionRayDistance.w)),
            float3(0.0),
            float3(1.0));
    result.hitDistance = primeNrdSanitizeHitDistance(
            primeShadowPayload.opticalDepthMomentHitDistance.w);
    primeRecordUnit(context.diagnostics, result.transmittance);
    return result;
}

PrimeShadowTrace primeTraceShadow(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        LightSample light,
        uint2 targetEmitter,
        PrimeOpenPbrVolumeStack volumeStack) {
    uint startingMediumCount = min(
            volumeStack.count, PRIME_OPENPBR_MAX_VOLUME_STACK_SIZE);
    float3 startingExtinction0 = startingMediumCount > 0u
            ? primeShadowCanonicalExtinction(
                    volumeStack.values[0].extinction)
            : float3(0.0);
    float3 startingExtinction1 = startingMediumCount > 1u
            ? primeShadowCanonicalExtinction(
                    volumeStack.values[1].extinction)
            : float3(0.0);
    return primeTracePreparedShadow(context,
            surface.position,
            light.direction,
            light.distance,
            uint2(surface.sectionIndex, primeSurfaceTriangleIndex(surface)),
            targetEmitter,
            startingExtinction0,
            startingExtinction1,
            startingMediumCount);
}

PrimeShadowTrace primeTraceShadow(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        LightSample light,
        PrimeOpenPbrVolumeStack volumeStack) {
    return primeTraceShadow(
            context,
            surface,
            light,
            uint2(PRIME_NO_PRIMITIVE_INDEX),
            volumeStack);
}

PrimeDirectLightingSplit primeEvaluateVisibleDirectSplit(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        float3 shadingNormal,
        LightSample light,
        float3 lightRadiance,
        PrimeOpenPbrVolumeStack volumeStack,
        bool conditionalTransparentBranch,
        bool usesMis) {
    PrimeDirectLightingSplit result;
    result.diffuse = float3(0.0);
    result.specular = float3(0.0);
    result.direction = light.direction;
    bool transmissive = primeMaterialIsTransmissive(surface.materialControl);
    bool foliage = primeMaterialIsFoliage(surface.materialControl);
    float cosine = transmissive || foliage
            ? abs(dot(shadingNormal, light.direction))
            : max(dot(shadingNormal, light.direction), 0.0);
    if (cosine <= 0.0 || light.pdf <= 0.0
            || all(lightRadiance <= float3(0.0))) {
        return result;
    }
    PrimeNumericalObservation observation = primeBsdfObservationCreate();
    if (transmissive) {
        float3 outwardNormal = primeSurfaceOutwardShadingNormal(surface);
        bool reflection = dot(outwardNormal, viewDirection)
                * dot(outwardNormal, light.direction) >= 0.0;
        BsdfEvaluation evaluation;
        if (conditionalTransparentBranch) {
            // The first visible interface exposes fixed-split conditional proposals. Smooth
            // transmission remains discrete; a rough stained-glass texel has a finite conditional
            // transmission lobe and therefore participates in NEE from the opposite hemisphere.
            evaluation = reflection
                    ? primeEvaluateMinecraftTransparentReflection(
                            surface.baseColor,
                            primeSurfaceOpacity(surface),
                            outwardNormal,
                            surface.materialControl,
                            surface.roughness,
                            surface.opticalControl,
                            viewDirection,
                            light.direction,
                            0.0,
                            volumeStack,
                            surface.adjacentBaseColor,
                            surface.adjacentInterfaceControl,
                            observation)
                    : primeEvaluateMinecraftTransparentTransmission(
                            surface.baseColor,
                            primeSurfaceOpacity(surface),
                            outwardNormal,
                            surface.materialControl,
                            surface.roughness,
                            surface.opticalControl,
                            viewDirection,
                            light.direction,
                            0.0,
                            volumeStack,
                            surface.adjacentBaseColor,
                            surface.adjacentInterfaceControl,
                            observation);
        } else {
            PrimeCompactTransmissionState state = primeMinecraftBoundaryTransmissionState(
                    surface.baseColor,
                    primeSurfaceOpacity(surface),
                    outwardNormal,
                    surface.materialControl,
                    surface.roughness,
                    surface.opticalControl,
                    viewDirection,
                    surface.t,
                    volumeStack,
                    surface.adjacentBaseColor,
                    surface.adjacentInterfaceControl);
            evaluation = primeEvaluateMinecraftTransmissionCompleteFromState(
                    state,
                    surface.baseColor,
                    primeSurfaceOpacity(surface),
                    surface.materialControl,
                    viewDirection,
                    light.direction,
                    observation);
        }
        float weightedInversePdf = primePowerHeuristicOverPdf(
                context.diagnostics, light.pdf, usesMis ? evaluation.pdf : 0.0);
        float3 contribution = primeTripleProduct(
                lightRadiance, evaluation.response, weightedInversePdf);
        if (reflection) {
            result.specular = contribution;
        } else {
            result.diffuse = contribution;
        }
        primeCommitBsdfObservation(context, observation);
        return result;
    }
    if (foliage) {
        PrimeBsdfComponents components = primeEvaluateMinecraftFoliageComponents(
                surface.baseColor,
                shadingNormal,
                surface.roughness,
                surface.opticalControl,
                surface.materialControl,
                viewDirection,
                light.direction,
                0.0,
                volumeStack,
                observation);
        float weightedInversePdf = primePowerHeuristicOverPdf(
                context.diagnostics, light.pdf, usesMis ? components.pdf : 0.0);
        result.diffuse = primeTripleProduct(
                lightRadiance, components.diffuseResponse, weightedInversePdf);
        result.specular = primeTripleProduct(
                lightRadiance, components.specularResponse, weightedInversePdf);
        primeCommitBsdfObservation(context, observation);
        return result;
    }
    // The translation layer supplies conservative defaults when no community material data is
    // authored. Material provenance must not select a second BSDF: every ordinary opaque surface
    // uses the compact OpenPBR white-furnace-tested BasicMetallic composition.
    PrimeBsdfComponents components = primeEvaluateOpaqueComponents(
            surface.baseColor,
            shadingNormal,
            surface.roughness,
            surface.opticalControl,
            surface.materialControl,
            viewDirection,
            light.direction,
            0.0,
            volumeStack,
            observation);
    float weightedInversePdf = primePowerHeuristicOverPdf(
            context.diagnostics, light.pdf, usesMis ? components.pdf : 0.0);
    result.diffuse = primeTripleProduct(
            lightRadiance, components.diffuseResponse, weightedInversePdf);
    result.specular = primeTripleProduct(
            lightRadiance, components.specularResponse, weightedInversePdf);
    primeCommitBsdfObservation(context, observation);
    return result;
}

float3 primeEvaluateCompleteDirect(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        float3 shadingNormal,
        LightSample light,
        float3 lightRadiance,
        PrimeOpenPbrVolumeStack volumeStack,
        bool usesMis) {
    PrimeDirectLightingSplit split = primeEvaluateVisibleDirectSplit(
            context,
            surface,
            viewDirection,
            shadingNormal,
            light,
            lightRadiance,
            volumeStack,
            false,
            usesMis);
    return split.diffuse + split.specular;
}

bool primeDirectSampleEligible(
        SurfaceInteraction surface,
        float3 shadingNormal,
        LightSample light) {
    return light.pdf > 0.0
            && (primeMaterialIsTransmissive(surface.materialControl)
                    || primeMaterialIsFoliage(surface.materialControl)
                    || dot(shadingNormal, light.direction) > 0.0);
}

struct PrimePrimarySunSample {
    PrimeDirectLightingSplit lighting;
    float penumbra;
    float visibility;
};

float primeSigmaPackDirectionalPenumbra(float distanceToOccluder) {
    distanceToOccluder = primeNrdSanitizeHitDistance(distanceToOccluder);
    if (distanceToOccluder >= PRIME_NRD_FP16_MAX) {
        return PRIME_NRD_FP16_MAX;
    }
    float penumbraRadius = 0.5 * distanceToOccluder
            * tan(ATM_SUN_ANGULAR_RADIUS_RADIANS);
    return min(max(penumbraRadius, 0.0), 32768.0);
}

PrimePrimarySunSample primeEstimatePrimaryDirectSun(
        inout PrimeIntegratorContext context,
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        float3 viewDirection,
        float2 sampleValue,
        PrimeOpenPbrVolumeStack volumeStack,
        bool conditionalTransparentBranch) {
    PrimePrimarySunSample result;
    result.lighting.diffuse = float3(0.0);
    result.lighting.specular = float3(0.0);
    result.lighting.direction = float3(0.0);
    result.penumbra = 0.0;
    result.visibility = 0.0;
    LightSample light = primeSampleSun(integrator, surface.position, sampleValue);
    if (!primeAtmosphereDistantDirectionVisible(light.direction)) {
        return result;
    }
    float3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
    if (!primeDirectSampleEligible(surface, shadingNormal, light)) {
        return result;
    }
    PrimeShadowTrace shadow = primeTraceShadow(
            context, surface, light, volumeStack);
    result.penumbra = primeSigmaPackDirectionalPenumbra(shadow.hitDistance);
    result.visibility = float(shadow.hitDistance >= PRIME_NRD_FP16_MAX);
    float3 radiance = primeResolveSampledSunRadiance(
            integrator, surface.position, light) * shadow.transmittance;
    result.lighting = primeEvaluateVisibleDirectSplit(
            context,
            surface,
            viewDirection,
            shadingNormal,
            light,
            radiance,
            volumeStack,
            conditionalTransparentBranch,
            true);
    return result;
}

PrimeDirectLightingSplit primeEstimatePrimaryDirectPreparedAreaLight(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        AreaLightSample area,
        PrimeOpenPbrVolumeStack volumeStack,
        bool conditionalTransparentBranch) {
    float3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
    if (!primeDirectSampleEligible(surface, shadingNormal, area.light)) {
        PrimeDirectLightingSplit result;
        result.diffuse = float3(0.0);
        result.specular = float3(0.0);
        result.direction = float3(0.0);
        return result;
    }
    PrimeShadowTrace shadow = primeTraceShadow(
            context,
            surface,
            area.light,
            uint2(area.sectionIndex, area.emitterIndex),
            volumeStack);
    if (shadow.hitDistance < PRIME_NRD_FP16_MAX) {
        PrimeDirectLightingSplit result;
        result.diffuse = float3(0.0);
        result.specular = float3(0.0);
        result.direction = float3(0.0);
        return result;
    }
    float3 radiance =
            primeResolveSampledAreaLightRadiance(area) * shadow.transmittance;
    return primeEvaluateVisibleDirectSplit(
            context,
            surface,
            viewDirection,
            shadingNormal,
            area.light,
            radiance,
            volumeStack,
            conditionalTransparentBranch,
            false);
}

PrimeDirectLightingSplit primeEstimatePrimaryDirectAreaLight(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        PrimePreparedSampleBase preparedSample,
        PrimeOpenPbrVolumeStack volumeStack,
        bool conditionalTransparentBranch) {
    AreaLightSample area = primeSampleAreaLight(
            context.diagnostics,
            surface.position,
            primeAreaLightReceiverNormal(surface, viewDirection),
            primeSobolSample2D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
                    PRIME_SAMPLE_DIMENSION_PRIMARY),
            primeSobolSample2D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
                    PRIME_SAMPLE_DIMENSION_SECONDARY));
    return primeEstimatePrimaryDirectPreparedAreaLight(
            context,
            surface,
            viewDirection,
            area,
            volumeStack,
            conditionalTransparentBranch);
}

float3 primeEstimateDirectPreparedAreaLight(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        AreaLightSample area,
        PrimeOpenPbrVolumeStack volumeStack,
        float lightFamilyProbability) {
    float3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
    area.light.pdf = primeLightFamilyPdfValue(
            area.light.pdf, lightFamilyProbability);
    if (!primeDirectSampleEligible(surface, shadingNormal, area.light)) {
        return float3(0.0);
    }
    PrimeShadowTrace shadow = primeTraceShadow(
            context,
            surface,
            area.light,
            uint2(area.sectionIndex, area.emitterIndex),
            volumeStack);
    if (shadow.hitDistance < PRIME_NRD_FP16_MAX) {
        return float3(0.0);
    }
    float3 radiance =
            primeResolveSampledAreaLightRadiance(area) * shadow.transmittance;
    return primeEvaluateCompleteDirect(
            context,
            surface,
            viewDirection,
            shadingNormal,
            area.light,
            radiance,
            volumeStack,
            true);
}

float3 primeEstimateDirectAreaLight(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        float2 treeSample,
        float2 positionSample,
        PrimeOpenPbrVolumeStack volumeStack,
        float lightFamilyProbability) {
    AreaLightSample area = primeSampleAreaLight(
            context.diagnostics,
            surface.position,
            primeAreaLightReceiverNormal(surface, viewDirection),
            treeSample,
            positionSample);
    return primeEstimateDirectPreparedAreaLight(
            context,
            surface,
            viewDirection,
            area,
            volumeStack,
            lightFamilyProbability);
}

float3 primeEstimateDirectSun(
        inout PrimeIntegratorContext context,
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        float3 viewDirection,
        float2 sampleValue,
        PrimeOpenPbrVolumeStack volumeStack,
        float lightFamilyProbability) {
    LightSample light = primeSampleSun(integrator, surface.position, sampleValue);
    light.pdf = primeLightFamilyPdfValue(
            light.pdf, lightFamilyProbability);
    if (!primeAtmosphereDistantDirectionVisible(light.direction)) {
        return float3(0.0);
    }
    float3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
    if (!primeDirectSampleEligible(surface, shadingNormal, light)) {
        return float3(0.0);
    }
    PrimeShadowTrace shadow = primeTraceShadow(
            context, surface, light, volumeStack);
    if (shadow.hitDistance < PRIME_NRD_FP16_MAX) {
        return float3(0.0);
    }
    float3 radiance = primeResolveSampledSunRadiance(
            integrator, surface.position, light) * shadow.transmittance;
    return primeEvaluateCompleteDirect(
            context,
            surface,
            viewDirection,
            shadingNormal,
            light,
            radiance,
            volumeStack,
            true);
}

struct PrimePreparedSunShadow {
    LightSample light;
    float3 contribution;
    bool active;
};

PrimePreparedSunShadow primePrepareDirectSunShadow(
        inout PrimeIntegratorContext context,
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        float3 viewDirection,
        float2 sampleValue,
        PrimeOpenPbrVolumeStack volumeStack,
        float lightFamilyProbability) {
    PrimePreparedSunShadow result;
    result.light = primeSampleSun(integrator, surface.position, sampleValue);
    result.light.pdf = primeLightFamilyPdfValue(
            result.light.pdf, lightFamilyProbability);
    result.contribution = float3(0.0);
    result.active = false;
    if (!primeAtmosphereDistantDirectionVisible(result.light.direction)) {
        return result;
    }
    float3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
    if (!primeDirectSampleEligible(surface, shadingNormal, result.light)) {
        return result;
    }
    float3 radiance = primeResolveSampledSunRadiance(
            integrator, surface.position, result.light);
    PrimeDirectLightingSplit split = primeEvaluateVisibleDirectSplit(
            context,
            surface,
            viewDirection,
            shadingNormal,
            result.light,
            radiance,
            volumeStack,
            false,
            true);
    result.contribution = split.diffuse + split.specular;
    result.active = any(result.contribution > float3(0.0));
    return result;
}

PrimeDirectLightingSplit primeEstimatePrimaryNonsunDirect(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        PrimePreparedSampleBase preparedSample,
        PrimeOpenPbrVolumeStack volumeStack,
        bool conditionalTransparentBranch) {
    // Stars are evaluated only when the continuation ray misses. Primary non-sun guides therefore
    // contain only the explicitly sampled area-light family.
    return primeEstimatePrimaryDirectAreaLight(
            context,
            surface,
            viewDirection,
            preparedSample,
            volumeStack,
            conditionalTransparentBranch);
}

interface IPrimeSecondaryAreaNee {
    bool enabled();
    void markSecondaryPath(
            inout PathState path,
            IntegratorRecord integrator,
            SurfaceInteraction surface,
            float3 viewDirection,
            uint storedSunProbability);

    PrimeDirectLightingSplit estimatePrimary(
            inout PrimeIntegratorContext context,
            SurfaceInteraction surface,
            float3 viewDirection,
            PrimePreparedSampleBase preparedSample,
            PrimeOpenPbrVolumeStack volumeStack,
            bool conditionalTransparentBranch);

    float3 estimate(
            inout PrimeIntegratorContext context,
            SurfaceInteraction surface,
            float3 viewDirection,
            PrimePreparedSampleBase preparedSample,
            PrimeOpenPbrVolumeStack volumeStack);
}

interface IPrimeSecondarySunNee {
    float3 estimate(
            inout PrimeIntegratorContext context,
            IntegratorRecord integrator,
            SurfaceInteraction surface,
            float3 viewDirection,
            PrimePreparedSampleBase preparedSample,
            PrimeOpenPbrVolumeStack volumeStack,
            float3 pathThroughput,
            uint pathIndex);
}

struct PrimeImmediateSecondarySunNee : IPrimeSecondarySunNee {
    float3 estimate(
            inout PrimeIntegratorContext context,
            IntegratorRecord integrator,
            SurfaceInteraction surface,
            float3 viewDirection,
            PrimePreparedSampleBase preparedSample,
            PrimeOpenPbrVolumeStack volumeStack,
            float3 pathThroughput,
            uint pathIndex) {
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
}

struct PrimeImmediateSecondaryAreaNee : IPrimeSecondaryAreaNee {
    bool enabled() { return true; }
    void markSecondaryPath(
            inout PathState path,
            IntegratorRecord integrator,
            SurfaceInteraction surface,
            float3 viewDirection,
            uint storedSunProbability) {}

    PrimeDirectLightingSplit estimatePrimary(
            inout PrimeIntegratorContext context,
            SurfaceInteraction surface,
            float3 viewDirection,
            PrimePreparedSampleBase preparedSample,
            PrimeOpenPbrVolumeStack volumeStack,
            bool conditionalTransparentBranch) {
        return primeEstimatePrimaryNonsunDirect(
                context,
                surface,
                viewDirection,
                preparedSample,
                volumeStack,
                conditionalTransparentBranch);
    }

    float3 estimate(
            inout PrimeIntegratorContext context,
            SurfaceInteraction surface,
            float3 viewDirection,
            PrimePreparedSampleBase preparedSample,
            PrimeOpenPbrVolumeStack volumeStack) {
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
                1.0);
    }
}

struct PrimeStagedSecondaryAreaNee : IPrimeSecondaryAreaNee {
    bool enabled() { return true; }
    void markSecondaryPath(
            inout PathState path,
            IntegratorRecord integrator,
            SurfaceInteraction surface,
            float3 viewDirection,
            uint storedSunProbability) {
        path.sourceReserved = storedSunProbability != 0u
                ? storedSunProbability
                : primeStoredSunFamilyProbability(
                        primeSecondarySunFamilyProbability(
                                integrator, surface, viewDirection));
    }

    PrimeDirectLightingSplit estimatePrimary(
            inout PrimeIntegratorContext context,
            SurfaceInteraction surface,
            float3 viewDirection,
            PrimePreparedSampleBase preparedSample,
            PrimeOpenPbrVolumeStack volumeStack,
            bool conditionalTransparentBranch) {
        return primeEstimatePrimaryNonsunDirect(
                context,
                surface,
                viewDirection,
                preparedSample,
                volumeStack,
                conditionalTransparentBranch);
    }

    float3 estimate(
            inout PrimeIntegratorContext context,
            SurfaceInteraction surface,
            float3 viewDirection,
            PrimePreparedSampleBase preparedSample,
            PrimeOpenPbrVolumeStack volumeStack) {
        // The merged Light stage consumes the persisted hit with this exact pre-scatter path.
        return float3(0.0);
    }
}

struct PrimeNoSecondaryAreaNee : IPrimeSecondaryAreaNee {
    bool enabled() { return false; }
    void markSecondaryPath(
            inout PathState path,
            IntegratorRecord integrator,
            SurfaceInteraction surface,
            float3 viewDirection,
            uint storedSunProbability) {}

    PrimeDirectLightingSplit estimatePrimary(
            inout PrimeIntegratorContext context,
            SurfaceInteraction surface,
            float3 viewDirection,
            PrimePreparedSampleBase preparedSample,
            PrimeOpenPbrVolumeStack volumeStack,
            bool conditionalTransparentBranch) {
        PrimeDirectLightingSplit result;
        result.diffuse = float3(0.0);
        result.specular = float3(0.0);
        result.direction = float3(0.0);
        return result;
    }

    float3 estimate(
            inout PrimeIntegratorContext context,
            SurfaceInteraction surface,
            float3 viewDirection,
            PrimePreparedSampleBase preparedSample,
            PrimeOpenPbrVolumeStack volumeStack) {
        return float3(0.0);
    }
}

float3 primeEstimateDirectLighting<SunNee, AreaNee>(
        SunNee sunNee,
        AreaNee areaNee,
        inout PrimeIntegratorContext context,
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        float3 viewDirection,
        PrimePreparedSampleBase preparedSample,
        PrimeOpenPbrVolumeStack volumeStack,
        float3 pathThroughput,
        uint pathIndex)
    where SunNee : IPrimeSecondarySunNee
    where AreaNee : IPrimeSecondaryAreaNee
{
    float3 result = sunNee.estimate(
            context,
            integrator,
            surface,
            viewDirection,
            preparedSample,
            volumeStack,
            pathThroughput,
            pathIndex);
    result += areaNee.estimate(
            context,
            surface,
            viewDirection,
            preparedSample,
            volumeStack);
    return result;
}

bool primeRussianRoulette(
        inout PrimeIntegratorContext context,
        inout PathState path,
        float sampleValue) {
    float survival = primeRussianRouletteSurvival(
            path.throughput, path.etaScale);
    primeRecordUnit(context.diagnostics, survival);
    if (sampleValue >= survival) {
        return false;
    }
    path.throughput = primeRussianRouletteReweight(path.throughput, survival);
    primeRecordNonnegative(context.diagnostics, path.throughput);
    return true;
}

// Renderer-specific integrators compose these terminal estimators. Their caller supplies whether
// the competing Area technique existed, so realtime and offline can keep independent NEE rules.
float3 primeEvaluateEnvironmentContribution(
        inout PrimeIntegratorContext context,
        PathState path, IntegratorRecord integrator) {
    primeSetNumericalContext(
            context.diagnostics, PRIME_NUMERICAL_STAGE_EMISSION, path.bounce);
    float3 stars = primeStarmapRadiance(
            integrator, path.physicalOrigin, path.rayDirection);
    LightEvaluation sun = primeEvaluateSun(
            integrator, path.physicalOrigin, path.rayDirection);
    if (primeHasStoredLightFamilyProbability(path.sourceReserved)) {
        sun.pdf = primeLightFamilyPdfValue(
                sun.pdf, primeSunFamilyProbabilityFromStored(path.sourceReserved));
    }
    bool previousUsedSunNee = primePreviousSunNeeEnabled(path.flags);
    float sunWeight = primePreviousCannotUseMis(path) || !previousUsedSunNee
            ? 1.0
            : primePowerHeuristic(
                    context.diagnostics, path.previousBsdfPdf, sun.pdf);
    return path.throughput
            * (primeEnvironmentRadiance(integrator, path.rayDirection)
            + stars
            + sun.radiance * sunWeight);
}

float3 primeEvaluateLocalHitEmission(
        inout PrimeIntegratorContext context,
        PathState path,
        SurfaceInteraction surface,
        bool previousUsedAreaNee) {
    primeSetNumericalContext(
            context.diagnostics, PRIME_NUMERICAL_STAGE_EMISSION, path.bounce);
    if ((surface.materialControl & PRIME_MATERIAL_VISIBLE_EMISSION) != 0u) {
        return surface.baseColor
                * PRIME_LEVEL_15_BLOCK_INTENSITY
                * primeBlockLightRadianceMultiplier();
    }
    // Reconstruct the two-level reverse tree PDF only when the previous renderer-specific
    // transport vertex actually had a competing Area NEE technique.
    bool evaluateAreaPdf = previousUsedAreaNee
            && !primePreviousCannotUseMis(path);
    LightEvaluation hitAreaLight = primeEvaluateAreaLight(
            context.diagnostics,
            surface,
            path.physicalOrigin,
            path.rayDirection,
            path.previousLightNormal,
            evaluateAreaPdf);
    if (primeHasStoredLightFamilyProbability(path.sourceReserved)) {
        hitAreaLight.pdf = primeLightFamilyPdfValue(
                hitAreaLight.pdf, primeAreaFamilyProbabilityFromStored(path.sourceReserved));
    }
    float hitAreaWeight = primeAreaHitMisWeightValue(
            previousUsedAreaNee,
            !evaluateAreaPdf,
            path.previousBsdfPdf,
            hitAreaLight.pdf);
    return hitAreaLight.radiance * hitAreaWeight;
}

float3 primeEvaluateHitEmission(
        inout PrimeIntegratorContext context,
        PathState path,
        SurfaceInteraction surface,
        bool previousUsedAreaNee) {
    return path.throughput * primeEvaluateLocalHitEmission(
            context, path, surface, previousUsedAreaNee);
}

struct PrimeIndirectReuseResult {
    float3 contribution;
    bool hit;
};

interface IPrimeIndirectReuse {
    PrimeIndirectReuseResult query(
            inout PrimeIntegratorContext context,
            PathState path,
            SurfaceInteraction surface,
            bool previousUsedAreaNee);
}

struct PrimeNoIndirectReuse : IPrimeIndirectReuse {
    PrimeIndirectReuseResult query(
            inout PrimeIntegratorContext context,
            PathState path,
            SurfaceInteraction surface,
            bool previousUsedAreaNee) {
        PrimeIndirectReuseResult result;
        result.contribution = float3(0.0);
        result.hit = false;
        return result;
    }
}

bool primeAdvancePath(
        inout PrimeIntegratorContext context,
        inout PathState path,
        SurfaceInteraction surface,
        BsdfSample bsdf,
        PrimePreparedSampleBase bounceSample,
        bool discreteOnlyInterface,
        float surfaceLinearRoughness,
        bool skipRoulette) {
    uint previousLightNormal = discreteOnlyInterface
            ? 0u
            : primeAreaLightReceiverNormal(surface, -path.rayDirection);
    if (!primeAdvancePathWithoutRoulette(
            context,
            path,
            surface,
            bsdf,
            previousLightNormal,
            surfaceLinearRoughness)) {
        return false;
    }
    if (skipRoulette) {
        return true;
    }
    // Two-stage realtime has already completed its non-delta bridge scatter before steady bounce
    // zero. Count that scatter for RR without folding primary delta depth into the steady bounce
    // domain or reducing its independent dispatch limit.
    uint rouletteCompletedScatters = path.bounce;
#if defined(PRIME_TWO_STAGE_WAVEFRONT)
    rouletteCompletedScatters =
            primeTwoStageRouletteCompletedScatters(path.bounce);
#endif
    if (!primeRussianRouletteApplies(
            rouletteCompletedScatters, PRIME_RUSSIAN_ROULETTE_START)) {
        return true;
    }
    float rouletteSample = primeHashSample1D(
            bounceSample,
            PRIME_SAMPLE_EFFECT_RUSSIAN_ROULETTE,
            PRIME_SAMPLE_DIMENSION_PRIMARY);
    return primeRussianRoulette(context, path, rouletteSample);
}

bool primeAdvancePath(
        inout PrimeIntegratorContext context,
        inout PathState path,
        SurfaceInteraction surface,
        BsdfSample bsdf,
        PrimePreparedSampleBase bounceSample,
        bool discreteOnlyInterface,
        float surfaceLinearRoughness) {
    return primeAdvancePath(
            context,
            path,
            surface,
            bsdf,
            bounceSample,
            discreteOnlyInterface,
            surfaceLinearRoughness,
            false);
}

// Queue-resident PSR state stores the bounded discrete chain as an incremental path length and
// orthogonal direction transform. The last discrete position is exactly path.physicalOrigin at every
// queue boundary, so keeping a second float3 would increase both slot size and live registers.

float primeQueuedPsrAnchorDistance(
        PrimeQueuedPsrState state,
        float3 target,
        float3 targetNormal) {
    return primeQueuedPsrAnchorDistanceValue(
            state,
            primePush.cameraPosition,
            target,
            targetNormal);
}

PrimeDenoiserGuides primeQueuedPsrGuide(
        PrimeDenoiserGuides guides,
        SurfaceInteraction surface,
        float3 shadingNormal,
        float linearRoughness,
        PrimeDenoiseAlbedos albedos,
        float3 guideThroughput,
        float3 lastPosition,
        PrimeQueuedPsrState state) {
    float3 position = surface.position - primePush.cameraPosition;
    float3 normal = shadingNormal;
    float3 virtualPosition;
    float3 virtualNormal;
    if (primeBuildQueuedPsrGuideValue(
            state,
            lastPosition,
            surface.position,
            normal,
            virtualPosition,
            virtualNormal)) {
        position = virtualPosition;
        normal = virtualNormal;
    }
    guides.primaryDistance = length(position);
    guides.primaryPosition = position;
    guides.primaryPreviousPosition = position;
    guides.primaryHasMotion = false;
    guides.primaryAlbedo = guideThroughput * albedos.diffuse;
    guides.primaryNormal = normal;
    guides.primaryHitKind = surface.hitKind;
    guides.primaryMaterialFlags = primeNrdGuideControl(
            surface.materialControl, surface.opticalControl);
    guides.primarySpecularAlbedo = guideThroughput * albedos.specular;
    guides.primaryLinearRoughness = linearRoughness;
    return guides;
}

// Advances one fixed primary-transparent branch by one queued vertex. Its signal lane remains
// branch-owned while traversal/SER sorts both continuations with every other path.
bool primeIntegrateTransparentWavefrontSurface<SunNee, AreaNee>(
        SunNee sunNee,
        AreaNee areaNee,
        inout PrimeIntegratorContext context,
        inout PathState path,
        IntegratorRecord integrator,
        inout PrimeOpenPbrVolumeStack volumeStack,
        inout PrimeTransparentBranchResult result,
        inout PrimeQueuedPsrState psrState,
        inout bool hasGuide,
        inout bool diffusePath,
        inout uint guideBounce,
        inout bool directionalGuide,
        inout bool guideProbeFallback,
        SurfaceInteraction surface,
        bool transmissionBranch,
        bool guideEnabled,
        uint storedSunProbability,
        bool skipRoulette,
        uint pathIndex)
    where SunNee : IPrimeSecondarySunNee
    where AreaNee : IPrimeSecondaryAreaNee
{
    primeSetNumericalContext(
            context.diagnostics, PRIME_NUMERICAL_STAGE_SURFACE, path.bounce);
    primeRecordNonFinite(context.diagnostics, surface.position);
    primeRecordNonnegative(context.diagnostics, surface.t);
    primeRecordDirection(context.diagnostics, surface.geometricNormal);
    primeRecordUnit(context.diagnostics, surface.baseColor);
    if (!primeKnownHitKind(surface)) {
        return false;
    }

    bool observesIndependentGuide = guideEnabled
            && !hasGuide
            && !guideProbeFallback;
#if !defined(PRIME_TWO_STAGE_WAVEFRONT)
    if (observesIndependentGuide
            && surface.hitKind == PRIME_HIT_SURFACE
            && primeSurfaceHasMotion(surface)) {
        // The queue carries only current-frame PSR. Preserve the existing dual current/previous
        // probe whenever a moving surface enters the unresolved discrete chain.
        guideProbeFallback = true;
    }
#endif

    float hitDistance = surface.hitKind == PRIME_HIT_NONE
            ? PRIME_NRD_FP16_MAX
            : primeNrdSanitizeHitDistance(surface.t);
    if (path.bounce ==
#if defined(PRIME_STEADY_WAVEFRONT)
            0u
#else
            1u
#endif
    ) {
        result.firstHitDistance = hitDistance;
    }
    if (hasGuide && path.bounce == guideBounce + 1u) {
        if (diffusePath) {
            result.guides.diffuseHitDistance = hitDistance;
        } else {
            result.guides.specularHitDistance = hitDistance;
        }
    }
    if (surface.hitKind == PRIME_HIT_NONE) {
        if (guideEnabled && !transmissionBranch && !hasGuide
                && !guideProbeFallback
                && primeQueuedPsrCount(psrState) > 0u
                && !primeQueuedPsrOverflowed(psrState)) {
            float3 direction =
                    primeQueuedPsrVirtualDirection(psrState, path.rayDirection);
            float lengthSquared = dot(direction, direction);
            if (primeNrdIsFinite(direction) && primeNrdIsFinite(lengthSquared)
                    && lengthSquared > 1.0e-12) {
                result.guides.primaryPosition =
                        direction * rsqrt(lengthSquared);
                directionalGuide = true;
            }
        }
        primeAccumulateTransparentBranch(
                context,
                result,
                guideEnabled ? diffusePath : transmissionBranch,
                primeEvaluateEnvironmentContribution(context, path, integrator));
        return false;
    }
    if (!primeApplySegmentMedium(context, path, surface, volumeStack)) {
        return false;
    }
    if (observesIndependentGuide
            && !guideProbeFallback
            && !primeNrdIsFinite(path.throughput)) {
        guideProbeFallback = true;
    }

    float3 viewDirection = -path.rayDirection;
    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
    bool transmissive = primeMaterialIsTransmissive(surface.materialControl);
    float surfaceLinearRoughness = (transmissive || !hasGuide)
            ? primeSurfaceLinearRoughness(surface)
            : PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS;
    PrimeClosureTraits closureTraits = primeSurfaceClosureTraits(
            surface, viewDirection, volumeStack);
    bool supportsSolidAngle = primeClosureSupports(
            closureTraits, PRIME_MEASURE_SOLID_ANGLE);
    bool discreteOnlyInterface = primeClosureIsDiscreteOnly(closureTraits);
    bool discreteContinuation = discreteOnlyInterface;
    bool primarySurfaceReplacement =
            observesIndependentGuide && !discreteContinuation;
    bool contributionDiffuse = guideEnabled
            ? (primarySurfaceReplacement || diffusePath)
            : transmissionBranch;
    bool previousUsedAreaNee = areaNee.enabled()
#if !defined(PRIME_STEADY_WAVEFRONT)
            && path.bounce > 0u
#endif
            ;
    float3 emitted = primeEvaluateHitEmission(
            context, path, surface, previousUsedAreaNee);
    primeAccumulateTransparentBranch(
            context, result, contributionDiffuse, emitted);

    if (supportsSolidAngle && !primarySurfaceReplacement) {
        // Wavefront Step evaluates primary transparent direct lighting after its surface trace,
        // keeping both shadow traces out of this register-heavy BSDF continuation.
        primeSetNumericalContext(
                context.diagnostics, PRIME_NUMERICAL_STAGE_DIRECT_LIGHT, path.bounce);
        float3 direct = path.throughput * primeEstimateDirectLighting(
                sunNee,
                areaNee,
                context,
                integrator,
                surface,
                viewDirection,
                preparedSample,
                volumeStack,
                path.throughput,
                pathIndex);
        primeAccumulateTransparentBranch(
                context,
                result,
                guideEnabled ? diffusePath : transmissionBranch,
                direct);
    }

    float3 scatterSample = primeSobolSample3D(
            preparedSample,
            PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
            PRIME_SAMPLE_DIMENSION_PRIMARY);
    PrimePathScatter scatter;
    bool compareGuideContinuation = observesIndependentGuide
            && !guideProbeFallback
            && discreteContinuation;
    bool guideContinuationReusable = true;
    PrimeDenoiseAlbedos surfaceAlbedos;
    surfaceAlbedos.diffuse = float3(0.0);
    surfaceAlbedos.specular = float3(0.0);
    if (primarySurfaceReplacement) {
        PrimeGuidedPathScatter guided = primeSampleGuidedPathSurface(
                context,
                surface,
                viewDirection,
                scatterSample,
                volumeStack,
                path.bounce);
        scatter = guided.scatter;
        surfaceAlbedos = guided.albedos;
        result.guides = primeQueuedPsrGuide(
                result.guides,
                surface,
                primeSurfaceShadingNormal(surface, viewDirection),
                surfaceLinearRoughness,
                surfaceAlbedos,
                path.throughput,
                path.physicalOrigin,
                psrState);
        if (transmissionBranch) {
            result.anchorDistance = primeQueuedPsrAnchorDistance(
                    psrState, surface.position, surface.geometricNormal);
        }
        hasGuide = true;
        guideBounce = path.bounce;
    } else {
        primeSetNumericalContext(
                context.diagnostics, PRIME_NUMERICAL_STAGE_BSDF_SAMPLE, path.bounce);
        scatter = primeSamplePathSurface(
                context, surface, viewDirection, scatterSample, volumeStack);
        if (compareGuideContinuation) {
            PrimePathScatter guideScatter;
            guideScatter.bsdf = primeInvalidBsdfSample();
            guideScatter.volumeStack = volumeStack;
            if (transmissive) {
                PrimeTransmissiveBsdfSample guideSample =
                        primeSampleMinecraftTransparentContinuation(
                                surface.baseColor,
                                primeSurfaceOpacity(surface),
                                primeSurfaceOutwardShadingNormal(surface),
                                surface.materialControl,
                                surface.roughness,
                                surface.opticalControl,
                                viewDirection,
                                surface.t,
                                volumeStack,
                                surface.adjacentBaseColor,
                                surface.adjacentInterfaceControl);
                guideScatter.bsdf = guideSample.bsdfSample;
                guideScatter.volumeStack = guideSample.volumeStack;
            } else {
                PrimeNumericalDiagnostics savedDiagnostics = context.diagnostics;
                guideScatter = primeSamplePathSurface(
                        context,
                        surface,
                        viewDirection,
                        float3(0.5),
                        volumeStack);
                context.diagnostics = savedDiagnostics;
            }
            guideContinuationReusable = primeTransparentGuideContinuationReusable(
                    scatter.bsdf,
                    scatter.volumeStack,
                    guideScatter.bsdf,
                    guideScatter.volumeStack);
        }
    }
    BsdfSample bsdf = scatter.bsdf;
    if (compareGuideContinuation && !guideContinuationReusable) {
#if !defined(PRIME_TWO_STAGE_WAVEFRONT)
        guideProbeFallback = true;
#endif
    }
    if (!primeHasScatter(bsdf)) {
        return false;
    }
    primeRecordDirection(context.diagnostics, bsdf.direction);
    primeRecordNonnegative(context.diagnostics, bsdf.response);
    primeRecordNonnegative(context.diagnostics, bsdf.pdf);
    primeRecordNonnegative(context.diagnostics, bsdf.relativeEta);
    if (primarySurfaceReplacement) {
        diffusePath = (bsdf.eventFlags & PRIME_BSDF_EVENT_DIFFUSE) != 0u;
        float3 virtualDirection =
                primeQueuedPsrVirtualDirection(psrState, bsdf.direction);
        if (diffusePath) {
            result.guides.diffuseDirection = virtualDirection;
        } else {
            result.guides.specularDirection = virtualDirection;
        }
    } else if (guideEnabled && !hasGuide && discreteContinuation
            && (bsdf.eventFlags & PRIME_BSDF_EVENT_DISCRETE) != 0u) {
        psrState = primeAppendQueuedPsrDiscrete(
                psrState, path.physicalOrigin, surface, bsdf);
    }
    volumeStack = scatter.volumeStack;
    if (!primeAdvancePath(
            context,
            path,
            surface,
            bsdf,
            preparedSample,
            discreteContinuation,
            surfaceLinearRoughness,
            skipRoulette || primeTransparentRouletteBlocked(
                    primeTransparentTransportOwnsGuide(
                            false,
                            guideProbeFallback),
                    guideEnabled,
                    hasGuide))) {
        return false;
    }
    if (areaNee.enabled() && supportsSolidAngle
            && !primarySurfaceReplacement) {
        areaNee.markSecondaryPath(
                path,
                integrator,
                surface,
                viewDirection,
                storedSunProbability);
    }
    path.bounce++;
    return true;
}

PrimeDenoiserState primeInitialDenoiserState() {
    PrimeDenoiserState state;
    state.hasPrimarySurface = false;
    state.reachedSolidAngle = false;
    state.diffuseAlbedoProduct = float3(1.0);
    state.specularAlbedoProduct = float3(0.0);
    state.diffusePath = false;
    state.primaryBounce = 0u;
    return state;
}

// Processes exactly one ordinary realtime path vertex. Primary transparent interfaces remain a
// local island because two reconstruction records share one output pixel. Every other
// continuation can cross this boundary without carrying invocation-local BSDF state.
bool primeIntegrateWavefrontSurface<SunNee, AreaNee, IndirectReuse>(
        SunNee sunNee,
        AreaNee areaNee,
        IndirectReuse indirectReuse,
        inout PrimeIntegratorContext context,
        inout PathState path,
        IntegratorRecord integrator,
        inout PrimeOpenPbrVolumeStack volumeStack,
        inout PrimeDenoiserState denoiserState,
        inout PrimeIntegrationResult result,
        SurfaceInteraction surface,
        uint storedSunProbability,
        bool skipRoulette,
        uint pathIndex)
    where SunNee : IPrimeSecondarySunNee
    where AreaNee : IPrimeSecondaryAreaNee
    where IndirectReuse : IPrimeIndirectReuse
{
    primeSetNumericalContext(
            context.diagnostics, PRIME_NUMERICAL_STAGE_SURFACE, path.bounce);
    primeRecordNonFinite(context.diagnostics, surface.position);
    primeRecordNonnegative(context.diagnostics, surface.t);
    primeRecordDirection(context.diagnostics, surface.geometricNormal);
    primeRecordUnit(context.diagnostics, surface.baseColor);
    if (!primeKnownHitKind(surface)) {
        return false;
    }

    if (denoiserState.hasPrimarySurface
            && path.bounce ==
#if defined(PRIME_STEADY_WAVEFRONT)
                    0u
#else
                    denoiserState.primaryBounce + 1u
#endif
    ) {
        float firstBounceHitDistance = surface.hitKind == PRIME_HIT_NONE
                ? PRIME_NRD_FP16_MAX
                : primeNrdSanitizeHitDistance(surface.t);
        if (denoiserState.diffusePath) {
            result.guides.diffuseHitDistance = firstBounceHitDistance;
        } else {
            result.guides.specularHitDistance = firstBounceHitDistance;
        }
    }
    if (surface.hitKind == PRIME_HIT_NONE) {
        float3 contribution = primeEvaluateEnvironmentContribution(
                context, path, integrator);
        if (!denoiserState.hasPrimarySurface) {
            primeAccumulate(context, result.radiance.stable, contribution);
        } else {
            primeAccumulateAfterPrimary(
                    context, result, denoiserState.diffusePath, contribution);
        }
        return false;
    }
    if (!primeApplySegmentMedium(context, path, surface, volumeStack)) {
        return false;
    }


    PrimeIndirectReuseResult reuse;
    reuse.contribution = float3(0.0);
    reuse.hit = false;
    if (denoiserState.hasPrimarySurface) {
        reuse = indirectReuse.query(
                    context,
                    path,
                    surface,
                    denoiserState.hasPrimarySurface);
    }
    if (reuse.hit) {
        primeAccumulateAfterPrimary(
                context,
                result,
                denoiserState.diffusePath,
                reuse.contribution);
        return false;
    }

    float3 viewDirection = -path.rayDirection;
    bool previousUsedAreaNee =
            areaNee.enabled() && denoiserState.hasPrimarySurface;
    float3 emitted = primeEvaluateHitEmission(
            context, path, surface, previousUsedAreaNee);
    if (!denoiserState.hasPrimarySurface) {
        primeAccumulate(context, result.radiance.stable, emitted);
    } else {
        primeAccumulateAfterPrimary(
                context, result, denoiserState.diffusePath, emitted);
    }

    bool transmissive = primeMaterialIsTransmissive(surface.materialControl);
    float surfaceLinearRoughness =
            (transmissive || !denoiserState.reachedSolidAngle)
            ? primeSurfaceLinearRoughness(surface)
            : PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS;
    PrimeClosureTraits closureTraits = primeSurfaceClosureTraits(
            surface, viewDirection, volumeStack);
    bool supportsSolidAngle = primeClosureSupports(
            closureTraits, PRIME_MEASURE_SOLID_ANGLE);
    bool discreteOnlyInterface = primeClosureIsDiscreteOnly(closureTraits);
    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
    if (supportsSolidAngle) {
        primeSetNumericalContext(
                context.diagnostics, PRIME_NUMERICAL_STAGE_DIRECT_LIGHT, path.bounce);
        if (denoiserState.hasPrimarySurface) {
            float3 direct = path.throughput * primeEstimateDirectLighting(
                    sunNee,
                    areaNee,
                    context,
                    integrator,
                    surface,
                    viewDirection,
                    preparedSample,
                    volumeStack,
                    path.throughput,
                    pathIndex);
            primeAccumulateAfterPrimary(
                    context,
                    result,
                    denoiserState.diffusePath,
                    direct);
        }
    }

    float3 scatterSample = primeSobolSample3D(
            preparedSample,
            PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
            PRIME_SAMPLE_DIMENSION_PRIMARY);
    PrimePathScatter scatter;
    PrimeDenoiseAlbedos surfaceAlbedos;
    surfaceAlbedos.diffuse = float3(0.0);
    surfaceAlbedos.specular = float3(0.0);
    if (!denoiserState.reachedSolidAngle) {
        PrimeGuidedPathScatter guided = primeSampleGuidedPathSurface(
                context,
                surface,
                viewDirection,
                scatterSample,
                volumeStack,
                path.bounce);
        scatter = guided.scatter;
        surfaceAlbedos = guided.albedos;
    } else {
        primeSetNumericalContext(
                context.diagnostics, PRIME_NUMERICAL_STAGE_BSDF_SAMPLE, path.bounce);
        scatter = primeSamplePathSurface(
                context, surface, viewDirection, scatterSample, volumeStack);
    }
    BsdfSample bsdf = scatter.bsdf;
    volumeStack = scatter.volumeStack;
    bool hasScatter = primeHasScatter(bsdf);
    if (hasScatter) {
        primeRecordDirection(context.diagnostics, bsdf.direction);
        primeRecordNonnegative(context.diagnostics, bsdf.response);
        primeRecordNonnegative(context.diagnostics, bsdf.pdf);
        primeRecordNonnegative(context.diagnostics, bsdf.relativeEta);
    }
    bool sampledSolidAngle = primeIsSolidAngleSample(bsdf);
    if (!denoiserState.reachedSolidAngle) {
        bool firstDenoiseSurface = !denoiserState.hasPrimarySurface;
        if (firstDenoiseSurface) {
            denoiserState.specularAlbedoProduct += surfaceAlbedos.specular;
            denoiserState.diffuseAlbedoProduct *= surfaceAlbedos.diffuse;
            denoiserState.diffusePath = sampledSolidAngle
                    && (bsdf.eventFlags & PRIME_BSDF_EVENT_DIFFUSE) != 0u;
            denoiserState.hasPrimarySurface = true;
            denoiserState.primaryBounce = path.bounce;
            result.guides.primaryDistance = length(
                    surface.position - primePush.cameraPosition);
            result.guides.primaryPosition = surface.position - primePush.cameraPosition;
            result.guides.primaryPreviousPosition =
                    primeSurfacePreviousPosition(
                            surface, primePush.sectionTableAddress)
                            - primePush.cameraPosition;
            result.guides.primaryHasMotion = primeSurfaceHasMotion(surface);
            result.guides.primaryAlbedo = primeSanitizeObservedDenoiseAlbedo(
                    context,
                    denoiserState.diffuseAlbedoProduct);
            result.guides.primaryNormal =
                    primeSurfaceShadingNormal(surface, viewDirection);
            result.guides.primaryHitKind = surface.hitKind;
            result.guides.primaryMaterialFlags = primeNrdGuideControl(
                    surface.materialControl, surface.opticalControl);
            result.guides.primarySpecularAlbedo = primeSanitizeObservedDenoiseAlbedo(
                    context,
                    denoiserState.specularAlbedoProduct);
            result.guides.primaryLinearRoughness = surfaceLinearRoughness;
            if (hasScatter) {
                if (denoiserState.diffusePath) {
                    result.guides.diffuseDirection = bsdf.direction;
                } else {
                    result.guides.specularDirection = bsdf.direction;
                }
            }
        }

        bool surfaceHasSolidAngleLobe = supportsSolidAngle;
        if (sampledSolidAngle || (!hasScatter && surfaceHasSolidAngleLobe)) {
            if (!firstDenoiseSurface) {
                denoiserState.specularAlbedoProduct *=
                        surfaceAlbedos.specular + surfaceAlbedos.diffuse;
            }
            denoiserState.reachedSolidAngle = true;
            result.guides.primarySpecularAlbedo = primeSanitizeObservedDenoiseAlbedo(
                    context,
                    denoiserState.specularAlbedoProduct);
        } else if (!firstDenoiseSurface && primeIsDiscreteSample(bsdf)) {
            denoiserState.specularAlbedoProduct *= surfaceAlbedos.specular;
            result.guides.primarySpecularAlbedo = primeSanitizeObservedDenoiseAlbedo(
                    context,
                    denoiserState.specularAlbedoProduct);
        }
    }
    if (!hasScatter
            || !primeAdvancePath(
                    context,
                    path,
                    surface,
                    bsdf,
                    preparedSample,
                    discreteOnlyInterface,
                    surfaceLinearRoughness,
                    skipRoulette)) {
        return false;
    }
    if (areaNee.enabled()
            && supportsSolidAngle
            && denoiserState.hasPrimarySurface
            &&
#if defined(PRIME_STEADY_WAVEFRONT)
                    true
#else
                    path.bounce != denoiserState.primaryBounce
#endif
    ) {
        areaNee.markSecondaryPath(
                path,
                integrator,
                surface,
                viewDirection,
                storedSunProbability);
    }
    path.bounce++;
    return true;
}


#endif
