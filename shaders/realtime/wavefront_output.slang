#ifndef PRIME_WAVEFRONT_OUTPUT_SLANG
#define PRIME_WAVEFRONT_OUTPUT_SLANG

import "core/auto_exposure.slang";

// The realtime output adapter encodes reconstruction signals from one resolved model sample and
// never feeds output data back into transport.

uint primeClassifyRawOutput(PrimeIntegrationResult result) {
    uint flags = primeClassifyRadiance(result.radiance.diffuse)
            | primeClassifyRadiance(result.radiance.specular)
            | primeClassifyRadiance(result.reflectionDiffuseRadiance)
            | primeClassifyRadiance(result.reflectionSpecularRadiance)
            | primeClassifyRadiance(result.radiance.stable)
            | primeClassifyRadiance(result.radiance.unshadowedSun)
            | primeClassifyUnit(result.radiance.sunVisibility)
            | primeClassifyRadiance(primeResolveIntegrationRadiance(result));
    flags |= primeClassifyNonFinite(result.guides.primaryDistance)
            | primeClassifyNonnegative(result.guides.specularHitDistance)
            | primeClassifyNonnegative(result.guides.diffuseHitDistance)
            | primeClassifyNonnegative(result.guides.sunPenumbra)
            | primeClassifyUnit(result.guides.primaryAlbedo)
            | primeClassifyDirection(result.guides.primaryNormal)
            | primeClassifyUnit(result.guides.primarySpecularAlbedo)
            | primeClassifyUnit(result.guides.primaryLinearRoughness)
            | primeClassifyNonFinite(result.guides.primaryPosition)
            | primeClassifyRadiance(result.guides.primaryAreaDiffuse)
            | primeClassifyRadiance(result.guides.primaryAreaSpecular);
    uint transparentGuideMode = primeTransparentGuideMode();
    if (result.transparentPrimary
            && transparentGuideMode != PRIME_PATH_TRANSPARENT_GUIDE_MODE_DISABLED) {
        flags |= primeClassifyNonFinite(result.transmissionAnchorDistance)
                | primeClassifyNonFinite(result.transmissionGuides.primaryDistance)
                | primeClassifyDirection(result.transmissionGuides.primaryNormal)
                | primeClassifyNonnegative(result.transmissionGuides.primaryAlbedo)
                | primeClassifyNonnegative(result.transmissionGuides.primarySpecularAlbedo);
    }
    if (result.transparentPrimary
            && (transparentGuideMode == PRIME_PATH_TRANSPARENT_GUIDE_MODE_NRD
                    || result.transparentReflectionOnly)) {
        flags |= primeClassifyNonFinite(result.reflectionGuides.primaryDistance)
                | primeClassifyDirection(result.reflectionGuides.primaryNormal)
                | primeClassifyNonnegative(result.reflectionGuides.primaryAlbedo)
                | primeClassifyNonnegative(result.reflectionGuides.primarySpecularAlbedo);
    }
    if (any(result.radiance.diffuse
            - result.guides.primaryAreaDiffuse > float3(0.0))) {
        flags |= primeClassifyOptionalDirection(result.guides.diffuseDirection);
    }
    if (any(result.radiance.specular
            - result.guides.primaryAreaSpecular > float3(0.0))) {
        flags |= primeClassifyOptionalDirection(result.guides.specularDirection);
    }
    if (any(result.guides.primaryAreaDiffuse > float3(0.0))
            || any(result.guides.primaryAreaSpecular > float3(0.0))) {
        flags |= primeClassifyOptionalDirection(result.guides.primaryAreaDirection);
    }
    return flags;
}

float3 primeNrdEffectiveDirection(
        float3 totalRadiance,
        float3 primaryAreaRadiance,
        float3 primaryAreaDirection,
        float3 continuationDirection,
        float3 albedo) {
    float3 direct = min(primaryAreaRadiance, totalRadiance);
    float3 continuation = max(totalRadiance - direct, float3(0.0));
    float directY = primeNrdY(primeNrdDemodulate(direct, albedo));
    float continuationY = primeNrdY(primeNrdDemodulate(continuation, albedo));
    float totalY = directY + continuationY;
    float3 moment = primaryAreaDirection * directY
            + continuationDirection * continuationY;
    return primeNrdIsFinite(moment) && primeNrdIsFinite(totalY) && totalY > 0.0
            ? clamp(moment / totalY, float3(-1.0), float3(1.0))
            : float3(0.0);
}

float3 primeRrGuideDiagnosticNormal(PrimeDenoiserGuides guides) {
    if (guides.primaryHitKind != PRIME_HIT_SURFACE) return float3(0.0);
    if (!primeNrdIsFinite(guides.primaryNormal)) return float3(1.0, 0.0, 1.0);
    return clamp(guides.primaryNormal * 0.5 + 0.5, 0.0, 1.0);
}

float3 primeRrGuideDiagnosticRoughness(PrimeDenoiserGuides guides) {
    if (guides.primaryHitKind != PRIME_HIT_SURFACE) return float3(0.0);
    if (!primeNrdIsFinite(guides.primaryLinearRoughness)) {
        return float3(1.0, 0.0, 1.0);
    }
    return float3(clamp(guides.primaryLinearRoughness, 0.0, 1.0));
}

float3 primeRrGuideDiagnosticAlbedo(float3 albedo, uint hitKind) {
    if (hitKind != PRIME_HIT_SURFACE) return float3(0.0);
    if (!primeNrdIsFinite(albedo)) return float3(1.0, 0.0, 1.0);
    return clamp(albedo, 0.0, 1.0);
}

void primeStoreRrGuideDiagnosticCell(
        int2 local,
        int2 cellExtent,
        uint column,
        PrimeDenoiserGuides guides) {
    int x = local.x + int(column) * cellExtent.x;
    primeImageStoreWriteOnly(
            primeRawNumericalDiagnostic,
            int2(x, local.y + 3 * cellExtent.y),
            float4(primeRrGuideDiagnosticNormal(guides), 1.0));
    primeImageStoreWriteOnly(
            primeRawNumericalDiagnostic,
            int2(x, local.y + 2 * cellExtent.y),
            float4(primeRrGuideDiagnosticRoughness(guides), 1.0));
    primeImageStoreWriteOnly(
            primeRawNumericalDiagnostic,
            int2(x, local.y + cellExtent.y),
            float4(primeRrGuideDiagnosticAlbedo(
                    guides.primaryAlbedo, guides.primaryHitKind), 1.0));
    primeImageStoreWriteOnly(
            primeRawNumericalDiagnostic,
            int2(x, local.y),
            float4(primeRrGuideDiagnosticAlbedo(
                    guides.primarySpecularAlbedo, guides.primaryHitKind), 1.0));
}

// One image records four resolve-time fields without changing reconstruction inputs. Selecting
// one in every 5x4 source pixels gives each logical guide a full-scene panel with unique writes.
void primeStoreRrGuideDiagnostic(
        uint2 pixel,
        PrimeIntegrationResult sampleResult,
        PrimeDenoiserGuides selectedGuides,
        bool usesTransmissionAnchor,
        bool rrUsesTransmissionGuide,
        bool rrUsesReflectionGuide,
        float3 transmissionGuideStatus) {
    if (primeTransparentGuideMode()
                    != PRIME_PATH_TRANSPARENT_GUIDE_MODE_DLSS_RR
            || pixel.x % 5u != 0u
            || pixel.y % 4u != 0u) {
        return;
    }
    uint width;
    uint height;
    primeRawNumericalDiagnostic.GetDimensions(width, height);
    int2 cellExtent = int2(width / 5u, height / 4u);
    int2 local = int2(pixel.x / 5u, pixel.y / 4u);
    if (any(cellExtent <= int2(0)) || any(local >= cellExtent)) return;

    primeStoreRrGuideDiagnosticCell(
            local, cellExtent, 0u, sampleResult.guides);
    primeStoreRrGuideDiagnosticCell(
            local, cellExtent, 1u, sampleResult.transmissionGuides);
    // Column two is the immutable first-owned transmission-guide snapshot.
    primeStoreRrGuideDiagnosticCell(
            local, cellExtent, 3u, selectedGuides);

    float3 validity = float3(
            sampleResult.guides.primaryHitKind == PRIME_HIT_SURFACE ? 1.0 : 0.0,
            sampleResult.transmissionGuides.primaryHitKind == PRIME_HIT_SURFACE
                    ? 1.0 : 0.0,
            sampleResult.reflectionGuides.primaryHitKind == PRIME_HIT_SURFACE
                    ? 1.0 : 0.0);
    float3 selection = float3(
            !rrUsesTransmissionGuide && !rrUsesReflectionGuide ? 1.0 : 0.0,
            rrUsesTransmissionGuide ? 1.0 : 0.0,
            rrUsesReflectionGuide ? 1.0 : 0.0);
    int x = local.x + 4 * cellExtent.x;
    primeImageStoreWriteOnly(
            primeRawNumericalDiagnostic,
            int2(x, local.y + 3 * cellExtent.y),
            float4(validity, 1.0));
    primeImageStoreWriteOnly(
            primeRawNumericalDiagnostic,
            int2(x, local.y + 2 * cellExtent.y),
            float4(selection, 1.0));
    primeImageStoreWriteOnly(
            primeRawNumericalDiagnostic,
            int2(x, local.y + cellExtent.y),
            float4(float3(
                    sampleResult.transparentPrimary ? 1.0 : 0.0,
                    sampleResult.transparentReflectionOnly ? 1.0 : 0.0,
                    usesTransmissionAnchor ? 1.0 : 0.0), 1.0));
    primeImageStoreWriteOnly(
            primeRawNumericalDiagnostic,
            int2(x, local.y),
            float4(transmissionGuideStatus, 1.0));
}

void primeWriteRealtimeOutput(
        inout PrimeNumericalDiagnostics diagnostics,
        uint2 pixel,
        float2 cameraSample,
        PrimeIntegrationResult sampleResult,
        float4 reflectionPreviousVirtualPosition,
        float3 transmissionGuideStatus) {
    if (primeWritesRawNumericalDiagnostic()) {
        primeSetNumericalContext(
                diagnostics, PRIME_NUMERICAL_STAGE_FINAL_OUTPUT, 0u);
        primeRecordNumerical(
                diagnostics,
                primeClassifyRawOutput(sampleResult),
                PRIME_NUMERICAL_FIELD_OUTPUT);
        primeImageStoreWriteOnly(
                primeRawNumericalDiagnostic,
                int2(pixel),
                primeRawNumericalMetadata(diagnostics));
    }
    bool nrdShInputs = primeWritesNrdShInputs();
    bool hasTransmissionGuide = sampleResult.transparentPrimary
            && sampleResult.transmissionGuides.primaryHitKind == PRIME_HIT_SURFACE;
    bool usesTransmissionAnchor = hasTransmissionGuide
            && primeNrdIsFinite(sampleResult.transmissionAnchorDistance)
            && sampleResult.transmissionAnchorDistance > 0.0;
    // RR cannot represent both the interface and the refracted scene as primary geometry. Keep
    // the guide topology fixed: a transmitted solid-angle surface owns the primary guide
    // regardless of its current Fresnel energy.
    bool rrUsesTransmissionGuide = !nrdShInputs && usesTransmissionAnchor;
    bool rrUsesReflectionGuide = !nrdShInputs
            && primeRrUsesReflectionPrimaryGuide(
                    sampleResult, reflectionPreviousVirtualPosition.w);
    bool rrUsesVirtualPrimaryGuide =
            rrUsesTransmissionGuide || rrUsesReflectionGuide;
    PrimeDenoiserGuides nrdGuides = primeSelectReconstructionPrimaryGuides(
            sampleResult,
            nrdShInputs,
            rrUsesTransmissionGuide,
            rrUsesReflectionGuide);
    if (sampleResult.transparentReflectionOnly && !rrUsesReflectionGuide) {
        nrdGuides.primaryAlbedo = float3(0.0);
        if (nrdShInputs) {
            nrdGuides.primarySpecularAlbedo = float3(0.0);
        }
    }
    primeStoreRrGuideDiagnostic(
            pixel,
            sampleResult,
            nrdGuides,
            usesTransmissionAnchor,
            rrUsesTransmissionGuide,
            rrUsesReflectionGuide,
            transmissionGuideStatus);
    float3 noisyDiffuse = sampleResult.radiance.diffuse;
    float3 noisySpecular = sampleResult.radiance.specular;
    if (!nrdShInputs && sampleResult.transparentPrimary) {
        if (rrUsesReflectionGuide) {
            // Pure TIR through a finite PSR target is a direct view of virtual geometry. Preserve
            // the target closure's signal split so RR receives the same guide topology as a
            // directly visible surface.
            noisyDiffuse = sampleResult.reflectionDiffuseRadiance;
            noisySpecular = sampleResult.reflectionSpecularRadiance;
        } else {
            noisyDiffuse += noisySpecular;
            noisySpecular = sampleResult.reflectionDiffuseRadiance
                    + sampleResult.reflectionSpecularRadiance;
        }
    }
    bool hasPrimarySurface = nrdGuides.primaryHitKind == PRIME_HIT_SURFACE;
    float primaryDistance = primeNrdSanitizePrimaryDistance(
            usesTransmissionAnchor
                    ? sampleResult.transmissionAnchorDistance
                    : nrdGuides.primaryDistance,
            hasPrimarySurface);
    bool hasVisibleSurface = sampleResult.guides.primaryHitKind == PRIME_HIT_SURFACE;
    float visibleDistance = primeNrdSanitizePrimaryDistance(
            sampleResult.guides.primaryDistance, hasVisibleSurface);
    uint primaryMaterialFlags = primeNrdPrimaryMaterialFlags(
            nrdGuides.primaryMaterialFlags,
            sampleResult.guides.primaryMaterialFlags,
            nrdShInputs && sampleResult.transparentPrimary);
    float3 primaryPosition = primeNrdIsFinite(nrdGuides.primaryPosition)
            ? nrdGuides.primaryPosition
            : float3(0.0);
    bool primaryHasMotion = nrdGuides.primaryHasMotion
            && primeNrdIsFinite(nrdGuides.primaryPreviousPosition);
    float3 primaryPreviousPosition = primaryHasMotion
            ? nrdGuides.primaryPreviousPosition
            : primaryPosition;
    if (usesTransmissionAnchor) {
        // A refracted hit does not project to this pixel under the camera's straight projection.
        // Put its plane intersection on the visible primary ray so a static camera has zero
        // motion while depth remains anchored to the target surface.
        float3 fallbackDirection = primeNrdSafeNormalize(
                nrdGuides.primaryPosition, float3(0.0, 0.0, 1.0));
        float3 primaryDirection = primeNrdSafeNormalize(
                sampleResult.guides.primaryPosition, fallbackDirection);
        primaryPosition = primaryDirection * primaryDistance;
    }
    float specularHitDistance = !nrdShInputs
            && sampleResult.transparentPrimary
            && !rrUsesReflectionGuide
            ? sampleResult.guides.specularHitDistance
            : nrdGuides.specularHitDistance;
    float rawSignalScale = primeNrdClampRadiancePair(
            noisyDiffuse, noisySpecular, PRIME_NRD_FP16_MAX);
    // These images are a private raygen -> denoiser-preparation scratch contract. The existing
    // ray-tracing-to-compute barrier publishes them before nrd_motion converts them in place.
    primeImageStore(
            primeNrdNoisyDiffuse,
            int2(pixel),
            float4(
                    primeNrdSanitizeRadiance(noisyDiffuse),
                    primeNrdSanitizeHitDistance(nrdGuides.diffuseHitDistance)));
    primeImageStore(
            primeNrdNoisySpecular,
            int2(pixel),
            float4(
                    primeNrdSanitizeRadiance(noisySpecular),
                    primeNrdSanitizeHitDistance(specularHitDistance)));
    primeImageStore(
            primeNrdPrimaryPosition,
            int2(pixel),
            float4(
                    primaryDistance < 0.0
                            ? float3(0.0)
                            : (!nrdShInputs && primaryHasMotion
                                    ? primaryPreviousPosition
                                    : primaryPosition),
                    nrdShInputs
                            ? asfloat(packHalf2x16(cameraSample))
                            : (primaryHasMotion
                                    ? -primaryDistance - 1.0
                                    : primaryDistance)));
    if (nrdShInputs) {
        float3 primaryAreaDiffuse = nrdGuides.primaryAreaDiffuse * rawSignalScale;
        float3 primaryAreaSpecular = nrdGuides.primaryAreaSpecular * rawSignalScale;
        primeImageStore(
                primeNrdDiffuseDirection,
                int2(pixel),
                float4(primeNrdEffectiveDirection(
                        noisyDiffuse,
                        primaryAreaDiffuse,
                        nrdGuides.primaryAreaDirection,
                        nrdGuides.diffuseDirection,
                        nrdGuides.primaryAlbedo), 0.0));
        primeImageStore(
                primeNrdSpecularDirection,
                int2(pixel),
                float4(primeNrdEffectiveDirection(
                        noisySpecular,
                        primaryAreaSpecular,
                        nrdGuides.primaryAreaDirection,
                        nrdGuides.specularDirection,
                        nrdGuides.primarySpecularAlbedo), 0.0));
    }
    primeImageStore(
            primeNrdMaterial,
            int2(pixel),
            float4(
                    primeNrdSanitizeAlbedo(nrdGuides.primaryAlbedo),
                    rrUsesVirtualPrimaryGuide ? visibleDistance : primaryDistance));
    primeImageStore(
            primeNrdNormalRoughness,
            int2(pixel),
            primeNrdPackNormalRoughness(
                    nrdGuides.primaryNormal,
                    nrdGuides.primaryLinearRoughness,
                    primeNrdMaterialId(primaryMaterialFlags)));
    primeImageStore(
            primeNrdSpecularMaterial,
            int2(pixel),
            float4(
                    primeNrdSanitizeAlbedo(rrUsesTransmissionGuide
                            ? sampleResult.guides.primarySpecularAlbedo
                            : nrdGuides.primarySpecularAlbedo),
                    nrdShInputs
                            ? float(nrdGuides.primaryMaterialFlags)
                            : (rrUsesReflectionGuide
                                    ? nrdGuides.primaryLinearRoughness
                                    : sampleResult.guides.primaryLinearRoughness)));
    if (nrdShInputs) {
        bool visibleHasMotion = sampleResult.guides.primaryHasMotion
                && hasVisibleSurface
                && primeNrdIsFinite(
                        sampleResult.guides.primaryPreviousPosition);
        primeImageStore(
                primeNrdDisplayPosition,
                int2(pixel),
                float4(
                        hasVisibleSurface
                                ? (visibleHasMotion
                                        ? sampleResult.guides.primaryPreviousPosition
                                        : sampleResult.guides.primaryPosition)
                                : float3(0.0),
                        asfloat(hasVisibleSurface
                                ? sampleResult.guides.primaryMaterialFlags
                                        | (visibleHasMotion
                                                ? PRIME_DISPLAY_MOTION_FLAG
                                                : 0u)
                                : 0u)));

        PrimeDenoiserGuides reflectionGuides = sampleResult.reflectionGuides;
        bool hasReflectionGuide = sampleResult.transparentPrimary
                && reflectionGuides.primaryHitKind == PRIME_HIT_SURFACE;
        if (hasReflectionGuide) {
            float reflectionDistance = primeNrdSanitizePrimaryDistance(
                    reflectionGuides.primaryDistance, true);
            float3 reflectionDiffuse = sampleResult.reflectionDiffuseRadiance;
            float3 reflectionSpecular = sampleResult.reflectionSpecularRadiance;
            float reflectionScale = primeNrdClampRadiancePair(
                    reflectionDiffuse, reflectionSpecular, PRIME_NRD_FP16_MAX);
            primeImageStore(
                    primeNrdReflectionNoisyDiffuse,
                    int2(pixel),
                    float4(
                            primeNrdSanitizeRadiance(reflectionDiffuse),
                            primeNrdSanitizeHitDistance(
                                    reflectionGuides.diffuseHitDistance)));
            primeImageStore(
                    primeNrdReflectionNoisySpecular,
                    int2(pixel),
                    float4(
                            primeNrdSanitizeRadiance(reflectionSpecular),
                            primeNrdSanitizeHitDistance(
                                    reflectionGuides.specularHitDistance)));
            primeImageStore(
                    primeNrdReflectionPosition,
                    int2(pixel),
                    float4(
                            reflectionGuides.primaryHasMotion
                                    && primeNrdIsFinite(
                                            reflectionGuides.primaryPreviousPosition)
                            ? reflectionGuides.primaryPreviousPosition
                            : reflectionGuides.primaryPosition,
                            sampleResult.reflectionDirectionalGuide ? 1.0 : 0.0));
            primeImageStore(
                    primeNrdReflectionDiffuseDirection,
                    int2(pixel),
                    float4(primeNrdEffectiveDirection(
                            reflectionDiffuse,
                            reflectionGuides.primaryAreaDiffuse * reflectionScale,
                            reflectionGuides.primaryAreaDirection,
                            reflectionGuides.diffuseDirection,
                            reflectionGuides.primaryAlbedo), 0.0));
            primeImageStore(
                    primeNrdReflectionSpecularDirection,
                    int2(pixel),
                    float4(primeNrdEffectiveDirection(
                            reflectionSpecular,
                            reflectionGuides.primaryAreaSpecular * reflectionScale,
                            reflectionGuides.primaryAreaDirection,
                            reflectionGuides.specularDirection,
                            reflectionGuides.primarySpecularAlbedo), 0.0));
            primeImageStore(
                    primeNrdReflectionMaterial,
                    int2(pixel),
                    float4(
                            primeNrdSanitizeAlbedo(reflectionGuides.primaryAlbedo),
                            reflectionDistance));
            primeImageStore(
                    primeNrdReflectionNormalRoughness,
                    int2(pixel),
                    primeNrdPackNormalRoughness(
                            reflectionGuides.primaryNormal,
                            reflectionGuides.primaryLinearRoughness,
                            primeNrdMaterialId(reflectionGuides.primaryMaterialFlags)));
            primeImageStore(
                    primeNrdReflectionSpecularMaterial,
                    int2(pixel),
                    float4(
                            primeNrdSanitizeAlbedo(
                                    reflectionGuides.primarySpecularAlbedo),
                            float(reflectionGuides.primaryMaterialFlags)));
        } else {
            // nrd_motion checks this marker before touching any other reflection input.
            primeImageStore(
                    primeNrdReflectionMaterial,
                    int2(pixel),
                    float4(1.0, 1.0, 1.0, -1.0));
        }
    } else if (primeTransparentGuideMode()
            == PRIME_PATH_TRANSPARENT_GUIDE_MODE_DLSS_RR) {
        primeImageStore(
                primeNrdReflectionPosition,
                int2(pixel),
                rrUsesReflectionGuide
                        ? float4(0.0, 0.0, 0.0, -1.0)
                        : reflectionPreviousVirtualPosition);
    }
    primeImageStore(
            primeNrdSunLighting,
            int2(pixel),
            float4(
                    primeNrdClampRadiance(
                            sampleResult.radiance.unshadowedSun, PRIME_NRD_FP16_MAX),
                    primeNrdSanitizeUnit(sampleResult.radiance.sunVisibility, 0.0)));
    primeImageStore(
            primeNrdSunPenumbra,
            int2(pixel),
            float4(primeNrdSanitizeHitDistance(sampleResult.guides.sunPenumbra)));
    // Visible sky/emission is deterministic current-frame energy. A second temporal history here
    // would retain stale silhouettes underneath the reconstruction pass.
    primeImageStore(
            primeStableRadiance,
            int2(pixel),
            float4(primeNrdSanitizeRadiance(sampleResult.radiance.stable), 1.0));
}

void primeWriteRealtimeOutput(
        inout PrimeNumericalDiagnostics diagnostics,
        uint2 pixel,
        float2 cameraSample,
        PrimeIntegrationResult sampleResult) {
    primeWriteRealtimeOutput(
            diagnostics,
            pixel,
            cameraSample,
            sampleResult,
            float4(0.0, 0.0, 0.0, -1.0),
            float3(0.0));
}

#endif
