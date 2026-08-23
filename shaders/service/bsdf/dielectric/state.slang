#language slang 2026
module "service/bsdf/dielectric/state.slang";

import "service/bsdf/contract/sample.slang";
import "bsdf/compact/contract/material.slang";
import "bsdf/compact/dielectric/state.slang";
import "bsdf/compact/dielectric/contract.slang";
import "service/bsdf/support/transmission_energy.slang";
import "model/material/defaults.slang";
import "model/material/translation.slang";
import "model/material/medium_boundary.slang";
import "model/material/transparent.slang";
import "service/bsdf/support/conversion.slang";
import "service/bsdf/support/volume_transition.slang";
import "bsdf/compact/contract/volume.slang";
import "bsdf/compact/math/spectrum.slang";
import "bsdf/compact/math/frame.slang";
import "bsdf/compact/model/material.slang";
import "bsdf/compact/model/volume.slang";

public float primeMinecraftInterfaceIor(uint materialControl, uint opticalControl) {
    PrimeOpticalMaterial translated = primeDecodeOpticalMaterial(
            0.0, opticalControl, materialControl);
    return primeMaterialIsWater(materialControl)
            ? 1.333
            : primeOpenPbrF0ToIor(translated.dielectricF0);
}

public PrimeCompactTransmissionState primeMinecraftTransmissionStateWithMinimumRoughness(
        float3 baseColor,
        float opacity,
        float3 outwardNormal,
        uint materialControl,
        float roughness,
        uint opticalControl,
        float3 viewDirection,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack,
        float minimumLinearRoughness) {
    bool thinWalled = (materialControl & PRIME_MATERIAL_THIN_WALLED) != 0u;
    float3 closureNormal = thinWalled && dot(outwardNormal, viewDirection) < 0.0
            ? -outwardNormal
            : outwardNormal;
    PrimeOpenPbrMaterial material = primeMinecraftTransmissionMaterialWithMinimumRoughness(
            baseColor,
            opacity,
            closureNormal,
            materialControl,
            roughness,
            opticalControl,
            minimumLinearRoughness);
    float3 localView = primeOpenPbrOnbToLocal(material.geometry.onb, viewDirection);
    float inverseOutsideIor = primeCompactInverseOutsideIor(localView.z, volumeStack);
    PrimeCompactTransmissionState state = primeCompactTransmissionStateInit(
            primeTransmissionGgxEnergy(),
            material,
            localView,
            inverseOutsideIor,
            rayT);
    return primeCompactTransmissionInterfaceState(
            primeTransmissionGgxEnergy(), localView, state);
}

public PrimeCompactTransmissionState primeMinecraftTransmissionState(
        float3 baseColor,
        float opacity,
        float3 outwardNormal,
        uint materialControl,
        float roughness,
        uint opticalControl,
        float3 viewDirection,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack) {
    return primeMinecraftTransmissionStateWithMinimumRoughness(
            baseColor,
            opacity,
            outwardNormal,
            materialControl,
            roughness,
            opticalControl,
            viewDirection,
            rayT,
            volumeStack,
            0.0);
}

public bool primeHasAdjacentMedium(uint adjacentInterfaceControl) {
    return (adjacentInterfaceControl & PRIME_ADJACENT_INTERFACE_VALID) != 0u;
}

public bool primeUsesAdjacentAirGap(
        bool usesAirGap,
        uint materialControl,
        uint opticalControl,
        uint adjacentInterfaceControl) {
    return (adjacentInterfaceControl & PRIME_ADJACENT_MICRO_GAP_ELIGIBLE) != 0u
            && primeAirGapCompatible(
                    usesAirGap,
                    materialControl,
                    opticalControl & PRIME_OPTICAL_FRESNEL_MASK,
                    primeAdjacentMaterialControl(adjacentInterfaceControl),
                    primeAdjacentFresnelCode(adjacentInterfaceControl));
}

public PrimeOpenPbrVolume primeAdjacentMediumVolume(
        float3 baseColor,
        uint adjacentInterfaceControl) {
    uint materialControl = primeAdjacentMaterialControl(adjacentInterfaceControl);
    uint opticalControl = primeAdjacentFresnelCode(adjacentInterfaceControl);
    PrimeOpenPbrMaterial material = primeMinecraftTransmissionMaterial(
            baseColor,
            1.0,
            float3(0.0, 1.0, 0.0),
            materialControl,
            0.0,
            opticalControl);
    PrimeOpenPbrVolume volume = primeOpenPbrVolumeFromTransmission(material.transmission);
    volume.ior = material.specular.ior;
    return volume;
}

public PrimeOpenPbrVolume primeBoundarySurfaceVolume(
        float3 baseColor,
        float opacity,
        uint materialControl,
        uint opticalControl) {
    PrimeOpenPbrMaterial material = primeMinecraftTransmissionMaterial(
            baseColor,
            opacity,
            float3(0.0, 1.0, 0.0),
            materialControl,
            0.0,
            opticalControl);
    PrimeOpenPbrVolume volume = primeOpenPbrVolumeFromTransmission(material.transmission);
    volume.ior = material.specular.ior;
    return volume;
}

public PrimeOpenPbrMaterial primeAirGapMaterial(
        float3 outwardNormal,
        float minimumLinearRoughness) {
    PrimeOpenPbrMaterial material = primeCompactMaterialFromMetallic(
            float3(1.0), minimumLinearRoughness, 0.0, outwardNormal);
    material.weight.transmission = 1.0;
    material.geometry.thinWalled = 1u;
    material.geometry.thickness = PRIME_THIN_WALLED_THICKNESS_M;
    material.specular.ior = 1.0;
    material.transmission.color = float3(1.0);
    material.transmission.depth = 0.0;
    material.transmission.scatter = float3(0.0);
    material.transmission.scatterAnisotropy = 0.0;
    material.transmission.dispersionScale = 0.0;
    return material;
}

public PrimeCompactTransmissionState primeMinecraftBoundaryTransmissionStateWithMinimumRoughness(
        float3 baseColor,
        float opacity,
        float3 outwardNormal,
        uint materialControl,
        float roughness,
        uint opticalControl,
        float3 viewDirection,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack,
        float minimumLinearRoughness,
        float3 adjacentBaseColor,
        uint adjacentInterfaceControl,
        bool usesAirGap) {
    if (!primeHasAdjacentMedium(adjacentInterfaceControl)) {
        return primeMinecraftTransmissionStateWithMinimumRoughness(
                baseColor,
                opacity,
                outwardNormal,
                materialControl,
                roughness,
                opticalControl,
                viewDirection,
                rayT,
                volumeStack,
                minimumLinearRoughness);
    }
    PrimeOpenPbrMaterial material = primeMinecraftTransmissionMaterialWithMinimumRoughness(
            baseColor,
            opacity,
            outwardNormal,
            materialControl,
            roughness,
            opticalControl,
            minimumLinearRoughness);
    float3 localView = primeOpenPbrOnbToLocal(material.geometry.onb, viewDirection);
    PrimeOpenPbrVolume adjacent = primeAdjacentMediumVolume(
            adjacentBaseColor, adjacentInterfaceControl);
    bool airGap = primeUsesAdjacentAirGap(
            usesAirGap,
            materialControl,
            opticalControl,
            adjacentInterfaceControl);
    if (airGap) {
        bool enteringBoundaryMaterial = dot(outwardNormal, viewDirection) >= 0.0;
        float3 closureNormal = enteringBoundaryMaterial
                ? outwardNormal
                : -outwardNormal;
        material = primeAirGapMaterial(
                closureNormal, minimumLinearRoughness);
        localView = primeOpenPbrOnbToLocal(material.geometry.onb, viewDirection);
        PrimeCompactTransmissionState state = primeCompactTransmissionStateInit(
                primeTransmissionGgxEnergy(),
                material,
                localView,
                1.0 / adjacent.ior,
                rayT);
        // Thin-wall shading orients its local frame toward the incident ray. Preserve which
        // physical voxel medium lies across the authored boundary for the later stack swap.
        state.entering = enteringBoundaryMaterial ? 1u : 0u;
        return state;
    }
    PrimeCompactTransmissionState state = primeCompactTransmissionStateInit(
            primeTransmissionGgxEnergy(),
            material,
            localView,
            1.0 / adjacent.ior,
            rayT);
    return primeCompactTransmissionInterfaceState(
            primeTransmissionGgxEnergy(), localView, state);
}

public PrimeCompactTransmissionState primeMinecraftBoundaryTransmissionState(
        float3 baseColor,
        float opacity,
        float3 outwardNormal,
        uint materialControl,
        float roughness,
        uint opticalControl,
        float3 viewDirection,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack,
        float3 adjacentBaseColor,
        uint adjacentInterfaceControl,
        bool usesAirGap) {
    return primeMinecraftBoundaryTransmissionStateWithMinimumRoughness(
            baseColor,
            opacity,
            outwardNormal,
            materialControl,
            roughness,
            opticalControl,
            viewDirection,
            rayT,
            volumeStack,
            0.0,
            adjacentBaseColor,
            adjacentInterfaceControl,
            usesAirGap);
}

public PrimeTransmissiveBsdfSample primeApplyAdjacentMediumTransition(
        PrimeTransmissiveBsdfSample sampled,
        PrimeCompactTransmissionState state,
        PrimeOpenPbrVolumeStack originalStack,
        float3 baseColor,
        float opacity,
        uint materialControl,
        uint opticalControl,
        float3 adjacentBaseColor,
        uint adjacentInterfaceControl,
        bool usesAirGap) {
    if (!primeHasAdjacentMedium(adjacentInterfaceControl)
            || (sampled.bsdfSample.eventFlags & PRIME_BSDF_EVENT_TRANSMISSION) == 0u) {
        return sampled;
    }
    PrimeOpenPbrVolume target;
    if (state.entering != 0u) {
        if (primeUsesAdjacentAirGap(
                usesAirGap,
                materialControl,
                opticalControl,
                adjacentInterfaceControl)) {
            target = primeBoundarySurfaceVolume(
                    baseColor, opacity, materialControl, opticalControl);
        } else {
            target = state.transmissionVolume;
            target.ior = state.originalIor;
        }
    } else {
        target = primeAdjacentMediumVolume(
                adjacentBaseColor, adjacentInterfaceControl);
    }
    sampled.volumeStack = originalStack;
    if (sampled.volumeStack.count == 0u) {
        primeOpenPbrStackPush(sampled.volumeStack, target);
    } else {
        sampled.volumeStack.values[sampled.volumeStack.count - 1u] = target;
    }
    return sampled;
}
