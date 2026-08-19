#language slang 2026
module "integrator/integration_result.slang";

import "integrator/path_contract.slang";
import "material/default_material.slang";

// This is the complete Monte Carlo estimate, split only because realtime denoisers consume
// different signal classes. Adding these mutually exclusive partitions reconstructs the beauty
// sample exactly; no guide is allowed to feed back into these values.
public struct PrimePathRadiance {
    public float3 diffuse;
    public float3 specular;
    public float3 stable;
    public float3 unshadowedSun;
    public float sunVisibility;
};

// Auxiliary observations used by reconstruction. They describe the sampled path and primary
// surface, but are not radiance contributions and therefore cannot change the reference estimate.
public struct PrimeDenoiserGuides {
    public float primaryDistance;
    public float specularHitDistance;
    public float diffuseHitDistance;
    public float sunPenumbra;
    public float3 primaryAlbedo;
    public uint primaryHitKind;
    public float3 primaryNormal;
    public uint primaryMaterialFlags;
    public float3 primarySpecularAlbedo;
    public float primaryLinearRoughness;
    public float3 primaryPosition;
    public float3 primaryPreviousPosition;
    public bool primaryHasMotion;
    public float3 diffuseDirection;
    public float3 specularDirection;
    public float3 primaryAreaDiffuse;
    public float3 primaryAreaSpecular;
    public float3 primaryAreaDirection;
};

public struct PrimeIntegrationResult {
    public PrimePathRadiance radiance;
    public PrimeDenoiserGuides guides;
    public float3 reflectionDiffuseRadiance;
    public float3 reflectionSpecularRadiance;
    public PrimeDenoiserGuides transmissionGuides;
    public PrimeDenoiserGuides reflectionGuides;
    public float transmissionAnchorDistance;
    public bool reflectionDirectionalGuide;
    public bool transparentPrimary;
    public bool transparentReflectionOnly;
};

public struct PrimeTransparentBranchResult {
    public float3 diffuseRadiance;
    public float3 specularRadiance;
    public PrimeDenoiserGuides guides;
    public float firstHitDistance;
    public float anchorDistance;
    public bool directionalGuide;
};

public float3 primeResolveIntegrationRadiance(PrimeIntegrationResult result) {
    return result.radiance.diffuse
            + result.radiance.specular
            + result.reflectionDiffuseRadiance
            + result.reflectionSpecularRadiance
            + result.radiance.stable
            + result.radiance.unshadowedSun * result.radiance.sunVisibility;
}

public PrimeDenoiserGuides primeEmptyDenoiserGuides() {
    PrimeDenoiserGuides guides;
    guides.primaryDistance = -1.0;
    guides.specularHitDistance = 0.0;
    guides.diffuseHitDistance = 0.0;
    guides.sunPenumbra = 0.0;
    guides.primaryAlbedo = float3(0.0);
    guides.primaryHitKind = PRIME_HIT_NONE;
    guides.primaryNormal = float3(0.0, 1.0, 0.0);
    guides.primaryMaterialFlags = 0u;
    guides.primarySpecularAlbedo = float3(0.0);
    guides.primaryLinearRoughness = PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS;
    guides.primaryPosition = float3(0.0);
    guides.primaryPreviousPosition = float3(0.0);
    guides.primaryHasMotion = false;
    guides.diffuseDirection = float3(0.0);
    guides.specularDirection = float3(0.0);
    guides.primaryAreaDiffuse = float3(0.0);
    guides.primaryAreaSpecular = float3(0.0);
    guides.primaryAreaDirection = float3(0.0);
    return guides;
}

public PrimeIntegrationResult primeEmptyIntegrationResult() {
    PrimeIntegrationResult result;
    result.radiance.diffuse = float3(0.0);
    result.radiance.specular = float3(0.0);
    result.radiance.stable = float3(0.0);
    result.radiance.unshadowedSun = float3(0.0);
    result.radiance.sunVisibility = 0.0;
    result.guides = primeEmptyDenoiserGuides();
    result.reflectionDiffuseRadiance = float3(0.0);
    result.reflectionSpecularRadiance = float3(0.0);
    result.transmissionGuides = primeEmptyDenoiserGuides();
    result.reflectionGuides = primeEmptyDenoiserGuides();
    result.transmissionAnchorDistance = -1.0;
    result.reflectionDirectionalGuide = false;
    result.transparentPrimary = false;
    result.transparentReflectionOnly = false;
    return result;
}

public bool primeRrUsesReflectionPrimaryGuide(
        PrimeIntegrationResult result,
        float reflectionVirtualPositionKind) {
    // A finite PSR target turns pure TIR into an ordinary virtual primary view. Directional sky
    // and unavailable probes remain specular signals on the visible interface.
    return result.transparentPrimary
            && result.transparentReflectionOnly
            && reflectionVirtualPositionKind == 0.0
            && result.reflectionGuides.primaryHitKind == PRIME_HIT_SURFACE;
}

public float4 primeCapturedReflectionPreviousVirtualPosition(
        PrimeIntegrationResult result,
        bool reachedSolidAngle) {
    float3 position = result.reflectionGuides.primaryHasMotion
            ? result.reflectionGuides.primaryPreviousPosition
            : result.reflectionGuides.primaryPosition;
    bool finite = !any(isnan(position)) && !any(isinf(position));
    if (!finite) {
        return float4(0.0, 0.0, 0.0, -1.0);
    }
    if (result.reflectionDirectionalGuide) {
        float lengthSquared = dot(position, position);
        return lengthSquared > 1.0e-12 && !isnan(lengthSquared) && !isinf(lengthSquared)
                ? float4(position * rsqrt(lengthSquared), 1.0)
                : float4(0.0, 0.0, 0.0, -1.0);
    }
    return reachedSolidAngle
            ? float4(position, 0.0)
            : float4(0.0, 0.0, 0.0, -1.0);
}

public PrimeDenoiserGuides primeSelectReconstructionPrimaryGuides(
        PrimeIntegrationResult result,
        bool nrdShInputs,
        bool usesTransmissionGuide,
        bool usesReflectionGuide) {
    if (usesReflectionGuide) {
        return result.reflectionGuides;
    }
    // NRD composites its independent reflection lane only after resolving a valid primary lane.
    // Pure TIR therefore keeps the visible interface as a zero-signal geometry sentinel instead
    // of selecting the deliberately empty transmission identity.
    if (result.transparentPrimary
            && (usesTransmissionGuide
                    || (nrdShInputs && !result.transparentReflectionOnly))) {
        return result.transmissionGuides;
    }
    return result.guides;
}
