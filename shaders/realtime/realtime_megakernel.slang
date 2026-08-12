#ifndef PRIME_REALTIME_MEGAKERNEL_SLANG
#define PRIME_REALTIME_MEGAKERNEL_SLANG

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
#include "realtime/transparent_guide_probe.slang"
#include "realtime/wavefront_output.slang"

IntegratorRecord primeMegakernelIntegrator() {
    IntegratorRecord integrator;
    integrator.sunDirectionIntensity = float4(
            primePush.sunDirection,
            ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
    integrator.environmentRadiance = float4(0.0);
    return integrator;
}

PrimeRcVolumeStack primeMegakernelInitialVolumeStack() {
    return (primePush.path.z & PRIME_PATH_CAMERA_IN_WATER_MASK) != 0u
            ? primeCameraWaterVolumeStack()
            : primeEmptyVolumeStack();
}

void primeMegakernelPrimaryDirect(
        inout PrimeIntegratorContext context,
        PathState path,
        PrimeRcVolumeStack volumeStack,
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        inout PrimeIntegrationResult result) {
    if (surface.hitKind != PRIME_HIT_SURFACE
            || !primeApplySegmentMedium(context, path, surface, volumeStack)) {
        return;
    }
    float3 viewDirection = -path.rayDirection;
    PrimeClosureTraits traits = primeSurfaceClosureTraits(
            surface, viewDirection, volumeStack);
    if (!primeClosureSupports(traits, PRIME_MEASURE_SOLID_ANGLE)) {
        return;
    }
    bool transparent = primeMaterialIsTransmissive(surface.materialControl);
    PrimePreparedSampleBase prepared = primePrepareSampleBase(
            primeMakeSampleBase(path, path.bounce + 1u));
    primeSetNumericalContext(
            context.diagnostics, PRIME_NUMERICAL_STAGE_DIRECT_LIGHT, path.bounce);
    PrimePrimarySunSample sun = primeEstimatePrimaryDirectSun(
            context,
            integrator,
            surface,
            viewDirection,
            primeSobolSample2D(
                    prepared,
                    PRIME_SAMPLE_EFFECT_DIRECT_SUN,
                    PRIME_SAMPLE_DIMENSION_PRIMARY),
            volumeStack,
            transparent);
    float3 sunDiffuse = path.throughput * sun.lighting.diffuse;
    float3 sunSpecular = path.throughput * sun.lighting.specular;
    if (transparent) {
        primeAccumulate(context, result.radiance.diffuse, sunDiffuse * sun.visibility);
        primeAccumulate(context, result.radiance.specular, sunSpecular * sun.visibility);
    } else {
        result.radiance.unshadowedSun = sunDiffuse + sunSpecular;
        result.radiance.sunVisibility = sun.visibility;
        result.guides.sunPenumbra = sun.penumbra;
    }

    PrimeDirectLightingSplit area = primeEstimatePrimaryNonsunDirect(
            context, surface, viewDirection, prepared, volumeStack, transparent);
    area.diffuse *= path.throughput;
    area.specular *= path.throughput;
    primeAccumulate(context, result.radiance.diffuse, area.diffuse);
    primeAccumulate(context, result.radiance.specular, area.specular);
    result.guides.primaryAreaDiffuse = area.diffuse;
    result.guides.primaryAreaSpecular = area.specular;
    if (any(area.diffuse > float3(0.0)) || any(area.specular > float3(0.0))) {
        result.guides.primaryAreaDirection = area.direction;
    }
}

void primeMegakernelFinalizeTransparentGuide(
        bool transmission,
        bool guideEnabled,
        bool hasGuide,
        inout PrimeTransparentBranchResult result) {
    if (!guideEnabled || hasGuide) {
        return;
    }
    float3 branchDirection = transmission
            ? result.guides.diffuseDirection
            : result.guides.specularDirection;
    result.guides.primaryAreaDiffuse = float3(0.0);
    result.guides.primaryAreaSpecular = float3(0.0);
    result.guides.primaryAreaDirection = float3(0.0);
    if (transmission) {
        result.guides.diffuseDirection = branchDirection;
        result.guides.diffuseHitDistance = PRIME_NRD_FP16_MAX;
    } else {
        result.guides.specularDirection = branchDirection;
        result.guides.specularHitDistance = PRIME_NRD_FP16_MAX;
    }
}

PrimeIntegrationResult primeMegakernelTransparent(
        inout PrimeIntegratorContext context,
        uint2 pixel,
        PathState cameraPath,
        PrimeRcVolumeStack cameraVolumeStack,
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        PrimeIntegrationResult initialResult,
        uint maximumScatters) {
    PathState path = cameraPath;
    PrimeRcVolumeStack volumeStack = cameraVolumeStack;
    if (!primeApplySegmentMedium(context, path, surface, volumeStack)) {
        return initialResult;
    }

    float3 viewDirection = -path.rayDirection;
    initialResult.reflectionSpecularRadiance = initialResult.radiance.specular;
    initialResult.radiance.specular = float3(0.0);
    primeAccumulate(
            context,
            initialResult.reflectionSpecularRadiance,
            primeEvaluateHitEmission(context, path, surface, false));
    float linearRoughness = primeSurfaceLinearRoughness(surface);
    PrimeClosureTraits traits = primeSurfaceClosureTraits(
            surface, viewDirection, volumeStack);
    bool discreteOnly = primeClosureIsDiscreteOnly(traits);
    PrimePreparedSampleBase prepared = primePrepareSampleBase(
            primeMakeSampleBase(path, path.bounce + 1u));
    primeSetNumericalContext(
            context.diagnostics, PRIME_NUMERICAL_STAGE_BSDF_SAMPLE, path.bounce);
    PrimeTransmissivePrimarySample primary = primeSampleMinecraftTransmissionPrimary(
            surface.baseColor,
            primeSurfaceOpacity(surface),
            primeSurfaceOutwardShadingNormal(surface),
            surface.materialControl,
            surface.roughness,
            surface.opticalControl,
            viewDirection,
            primeSobolSample3D(
                    prepared,
                    PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
                    PRIME_SAMPLE_DIMENSION_PRIMARY),
            surface.t,
            volumeStack,
            surface.adjacentBaseColor,
            surface.adjacentInterfaceControl);
    PrimeDenoiseAlbedos albedos = primary.albedos;
    float3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
    initialResult.guides.primaryDistance =
            length(surface.position - primePush.cameraPosition);
    initialResult.guides.primaryPosition = surface.position - primePush.cameraPosition;
    initialResult.guides.primaryPreviousPosition =
            primeSurfacePreviousPosition(surface) - primePush.cameraPosition;
    initialResult.guides.primaryHasMotion = primeSurfaceHasMotion(surface);
    initialResult.guides.primaryAlbedo = albedos.diffuse;
    initialResult.guides.primaryNormal = shadingNormal;
    initialResult.guides.primaryHitKind = surface.hitKind;
    initialResult.guides.primaryMaterialFlags = surface.materialControl;
    initialResult.guides.primarySpecularAlbedo = albedos.specular;
    initialResult.guides.primaryLinearRoughness = linearRoughness;
    initialResult.transparentPrimary = true;
    PrimeDenoiserGuides visibleGuides = initialResult.guides;

    uint guideMode = primeTransparentGuideMode();
    bool transmissionGuide = guideMode == PRIME_PATH_TRANSPARENT_GUIDE_MODE_NRD
            || guideMode == PRIME_PATH_TRANSPARENT_GUIDE_MODE_DLSS_RR;
    bool reflectionGuide = guideMode == PRIME_PATH_TRANSPARENT_GUIDE_MODE_NRD;
    PrimeTransmissiveBsdfSample branches[2] = PrimeTransmissiveBsdfSample[2](
            primary.paths.transmission, primary.paths.reflection);
    bool sampleReflection = primeTransparentCheckerboardSamplesReflection(
            pixel, path.sampleIndex);
    PrimeTransparentBranchResult results[2];

    for (uint branch = 0u; branch < 2u; ++branch) {
        bool transmission = branch == 0u;
        bool selected = transmission != sampleReflection;
        bool guideEnabled = transmission ? transmissionGuide : reflectionGuide;
        PrimeTransmissiveBsdfSample choice = branches[branch];
        BsdfSample bsdf = selected ? choice.bsdfSample : primeInvalidBsdfSample();
        if (selected) {
            bsdf.pdf = primeTransparentCheckerboardPdf(bsdf.pdf);
        } else {
            choice.volumeStack = volumeStack;
        }

        PrimeTransparentBranchResult branchResult;
        branchResult.diffuseRadiance = transmission
                ? initialResult.radiance.diffuse
                : initialResult.reflectionDiffuseRadiance;
        branchResult.specularRadiance = transmission
                ? initialResult.radiance.specular
                : initialResult.reflectionSpecularRadiance;
        branchResult.guides = visibleGuides;
        branchResult.firstHitDistance = 0.0;
        branchResult.anchorDistance = -1.0;
        branchResult.directionalGuide = false;
        PrimeQueuedPsrState psrState = primeEmptyQueuedPsrState();
        bool hasScatter = primeHasScatter(bsdf);
        bool hasGuide = guideEnabled && hasScatter
                && (bsdf.eventFlags & PRIME_BSDF_EVENT_DISCRETE) == 0u;
        bool diffusePath = transmission;
        uint guideBounce = path.bounce;

        if (hasScatter) {
            primeRecordDirection(context.diagnostics, bsdf.direction);
            primeRecordNonnegative(context.diagnostics, bsdf.response);
            primeRecordNonnegative(context.diagnostics, bsdf.pdf);
            primeRecordNonnegative(context.diagnostics, bsdf.relativeEta);
            if (transmission) {
                visibleGuides.diffuseDirection = bsdf.direction;
                branchResult.guides.diffuseDirection = bsdf.direction;
            } else {
                visibleGuides.specularDirection = bsdf.direction;
                branchResult.guides.specularDirection = bsdf.direction;
            }
        }
        if (hasGuide) {
            branchResult.guides = primeQueuedPsrGuide(
                    branchResult.guides,
                    surface,
                    shadingNormal,
                    linearRoughness,
                    albedos,
                    path.throughput,
                    path.physicalOrigin,
                    psrState);
            if (transmission) {
                branchResult.anchorDistance = primeQueuedPsrAnchorDistance(
                        psrState, surface.position, surface.geometricNormal);
            }
        } else if (guideEnabled && hasScatter
                && (bsdf.eventFlags & PRIME_BSDF_EVENT_DISCRETE) != 0u) {
            psrState = primeAppendQueuedPsrDiscrete(
                    psrState, path.physicalOrigin, surface, bsdf);
        }
        if (transmission) {
            branchResult.guides.primaryAreaDiffuse =
                    initialResult.guides.primaryAreaDiffuse;
        } else {
            branchResult.guides.primaryAreaSpecular =
                    initialResult.guides.primaryAreaSpecular;
        }
        branchResult.guides.primaryAreaDirection =
                initialResult.guides.primaryAreaDirection;

        PathState branchPath = path;
        branchPath.sampleDimension = 0u;
        bool continuation = hasScatter && primeAdvancePath(
                context,
                branchPath,
                surface,
                bsdf,
                prepared,
                discreteOnly,
                linearRoughness);
        if (continuation) {
            branchPath.bounce = path.bounce + 1u;
            continuation = branchPath.bounce < maximumScatters;
        }
        PrimeRcVolumeStack branchVolumeStack = choice.volumeStack;
        while (continuation) {
            primeSetNumericalContext(
                    context.diagnostics,
                    PRIME_NUMERICAL_STAGE_TRACE,
                    branchPath.bounce);
            SurfaceInteraction branchSurface = primeTraceSurfaceWithoutReorder(
                    branchPath.traceOrigin, branchPath.rayDirection);
            continuation = primeIntegrateTransparentWavefrontSurface(
                    PrimeImmediateSecondarySunNee(),
                    PrimeImmediateSecondaryAreaNee(),
                    context,
                    branchPath,
                    integrator,
                    branchVolumeStack,
                    branchResult,
                    psrState,
                    hasGuide,
                    diffusePath,
                    guideBounce,
                    branchResult.directionalGuide,
                    branchSurface,
                    transmission,
                    guideEnabled,
                    0u);
            continuation = continuation && branchPath.bounce < maximumScatters;
        }
        primeMegakernelFinalizeTransparentGuide(
                transmission, guideEnabled, hasGuide, branchResult);
        results[branch] = branchResult;
    }

    PrimeTransparentBranchResult transmission = results[0];
    PrimeTransparentBranchResult reflection = results[1];
    PrimeIntegrationResult result = primeEmptyIntegrationResult();
    result.radiance.diffuse = transmission.diffuseRadiance;
    result.radiance.specular = transmission.specularRadiance;
    result.guides = visibleGuides;
    result.guides.diffuseHitDistance = transmission.firstHitDistance;
    result.guides.specularHitDistance = reflection.firstHitDistance;
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

PrimeIntegrationResult primeMegakernelIntegrate<IndirectReuse>(
        IndirectReuse indirectReuse,
        inout PrimeIntegratorContext context,
        uint2 pixel,
        float2 cameraSample)
    where IndirectReuse : IPrimeIndirectReuse
{
    uint maximumScatters = primeMaximumBounces();
    PathState path = primeCameraPath(pixel, 0u, cameraSample);
    PrimeRcVolumeStack volumeStack = primeMegakernelInitialVolumeStack();
    PrimeIntegrationResult result = primeEmptyIntegrationResult();
    if (maximumScatters == 0u) {
        return result;
    }

    primeSetNumericalContext(context.diagnostics, PRIME_NUMERICAL_STAGE_TRACE, 0u);
    SurfaceInteraction surface = primeTraceSurfaceWithoutReorder(
            primePush.cameraPosition, path.rayDirection);
    IntegratorRecord integrator = primeMegakernelIntegrator();
    primeMegakernelPrimaryDirect(
            context, path, volumeStack, integrator, surface, result);
    if (surface.hitKind == PRIME_HIT_SURFACE
            && primeMaterialIsTransmissive(surface.materialControl)) {
        return primeMegakernelTransparent(
                context,
                pixel,
                path,
                volumeStack,
                integrator,
                surface,
                result,
                maximumScatters);
    }

    PrimeDenoiserState denoiserState = primeInitialDenoiserState();
    [loop]
    while (path.bounce < maximumScatters) {
        bool continuation = primeIntegrateWavefrontSurface(
                PrimeImmediateSecondarySunNee(),
                PrimeImmediateSecondaryAreaNee(),
                indirectReuse,
                context,
                path,
                integrator,
                volumeStack,
                denoiserState,
                result,
                surface,
                0u);
        if (!continuation || path.bounce >= maximumScatters) {
            break;
        }
        primeSetNumericalContext(
                context.diagnostics, PRIME_NUMERICAL_STAGE_TRACE, path.bounce);
        surface = primeTraceSurfaceWithoutReorder(
                path.traceOrigin, path.rayDirection);
    }
    return result;
}

void primeMegakernelWrite(
        inout PrimeIntegratorContext context,
        uint2 pixel,
        float2 cameraSample,
        PrimeIntegrationResult result) {
    float4 reflectionPreviousVirtualPosition = float4(0.0, 0.0, 0.0, -1.0);
    if (result.transparentPrimary
            && primeTransparentGuideMode()
                    != PRIME_PATH_TRANSPARENT_GUIDE_MODE_DISABLED) {
        PathState cameraPath = primeCameraPath(pixel, 0u, cameraSample);
        SurfaceInteraction primarySurface = primeTraceSurfaceWithoutReorder(
                primePush.cameraPosition, cameraPath.rayDirection);
        PrimeTransparentGuideProbeResult probes = primeTraceTransparentGuideProbes(
                context,
                cameraPath,
                primarySurface,
                primeMegakernelInitialVolumeStack(),
                primeMaximumBounces());
        result.transmissionGuides = primeGuideWithProbeSurface(
                result.transmissionGuides, probes.transmission);
        if (probes.transmission.primaryHitKind == PRIME_HIT_SURFACE) {
            result.transmissionAnchorDistance = probes.transmissionAnchorDistance;
        }
        result.reflectionGuides = primeGuideWithProbeSurface(
                result.reflectionGuides, probes.reflection);
        if (probes.reflection.primaryHitKind == PRIME_HIT_SURFACE) {
            result.reflectionDirectionalGuide = false;
        }
        reflectionPreviousVirtualPosition = probes.reflectionPreviousVirtualPosition;
    }
    primeWriteRealtimeOutput(
            context.diagnostics,
            pixel,
            cameraSample,
            result,
            reflectionPreviousVirtualPosition);
}

#endif
