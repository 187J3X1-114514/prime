#ifndef PRIME_BSDF_SLANG
#define PRIME_BSDF_SLANG

import "bsdf/bsdf_common.slang";
import "bsdf/adapter/transmission_ggx_energy.slang";
import "bsdf/compact/openpbr_foliage.slang";
import "bsdf/compact/openpbr_material.slang";
import "bsdf/compact/openpbr_opaque.slang";
import "bsdf/compact/openpbr_transmission.slang";
import "bsdf/compact/openpbr_common.slang";
import "bsdf/compact/openpbr_fresnel.slang";
import "bsdf/compact/openpbr_microfacet.slang";
import "core/numerical.slang";
import "material/default_material.slang";
import "material/material_translation.slang";
import "material/medium_boundary.slang";
import "material/transparent_material.slang";

bool3 lessThan(float3 first, float3 second) { return first < second; }
bool3 lessThanEqual(float3 first, float3 second) { return first <= second; }

// Minecraft's translucent render layer exposes fixed conditional reflection/transmission proposals
// for the first-visible split, then uses the complete compact closure for later queued
// continuation. The adapter owns the conditional primary proposals and medium handoff.
struct PrimeTransmissiveBsdfSample {
    BsdfSample bsdfSample;
    PrimeOpenPbrVolumeStack volumeStack;
};

struct PrimeTransmissiveBsdfSplit {
    PrimeTransmissiveBsdfSample reflection;
    PrimeTransmissiveBsdfSample transmission;
};

bool primeOpenPbrHasSample(
        PrimeOpenPbrSample sampleValue,
        inout PrimeNumericalObservation observation) {
    if (sampleValue.throughput.flags == PRIME_OPENPBR_FLAG_NONE) {
        // A rejected proposal has no consumable payload. Prime replaces it with its canonical
        // zero-event sample, so inspecting discarded fields only creates diagnostic false positives.
        return false;
    }
    primeObserveNonnegative(observation, sampleValue.throughput.value);
    primeObserveNonnegative(observation, sampleValue.pdf);
    // relativeEta is sanitized after the adapter has accepted the internal proposal. Observe the
    // raw value here so that the sanitizer cannot erase the first numerical failure.
    primeObserveNonnegative(observation, sampleValue.eta);
    primeObserveDirection(observation, sampleValue.wo);
    if (!primeBsdfDirection(sampleValue.wo)
            || !primeBsdfFinite(sampleValue.throughput.value)
            || !primeBsdfFinite(sampleValue.pdf)
            || any(lessThan(sampleValue.throughput.value, float3(0.0)))
            || !(sampleValue.pdf > 0.0)) {
        // Float singularities in the reference closure are contained at Prime's adapter boundary.
        // A rejected proposal contributes zero and cannot poison later MIS or throughput state.
        return false;
    }
    return true;
}

bool primeOpenPbrHasSample(PrimeOpenPbrSample sampleValue) {
    PrimeNumericalObservation observation = primeNumericalObservationCreate(false);
    return primeOpenPbrHasSample(sampleValue, observation);
}

PrimeOpenPbrVolumeStack primeEmptyVolumeStack() {
    PrimeOpenPbrVolumeStack result;
    result.values[0].extinction = float3(0.0);
    result.values[0].albedo = float3(0.0);
    result.values[0].anisotropy = 0.0;
    result.values[0].ior = 1.0;
    result.values[1] = result.values[0];
    result.count = 0u;
    return result;
}

PrimeOpenPbrMaterial primeMinecraftTransmissionMaterialWithMinimumRoughness(
        float3 baseColor,
        float opacity,
        float3 outwardNormal,
        uint materialControl,
        float roughness,
        uint opticalControl,
        float minimumLinearRoughness) {
    bool water = primeMaterialIsWater(materialControl);
    bool thinWalled = (materialControl & PRIME_MATERIAL_THIN_WALLED) != 0u;
    PrimeOpticalMaterial translated = primeDecodeOpticalMaterial(
            roughness, opticalControl, materialControl);
    // Alpha classifies the interface; it never scales roughness. Rough texels use authored
    // Authored roughness or the configured fallback is already resolved by the material composer.
    float interfaceRoughness = max(
            primeMaterialEffectiveLinearRoughness(
                    materialControl, translated.perceptualRoughness),
            minimumLinearRoughness);
    PrimeOpenPbrMaterial material = primeCompactMaterialFromMetallic(
            float3(1.0), interfaceRoughness, 0.0, outwardNormal);
    material.weight.transmission = 1.0;
    material.geometry.thinWalled = thinWalled ? 1u : 0u;
    material.geometry.thickness = thinWalled ? PRIME_THIN_WALLED_THICKNESS_M : 1.0;
    // Water's measured boundary is authoritative. Other transmissive models accept only the
    // translator's clamped dielectric F0; custom and predefined-metal encodings fall back to 4%.
    material.specular.ior = water
            ? 1.333
            : primeOpenPbrF0ToIor(translated.dielectricF0);

    if (water) {
        // RoboCute's transmission ABI stores transmittance at a reference depth and recovers
        // extinction as -log(T) / depth. Supplying the measured one-metre transmittance therefore
        // reconstructs PRIME_PURE_WATER_ABSORPTION_M_INV without changing its volume-stack code.
        material.transmission.color = exp(-PRIME_PURE_WATER_ABSORPTION_M_INV);
    } else if (primeMaterialIsColorlessGlass(materialControl)) {
        material.transmission.color = float3(1.0);
    } else {
        material.transmission.color = primeGlassVolumeTransmittance(
                baseColor, PRIME_STAINED_GLASS_ABSORPTION_OPACITY);
    }
    // Solid transparent materials describe one-metre Beer-Lambert transmittance and enter the
    // path medium stack. True zero-volume geometry retains RoboCute's explicit thin-wall tint.
    material.transmission.depth = thinWalled ? 0.0 : 1.0;
    material.transmission.scatter = float3(0.0);
    material.transmission.scatterAnisotropy = 0.0;
    material.transmission.dispersionScale = 0.0;
    return material;
}

PrimeOpenPbrMaterial primeMinecraftTransmissionMaterial(
        float3 baseColor,
        float opacity,
        float3 outwardNormal,
        uint materialControl,
        float roughness,
        uint opticalControl) {
    return primeMinecraftTransmissionMaterialWithMinimumRoughness(
            baseColor,
            opacity,
            outwardNormal,
            materialControl,
            roughness,
            opticalControl,
            0.0);
}

PrimeOpenPbrVolumeStack primeCameraWaterVolumeStack() {
    PrimeOpenPbrVolumeStack result = primeEmptyVolumeStack();
    // The camera starts without an intersected surface, but pure-water absorption is independent
    // of the discarded biome surface tint, so a neutral placeholder reconstructs the same medium.
    PrimeOpenPbrMaterial material = primeMinecraftTransmissionMaterial(
            float3(1.0),
            1.0,
            float3(0.0, 1.0, 0.0),
            PRIME_MATERIAL_FAMILY_DIELECTRIC | PRIME_MATERIAL_MEDIUM_WATER,
            0.0,
            0u);
    PrimeOpenPbrVolume volume = primeOpenPbrVolumeFromTransmission(material.transmission);
    volume.ior = material.specular.ior;
    primeOpenPbrStackPush(result, volume);
    return result;
}

uint primeOpenPbrToBsdfEventFlags(uint flags) {
    uint result = 0u;
    if (primeOpenPbrIsReflective(flags)) {
        result |= PRIME_BSDF_EVENT_REFLECTION;
    }
    if (primeOpenPbrIsTransmissive(flags)) {
        result |= PRIME_BSDF_EVENT_TRANSMISSION;
    }
    if (primeOpenPbrIsDiffuse(flags)) {
        result |= PRIME_BSDF_EVENT_DIFFUSE;
    }
    if (primeOpenPbrIsSpecular(flags)) {
        result |= PRIME_BSDF_EVENT_GLOSSY;
    }
    if (primeOpenPbrIsDelta(flags)) {
        result |= PRIME_BSDF_EVENT_DISCRETE;
    }
    return result;
}

PrimeOpenPbrMaterial primeOpaqueMaterialWithMinimumRoughness(
        float3 baseColor,
        float3 normal,
        float roughness,
        uint opticalControl,
        uint flags,
        float minimumLinearRoughness) {
    PrimeOpticalMaterial translated = primeDecodeOpticalMaterial(
            roughness, opticalControl, flags);
    bool metal = primeOpticalMaterialIsMetal(translated);
    PrimeOpenPbrMaterial material = primeCompactMaterialFromMetallic(
            baseColor,
            max(translated.perceptualRoughness, minimumLinearRoughness),
            metal ? 1.0 : 0.0,
            normal);
    if (metal) {
        PrimeConductorFresnel fresnel = primeConductorFresnel(
                baseColor, translated.fresnelCode);
        material.base.color = fresnel.f0;
        material.specular.color = fresnel.f82Tint;
    } else {
        material.specular.ior = primeOpenPbrF0ToIor(translated.dielectricF0);
        material.weight.subsurface = translated.subsurfaceWeight;
        material.subsurface.color = baseColor;
        if (translated.thinWalled != 0u) {
            material.geometry.thinWalled = 1u;
            material.geometry.thickness = 0.0625;
        }
    }
    return material;
}

PrimeOpenPbrMaterial primeOpaqueMaterial(
        float3 baseColor,
        float3 normal,
        float roughness,
        uint opticalControl,
        uint flags) {
    return primeOpaqueMaterialWithMinimumRoughness(
            baseColor, normal, roughness, opticalControl, flags, 0.0);
}

PrimeOpenPbrMaterial primeMinecraftTransmissionMaterial(
        float3 baseColor,
        float opacity,
        float3 outwardNormal,
        uint materialControl) {
    return primeMinecraftTransmissionMaterial(
            baseColor,
            opacity,
            outwardNormal,
            materialControl,
            packUnorm4x8(float4(0.5, 0.5, 1.0, 1.0)),
            packUnorm4x8(float4(0.0, 4.0 / 255.0, 0.0, 1.0)));
}

PrimeCompactOpaqueState primeOpaqueStateWithMinimumRoughness(
        float3 baseColor,
        float3 normal,
        float roughness,
        uint opticalControl,
        uint flags,
        float3 viewDirection,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack,
        float minimumLinearRoughness) {
    PrimeOpenPbrMaterial material = primeOpaqueMaterialWithMinimumRoughness(
            baseColor,
            normal,
            roughness,
            opticalControl,
            flags,
            minimumLinearRoughness);
    float3 localView = primeOpenPbrOnbToLocal(material.geometry.onb, viewDirection);
    float inverseOutsideIor = primeCompactInverseOutsideIor(localView.z, volumeStack);
    // Material translation restricts opaque production inputs to the exact OpenPBR
    // dielectric/conductor/subsurface submanifold accepted by the compact module.
    return primeCompactOpaqueStateInit(
            primeTransmissionGgxEnergy(),
            material,
            localView,
            inverseOutsideIor,
            rayT);
}

PrimeCompactOpaqueState primeOpaqueState(
        float3 baseColor,
        float3 normal,
        float roughness,
        uint opticalControl,
        uint flags,
        float3 viewDirection,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack) {
    return primeOpaqueStateWithMinimumRoughness(
            baseColor,
            normal,
            roughness,
            opticalControl,
            flags,
            viewDirection,
            rayT,
            volumeStack,
            0.0);
}

struct PrimeBsdfComponents {
    float3 diffuseResponse;
    float3 specularResponse;
    float pdf;
};

PrimeBsdfComponents primeSanitizeBsdfComponents(PrimeBsdfComponents components) {
    if (!primeBsdfFinite(components.diffuseResponse)
            || !primeBsdfFinite(components.specularResponse)
            || !primeBsdfFinite(components.pdf)
            || any(lessThan(components.diffuseResponse, float3(0.0)))
            || any(lessThan(components.specularResponse, float3(0.0)))
            || components.pdf < 0.0) {
        components.diffuseResponse = float3(0.0);
        components.specularResponse = float3(0.0);
        components.pdf = 0.0;
    }
    return components;
}

PrimeBsdfComponents primeEvaluateOpaqueComponents(
        float3 baseColor,
        float3 normal,
        float roughness,
        uint opticalControl,
        uint flags,
        float3 viewDirection,
        float3 scatterDirection,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack,
        inout PrimeNumericalObservation observation) {
    PrimeBsdfComponents result;
    result.diffuseResponse = float3(0.0);
    result.specularResponse = float3(0.0);
    result.pdf = 0.0;
    PrimeCompactOpaqueState state = primeOpaqueState(
            baseColor,
            normal,
            roughness,
            opticalControl,
            flags,
            viewDirection,
            rayT,
            volumeStack);
    float3 localView = primeOpenPbrOnbToLocal(state.onb, viewDirection);
    float3 localScatter = primeOpenPbrOnbToLocal(state.onb, scatterDirection);
    PrimeCompactOpaqueComponents compact = primeCompactOpaqueEvaluateComponents(
            localView, localScatter, state);
    result.diffuseResponse = compact.diffuse;
    result.specularResponse = compact.specular;
    result.pdf = compact.pdf;
    primeObserveNonnegative(observation, result.diffuseResponse);
    primeObserveNonnegative(observation, result.specularResponse);
    primeObserveNonnegative(observation, result.pdf);
    result = primeSanitizeBsdfComponents(result);
    return result;
}

PrimeBsdfComponents primeEvaluateOpaqueComponents(
        float3 baseColor,
        float3 normal,
        float roughness,
        uint opticalControl,
        uint flags,
        float3 viewDirection,
        float3 scatterDirection,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack) {
    PrimeNumericalObservation observation = primeNumericalObservationCreate(false);
    return primeEvaluateOpaqueComponents(
            baseColor,
            normal,
            roughness,
            opticalControl,
            flags,
            viewDirection,
            scatterDirection,
            rayT,
            volumeStack,
            observation);
}

BsdfSample primeSampleOpaqueFromState(
        PrimeCompactOpaqueState state,
        float3 normal,
        float3 viewDirection,
        float3 sampleValue,
        PrimeOpenPbrVolumeStack volumeStack,
        inout PrimeNumericalObservation observation) {
    BsdfSample result = primeInvalidBsdfSample();
    float3 localView = primeOpenPbrOnbToLocal(state.onb, viewDirection);
    PrimeOpenPbrSampleResult sampled = primeCompactOpaqueSample(
            localView, sampleValue, state, volumeStack);
    if (!primeOpenPbrHasSample(sampled.bsdfSample, observation)) {
        return result;
    }
    result.direction = primeOpenPbrOnbToWorld(
            state.onb, sampled.bsdfSample.wo);
    if ((sampled.bsdfSample.throughput.flags & PRIME_OPENPBR_FLAG_DELTA_REFLECTION) != 0u) {
        // Avoid a local-to-world round trip exactly where the reflected direction can be only a
        // few ulps above the tangent plane. This is algebraically the same discrete direction, but
        // preserves the world-space hemisphere at extreme grazing angles.
        result.direction = normalize(reflect(-viewDirection, normal));
    }
    result.response = sampled.bsdfSample.throughput.value;
    result.pdf = sampled.bsdfSample.pdf;
    result.relativeEta = 1.0;
    result.eventFlags = primeOpenPbrToBsdfEventFlags(sampled.bsdfSample.throughput.flags);
    return primeSanitizeBsdfSample(result);
}

BsdfSample primeSampleOpaqueFromState(
        PrimeCompactOpaqueState state,
        float3 normal,
        float3 viewDirection,
        float3 sampleValue,
        PrimeOpenPbrVolumeStack volumeStack) {
    PrimeNumericalObservation observation = primeNumericalObservationCreate(false);
    return primeSampleOpaqueFromState(
            state,
            normal,
            viewDirection,
            sampleValue,
            volumeStack,
            observation);
}

BsdfSample primeSampleOpaque(
        float3 baseColor,
        float3 normal,
        float roughness,
        uint opticalControl,
        uint flags,
        float3 viewDirection,
        float3 sampleValue,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack) {
    PrimeCompactOpaqueState state = primeOpaqueState(
            baseColor,
            normal,
            roughness,
            opticalControl,
            flags,
            viewDirection,
            rayT,
            volumeStack);
    return primeSampleOpaqueFromState(
            state, normal, viewDirection, sampleValue, volumeStack);
}

PrimeCompactTransmissionState primeMinecraftTransmissionStateWithMinimumRoughness(
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

PrimeCompactTransmissionState primeMinecraftTransmissionState(
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

bool primeHasAdjacentMedium(uint adjacentInterfaceControl) {
    return (adjacentInterfaceControl & PRIME_ADJACENT_INTERFACE_VALID) != 0u;
}

bool primeUsesAdjacentAirGap(
        uint materialControl,
        uint opticalControl,
        uint adjacentInterfaceControl) {
    return (adjacentInterfaceControl & PRIME_ADJACENT_MICRO_GAP_ELIGIBLE) != 0u
            && primeAirGapCompatible(
                    primeUsesAirGap(),
                    materialControl,
                    opticalControl & PRIME_OPTICAL_FRESNEL_MASK,
                    primeAdjacentMaterialControl(adjacentInterfaceControl),
                    primeAdjacentFresnelCode(adjacentInterfaceControl));
}

PrimeOpenPbrVolume primeAdjacentMediumVolume(
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

PrimeOpenPbrVolume primeBoundarySurfaceVolume(
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

PrimeOpenPbrMaterial primeAirGapMaterial(
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

PrimeCompactTransmissionState primeMinecraftBoundaryTransmissionStateWithMinimumRoughness(
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
        uint adjacentInterfaceControl) {
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
            materialControl, opticalControl, adjacentInterfaceControl);
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

PrimeCompactTransmissionState primeMinecraftBoundaryTransmissionState(
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
        uint adjacentInterfaceControl) {
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
            adjacentInterfaceControl);
}

PrimeTransmissiveBsdfSample primeApplyAdjacentMediumTransition(
        PrimeTransmissiveBsdfSample sampled,
        PrimeCompactTransmissionState state,
        PrimeOpenPbrVolumeStack originalStack,
        float3 baseColor,
        float opacity,
        uint materialControl,
        uint opticalControl,
        float3 adjacentBaseColor,
        uint adjacentInterfaceControl) {
    if (!primeHasAdjacentMedium(adjacentInterfaceControl)
            || (sampled.bsdfSample.eventFlags & PRIME_BSDF_EVENT_TRANSMISSION) == 0u) {
        return sampled;
    }
    PrimeOpenPbrVolume target;
    if (state.entering != 0u) {
        if (primeUsesAdjacentAirGap(
                materialControl, opticalControl, adjacentInterfaceControl)) {
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

PrimeOpenPbrMicrofacet primeMinecraftTransmissionMicrofacet(
        PrimeCompactTransmissionState state) {
    // RoboCute scales a thin dielectric's apparent interface roughness by its relative IOR.
    // At an index-matched boundary this can be discrete even when the authored roughness is not.
    return state.geometryThinWalled != 0u
            ? state.transmissionMicrofacet
            : state.specularMicrofacet;
}

bool primeMinecraftTransmissionIsDiscrete(PrimeCompactTransmissionState state) {
    // An index-matched dielectric has no visible rough boundary. RoboCute therefore collapses
    // transmission to straight-through discrete transport even when the authored NDF is rough.
    return state.specularFresnel.ior == 1.0
            || primeOpenPbrMicrofacetEffectivelySmooth(
                    primeMinecraftTransmissionMicrofacet(state));
}

struct PrimeMinecraftMirrorSplit {
    float3 reflectance;
};

PrimeMinecraftMirrorSplit primeMinecraftMirrorSplit(
        PrimeCompactTransmissionState state,
        float3 localView,
        inout PrimeNumericalObservation observation) {
    PrimeMinecraftMirrorSplit result;
    // The default Minecraft adapter keeps dielectric reflection achromatic. Retaining the color
    // term here makes the split remain correct if a future material decoder tints the interface.
    float directionalReflectance = state.geometryThinWalled != 0u
            ? primeOpenPbrMicrofacetDielectricMsCompensation(
                    primeTransmissionGgxEnergy(),
                    state.specularMicrofacet,
                    abs(localView.z),
                    state.specularFresnel.ior).z
            : state.transmissionMultipleScattering.z;
    result.reflectance = directionalReflectance * state.specularFresnel.color;
    primeObserveUnit(observation, result.reflectance);
    if (!primeBsdfFinite(result.reflectance)
            || any(lessThan(result.reflectance, float3(0.0)))) {
        result.reflectance = float3(0.0);
    } else {
        result.reflectance = clamp(result.reflectance, float3(0.0), float3(1.0));
    }
    return result;
}

PrimeMinecraftMirrorSplit primeMinecraftMirrorSplit(
        PrimeCompactTransmissionState state,
        float3 localView) {
    PrimeNumericalObservation observation = primeNumericalObservationCreate(false);
    return primeMinecraftMirrorSplit(state, localView, observation);
}

PrimeOpenPbrVolumeStack primeMinecraftTransmissionStack(
        PrimeCompactTransmissionState state,
        PrimeOpenPbrVolumeStack volumeStack) {
    if (state.geometryThinWalled != 0u) {
        return volumeStack;
    }
    if (state.entering != 0u) {
        PrimeOpenPbrVolume volume = state.transmissionVolume;
        volume.ior = state.originalIor;
        primeOpenPbrStackPush(volumeStack, volume);
    } else if (volumeStack.count > 0u) {
        primeOpenPbrStackPop(volumeStack);
    }
    return volumeStack;
}

float primeMinecraftInterfaceIor(
        PrimeCompactTransmissionState state) {
    // State initialization selected the medium on the other side of the boundary from the stack.
    return state.specularFresnel.ior;
}

PrimeTransmissiveBsdfSample primeSampleMinecraftRefractedTransmissionFromState(
        PrimeCompactTransmissionState state,
        float3 outwardNormal,
        float3 viewDirection,
        float3 interfaceWeight,
        bool reflectOnTotalInternalReflection,
        PrimeOpenPbrVolumeStack volumeStack) {
    PrimeTransmissiveBsdfSample result;
    result.bsdfSample = primeInvalidBsdfSample();
    result.volumeStack = volumeStack;
    if (state.geometryThinWalled != 0u) {
        result.bsdfSample.direction = -viewDirection;
        result.bsdfSample.response = interfaceWeight * state.transmissionTint;
        result.bsdfSample.pdf = 1.0;
        result.bsdfSample.relativeEta = 1.0;
        result.bsdfSample.eventFlags = PRIME_BSDF_EVENT_TRANSMISSION
                | PRIME_BSDF_EVENT_DISCRETE;
        result.bsdfSample = primeSanitizeBsdfSample(result.bsdfSample);
        return result;
    }

    PrimeOpenPbrRefractResult refracted = primeOpenPbrDielectricRefract(
            primeMinecraftInterfaceIor(state),
            -viewDirection,
            outwardNormal);
    if (refracted.valid == 0u) {
        // A forced transmission proposal has no support under TIR. Guide-only continuations use
        // the sole valid discrete event instead; the primary transmission slot stays empty because
        // its paired Fresnel reflection already carries this energy.
        if (!reflectOnTotalInternalReflection) {
            return result;
        }
        result.bsdfSample.direction = reflect(-viewDirection, outwardNormal);
        result.bsdfSample.response = interfaceWeight;
        result.bsdfSample.pdf = 1.0;
        result.bsdfSample.relativeEta = 1.0;
        result.bsdfSample.eventFlags = PRIME_BSDF_EVENT_REFLECTION
                | PRIME_BSDF_EVENT_DISCRETE;
        result.bsdfSample = primeSanitizeBsdfSample(result.bsdfSample);
        return result;
    }

    result.bsdfSample.direction = refracted.wo;
    // Match RoboCute's radiance-mode discrete transmission: the event carries 1 / eta^2, while
    // path etaScale remains reserved for Russian-roulette survival probability.
    result.bsdfSample.response = interfaceWeight
            / primeOpenPbrSquare(refracted.relativeIor);
    result.bsdfSample.pdf = 1.0;
    result.bsdfSample.relativeEta = refracted.relativeIor;
    result.bsdfSample.eventFlags = PRIME_BSDF_EVENT_TRANSMISSION
            | PRIME_BSDF_EVENT_DISCRETE;
    result.volumeStack = primeMinecraftTransmissionStack(
            state, volumeStack);
    result.bsdfSample = primeSanitizeBsdfSample(result.bsdfSample);
    if (result.bsdfSample.eventFlags == 0u) {
        result.volumeStack = volumeStack;
    }
    return result;
}

BsdfEvaluation primeEvaluateMinecraftTransparentReflection(
        float3 baseColor,
        float opacity,
        float3 outwardNormal,
        uint materialControl,
        float roughness,
        uint opticalControl,
        float3 viewDirection,
        float3 scatterDirection,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack,
        float3 adjacentBaseColor,
        uint adjacentInterfaceControl,
        inout PrimeNumericalObservation observation) {
    PrimeCompactTransmissionState state = primeMinecraftBoundaryTransmissionState(
            baseColor,
            opacity,
            outwardNormal,
            materialControl,
            roughness,
            opticalControl,
            viewDirection,
            rayT,
            volumeStack,
            adjacentBaseColor,
            adjacentInterfaceControl);
    float3 localView = primeOpenPbrOnbToLocal(state.onb, viewDirection);
    float3 localScatter = primeOpenPbrOnbToLocal(state.onb, scatterDirection);
    if (localView.z * localScatter.z < 0.0
            || primeMinecraftTransmissionIsDiscrete(state)) {
        // A coherent reflection has no finite solid-angle evaluation.
        return primeInvalidBsdfEvaluation();
    }
    PrimeCompactTransmissionEvaluation evaluation = primeCompactTransmissionEvaluate(
            localView, localScatter, state, PRIME_OPENPBR_FLAG_REFLECTION);
    BsdfEvaluation result = primeInvalidBsdfEvaluation();
    if (evaluation.throughput.flags != PRIME_OPENPBR_FLAG_NONE) {
        result.response = evaluation.throughput.value;
        result.pdf = evaluation.pdf;
    }
    primeObserveNonnegative(observation, result.response);
    primeObserveNonnegative(observation, result.pdf);
    result = primeSanitizeBsdfEvaluation(result);
    return result;
}

BsdfEvaluation primeEvaluateMinecraftTransparentReflection(
        float3 baseColor,
        float opacity,
        float3 outwardNormal,
        uint materialControl,
        float roughness,
        uint opticalControl,
        float3 viewDirection,
        float3 scatterDirection,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack,
        float3 adjacentBaseColor,
        uint adjacentInterfaceControl) {
    PrimeNumericalObservation observation = primeNumericalObservationCreate(false);
    return primeEvaluateMinecraftTransparentReflection(
            baseColor,
            opacity,
            outwardNormal,
            materialControl,
            roughness,
            opticalControl,
            viewDirection,
            scatterDirection,
            rayT,
            volumeStack,
            adjacentBaseColor,
            adjacentInterfaceControl,
            observation);
}

struct PrimeCompiledClosure {
    uint family;
    PrimeClosureTraits traits;
};

PrimeClosureTraits primeOpaqueClosureTraits(PrimeCompactOpaqueState state) {
    bool conductor = state.kind == PRIME_COMPACT_OPAQUE_CONDUCTOR;
    bool discreteSpecular = primeOpenPbrMicrofacetEffectivelySmooth(state.microfacet);
    PrimeClosureTraits result;
    result.measureMask = !conductor || !discreteSpecular
            ? PRIME_MEASURE_SOLID_ANGLE
            : 0u;
    if (discreteSpecular) {
        result.measureMask |= PRIME_MEASURE_DISCRETE;
    }
    result.eventMask = PRIME_BSDF_EVENT_REFLECTION
            | (!conductor ? PRIME_BSDF_EVENT_DIFFUSE : 0u)
            | (discreteSpecular
                    ? PRIME_BSDF_EVENT_DISCRETE
                    : PRIME_BSDF_EVENT_GLOSSY);
    return result;
}

PrimeCompiledClosure primeCompileOpaqueClosure(
        float3 baseColor,
        float3 normal,
        float roughness,
        uint opticalControl,
        uint materialControl,
        float3 viewDirection,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack) {
    PrimeCompiledClosure result;
    PrimeCompactOpaqueState state = primeOpaqueState(
            baseColor,
            normal,
            roughness,
            opticalControl,
            materialControl,
            viewDirection,
            rayT,
            volumeStack);
    result.family = PRIME_MATERIAL_FAMILY_OPAQUE;
    result.traits = primeOpaqueClosureTraits(state);
    return result;
}

PrimeClosureTraits primeMinecraftTransmissionClosureTraits(
        PrimeCompactTransmissionState state) {
    PrimeClosureTraits result;
    result.measureMask = primeMinecraftTransmissionIsDiscrete(state)
            ? PRIME_MEASURE_DISCRETE
            : PRIME_MEASURE_SOLID_ANGLE;
    result.eventMask = PRIME_BSDF_EVENT_REFLECTION
            | PRIME_BSDF_EVENT_TRANSMISSION
            | (result.measureMask == PRIME_MEASURE_DISCRETE
                    ? PRIME_BSDF_EVENT_DISCRETE
                    : PRIME_BSDF_EVENT_GLOSSY);
    return result;
}

PrimeCompiledClosure primeCompileMinecraftTransmissionClosure(
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
        uint adjacentInterfaceControl) {
    PrimeCompiledClosure result;
    PrimeCompactTransmissionState state = primeMinecraftBoundaryTransmissionState(
            baseColor,
            opacity,
            outwardNormal,
            materialControl,
            roughness,
            opticalControl,
            viewDirection,
            rayT,
            volumeStack,
            adjacentBaseColor,
            adjacentInterfaceControl);
    result.family = PRIME_MATERIAL_FAMILY_DIELECTRIC;
    result.traits = primeMinecraftTransmissionClosureTraits(state);
    return result;
}

BsdfEvaluation primeEvaluateMinecraftTransparentReflection(
        float3 baseColor,
        float opacity,
        float3 outwardNormal,
        uint materialControl,
        float roughness,
        uint opticalControl,
        float3 viewDirection,
        float3 scatterDirection,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack) {
    return primeEvaluateMinecraftTransparentReflection(
            baseColor,
            opacity,
            outwardNormal,
            materialControl,
            roughness,
            opticalControl,
            viewDirection,
            scatterDirection,
            rayT,
            volumeStack,
            float3(0.0),
            0u);
}

BsdfEvaluation primeEvaluateMinecraftTransparentTransmission(
        float3 baseColor,
        float opacity,
        float3 outwardNormal,
        uint materialControl,
        float roughness,
        uint opticalControl,
        float3 viewDirection,
        float3 scatterDirection,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack,
        float3 adjacentBaseColor,
        uint adjacentInterfaceControl,
        inout PrimeNumericalObservation observation) {
    PrimeCompactTransmissionState state = primeMinecraftBoundaryTransmissionState(
            baseColor,
            opacity,
            outwardNormal,
            materialControl,
            roughness,
            opticalControl,
            viewDirection,
            rayT,
            volumeStack,
            adjacentBaseColor,
            adjacentInterfaceControl);
    float3 localView = primeOpenPbrOnbToLocal(state.onb, viewDirection);
    float3 localScatter = primeOpenPbrOnbToLocal(state.onb, scatterDirection);
    if (localView.z * localScatter.z >= 0.0
            || primeMinecraftTransmissionIsDiscrete(state)) {
        return primeInvalidBsdfEvaluation();
    }
    PrimeCompactTransmissionEvaluation evaluation = primeCompactTransmissionEvaluate(
            localView, localScatter, state, PRIME_OPENPBR_FLAG_TRANSMISSION);
    BsdfEvaluation result = primeInvalidBsdfEvaluation();
    if (evaluation.throughput.flags != PRIME_OPENPBR_FLAG_NONE) {
        result.response = evaluation.throughput.value;
        result.pdf = evaluation.pdf;
    }
    primeObserveNonnegative(observation, result.response);
    primeObserveNonnegative(observation, result.pdf);
    result = primeSanitizeBsdfEvaluation(result);
    return result;
}

BsdfEvaluation primeEvaluateMinecraftTransparentTransmission(
        float3 baseColor,
        float opacity,
        float3 outwardNormal,
        uint materialControl,
        float roughness,
        uint opticalControl,
        float3 viewDirection,
        float3 scatterDirection,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack,
        float3 adjacentBaseColor,
        uint adjacentInterfaceControl) {
    PrimeNumericalObservation observation = primeNumericalObservationCreate(false);
    return primeEvaluateMinecraftTransparentTransmission(
            baseColor,
            opacity,
            outwardNormal,
            materialControl,
            roughness,
            opticalControl,
            viewDirection,
            scatterDirection,
            rayT,
            volumeStack,
            adjacentBaseColor,
            adjacentInterfaceControl,
            observation);
}

BsdfEvaluation primeEvaluateMinecraftTransparentTransmission(
        float3 baseColor,
        float opacity,
        float3 outwardNormal,
        uint materialControl,
        float roughness,
        uint opticalControl,
        float3 viewDirection,
        float3 scatterDirection,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack) {
    return primeEvaluateMinecraftTransparentTransmission(
            baseColor,
            opacity,
            outwardNormal,
            materialControl,
            roughness,
            opticalControl,
            viewDirection,
            scatterDirection,
            rayT,
            volumeStack,
            float3(0.0),
            0u);
}

PrimeTransmissiveBsdfSample primeSampleMinecraftTransparentReflectionFromState(
        PrimeCompactTransmissionState state,
        PrimeMinecraftMirrorSplit mirror,
        float3 outwardNormal,
        float3 viewDirection,
        float3 localView,
        float3 sampleValue,
        PrimeOpenPbrVolumeStack volumeStack,
        inout PrimeNumericalObservation observation);

PrimeTransmissiveBsdfSample primeSampleMinecraftTransparentTransmissionFromState(
        PrimeCompactTransmissionState state,
        PrimeMinecraftMirrorSplit mirror,
        float3 outwardNormal,
        float3 viewDirection,
        float3 localView,
        float3 sampleValue,
        PrimeOpenPbrVolumeStack volumeStack,
        inout PrimeNumericalObservation observation) {
    if (primeMinecraftTransmissionIsDiscrete(state)) {
        return primeSampleMinecraftRefractedTransmissionFromState(
                state,
                outwardNormal,
                viewDirection,
                float3(1.0) - mirror.reflectance,
                false,
                volumeStack);
    }

    PrimeTransmissiveBsdfSample result;
    result.bsdfSample = primeInvalidBsdfSample();
    result.volumeStack = volumeStack;
    PrimeOpenPbrSampleResult sampled = primeCompactTransmissionSample(
            localView,
            sampleValue,
            state,
            PRIME_OPENPBR_FLAG_TRANSMISSION,
            volumeStack);
    if (!primeOpenPbrHasSample(sampled.bsdfSample, observation)) {
        return result;
    }
    // Sampling flags normalize the proposal over transmission while the response retains the
    // complete Fresnel and rough-microfacet energy.
    result.bsdfSample.direction = primeOpenPbrOnbToWorld(
            state.onb, sampled.bsdfSample.wo);
    result.bsdfSample.response = sampled.bsdfSample.throughput.value;
    result.bsdfSample.pdf = sampled.bsdfSample.pdf;
    result.bsdfSample.relativeEta = sampled.bsdfSample.eta;
    result.bsdfSample.eventFlags = primeOpenPbrToBsdfEventFlags(
            sampled.bsdfSample.throughput.flags);
    result.volumeStack = sampled.volumeStack;
    result.bsdfSample = primeSanitizeBsdfSample(result.bsdfSample);
    if (result.bsdfSample.eventFlags == 0u) {
        result.volumeStack = volumeStack;
    }
    return result;
}

PrimeTransmissiveBsdfSample primeSampleMinecraftTransparentTransmissionFromState(
        PrimeCompactTransmissionState state,
        PrimeMinecraftMirrorSplit mirror,
        float3 outwardNormal,
        float3 viewDirection,
        float3 localView,
        float3 sampleValue,
        PrimeOpenPbrVolumeStack volumeStack) {
    PrimeNumericalObservation observation = primeNumericalObservationCreate(false);
    return primeSampleMinecraftTransparentTransmissionFromState(
            state,
            mirror,
            outwardNormal,
            viewDirection,
            localView,
            sampleValue,
            volumeStack,
            observation);
}

PrimeTransmissiveBsdfSample primeSampleMinecraftTransparentContinuationFromState(
        PrimeCompactTransmissionState state,
        float3 baseColor,
        float opacity,
        uint materialControl,
        float3 outwardNormal,
        float3 viewDirection,
        PrimeOpenPbrVolumeStack volumeStack) {
    return primeSampleMinecraftRefractedTransmissionFromState(
            state,
            outwardNormal,
            viewDirection,
            float3(1.0),
            true,
            volumeStack);
}

PrimeTransmissiveBsdfSample primeSampleMinecraftTransparentContinuation(
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
        uint adjacentInterfaceControl) {
    PrimeCompactTransmissionState state = primeMinecraftBoundaryTransmissionState(
            baseColor,
            opacity,
            outwardNormal,
            materialControl,
            roughness,
            opticalControl,
            viewDirection,
            rayT,
            volumeStack,
            adjacentBaseColor,
            adjacentInterfaceControl);
    PrimeTransmissiveBsdfSample sampled =
            primeSampleMinecraftTransparentContinuationFromState(
            state,
            baseColor,
            opacity,
            materialControl,
            outwardNormal,
            viewDirection,
            volumeStack);
    return primeApplyAdjacentMediumTransition(
            sampled,
            state,
            volumeStack,
            baseColor,
            opacity,
            materialControl,
            opticalControl,
            adjacentBaseColor,
            adjacentInterfaceControl);
}

PrimeTransmissiveBsdfSample primeSampleMinecraftTransparentContinuation(
        float3 baseColor,
        float opacity,
        float3 outwardNormal,
        uint materialControl,
        float roughness,
        uint opticalControl,
        float3 viewDirection,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack) {
    return primeSampleMinecraftTransparentContinuation(
            baseColor,
            opacity,
            outwardNormal,
            materialControl,
            roughness,
            opticalControl,
            viewDirection,
            rayT,
            volumeStack,
            float3(0.0),
            0u);
}

// A queued single-path integrator samples the complete transmissive closure instead of forcing a
// hemisphere. The closure's reflection/transmission selection probability remains in the PDF,
// and only a sampled transmission event may replace the medium stack.
PrimeTransmissiveBsdfSample primeSampleMinecraftTransmissionCompleteFromState(
        PrimeCompactTransmissionState state,
        float3 baseColor,
        float opacity,
        uint materialControl,
        float3 viewDirection,
        float3 sampleValue,
        PrimeOpenPbrVolumeStack volumeStack,
        inout PrimeNumericalObservation observation) {
    PrimeTransmissiveBsdfSample result;
    result.bsdfSample = primeInvalidBsdfSample();
    result.volumeStack = volumeStack;
    float3 localView = primeOpenPbrOnbToLocal(state.onb, viewDirection);
    PrimeOpenPbrSampleResult sampled = primeCompactTransmissionSample(
            localView, sampleValue, state, PRIME_OPENPBR_FLAG_ALL, volumeStack);
    if (!primeOpenPbrHasSample(sampled.bsdfSample, observation)) {
        return result;
    }
    result.bsdfSample.direction = primeOpenPbrOnbToWorld(
            state.onb, sampled.bsdfSample.wo);
    result.bsdfSample.response = sampled.bsdfSample.throughput.value;
    result.bsdfSample.pdf = sampled.bsdfSample.pdf;
    result.bsdfSample.relativeEta = sampled.bsdfSample.eta;
    result.bsdfSample.eventFlags = primeOpenPbrToBsdfEventFlags(
            sampled.bsdfSample.throughput.flags);
    result.volumeStack = sampled.volumeStack;
    result.bsdfSample = primeSanitizeBsdfSample(result.bsdfSample);
    if (result.bsdfSample.eventFlags == 0u) {
        result.volumeStack = volumeStack;
    }
    return result;
}

PrimeTransmissiveBsdfSample primeSampleMinecraftTransmissionCompleteFromState(
        PrimeCompactTransmissionState state,
        float3 baseColor,
        float opacity,
        uint materialControl,
        float3 viewDirection,
        float3 sampleValue,
        PrimeOpenPbrVolumeStack volumeStack) {
    PrimeNumericalObservation observation = primeNumericalObservationCreate(false);
    return primeSampleMinecraftTransmissionCompleteFromState(
            state,
            baseColor,
            opacity,
            materialControl,
            viewDirection,
            sampleValue,
            volumeStack,
            observation);
}

BsdfEvaluation primeEvaluateMinecraftTransmissionCompleteFromState(
        PrimeCompactTransmissionState state,
        float3 baseColor,
        float opacity,
        uint materialControl,
        float3 viewDirection,
        float3 scatterDirection,
        inout PrimeNumericalObservation observation) {
    float3 localView = primeOpenPbrOnbToLocal(state.onb, viewDirection);
    float3 localScatter = primeOpenPbrOnbToLocal(
            state.onb, scatterDirection);
    PrimeCompactTransmissionEvaluation evaluated = primeCompactTransmissionEvaluate(
            localView, localScatter, state, PRIME_OPENPBR_FLAG_ALL);
    BsdfEvaluation result = primeInvalidBsdfEvaluation();
    if (evaluated.throughput.flags != PRIME_OPENPBR_FLAG_NONE) {
        result.response = evaluated.throughput.value;
        result.pdf = evaluated.pdf;
    }
    primeObserveNonnegative(observation, result.response);
    primeObserveNonnegative(observation, result.pdf);
    return primeSanitizeBsdfEvaluation(result);
}

BsdfEvaluation primeEvaluateMinecraftTransmissionCompleteFromState(
        PrimeCompactTransmissionState state,
        float3 baseColor,
        float opacity,
        uint materialControl,
        float3 viewDirection,
        float3 scatterDirection) {
    PrimeNumericalObservation observation = primeNumericalObservationCreate(false);
    return primeEvaluateMinecraftTransmissionCompleteFromState(
            state,
            baseColor,
            opacity,
            materialControl,
            viewDirection,
            scatterDirection,
            observation);
}

PrimeTransmissiveBsdfSplit primeSampleMinecraftTransmissionSplitFromState(
        PrimeCompactTransmissionState state,
        float3 localView,
        PrimeMinecraftMirrorSplit mirror,
        float3 outwardNormal,
        float3 viewDirection,
        float3 reflectionSample,
        float3 baseColor,
        float opacity,
        uint materialControl,
        PrimeOpenPbrVolumeStack volumeStack,
        inout PrimeNumericalObservation observation) {
    // The first visible transparent interface evaluates one conditional reflection and one
    // conditional transmission event. Smooth interfaces remain exact; rough interfaces sample
    // the corresponding finite lobe without adding an event-selection probability.
    PrimeTransmissiveBsdfSplit result;
    result.reflection = primeSampleMinecraftTransparentReflectionFromState(
            state,
            mirror,
            outwardNormal,
            viewDirection,
            localView,
            reflectionSample,
            volumeStack,
            observation);
    result.transmission = primeSampleMinecraftTransparentTransmissionFromState(
            state,
            mirror,
            outwardNormal,
            viewDirection,
            localView,
            reflectionSample,
            volumeStack,
            observation);
    // Both paths are evaluated, so neither PDF contains a branch-selection probability.
    result.reflection.bsdfSample = primeSanitizeBsdfSample(result.reflection.bsdfSample);
    if (result.reflection.bsdfSample.eventFlags == 0u) {
        result.reflection.volumeStack = volumeStack;
    }
    result.transmission.bsdfSample = primeSanitizeBsdfSample(result.transmission.bsdfSample);
    if (result.transmission.bsdfSample.eventFlags == 0u) {
        result.transmission.volumeStack = volumeStack;
    }
    return result;
}

PrimeTransmissiveBsdfSample primeSampleMinecraftTransparentReflectionFromState(
        PrimeCompactTransmissionState state,
        PrimeMinecraftMirrorSplit mirror,
        float3 outwardNormal,
        float3 viewDirection,
        float3 localView,
        float3 sampleValue,
        PrimeOpenPbrVolumeStack volumeStack,
        inout PrimeNumericalObservation observation) {
    PrimeTransmissiveBsdfSample result;
    result.bsdfSample = primeInvalidBsdfSample();
    result.volumeStack = volumeStack;
    if (state.geometryThinWalled == 0u
            && primeOpenPbrMicrofacetEffectivelySmooth(state.specularMicrofacet)) {
        // Closed Minecraft glass models the reflected interface as a discrete mirror. This
        // conditional response already carries the complete Fresnel energy.
        if (all(lessThanEqual(mirror.reflectance, float3(0.0)))) {
            return result;
        }
        result.bsdfSample.direction = reflect(-viewDirection, outwardNormal);
        result.bsdfSample.response = mirror.reflectance;
        result.bsdfSample.pdf = 1.0;
        result.bsdfSample.relativeEta = 1.0;
        result.bsdfSample.eventFlags = PRIME_BSDF_EVENT_REFLECTION
                | PRIME_BSDF_EVENT_DISCRETE;
        result.bsdfSample = primeSanitizeBsdfSample(result.bsdfSample);
        return result;
    }

    PrimeOpenPbrSampleResult sampled = primeCompactTransmissionSample(
            localView,
            sampleValue,
            state,
            PRIME_OPENPBR_FLAG_REFLECTION,
            volumeStack);
    if (!primeOpenPbrHasSample(sampled.bsdfSample, observation)) {
        return result;
    }
    // The conditional proposal is normalized over reflection. Its response retains the complete
    // reference Fresnel and rough-microfacet energy.
    result.bsdfSample.direction = primeOpenPbrOnbToWorld(
            state.onb, sampled.bsdfSample.wo);
    result.bsdfSample.response = sampled.bsdfSample.throughput.value;
    result.bsdfSample.pdf = sampled.bsdfSample.pdf;
    result.bsdfSample.relativeEta = 1.0;
    result.bsdfSample.eventFlags = primeOpenPbrToBsdfEventFlags(
            sampled.bsdfSample.throughput.flags);
    result.volumeStack = sampled.volumeStack;
    result.bsdfSample = primeSanitizeBsdfSample(result.bsdfSample);
    if (result.bsdfSample.eventFlags == 0u) {
        result.volumeStack = volumeStack;
    }
    return result;
}

PrimeTransmissiveBsdfSplit primeSampleMinecraftTransmissionSplitFromState(
        PrimeCompactTransmissionState state,
        float3 localView,
        PrimeMinecraftMirrorSplit mirror,
        float3 outwardNormal,
        float3 viewDirection,
        float3 reflectionSample,
        float3 baseColor,
        float opacity,
        uint materialControl,
        PrimeOpenPbrVolumeStack volumeStack) {
    PrimeNumericalObservation observation = primeNumericalObservationCreate(false);
    return primeSampleMinecraftTransmissionSplitFromState(
            state,
            localView,
            mirror,
            outwardNormal,
            viewDirection,
            reflectionSample,
            baseColor,
            opacity,
            materialControl,
            volumeStack,
            observation);
}


PrimeTransmissiveBsdfSample primeSampleMinecraftTransparentReflectionFromState(
        PrimeCompactTransmissionState state,
        PrimeMinecraftMirrorSplit mirror,
        float3 outwardNormal,
        float3 viewDirection,
        float3 localView,
        float3 sampleValue,
        PrimeOpenPbrVolumeStack volumeStack) {
    PrimeNumericalObservation observation = primeNumericalObservationCreate(false);
    return primeSampleMinecraftTransparentReflectionFromState(
            state,
            mirror,
            outwardNormal,
            viewDirection,
            localView,
            sampleValue,
            volumeStack,
            observation);
}

// Vanilla grass blades and leaf texels are zero-thickness surfaces rather than dielectric
// volumes. Keep most of the ordinary rough terrain response and lerp a deliberately small amount
// of colored thin-wall transmission through OpenPBR's energy-aware lobe composition. Unlike
// glass and water, this closure never pushes or pops the path volume stack.
static const float PRIME_FOLIAGE_TRANSMISSION_WEIGHT = 0.15;

PrimeOpenPbrMaterial primeMinecraftFoliageMaterialWithMinimumRoughness(
        float3 baseColor,
        float3 normal,
        float roughness,
        uint opticalControl,
        uint materialControl,
        float minimumLinearRoughness) {
    PrimeOpenPbrMaterial material = primeOpaqueMaterialWithMinimumRoughness(
            baseColor,
            normal,
            roughness,
            opticalControl,
            materialControl,
            minimumLinearRoughness);
    material.weight.transmission = PRIME_FOLIAGE_TRANSMISSION_WEIGHT;
    material.geometry.thinWalled = 1u;
    material.geometry.thickness = 0.0625;
    material.specular.ior = 1.45;
    material.transmission.color = clamp(baseColor, float3(0.02), float3(1.0));
    material.transmission.depth = 0.0;
    return material;
}

PrimeOpenPbrMaterial primeMinecraftFoliageMaterial(
        float3 baseColor,
        float3 normal,
        float roughness,
        uint opticalControl,
        uint materialControl) {
    return primeMinecraftFoliageMaterialWithMinimumRoughness(
            baseColor,
            normal,
            roughness,
            opticalControl,
            materialControl,
            0.0);
}

PrimeCompactFoliageState primeMinecraftFoliageStateWithMinimumRoughness(
        float3 baseColor,
        float3 outwardNormal,
        float roughness,
        uint opticalControl,
        uint materialControl,
        float3 viewDirection,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack,
        float minimumLinearRoughness) {
    float3 closureNormal = dot(outwardNormal, viewDirection) < 0.0
            ? -outwardNormal
            : outwardNormal;
    PrimeOpenPbrMaterial material = primeMinecraftFoliageMaterialWithMinimumRoughness(
            baseColor,
            closureNormal,
            roughness,
            opticalControl,
            materialControl,
            minimumLinearRoughness);
    float3 localView = primeOpenPbrOnbToLocal(material.geometry.onb, viewDirection);
    float inverseOutsideIor = primeCompactInverseOutsideIor(localView.z, volumeStack);
    return primeCompactFoliageStateInit(
            primeTransmissionGgxEnergy(),
            material,
            localView,
            inverseOutsideIor,
            rayT);
}

PrimeCompactFoliageState primeMinecraftFoliageState(
        float3 baseColor,
        float3 outwardNormal,
        float roughness,
        uint opticalControl,
        uint materialControl,
        float3 viewDirection,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack) {
    return primeMinecraftFoliageStateWithMinimumRoughness(
            baseColor,
            outwardNormal,
            roughness,
            opticalControl,
            materialControl,
            viewDirection,
            rayT,
            volumeStack,
            0.0);
}

PrimeCompiledClosure primeCompileFoliageClosure(
        float3 baseColor,
        float3 outwardNormal,
        float roughness,
        uint opticalControl,
        uint materialControl,
        float3 viewDirection,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack) {
    PrimeCompiledClosure result;
    PrimeCompactFoliageState state = primeMinecraftFoliageState(
            baseColor,
            outwardNormal,
            roughness,
            opticalControl,
            materialControl,
            viewDirection,
            rayT,
            volumeStack);
    bool discreteSpecular = primeOpenPbrMicrofacetEffectivelySmooth(
            state.base.microfacet);
    result.family = PRIME_MATERIAL_FAMILY_FOLIAGE;
    result.traits.measureMask = PRIME_MEASURE_SOLID_ANGLE
            | (discreteSpecular ? PRIME_MEASURE_DISCRETE : 0u);
    result.traits.eventMask = PRIME_BSDF_EVENT_REFLECTION
            | PRIME_BSDF_EVENT_TRANSMISSION
            | PRIME_BSDF_EVENT_DIFFUSE
            | (discreteSpecular
                    ? PRIME_BSDF_EVENT_DISCRETE
                    : PRIME_BSDF_EVENT_GLOSSY);
    return result;
}

BsdfEvaluation primeEvaluateMinecraftFoliage(
        float3 baseColor,
        float3 outwardNormal,
        float roughness,
        uint opticalControl,
        uint materialControl,
        float3 viewDirection,
        float3 scatterDirection,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack,
        inout PrimeNumericalObservation observation) {
    PrimeCompactFoliageState state = primeMinecraftFoliageState(
            baseColor,
            outwardNormal,
            roughness,
            opticalControl,
            materialControl,
            viewDirection,
            rayT,
            volumeStack);
    float3 localView = primeOpenPbrOnbToLocal(state.base.onb, viewDirection);
    float3 localScatter = primeOpenPbrOnbToLocal(state.base.onb, scatterDirection);
    PrimeCompactFoliageEvaluation evaluation = primeCompactFoliageEvaluate(
            localView, localScatter, state);
    BsdfEvaluation result = primeInvalidBsdfEvaluation();
    if (evaluation.throughput.flags != PRIME_OPENPBR_FLAG_NONE) {
        result.response = evaluation.throughput.value;
        result.pdf = evaluation.pdf;
    }
    primeObserveNonnegative(observation, result.response);
    primeObserveNonnegative(observation, result.pdf);
    result = primeSanitizeBsdfEvaluation(result);
    return result;
}

BsdfEvaluation primeEvaluateMinecraftFoliage(
        float3 baseColor,
        float3 outwardNormal,
        float roughness,
        uint opticalControl,
        uint materialControl,
        float3 viewDirection,
        float3 scatterDirection,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack) {
    PrimeNumericalObservation observation = primeNumericalObservationCreate(false);
    return primeEvaluateMinecraftFoliage(
            baseColor,
            outwardNormal,
            roughness,
            opticalControl,
            materialControl,
            viewDirection,
            scatterDirection,
            rayT,
            volumeStack,
            observation);
}

PrimeBsdfComponents primeEvaluateMinecraftFoliageComponents(
        float3 baseColor,
        float3 outwardNormal,
        float roughness,
        uint opticalControl,
        uint materialControl,
        float3 viewDirection,
        float3 scatterDirection,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack,
        inout PrimeNumericalObservation observation) {
    PrimeBsdfComponents result;
    result.diffuseResponse = float3(0.0);
    result.specularResponse = float3(0.0);
    result.pdf = 0.0;
    PrimeCompactFoliageState state = primeMinecraftFoliageState(
            baseColor,
            outwardNormal,
            roughness,
            opticalControl,
            materialControl,
            viewDirection,
            rayT,
            volumeStack);
    float3 localView = primeOpenPbrOnbToLocal(state.base.onb, viewDirection);
    float3 localScatter = primeOpenPbrOnbToLocal(state.base.onb, scatterDirection);
    PrimeCompactFoliageComponents components = primeCompactFoliageEvaluateComponents(
            localView, localScatter, state);
    result.pdf = components.pdf;
    result.diffuseResponse = components.diffuse;
    result.specularResponse = components.specular;
    primeObserveNonnegative(observation, result.diffuseResponse);
    primeObserveNonnegative(observation, result.specularResponse);
    primeObserveNonnegative(observation, result.pdf);
    result = primeSanitizeBsdfComponents(result);
    return result;
}

PrimeBsdfComponents primeEvaluateMinecraftFoliageComponents(
        float3 baseColor,
        float3 outwardNormal,
        float roughness,
        uint opticalControl,
        uint materialControl,
        float3 viewDirection,
        float3 scatterDirection,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack) {
    PrimeNumericalObservation observation = primeNumericalObservationCreate(false);
    return primeEvaluateMinecraftFoliageComponents(
            baseColor,
            outwardNormal,
            roughness,
            opticalControl,
            materialControl,
            viewDirection,
            scatterDirection,
            rayT,
            volumeStack,
            observation);
}

BsdfSample primeSampleMinecraftFoliageFromState(
        PrimeCompactFoliageState state,
        float3 viewDirection,
        float3 sampleValue,
        PrimeOpenPbrVolumeStack volumeStack,
        inout PrimeNumericalObservation observation) {
    BsdfSample result = primeInvalidBsdfSample();
    float3 localView = primeOpenPbrOnbToLocal(state.base.onb, viewDirection);
    PrimeOpenPbrSampleResult sampled = primeCompactFoliageSample(
            localView, sampleValue, state, volumeStack);
    if (!primeOpenPbrHasSample(sampled.bsdfSample, observation)) {
        return result;
    }
    result.direction = primeOpenPbrOnbToWorld(
            state.base.onb, sampled.bsdfSample.wo);
    result.response = sampled.bsdfSample.throughput.value;
    result.pdf = sampled.bsdfSample.pdf;
    result.relativeEta = 1.0;
    result.eventFlags = primeOpenPbrToBsdfEventFlags(sampled.bsdfSample.throughput.flags);
    return primeSanitizeBsdfSample(result);
}

BsdfSample primeSampleMinecraftFoliageFromState(
        PrimeCompactFoliageState state,
        float3 viewDirection,
        float3 sampleValue,
        PrimeOpenPbrVolumeStack volumeStack) {
    PrimeNumericalObservation observation = primeNumericalObservationCreate(false);
    return primeSampleMinecraftFoliageFromState(
            state,
            viewDirection,
            sampleValue,
            volumeStack,
            observation);
}

BsdfSample primeSampleMinecraftFoliage(
        float3 baseColor,
        float3 outwardNormal,
        float roughness,
        uint opticalControl,
        uint materialControl,
        float3 viewDirection,
        float3 sampleValue,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack) {
    PrimeCompactFoliageState state = primeMinecraftFoliageState(
            baseColor,
            outwardNormal,
            roughness,
            opticalControl,
            materialControl,
            viewDirection,
            rayT,
            volumeStack);
    return primeSampleMinecraftFoliageFromState(
            state, viewDirection, sampleValue, volumeStack);
}

static const uint PRIME_DENOISE_CLOSURE_OPAQUE = 0u;
static const uint PRIME_DENOISE_CLOSURE_FOLIAGE = 1u;
static const uint PRIME_DENOISE_CLOSURE_TRANSMISSIVE = 2u;

struct PrimeDenoiseAlbedos {
    float3 diffuse;
    float3 specular;
};

float3 primeSanitizeDenoiseAlbedo(
        float3 albedo,
        inout PrimeNumericalObservation observation) {
    primeObserveUnit(observation, albedo);
    float3 result = float3(
            primeBsdfFinite(albedo.x) ? clamp(albedo.x, 0.0, 1.0) : 0.0,
            primeBsdfFinite(albedo.y) ? clamp(albedo.y, 0.0, 1.0) : 0.0,
            primeBsdfFinite(albedo.z) ? clamp(albedo.z, 0.0, 1.0) : 0.0);
    return result;
}

float3 primeSanitizeDenoiseAlbedo(float3 albedo) {
    PrimeNumericalObservation observation = primeNumericalObservationCreate(false);
    return primeSanitizeDenoiseAlbedo(albedo, observation);
}

PrimeDenoiseAlbedos primeDenoiseAlbedosFromState(
        PrimeCompactOpaqueState state,
        float3 viewDirection,
        uint closureKind,
        inout PrimeNumericalObservation observation) {
    float3 localView = primeOpenPbrOnbToLocal(state.onb, viewDirection);
    PrimeCompactOpaqueEnergy energy = primeCompactOpaqueEnergy(localView, state);
    PrimeDenoiseAlbedos result;
    result.diffuse = primeSanitizeDenoiseAlbedo(energy.diffuse, observation);
    result.specular = primeSanitizeDenoiseAlbedo(energy.specular, observation);
    return result;
}

PrimeDenoiseAlbedos primeDenoiseAlbedosFromState(
        PrimeCompactOpaqueState state,
        float3 viewDirection,
        uint closureKind) {
    PrimeNumericalObservation observation = primeNumericalObservationCreate(false);
    return primeDenoiseAlbedosFromState(
            state, viewDirection, closureKind, observation);
}

PrimeDenoiseAlbedos primeDenoiseAlbedosFromState(
        PrimeCompactFoliageState state,
        float3 viewDirection,
        uint closureKind,
        inout PrimeNumericalObservation observation) {
    float3 localView = primeOpenPbrOnbToLocal(state.base.onb, viewDirection);
    PrimeCompactFoliageEnergy energy = primeCompactFoliageEnergy(
            primeTransmissionGgxEnergy(), localView, state);
    PrimeDenoiseAlbedos result;
    result.diffuse = primeSanitizeDenoiseAlbedo(energy.diffuse, observation);
    result.specular = primeSanitizeDenoiseAlbedo(energy.specular, observation);
    return result;
}

PrimeDenoiseAlbedos primeDenoiseAlbedosFromState(
        PrimeCompactFoliageState state,
        float3 viewDirection,
        uint closureKind) {
    PrimeNumericalObservation observation = primeNumericalObservationCreate(false);
    return primeDenoiseAlbedosFromState(
            state, viewDirection, closureKind, observation);
}

PrimeDenoiseAlbedos primeDenoiseAlbedosFromState(
        PrimeCompactTransmissionState state,
        float3 viewDirection,
        uint closureKind,
        inout PrimeNumericalObservation observation) {
    float3 localView = primeOpenPbrOnbToLocal(state.onb, viewDirection);
    PrimeDenoiseAlbedos result;
    result.diffuse = primeSanitizeDenoiseAlbedo(
            primeCompactTransmissionEnergy(
                    primeTransmissionGgxEnergy(),
                    localView,
                    state,
                    PRIME_OPENPBR_FLAG_TRANSMISSION),
            observation);
    result.specular = primeSanitizeDenoiseAlbedo(
            primeCompactTransmissionEnergy(
                    primeTransmissionGgxEnergy(),
                    localView,
                    state,
                    PRIME_OPENPBR_FLAG_REFLECTION),
            observation);
    return result;
}

PrimeDenoiseAlbedos primeDenoiseAlbedosFromState(
        PrimeCompactTransmissionState state,
        float3 viewDirection,
        uint closureKind) {
    PrimeNumericalObservation observation = primeNumericalObservationCreate(false);
    return primeDenoiseAlbedosFromState(
            state, viewDirection, closureKind, observation);
}

float3 primeMinecraftPrimaryTransmissionAlbedo(
        PrimeCompactTransmissionState state, uint materialControl) {
    // A rough primary interface has no deterministic transmitted target to supply a material
    // identity. Preserve the medium's one-metre reference color in its reconstruction albedo so
    // adjacent stained glasses are demodulated to comparable illumination instead of exchanging
    // colored radiance through a shared white guide. This changes reconstruction only.
    return primeMaterialIsRoughGlass(materialControl)
            ? state.transmissionColor
            : state.transmissionTint;
}

struct PrimeTransmissivePrimarySample {
    PrimeTransmissiveBsdfSplit paths;
    PrimeDenoiseAlbedos albedos;
};

PrimeTransmissivePrimarySample primeSampleMinecraftTransmissionPrimary(
        float3 baseColor,
        float opacity,
        float3 outwardNormal,
        uint materialControl,
        float roughness,
        uint opticalControl,
        float3 viewDirection,
        float3 reflectionSample,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack,
        float3 adjacentBaseColor,
        uint adjacentInterfaceControl,
        inout PrimeNumericalObservation observation) {
    // This is the only fixed-split entry point. Translation, closure initialization, local-view
    // conversion and guide energy are shared before reflection and refraction diverge.
    PrimeCompactTransmissionState state = primeMinecraftBoundaryTransmissionState(
            baseColor,
            opacity,
            outwardNormal,
            materialControl,
            roughness,
            opticalControl,
            viewDirection,
            rayT,
            volumeStack,
            adjacentBaseColor,
            adjacentInterfaceControl);
    float3 localView = primeOpenPbrOnbToLocal(state.onb, viewDirection);
    // One directional-energy lookup supplies the reflection guide and the complementary weights
    // used by the smooth conditional events. Rough event responses come from the closure itself.
    PrimeMinecraftMirrorSplit mirror = primeMinecraftMirrorSplit(
            state, localView, observation);

    PrimeTransmissivePrimarySample result;
    result.albedos.diffuse = primeSanitizeDenoiseAlbedo(
            primeMinecraftPrimaryTransmissionAlbedo(state, materialControl),
            observation);
    result.albedos.specular = primeSanitizeDenoiseAlbedo(
            mirror.reflectance, observation);
    result.paths = primeSampleMinecraftTransmissionSplitFromState(
            state,
            localView,
            mirror,
            outwardNormal,
            viewDirection,
            reflectionSample,
            baseColor,
            opacity,
            materialControl,
            volumeStack,
            observation);
    result.paths.transmission = primeApplyAdjacentMediumTransition(
            result.paths.transmission,
            state,
            volumeStack,
            baseColor,
            opacity,
            materialControl,
            opticalControl,
            adjacentBaseColor,
            adjacentInterfaceControl);
    return result;
}

PrimeTransmissivePrimarySample primeSampleMinecraftTransmissionPrimary(
        float3 baseColor,
        float opacity,
        float3 outwardNormal,
        uint materialControl,
        float roughness,
        uint opticalControl,
        float3 viewDirection,
        float3 reflectionSample,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack,
        float3 adjacentBaseColor,
        uint adjacentInterfaceControl) {
    PrimeNumericalObservation observation = primeNumericalObservationCreate(false);
    return primeSampleMinecraftTransmissionPrimary(
            baseColor,
            opacity,
            outwardNormal,
            materialControl,
            roughness,
            opticalControl,
            viewDirection,
            reflectionSample,
            rayT,
            volumeStack,
            adjacentBaseColor,
            adjacentInterfaceControl,
            observation);
}

PrimeTransmissivePrimarySample primeSampleMinecraftTransmissionPrimary(
        float3 baseColor,
        float opacity,
        float3 outwardNormal,
        uint materialControl,
        float roughness,
        uint opticalControl,
        float3 viewDirection,
        float3 reflectionSample,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack,
        inout PrimeNumericalObservation observation) {
    // Preserve the original no-boundary instruction graph: its guide values are part of the
    // deterministic primary split contract and do not need adjacent-medium specialization.
    PrimeCompactTransmissionState state = primeMinecraftTransmissionState(
            baseColor,
            opacity,
            outwardNormal,
            materialControl,
            roughness,
            opticalControl,
            viewDirection,
            rayT,
            volumeStack);
    float3 localView = primeOpenPbrOnbToLocal(state.onb, viewDirection);
    PrimeMinecraftMirrorSplit mirror = primeMinecraftMirrorSplit(
            state, localView, observation);

    PrimeTransmissivePrimarySample result;
    result.albedos.diffuse = primeSanitizeDenoiseAlbedo(
            primeMinecraftPrimaryTransmissionAlbedo(state, materialControl),
            observation);
    result.albedos.specular = primeSanitizeDenoiseAlbedo(
            mirror.reflectance, observation);
    result.paths = primeSampleMinecraftTransmissionSplitFromState(
            state,
            localView,
            mirror,
            outwardNormal,
            viewDirection,
            reflectionSample,
            baseColor,
            opacity,
            materialControl,
            volumeStack,
            observation);
    return result;
}

PrimeTransmissivePrimarySample primeSampleMinecraftTransmissionPrimary(
        float3 baseColor,
        float opacity,
        float3 outwardNormal,
        uint materialControl,
        float roughness,
        uint opticalControl,
        float3 viewDirection,
        float3 reflectionSample,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack) {
    PrimeNumericalObservation observation = primeNumericalObservationCreate(false);
    return primeSampleMinecraftTransmissionPrimary(
            baseColor,
            opacity,
            outwardNormal,
            materialControl,
            roughness,
            opticalControl,
            viewDirection,
            reflectionSample,
            rayT,
            volumeStack,
            observation);
}

#endif
