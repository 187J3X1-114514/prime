#ifndef PRIME_WAVEFRONT_OUTPUT_GLSL
#define PRIME_WAVEFRONT_OUTPUT_GLSL

#include "auto_exposure.glsl"

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
            && transparentGuideMode == PRIME_PATH_TRANSPARENT_GUIDE_MODE_NRD) {
        flags |= primeClassifyNonFinite(result.reflectionGuides.primaryDistance)
                | primeClassifyDirection(result.reflectionGuides.primaryNormal)
                | primeClassifyNonnegative(result.reflectionGuides.primaryAlbedo)
                | primeClassifyNonnegative(result.reflectionGuides.primarySpecularAlbedo);
    }
    if (any(greaterThan(
            result.radiance.diffuse - result.guides.primaryAreaDiffuse, vec3(0.0)))) {
        flags |= primeClassifyOptionalDirection(result.guides.diffuseDirection);
    }
    if (any(greaterThan(
            result.radiance.specular - result.guides.primaryAreaSpecular, vec3(0.0)))) {
        flags |= primeClassifyOptionalDirection(result.guides.specularDirection);
    }
    if (any(greaterThan(result.guides.primaryAreaDiffuse, vec3(0.0)))
            || any(greaterThan(result.guides.primaryAreaSpecular, vec3(0.0)))) {
        flags |= primeClassifyOptionalDirection(result.guides.primaryAreaDirection);
    }
    return flags;
}

vec3 primeNrdEffectiveDirection(
        vec3 totalRadiance,
        vec3 primaryAreaRadiance,
        vec3 primaryAreaDirection,
        vec3 continuationDirection,
        vec3 albedo) {
    vec3 direct = min(primaryAreaRadiance, totalRadiance);
    vec3 continuation = max(totalRadiance - direct, vec3(0.0));
    float directY = primeNrdY(primeNrdDemodulate(direct, albedo));
    float continuationY = primeNrdY(primeNrdDemodulate(continuation, albedo));
    float totalY = directY + continuationY;
    vec3 moment = primaryAreaDirection * directY
            + continuationDirection * continuationY;
    return primeNrdIsFinite(moment) && primeNrdIsFinite(totalY) && totalY > 0.0
            ? clamp(moment / totalY, vec3(-1.0), vec3(1.0))
            : vec3(0.0);
}

void primeWriteRealtimeOutput(
        uvec2 pixel,
        vec2 cameraSample,
        PrimeIntegrationResult sampleResult,
        vec4 reflectionPreviousVirtualPosition) {
    if (primeWritesRawNumericalDiagnostic()) {
        primeSetNumericalContext(PRIME_NUMERICAL_STAGE_FINAL_OUTPUT, 0u);
        primeRecordNumerical(
                primeClassifyRawOutput(sampleResult), PRIME_NUMERICAL_FIELD_OUTPUT);
        imageStore(
                primeRawNumericalDiagnostic,
                ivec2(pixel),
                primeRawNumericalMetadata());
    }
    bool nrdShInputs = primeWritesNrdShInputs();
    bool hasTransmissionGuide = sampleResult.transparentPrimary
            && sampleResult.transmissionGuides.primaryHitKind == PRIME_HIT_SURFACE;
    bool usesTransmissionAnchor = hasTransmissionGuide
            && primeNrdIsFinite(sampleResult.transmissionAnchorDistance)
            && sampleResult.transmissionAnchorDistance > 0.0;
    // RR cannot represent both the interface and the refracted scene as primary geometry. Keep
    // the guide topology fixed: a finite transmitted non-delta surface owns the primary guide
    // regardless of its current Fresnel energy.
    bool rrUsesTransmissionGuide = !nrdShInputs && usesTransmissionAnchor;
    PrimeDenoiserGuides nrdGuides = sampleResult.transparentPrimary
            && (nrdShInputs || rrUsesTransmissionGuide)
            ? sampleResult.transmissionGuides
            : sampleResult.guides;
    vec3 noisyDiffuse = sampleResult.radiance.diffuse;
    vec3 noisySpecular = sampleResult.radiance.specular;
    if (!nrdShInputs && sampleResult.transparentPrimary) {
        noisyDiffuse += noisySpecular;
        noisySpecular = sampleResult.reflectionDiffuseRadiance
                + sampleResult.reflectionSpecularRadiance;
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
    vec3 primaryPosition = primeNrdIsFinite(nrdGuides.primaryPosition)
            ? nrdGuides.primaryPosition
            : vec3(0.0);
    bool primaryHasMotion = nrdGuides.primaryHasMotion
            && primeNrdIsFinite(nrdGuides.primaryPreviousPosition);
    vec3 primaryPreviousPosition = primaryHasMotion
            ? nrdGuides.primaryPreviousPosition
            : primaryPosition;
    if (usesTransmissionAnchor) {
        // A refracted hit does not project to this pixel under the camera's straight projection.
        // Put its plane intersection on the visible primary ray so a static camera has zero
        // motion while depth remains anchored to the target surface.
        vec3 fallbackDirection = primeNrdSafeNormalize(
                nrdGuides.primaryPosition, vec3(0.0, 0.0, 1.0));
        vec3 primaryDirection = primeNrdSafeNormalize(
                sampleResult.guides.primaryPosition, fallbackDirection);
        primaryPosition = primaryDirection * primaryDistance;
    }
    float specularHitDistance = !nrdShInputs && sampleResult.transparentPrimary
            ? sampleResult.guides.specularHitDistance
            : nrdGuides.specularHitDistance;
    float rawSignalScale = primeNrdClampRadiancePair(
            noisyDiffuse, noisySpecular, PRIME_NRD_FP16_MAX);
    // These images are a private raygen -> denoiser-preparation scratch contract. The existing
    // ray-tracing-to-compute barrier publishes them before nrd_motion converts them in place.
    imageStore(
            primeNrdNoisyDiffuse,
            ivec2(pixel),
            vec4(
                    primeNrdSanitizeRadiance(noisyDiffuse),
                    primeNrdSanitizeHitDistance(nrdGuides.diffuseHitDistance)));
    imageStore(
            primeNrdNoisySpecular,
            ivec2(pixel),
            vec4(
                    primeNrdSanitizeRadiance(noisySpecular),
                    primeNrdSanitizeHitDistance(specularHitDistance)));
    imageStore(
            primeNrdPrimaryPosition,
            ivec2(pixel),
            vec4(
                    primaryDistance < 0.0
                            ? vec3(0.0)
                            : (!nrdShInputs && primaryHasMotion
                                    ? primaryPreviousPosition
                                    : primaryPosition),
                    nrdShInputs
                            ? uintBitsToFloat(packHalf2x16(cameraSample))
                            : primaryDistance));
    if (nrdShInputs) {
        vec3 primaryAreaDiffuse = nrdGuides.primaryAreaDiffuse * rawSignalScale;
        vec3 primaryAreaSpecular = nrdGuides.primaryAreaSpecular * rawSignalScale;
        imageStore(
                primeNrdDiffuseDirection,
                ivec2(pixel),
                vec4(primeNrdEffectiveDirection(
                        noisyDiffuse,
                        primaryAreaDiffuse,
                        nrdGuides.primaryAreaDirection,
                        nrdGuides.diffuseDirection,
                        nrdGuides.primaryAlbedo), 0.0));
        imageStore(
                primeNrdSpecularDirection,
                ivec2(pixel),
                vec4(primeNrdEffectiveDirection(
                        noisySpecular,
                        primaryAreaSpecular,
                        nrdGuides.primaryAreaDirection,
                        nrdGuides.specularDirection,
                        nrdGuides.primarySpecularAlbedo), 0.0));
    }
    imageStore(
            primeNrdMaterial,
            ivec2(pixel),
            vec4(
                    primeNrdSanitizeAlbedo(nrdGuides.primaryAlbedo),
                    rrUsesTransmissionGuide ? visibleDistance : primaryDistance));
    imageStore(
            primeNrdNormalRoughness,
            ivec2(pixel),
            primeNrdPackNormalRoughness(
                    nrdGuides.primaryNormal,
                    nrdGuides.primaryLinearRoughness,
                    primeNrdMaterialId(primaryMaterialFlags)));
    imageStore(
            primeNrdSpecularMaterial,
            ivec2(pixel),
            vec4(
                    primeNrdSanitizeAlbedo(rrUsesTransmissionGuide
                            ? sampleResult.guides.primarySpecularAlbedo
                            : nrdGuides.primarySpecularAlbedo),
                    nrdShInputs
                            ? float(nrdGuides.primaryMaterialFlags)
                            : (primaryHasMotion
                                    ? -sampleResult.guides.primaryLinearRoughness - 1.0
                                    : sampleResult.guides.primaryLinearRoughness)));
    if (nrdShInputs) {
        bool visibleHasMotion = sampleResult.guides.primaryHasMotion
                && hasVisibleSurface
                && primeNrdIsFinite(
                        sampleResult.guides.primaryPreviousPosition);
        imageStore(
                primeNrdDisplayPosition,
                ivec2(pixel),
                vec4(
                        hasVisibleSurface
                                ? (visibleHasMotion
                                        ? sampleResult.guides.primaryPreviousPosition
                                        : sampleResult.guides.primaryPosition)
                                : vec3(0.0),
                        uintBitsToFloat(hasVisibleSurface
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
            vec3 reflectionDiffuse = sampleResult.reflectionDiffuseRadiance;
            vec3 reflectionSpecular = sampleResult.reflectionSpecularRadiance;
            float reflectionScale = primeNrdClampRadiancePair(
                    reflectionDiffuse, reflectionSpecular, PRIME_NRD_FP16_MAX);
            imageStore(
                    primeNrdReflectionNoisyDiffuse,
                    ivec2(pixel),
                    vec4(
                            primeNrdSanitizeRadiance(reflectionDiffuse),
                            primeNrdSanitizeHitDistance(
                                    reflectionGuides.diffuseHitDistance)));
            imageStore(
                    primeNrdReflectionNoisySpecular,
                    ivec2(pixel),
                    vec4(
                            primeNrdSanitizeRadiance(reflectionSpecular),
                            primeNrdSanitizeHitDistance(
                                    reflectionGuides.specularHitDistance)));
            imageStore(
                    primeNrdReflectionPosition,
                    ivec2(pixel),
                    vec4(
                            reflectionGuides.primaryPosition,
                            sampleResult.reflectionDirectionalGuide ? 1.0 : 0.0));
            imageStore(
                    primeNrdReflectionDiffuseDirection,
                    ivec2(pixel),
                    vec4(primeNrdEffectiveDirection(
                            reflectionDiffuse,
                            reflectionGuides.primaryAreaDiffuse * reflectionScale,
                            reflectionGuides.primaryAreaDirection,
                            reflectionGuides.diffuseDirection,
                            reflectionGuides.primaryAlbedo), 0.0));
            imageStore(
                    primeNrdReflectionSpecularDirection,
                    ivec2(pixel),
                    vec4(primeNrdEffectiveDirection(
                            reflectionSpecular,
                            reflectionGuides.primaryAreaSpecular * reflectionScale,
                            reflectionGuides.primaryAreaDirection,
                            reflectionGuides.specularDirection,
                            reflectionGuides.primarySpecularAlbedo), 0.0));
            imageStore(
                    primeNrdReflectionMaterial,
                    ivec2(pixel),
                    vec4(
                            primeNrdSanitizeAlbedo(reflectionGuides.primaryAlbedo),
                            reflectionDistance));
            imageStore(
                    primeNrdReflectionNormalRoughness,
                    ivec2(pixel),
                    primeNrdPackNormalRoughness(
                            reflectionGuides.primaryNormal,
                            reflectionGuides.primaryLinearRoughness,
                            primeNrdMaterialId(reflectionGuides.primaryMaterialFlags)));
            imageStore(
                    primeNrdReflectionSpecularMaterial,
                    ivec2(pixel),
                    vec4(
                            primeNrdSanitizeAlbedo(
                                    reflectionGuides.primarySpecularAlbedo),
                            float(reflectionGuides.primaryMaterialFlags)));
        } else {
            // nrd_motion checks this marker before touching any other reflection input.
            imageStore(
                    primeNrdReflectionMaterial,
                    ivec2(pixel),
                    vec4(1.0, 1.0, 1.0, -1.0));
        }
    } else if (primeTransparentGuideMode()
            == PRIME_PATH_TRANSPARENT_GUIDE_MODE_DLSS_RR) {
        imageStore(
                primeNrdReflectionPosition,
                ivec2(pixel),
                reflectionPreviousVirtualPosition);
    }
    imageStore(
            primeNrdSunLighting,
            ivec2(pixel),
            vec4(
                    primeNrdClampRadiance(
                            sampleResult.radiance.unshadowedSun, PRIME_NRD_FP16_MAX),
                    primeNrdSanitizeUnit(sampleResult.radiance.sunVisibility, 0.0)));
    imageStore(
            primeNrdSunPenumbra,
            ivec2(pixel),
            vec4(primeNrdSanitizeHitDistance(sampleResult.guides.sunPenumbra)));
    // Visible sky/emission is deterministic current-frame energy. A second temporal history here
    // would retain stale silhouettes underneath the reconstruction pass.
    imageStore(
            primeStableRadiance,
            ivec2(pixel),
            vec4(primeNrdSanitizeRadiance(sampleResult.radiance.stable), 1.0));
}

void primeWriteRealtimeOutput(
        uvec2 pixel,
        vec2 cameraSample,
        PrimeIntegrationResult sampleResult) {
    primeWriteRealtimeOutput(
            pixel,
            cameraSample,
            sampleResult,
            vec4(0.0, 0.0, 0.0, -1.0));
}

#endif
