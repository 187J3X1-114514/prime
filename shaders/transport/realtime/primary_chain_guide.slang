#language slang 2026
module "transport/realtime/primary_chain_guide.slang";

import "service/bsdf/support/sanitization.slang";

import "prime_abi_types.slang";
import "transport/guide/coincidence.slang";
import "service/bsdf/dispatch/primary_chain_albedo.slang";
import "service/bsdf/contract/albedo.slang";
import "service/reconstruct/nrd/common.slang";
import "math/packed_geometry.slang";
import "math/queued_psr.slang";
import "policy/runtime/config.slang";
import "math/sampling.slang";
import "prime_abi.slang";
import "service/bsdf/contract/sample.slang";
import "math/transport.slang";
import "service/trace/context.slang";
import "service/trace/surface.slang";
import "transport/scatter/delta.slang";
import "transport/scatter/contract.slang";
import "transport/scatter/path_update.slang";
import "transport/surface/access.slang";
import "state/realtime/contract.slang";
import "state/realtime/guide.slang";
import "state/realtime/light.slang";
import "state/realtime/ordinary.slang";
import "state/realtime/transparent.slang";
import "state/realtime/transport.slang";
import "bsdf/compact/contract/volume.slang";
import "contract/path.slang";
import "contract/realtime/transparent.slang";

public PrimePathScatter primeSamplePrimaryChainGuideContinuation(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        PrimeOpenPbrVolumeStack volumeStack) {
    return primeSampleDiscreteGuideContinuation(
            context, surface, viewDirection, volumeStack);
}

public bool primeAdvancePrimaryChainGuideThroughput(
        inout float3 throughput, BsdfSample sampleValue) {
    if (!primeHasScatter(sampleValue)) return false;
    throughput = primeProductOver(
            throughput, sampleValue.response, sampleValue.pdf);
    return primeNrdIsFinite(throughput)
            && any(throughput > float3(0.0));
}

public bool primeAdvancePrimaryChainGuideMedium(
        inout float3 throughput,
        SurfaceInteraction surface,
        PrimeOpenPbrVolumeStack volumeStack) {
    if (volumeStack.count > 0u) {
        throughput *= primeSegmentTransmittance(
                volumeStack.values[volumeStack.count - 1u].extinction,
                surface.t);
    }
    return primeNrdIsFinite(throughput)
            && any(throughput > float3(0.0));
}

public void primeStorePrimaryChainGuideSurface(
        uint recordIndex,
        uint control,
        uint guideBounce,
        SurfaceInteraction surface,
        float3 viewDirection,
        PrimeOpenPbrVolumeStack volumeStack,
        PrimeQueuedPsrState psrState,
        float3 throughput) {
    float3 virtualPosition;
    float3 virtualNormal;
    if (!primeBuildQueuedPsrGuideValueFromSegment(
            psrState,
            surface.t,
            primeSurfaceShadingNormal(surface, viewDirection),
            virtualPosition,
            virtualNormal)) {
        return;
    }

    bool transmission = primeWavefrontTransmissionBranch(control);
    float anchorDistance = -1.0;
    if (transmission) {
        anchorDistance = primeQueuedPsrAnchorDistanceValue(
                psrState,
                primePush.cameraPosition,
                surface.position,
                surface.geometricNormal);
        if (anchorDistance > 0.0) {
            virtualPosition = psrState.firstDirectionLength.xyz
                    * anchorDistance;
        }
    }

    PrimeTransparentBranchResult result =
            primeLoadTransparentBranchActiveIntermediate(
                    primeWavefrontPixel(recordIndex), transmission, control);
    PrimeDenoiseAlbedos albedos;
    if (!primePrimaryChainGuideAlbedos(
            surface,
            viewDirection,
            volumeStack,
            primeUsesAirGap(),
            albedos)) {
        return;
    }
    result.guides.primaryDistance = length(virtualPosition);
    result.guides.primaryPosition = virtualPosition;
    result.guides.primaryPreviousPosition = virtualPosition;
    result.guides.primaryHasMotion = false;
    result.guides.primaryAlbedo = primeSanitizeDenoiseAlbedo(
            throughput * albedos.diffuse);
    result.guides.primaryNormal = primeNrdSafeNormalize(
            virtualNormal, float3(0.0, 1.0, 0.0));
    result.guides.primaryHitKind = surface.hitKind;
    result.guides.primaryMaterialFlags = primeNrdGuideControl(
            surface.materialControl, surface.opticalControl);
    result.guides.primarySpecularAlbedo = primeSanitizeDenoiseAlbedo(
            throughput * albedos.specular);
    result.guides.primaryLinearRoughness =
            primeSurfaceLinearRoughness(surface);
    result.anchorDistance = anchorDistance;
    result.directionalGuide = false;
    primeStoreTransparentBranchActiveIntermediate(
            primeWavefrontPixel(recordIndex),
            transmission,
            true,
            false,
            (control & PRIME_WAVEFRONT_PRIMARY_REFLECTION_ONLY) != 0u,
            result);

    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    uint updatedControl = asuint(record.rayDirectionAndDenoiserControl.w);
    updatedControl |= PRIME_WAVEFRONT_REACHED_SOLID_ANGLE;
    updatedControl &= ~PRIME_WAVEFRONT_DIRECTIONAL_GUIDE;
    updatedControl &= ~(PRIME_WAVEFRONT_BYTE_MASK
            << PRIME_WAVEFRONT_PRIMARY_BOUNCE_SHIFT);
    updatedControl |= min(guideBounce, PRIME_WAVEFRONT_BYTE_MASK)
            << PRIME_WAVEFRONT_PRIMARY_BOUNCE_SHIFT;
    updatedControl &= ~(PRIME_WAVEFRONT_PRIMARY_FLAGS_MASK
            << PRIME_WAVEFRONT_PRIMARY_FLAGS_SHIFT);
    updatedControl |= (result.guides.primaryMaterialFlags
            & PRIME_WAVEFRONT_PRIMARY_FLAGS_MASK)
            << PRIME_WAVEFRONT_PRIMARY_FLAGS_SHIFT;
    record.rayDirectionAndDenoiserControl.w = asfloat(updatedControl);
    record = primeWavefrontWithQueuedPsrControl(record, 0u);
    record = primeWavefrontWithGuideProbeFallback(record, false);
    primeStoreWavefrontTransportRecord(recordIndex, record);
}

public void primeStorePrimaryChainDirectionalGuide(
        uint recordIndex,
        uint control,
        PrimeQueuedPsrState psrState,
        float3 direction) {
    if (primeWavefrontTransmissionBranch(control)) return;
    float3 virtualDirection = primeQueuedPsrVirtualDirection(
            psrState, direction);
    float lengthSquared = dot(virtualDirection, virtualDirection);
    if (!primeNrdIsFinite(virtualDirection)
            || !primeNrdIsFinite(lengthSquared)
            || !(lengthSquared > 1.0e-12)) {
        return;
    }
    virtualDirection *= rsqrt(lengthSquared);

    uint2 pixel = primeWavefrontPixel(recordIndex);
    PrimeTransparentBranchResult result =
            primeLoadTransparentBranchActiveIntermediate(
                    pixel, false, control);
    result.guides.primaryPosition = virtualDirection;
    result.directionalGuide = true;
    primeStoreTransparentBranchActiveIntermediate(
            pixel,
            false,
            false,
            true,
            (control & PRIME_WAVEFRONT_PRIMARY_REFLECTION_ONLY) != 0u,
            result);

    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    uint updatedControl = asuint(record.rayDirectionAndDenoiserControl.w)
            | PRIME_WAVEFRONT_DIRECTIONAL_GUIDE;
    record.rayDirectionAndDenoiserControl.w = asfloat(updatedControl);
    record = primeWavefrontWithQueuedPsrControl(record, 0u);
    primeStoreWavefrontTransportRecord(recordIndex, record);
}

// A signal branch and its deterministic guide share traversal until their first discrete event
// differs. Only that sparse fork enters this loop, so the common Fresnel-transmission case retains
// one trace and the primary-chain shader never carries four full path states simultaneously.
public void primeTracePrimaryChainGuideFork(
        inout PrimeIntegratorContext context,
        uint recordIndex,
        uint control,
        float3 previousPosition,
        float3 throughput,
        uint forkBounce,
        SurfaceInteraction forkSurface,
        PrimePathScatter guideScatter,
        PrimeQueuedPsrState psrState,
        uint completedTransitions,
        uint limit) {
    if (primeSurfaceHasMotion(forkSurface)
            || !primeIsDiscreteSample(guideScatter.bsdf)
            || !primeAdvancePrimaryChainGuideThroughput(
                    throughput, guideScatter.bsdf)) {
        return;
    }

    psrState = primeAppendQueuedPsrDiscrete(
            psrState,
            previousPosition,
            forkSurface,
            guideScatter.bsdf);
    if (primeQueuedPsrOverflowed(psrState)) return;

    float3 physicalOrigin = forkSurface.position;
    uint2 sourcePrimitive = uint2(
            forkSurface.sectionIndex,
            primeSurfaceTriangleIndex(forkSurface));
    float3 direction = guideScatter.bsdf.direction;
    PrimeOpenPbrVolumeStack volumeStack = guideScatter.volumeStack;
    uint transitions = completedTransitions + 1u;
    uint pathClass = primeWavefrontTransmissionBranch(control) ? 2u : 1u;

    [loop]
    for (uint step = transitions; step <= limit; ++step) {
        SurfaceInteraction surface = primeTraceSurfaceClassified(
                PrimeSurfaceTracePolicy(),
                physicalOrigin,
                direction,
                sourcePrimitive,
                pathClass);
        if (!primeKnownHitKind(surface)) return;
        if (surface.hitKind == PRIME_HIT_NONE) {
            primeStorePrimaryChainDirectionalGuide(
                    recordIndex, control, psrState, direction);
            return;
        }
        if (!primeAdvancePrimaryChainGuideMedium(
                throughput, surface, volumeStack)) {
            return;
        }
        if (primeSurfaceHasMotion(surface)) return;

        float3 viewDirection = -direction;
        if (!primeSurfaceIsDiscreteOnly(surface, viewDirection, volumeStack)) {
            primeStorePrimaryChainGuideSurface(
                    recordIndex,
                    control,
                    forkBounce + transitions - completedTransitions,
                    surface,
                    viewDirection,
                    volumeStack,
                    psrState,
                    throughput);
            return;
        }
        if (transitions >= limit
                || primeQueuedPsrCount(psrState)
                        >= PRIME_QUEUED_PSR_CAPACITY) {
            return;
        }

        PrimePathScatter continuation =
                primeSamplePrimaryChainGuideContinuation(
                        context, surface, viewDirection, volumeStack);
        if (!primeIsDiscreteSample(continuation.bsdf)
                || !primeAdvancePrimaryChainGuideThroughput(
                        throughput, continuation.bsdf)) {
            return;
        }
        psrState = primeAppendQueuedPsrDiscrete(
                psrState,
                physicalOrigin,
                surface,
                continuation.bsdf);
        if (primeQueuedPsrOverflowed(psrState)) return;
        physicalOrigin = surface.position;
        sourcePrimitive = uint2(
                surface.sectionIndex,
                primeSurfaceTriangleIndex(surface));
        direction = continuation.bsdf.direction;
        volumeStack = continuation.volumeStack;
        transitions++;
    }
}

public void primeInvalidatePrimaryChainGuide(
        uint recordIndex,
        PrimeWavefrontTransportRecord record) {
    uint2 pixel = primeWavefrontPixel(recordIndex);
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    bool transmission = primeWavefrontTransmissionBranch(control);
    PrimeTransparentBranchResult result =
            primeLoadTransparentBranchActiveIntermediate(
                    pixel, transmission, control);
    result.anchorDistance = -1.0;
    result.directionalGuide = false;
    // The full branch store owns the visible-interface fallback. Invalidating queued PSR must
    // only discard its anchor and continuation state; replacing the resident guide with the
    // empty default leaves random chain-limit pixels without a reconstruction surface.
    primeStoreTransparentBranchActiveIntermediate(
            pixel,
            transmission,
            false,
            false,
            (control & PRIME_WAVEFRONT_PRIMARY_REFLECTION_ONLY) != 0u,
            result);
}
