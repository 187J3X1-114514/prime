#ifndef PRIME_BSDF_GLSL
#define PRIME_BSDF_GLSL

#include "bsdf_common.glsl"
#include "default_material.glsl"
#include "labpbr.glsl"
#include "material_translation.glsl"
#include "transparent_material.glsl"
#ifndef PRIME_RC_TRANSMISSION_GGX_SET
#define PRIME_RC_TRANSMISSION_GGX_SET 0
#endif
#ifndef PRIME_RC_TRANSMISSION_GGX_BINDING
#define PRIME_RC_TRANSMISSION_GGX_BINDING PRIME_DESCRIPTOR_TRANSMISSION_GGX_ENERGY
#endif
#include "prime_bsdf_specializations.glsl"

// Minecraft's translucent render layer exposes conditional reflection/transmission proposals for
// the first-visible checkerboard choice, then uses RoboCute's complete closure for later queued
// continuation. The adapter owns deterministic primary refraction, per-surface glass filtering
// and medium handoff.
struct PrimeTransmissiveBsdfSample {
    BsdfSample bsdfSample;
    PrimeRcVolumeStack volumeStack;
};

struct PrimeTransmissiveBsdfSplit {
    PrimeTransmissiveBsdfSample reflection;
    PrimeTransmissiveBsdfSample transmission;
};

const vec3 PRIME_REC2020_PRIMARY_WAVELENGTHS_NM = vec3(630.0, 532.0, 467.0);

bool primeRcHasSample(PrimeRcSample sampleValue) {
    if (sampleValue.throughput.flags == PRIME_RC_FLAG_NONE) {
        // RoboCute does not promise initialized payloads for a rejected proposal. Prime replaces
        // it with its canonical zero-event sample, so inspecting the discarded payload only
        // creates diagnostic false positives and says nothing about consumed transport.
        return false;
    }
    if (!primeBsdfDirection(sampleValue.wo)
            || !primeBsdfFinite(sampleValue.throughput.value)
            || !primeBsdfFinite(sampleValue.pdf)
            || any(lessThan(sampleValue.throughput.value, vec3(0.0)))
            || !(sampleValue.pdf > 0.0)) {
        // Float singularities in the reference closure are contained at Prime's adapter boundary.
        // A rejected proposal contributes zero and cannot poison later MIS or throughput state.
        return false;
    }
    primeRecordNonnegative(sampleValue.throughput.value);
    primeRecordNonnegative(sampleValue.pdf);
    primeRecordDirection(sampleValue.wo);
    return true;
}

PrimeRcVolumeStack primeEmptyVolumeStack() {
    PrimeRcVolumeStack result;
    result.values[0].extinction = vec3(0.0);
    result.values[0].albedo = vec3(0.0);
    result.values[0].anisotropy = 0.0;
    result.values[0].ior = 1.0;
    result.values[1] = result.values[0];
    result.count = 0u;
    return result;
}

PrimeRcMaterial primeMinecraftTransmissionMaterial(
        vec3 baseColor,
        float opacity,
        vec3 outwardNormal,
        uint materialFlags,
        uint packedNormal,
        uint packedSpecular) {
    bool water = (materialFlags & PRIME_MATERIAL_FLAG_WATER) != 0u;
    bool thinWalled = (materialFlags & PRIME_MATERIAL_FLAG_THIN_WALLED) != 0u;
    // Vanilla translucent materials have no authored rough interface and are therefore exact
    // smooth interfaces. The first-visible split supplies its own refraction event; queued
    // single-path transport samples this same state through the complete closure.
    PrimeTranslatedLabPbrMaterial translated = primeDecodeAndTranslateLabPbr(
            packedNormal, packedSpecular, materialFlags);
    float roughness = primeHasLabPbrSpecular(materialFlags)
            ? translated.perceptualRoughness
            : 0.0;
    PrimeRcMaterial material = primeRcMaterialFromMetallic(
            vec3(1.0), roughness, 0.0, outwardNormal);
    material.weight.transmission = 1.0;
    material.geometry.thinWalled = thinWalled ? 1u : 0u;
    material.geometry.thickness = thinWalled ? 0.0625 : 1.0;
    // Water's measured boundary is authoritative. Other transmissive models accept only the
    // translator's clamped dielectric F0; custom and predefined-metal encodings fall back to 4%.
    material.specular.ior = water
            ? 1.333
            : primeRcF0ToIor(translated.dielectricF0);

    if (water) {
        // RoboCute's transmission ABI stores transmittance at a reference depth and recovers
        // extinction as -log(T) / depth. Supplying the measured one-metre transmittance therefore
        // reconstructs PRIME_PURE_WATER_ABSORPTION_M_INV without changing its volume-stack code.
        material.transmission.color = exp(-PRIME_PURE_WATER_ABSORPTION_M_INV);
        material.transmission.depth = 1.0;
    } else {
        // Glass is a surface filter, not a volume. Keep the reference closure colorless so it
        // cannot reintroduce distance attenuation through an accidental stack transition.
        material.transmission.color = vec3(1.0);
        material.transmission.depth = 0.0;
    }
    material.transmission.scatter = vec3(0.0);
    material.transmission.scatterAnisotropy = 0.0;
    material.transmission.dispersionScale = 0.0;
    return material;
}

PrimeRcVolumeStack primeCameraWaterVolumeStack() {
    PrimeRcVolumeStack result = primeEmptyVolumeStack();
    // The camera starts without an intersected surface, but pure-water absorption is independent
    // of the discarded biome surface tint, so a neutral placeholder reconstructs the same medium.
    PrimeRcMaterial material = primeMinecraftTransmissionMaterial(
            vec3(1.0),
            1.0,
            vec3(0.0, 1.0, 0.0),
            PRIME_MATERIAL_FLAG_TRANSMISSIVE | PRIME_MATERIAL_FLAG_WATER,
            packUnorm4x8(vec4(0.5, 0.5, 1.0, 1.0)),
            packUnorm4x8(vec4(0.0, 4.0 / 255.0, 0.0, 1.0)));
    PrimeRcVolume volume = primeRcVolumeFromTransmission(material.transmission);
    volume.ior = material.specular.ior;
    primeRcStackPush(result, volume);
    return result;
}

uint primeRcToBsdfEventFlags(uint flags) {
    uint result = 0u;
    if (primeRcIsReflective(flags)) {
        result |= PRIME_BSDF_EVENT_REFLECTION;
    }
    if (primeRcIsTransmissive(flags)) {
        result |= PRIME_BSDF_EVENT_TRANSMISSION;
    }
    if (primeRcIsDiffuse(flags)) {
        result |= PRIME_BSDF_EVENT_DIFFUSE;
    }
    if (primeRcIsSpecular(flags)) {
        result |= PRIME_BSDF_EVENT_GLOSSY;
    }
    if (primeRcIsDelta(flags)) {
        result |= PRIME_BSDF_EVENT_DELTA;
    }
    return result;
}

PrimeRcMaterial primeOpaqueMaterial(
        vec3 baseColor,
        vec3 normal,
        uint packedNormal,
        uint packedSpecular,
        uint flags) {
    PrimeTranslatedLabPbrMaterial translated = primeDecodeAndTranslateLabPbr(
            packedNormal, packedSpecular, flags);
    bool metal = primeTranslatedLabPbrIsMetal(translated);
    PrimeRcMaterial material = primeRcMaterialFromMetallic(
            baseColor,
            translated.perceptualRoughness,
            metal ? 1.0 : 0.0,
            normal);
    if (metal) {
        PrimeLabPbrFresnel fresnel = primeLabPbrMetalFresnel(
                baseColor, translated.metalId);
        material.base.color = fresnel.f0;
        material.specular.color = fresnel.f82Tint;
    } else {
        material.specular.ior = primeRcF0ToIor(translated.dielectricF0);
        material.weight.subsurface = translated.subsurfaceWeight;
        material.subsurface.color = baseColor;
        if (translated.thinWalled != 0u) {
            material.geometry.thinWalled = 1u;
            material.geometry.thickness = 0.0625;
        }
    }
    return material;
}

PrimeRcMaterial primeMinecraftTransmissionMaterial(
        vec3 baseColor,
        float opacity,
        vec3 outwardNormal,
        uint materialFlags) {
    return primeMinecraftTransmissionMaterial(
            baseColor,
            opacity,
            outwardNormal,
            materialFlags,
            packUnorm4x8(vec4(0.5, 0.5, 1.0, 1.0)),
            packUnorm4x8(vec4(0.0, 4.0 / 255.0, 0.0, 1.0)));
}

PrimeRcState primeOpaqueState(
        vec3 baseColor,
        vec3 normal,
        uint packedNormal,
        uint packedSpecular,
        uint flags,
        vec3 viewDirection,
        float rayT,
        PrimeRcVolumeStack volumeStack) {
    PrimeRcMaterial material = primeOpaqueMaterial(
            baseColor, normal, packedNormal, packedSpecular, flags);
    vec3 localView = primeRcOnbToLocal(material.geometry.onb, viewDirection);
    float inverseOutsideIor = primeRcInverseOutsideIor(localView.z, volumeStack);
    if (material.weight.subsurface > 0.0) {
        // The conservative alpha-cut SSS translation maps exactly to RoboCute's existing
        // SubsurfaceGlossy polymorphic specialization; it does not need the generic OpenPBR graph.
        return primeRcSubsurfaceGlossyStateInit(
                material,
                localView,
                inverseOutsideIor,
                rayT,
                PRIME_REC2020_PRIMARY_WAVELENGTHS_NM,
                0u,
                PRIME_RC_DETAIL_DEFAULT,
                0u);
    }
    return primeRcBasicMetallicStateInit(
            material,
            localView,
            inverseOutsideIor,
            rayT,
            PRIME_REC2020_PRIMARY_WAVELENGTHS_NM,
            0u,
            PRIME_RC_DETAIL_DEFAULT,
            0u);
}

struct PrimeBsdfComponents {
    vec3 diffuseResponse;
    vec3 specularResponse;
    float pdf;
};

PrimeBsdfComponents primeSanitizeBsdfComponents(PrimeBsdfComponents components) {
    if (!primeBsdfFinite(components.diffuseResponse)
            || !primeBsdfFinite(components.specularResponse)
            || !primeBsdfFinite(components.pdf)
            || any(lessThan(components.diffuseResponse, vec3(0.0)))
            || any(lessThan(components.specularResponse, vec3(0.0)))
            || components.pdf < 0.0) {
        components.diffuseResponse = vec3(0.0);
        components.specularResponse = vec3(0.0);
        components.pdf = 0.0;
    }
    return components;
}

PrimeBsdfComponents primeEvaluateOpaqueComponents(
        vec3 baseColor,
        vec3 normal,
        uint packedNormal,
        uint packedSpecular,
        uint flags,
        vec3 viewDirection,
        vec3 scatterDirection,
        float rayT,
        PrimeRcVolumeStack volumeStack) {
    PrimeBsdfComponents result;
    result.diffuseResponse = vec3(0.0);
    result.specularResponse = vec3(0.0);
    result.pdf = 0.0;
    PrimeRcState state = primeOpaqueState(
            baseColor,
            normal,
            packedNormal,
            packedSpecular,
            flags,
            viewDirection,
            rayT,
            volumeStack);
    vec3 localView = primeRcOnbToLocal(state.material.geometry.onb, viewDirection);
    vec3 localScatter = primeRcOnbToLocal(state.material.geometry.onb, scatterDirection);
    bool subsurface = state.material.weight.subsurface > 0.0;
    PrimeRcEval full = subsurface
            ? primeRcPrimeSubsurfaceGlossyEvaluate(localView, localScatter, state)
            : primeRcPrimeBasicMetallicEvaluate(localView, localScatter, state);
    result.pdf = full.pdf;
    PrimeRcState diffuseState = state;
    diffuseState.samplingFlags = PRIME_RC_FLAG_DIFFUSE;
    PrimeRcThroughput diffuse = subsurface
            ? primeRcPrimeSubsurfaceGlossyEval(localView, localScatter, diffuseState)
            : primeRcPrimeBasicMetallicEval(localView, localScatter, diffuseState);
    PrimeRcState specularState = state;
    specularState.samplingFlags = PRIME_RC_FLAG_SPECULAR | PRIME_RC_FLAG_DELTA;
    PrimeRcThroughput specular = subsurface
            ? primeRcPrimeSubsurfaceGlossyEval(localView, localScatter, specularState)
            : primeRcPrimeBasicMetallicEval(localView, localScatter, specularState);
    result.diffuseResponse = diffuse.value;
    result.specularResponse = specular.value;
    result = primeSanitizeBsdfComponents(result);
    primeRecordNonnegative(result.diffuseResponse);
    primeRecordNonnegative(result.specularResponse);
    primeRecordNonnegative(result.pdf);
    return result;
}

BsdfSample primeSampleOpaqueFromState(
        PrimeRcState state,
        vec3 normal,
        vec3 viewDirection,
        vec3 sampleValue,
        PrimeRcVolumeStack volumeStack) {
    BsdfSample result = primeInvalidBsdfSample();
    vec3 localView = primeRcOnbToLocal(state.material.geometry.onb, viewDirection);
    PrimeRcSampleResult sampled = state.material.weight.subsurface > 0.0
            ? primeRcSubsurfaceGlossySample(localView, sampleValue, state, volumeStack)
            : primeRcBasicMetallicSample(localView, sampleValue, state, volumeStack);
    if (!primeRcHasSample(sampled.bsdfSample)) {
        return result;
    }
    result.direction = primeRcOnbToWorld(
            state.material.geometry.onb, sampled.bsdfSample.wo);
    if ((sampled.bsdfSample.throughput.flags & PRIME_RC_FLAG_DELTA_REFLECTION) != 0u) {
        // Avoid a local-to-world round trip exactly where the reflected direction can be only a
        // few ulps above the tangent plane. This is algebraically the same delta direction, but
        // preserves the world-space hemisphere at extreme grazing angles.
        result.direction = normalize(reflect(-viewDirection, normal));
    }
    result.response = sampled.bsdfSample.throughput.value;
    result.pdf = sampled.bsdfSample.pdf;
    result.relativeEta = 1.0;
    result.eventFlags = primeRcToBsdfEventFlags(sampled.bsdfSample.throughput.flags);
    return primeSanitizeBsdfSample(result);
}

BsdfSample primeSampleOpaque(
        vec3 baseColor,
        vec3 normal,
        uint packedNormal,
        uint packedSpecular,
        uint flags,
        vec3 viewDirection,
        vec3 sampleValue,
        float rayT,
        PrimeRcVolumeStack volumeStack) {
    PrimeRcState state = primeOpaqueState(
            baseColor,
            normal,
            packedNormal,
            packedSpecular,
            flags,
            viewDirection,
            rayT,
            volumeStack);
    return primeSampleOpaqueFromState(
            state, normal, viewDirection, sampleValue, volumeStack);
}

PrimeRcState primeMinecraftTransmissionState(
        vec3 baseColor,
        float opacity,
        vec3 outwardNormal,
        uint materialFlags,
        uint packedNormal,
        uint packedSpecular,
        vec3 viewDirection,
        float rayT,
        PrimeRcVolumeStack volumeStack) {
    bool thinWalled = (materialFlags & PRIME_MATERIAL_FLAG_THIN_WALLED) != 0u;
    vec3 closureNormal = thinWalled && dot(outwardNormal, viewDirection) < 0.0
            ? -outwardNormal
            : outwardNormal;
    PrimeRcMaterial material = primeMinecraftTransmissionMaterial(
            baseColor,
            opacity,
            closureNormal,
            materialFlags,
            packedNormal,
            packedSpecular);
    vec3 localView = primeRcOnbToLocal(material.geometry.onb, viewDirection);
    float inverseOutsideIor = primeRcInverseOutsideIor(localView.z, volumeStack);
    PrimeRcState state = primeRcTransmissionStateInit(
            material,
            localView,
            inverseOutsideIor,
            rayT,
            PRIME_REC2020_PRIMARY_WAVELENGTHS_NM,
            0u,
            PRIME_RC_DETAIL_DEFAULT,
            0u);
    return primeRcPrimeTransmissionInterfaceState(localView, state);
}

struct PrimeMinecraftMirrorSplit {
    vec3 reflectance;
};

PrimeMinecraftMirrorSplit primeMinecraftMirrorSplit(
        PrimeRcState state,
        vec3 localView) {
    PrimeMinecraftMirrorSplit result;
    // The default Minecraft adapter keeps dielectric reflection achromatic. Retaining the color
    // term here makes the split remain correct if a future material decoder tints the interface.
    float directionalReflectance = state.geometryThinWalled != 0u
            ? primeRcMicrofacetDielectricMsCompensation(
                    state.specularMicrofacet,
                    abs(localView.z),
                    state.specularFresnel.ior).z
            : state.transmissionMultipleScattering.z;
    result.reflectance = directionalReflectance * state.specularFresnel.color;
    if (!primeBsdfFinite(result.reflectance)
            || any(lessThan(result.reflectance, vec3(0.0)))) {
        result.reflectance = vec3(0.0);
    } else {
        result.reflectance = clamp(result.reflectance, vec3(0.0), vec3(1.0));
    }
    primeRecordUnit(result.reflectance);
    return result;
}

vec3 primeMinecraftSurfaceTransmittance(
        vec3 baseColor,
        float opacity,
        uint materialFlags) {
    return (materialFlags & PRIME_MATERIAL_FLAG_WATER) != 0u
            ? vec3(1.0)
            : primeGlassSurfaceTransmittance(baseColor, opacity);
}

PrimeRcVolumeStack primeMinecraftTransmissionStack(
        PrimeRcState state,
        uint materialFlags,
        PrimeRcVolumeStack volumeStack) {
    if ((materialFlags & PRIME_MATERIAL_FLAG_WATER) == 0u
            || state.geometryThinWalled != 0u) {
        return volumeStack;
    }
    if (state.entering != 0u) {
        PrimeRcVolume volume = state.transmissionVolume;
        volume.ior = state.originalIor;
        primeRcStackPush(volumeStack, volume);
    } else if (volumeStack.count > 0u) {
        primeRcStackPop(volumeStack);
    }
    return volumeStack;
}

float primeMinecraftInterfaceIor(
        PrimeRcState state,
        uint materialFlags,
        PrimeRcVolumeStack volumeStack) {
    if ((materialFlags & PRIME_MATERIAL_FLAG_WATER) != 0u) {
        // Water owns a stack entry, so the reference state already selected the medium outside
        // the boundary for both entry and exit.
        return state.specularFresnel.ior;
    }
    // Glass is a surface filter and never owns a stack entry. Its surrounding medium is therefore
    // the current stack top on either side of the geometric shell.
    float outsideIor = volumeStack.count > 0u
            ? volumeStack.values[volumeStack.count - 1u].ior
            : 1.0;
    return state.originalIor / outsideIor;
}

PrimeTransmissiveBsdfSample primeSampleMinecraftRefractedTransmissionFromState(
        PrimeRcState state,
        vec3 baseColor,
        float opacity,
        uint materialFlags,
        vec3 outwardNormal,
        vec3 viewDirection,
        vec3 interfaceWeight,
        bool reflectOnTotalInternalReflection,
        PrimeRcVolumeStack volumeStack) {
    PrimeTransmissiveBsdfSample result;
    result.bsdfSample = primeInvalidBsdfSample();
    result.volumeStack = volumeStack;
    if (state.geometryThinWalled != 0u) {
        result.bsdfSample.direction = -viewDirection;
        result.bsdfSample.response = interfaceWeight
                * primeMinecraftSurfaceTransmittance(
                        baseColor, opacity, materialFlags);
        result.bsdfSample.pdf = 1.0;
        result.bsdfSample.relativeEta = 1.0;
        result.bsdfSample.eventFlags = PRIME_BSDF_EVENT_TRANSMISSION
                | PRIME_BSDF_EVENT_DELTA;
        result.bsdfSample = primeSanitizeBsdfSample(result.bsdfSample);
        return result;
    }

    PrimeRcRefractResult refracted = primeRcDielectricRefract(
            primeMinecraftInterfaceIor(state, materialFlags, volumeStack),
            -viewDirection,
            outwardNormal);
    if (refracted.valid == 0u) {
        // A forced transmission proposal has no support under TIR. Guide-only continuations use
        // the sole valid delta event instead; the primary transmission slot stays empty because
        // its paired Fresnel reflection already carries this energy.
        if (!reflectOnTotalInternalReflection) {
            return result;
        }
        result.bsdfSample.direction = reflect(-viewDirection, outwardNormal);
        result.bsdfSample.response = interfaceWeight;
        result.bsdfSample.pdf = 1.0;
        result.bsdfSample.relativeEta = 1.0;
        result.bsdfSample.eventFlags = PRIME_BSDF_EVENT_REFLECTION
                | PRIME_BSDF_EVENT_DELTA;
        result.bsdfSample = primeSanitizeBsdfSample(result.bsdfSample);
        return result;
    }

    result.bsdfSample.direction = refracted.wo;
    result.bsdfSample.response = interfaceWeight
            * primeMinecraftSurfaceTransmittance(baseColor, opacity, materialFlags);
    result.bsdfSample.pdf = 1.0;
    result.bsdfSample.relativeEta = refracted.relativeIor;
    result.bsdfSample.eventFlags = PRIME_BSDF_EVENT_TRANSMISSION
            | PRIME_BSDF_EVENT_DELTA;
    result.volumeStack = primeMinecraftTransmissionStack(
            state, materialFlags, volumeStack);
    result.bsdfSample = primeSanitizeBsdfSample(result.bsdfSample);
    if (result.bsdfSample.eventFlags == 0u) {
        result.volumeStack = volumeStack;
    }
    return result;
}

BsdfEvaluation primeEvaluateMinecraftTransparentReflection(
        vec3 baseColor,
        float opacity,
        vec3 outwardNormal,
        uint materialFlags,
        uint packedNormal,
        uint packedSpecular,
        vec3 viewDirection,
        vec3 scatterDirection,
        float rayT,
        PrimeRcVolumeStack volumeStack) {
    PrimeRcState state = primeMinecraftTransmissionState(
            baseColor,
            opacity,
            outwardNormal,
            materialFlags,
            packedNormal,
            packedSpecular,
            viewDirection,
            rayT,
            volumeStack);
    vec3 localView = primeRcOnbToLocal(state.material.geometry.onb, viewDirection);
    vec3 localScatter = primeRcOnbToLocal(state.material.geometry.onb, scatterDirection);
    if (localView.z * localScatter.z < 0.0
            || primeRcMicrofacetEffectivelySmooth(state.specularMicrofacet)) {
        // A coherent reflection has no finite solid-angle evaluation.
        return primeInvalidBsdfEvaluation();
    }
    state.samplingFlags = PRIME_RC_FLAG_REFLECTION;
    PrimeRcEval evaluation = primeRcPrimeTransmissionEvaluate(
            localView, localScatter, state);
    BsdfEvaluation result = primeInvalidBsdfEvaluation();
    if (evaluation.throughput.flags != PRIME_RC_FLAG_NONE) {
        result.response = evaluation.throughput.value;
        result.pdf = evaluation.pdf;
    }
    result = primeSanitizeBsdfEvaluation(result);
    primeRecordNonnegative(result.response);
    primeRecordNonnegative(result.pdf);
    return result;
}

PrimeTransmissiveBsdfSample primeSampleMinecraftTransparentReflectionFromState(
        PrimeRcState state,
        PrimeMinecraftMirrorSplit mirror,
        vec3 outwardNormal,
        vec3 viewDirection,
        vec3 localView,
        vec3 sampleValue,
        PrimeRcVolumeStack volumeStack);

PrimeTransmissiveBsdfSample primeSampleMinecraftTransparentContinuationFromState(
        PrimeRcState state,
        vec3 baseColor,
        float opacity,
        uint materialFlags,
        vec3 outwardNormal,
        vec3 viewDirection,
        PrimeRcVolumeStack volumeStack) {
    return primeSampleMinecraftRefractedTransmissionFromState(
            state,
            baseColor,
            opacity,
            materialFlags,
            outwardNormal,
            viewDirection,
            vec3(1.0),
            true,
            volumeStack);
}

PrimeTransmissiveBsdfSample primeSampleMinecraftTransparentContinuation(
        vec3 baseColor,
        float opacity,
        vec3 outwardNormal,
        uint materialFlags,
        uint packedNormal,
        uint packedSpecular,
        vec3 viewDirection,
        float rayT,
        PrimeRcVolumeStack volumeStack) {
    PrimeRcState state = primeMinecraftTransmissionState(
            baseColor,
            opacity,
            outwardNormal,
            materialFlags,
            packedNormal,
            packedSpecular,
            viewDirection,
            rayT,
            volumeStack);
    return primeSampleMinecraftTransparentContinuationFromState(
            state,
            baseColor,
            opacity,
            materialFlags,
            outwardNormal,
            viewDirection,
            volumeStack);
}

// A queued single-path integrator samples the complete transmissive closure instead of forcing a
// hemisphere. The closure's reflection/transmission selection probability remains in the PDF,
// and only a sampled transmission event may replace the medium stack.
PrimeTransmissiveBsdfSample primeSampleMinecraftTransmissionCompleteFromState(
        PrimeRcState state,
        vec3 baseColor,
        float opacity,
        uint materialFlags,
        vec3 viewDirection,
        vec3 sampleValue,
        PrimeRcVolumeStack volumeStack) {
    PrimeTransmissiveBsdfSample result;
    result.bsdfSample = primeInvalidBsdfSample();
    result.volumeStack = volumeStack;
    state.samplingFlags = PRIME_RC_FLAG_ALL;
    vec3 localView = primeRcOnbToLocal(state.material.geometry.onb, viewDirection);
    PrimeRcSampleResult sampled = primeRcPrimeTransmissionSample(
            localView, sampleValue, state, volumeStack);
    if (!primeRcHasSample(sampled.bsdfSample)) {
        return result;
    }
    result.bsdfSample.direction = primeRcOnbToWorld(
            state.material.geometry.onb, sampled.bsdfSample.wo);
    result.bsdfSample.response = sampled.bsdfSample.throughput.value;
    result.bsdfSample.pdf = sampled.bsdfSample.pdf;
    result.bsdfSample.relativeEta = sampled.bsdfSample.eta;
    result.bsdfSample.eventFlags = primeRcToBsdfEventFlags(
            sampled.bsdfSample.throughput.flags);
    if ((result.bsdfSample.eventFlags & PRIME_BSDF_EVENT_TRANSMISSION) != 0u) {
        result.bsdfSample.response *= primeMinecraftSurfaceTransmittance(
                baseColor, opacity, materialFlags);
    }
    result.volumeStack = sampled.volumeStack;
    result.bsdfSample = primeSanitizeBsdfSample(result.bsdfSample);
    if (result.bsdfSample.eventFlags == 0u) {
        result.volumeStack = volumeStack;
    }
    return result;
}

BsdfEvaluation primeEvaluateMinecraftTransmissionCompleteFromState(
        PrimeRcState state,
        vec3 baseColor,
        float opacity,
        uint materialFlags,
        vec3 viewDirection,
        vec3 scatterDirection) {
    state.samplingFlags = PRIME_RC_FLAG_ALL;
    vec3 localView = primeRcOnbToLocal(state.material.geometry.onb, viewDirection);
    vec3 localScatter = primeRcOnbToLocal(
            state.material.geometry.onb, scatterDirection);
    PrimeRcEval evaluated = primeRcPrimeTransmissionEvaluate(
            localView, localScatter, state);
    BsdfEvaluation result = primeInvalidBsdfEvaluation();
    if (evaluated.throughput.flags != PRIME_RC_FLAG_NONE) {
        result.response = evaluated.throughput.value;
        result.pdf = evaluated.pdf;
        if (primeRcIsTransmissive(evaluated.throughput.flags)) {
            result.response *= primeMinecraftSurfaceTransmittance(
                    baseColor, opacity, materialFlags);
        }
    }
    return primeSanitizeBsdfEvaluation(result);
}

PrimeTransmissiveBsdfSplit primeSampleMinecraftTransmissionSplitFromState(
        PrimeRcState state,
        vec3 localView,
        PrimeMinecraftMirrorSplit mirror,
        vec3 outwardNormal,
        vec3 viewDirection,
        vec3 reflectionSample,
        vec3 baseColor,
        float opacity,
        uint materialFlags,
        PrimeRcVolumeStack volumeStack) {
    // The first visible transparent interface evaluates one conditional reflection and one
    // deterministic refracted event. Their responses carry complementary Fresnel energy.
    PrimeTransmissiveBsdfSplit result;
    result.reflection = primeSampleMinecraftTransparentReflectionFromState(
            state,
            mirror,
            outwardNormal,
            viewDirection,
            localView,
            reflectionSample,
            volumeStack);
    result.transmission = primeSampleMinecraftRefractedTransmissionFromState(
            state,
            baseColor,
            opacity,
            materialFlags,
            outwardNormal,
            viewDirection,
            vec3(1.0) - mirror.reflectance,
            false,
            volumeStack);
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
        PrimeRcState state,
        PrimeMinecraftMirrorSplit mirror,
        vec3 outwardNormal,
        vec3 viewDirection,
        vec3 localView,
        vec3 sampleValue,
        PrimeRcVolumeStack volumeStack) {
    PrimeTransmissiveBsdfSample result;
    result.bsdfSample = primeInvalidBsdfSample();
    result.volumeStack = volumeStack;
    if (state.geometryThinWalled == 0u
            && primeRcMicrofacetEffectivelySmooth(state.specularMicrofacet)) {
        // Closed Minecraft glass models the reflected interface as a delta mirror. This
        // conditional response already carries the complete Fresnel energy.
        if (all(lessThanEqual(mirror.reflectance, vec3(0.0)))) {
            return result;
        }
        result.bsdfSample.direction = reflect(-viewDirection, outwardNormal);
        result.bsdfSample.response = mirror.reflectance;
        result.bsdfSample.pdf = 1.0;
        result.bsdfSample.relativeEta = 1.0;
        result.bsdfSample.eventFlags = PRIME_BSDF_EVENT_REFLECTION
                | PRIME_BSDF_EVENT_DELTA;
        result.bsdfSample = primeSanitizeBsdfSample(result.bsdfSample);
        return result;
    }

    state.samplingFlags = PRIME_RC_FLAG_REFLECTION;
    PrimeRcSampleResult sampled = primeRcPrimeTransmissionSample(
            localView, sampleValue, state, volumeStack);
    if (!primeRcHasSample(sampled.bsdfSample)) {
        return result;
    }
    // The conditional proposal is normalized over reflection. Its response retains the complete
    // reference Fresnel and rough-microfacet energy.
    result.bsdfSample.direction = primeRcOnbToWorld(
            state.material.geometry.onb, sampled.bsdfSample.wo);
    result.bsdfSample.response = sampled.bsdfSample.throughput.value;
    result.bsdfSample.pdf = sampled.bsdfSample.pdf;
    result.bsdfSample.relativeEta = 1.0;
    result.bsdfSample.eventFlags = primeRcToBsdfEventFlags(
            sampled.bsdfSample.throughput.flags);
    result.volumeStack = sampled.volumeStack;
    result.bsdfSample = primeSanitizeBsdfSample(result.bsdfSample);
    if (result.bsdfSample.eventFlags == 0u) {
        result.volumeStack = volumeStack;
    }
    return result;
}

// Vanilla grass blades and leaf texels are zero-thickness surfaces rather than dielectric
// volumes. Keep most of the ordinary rough terrain response and mix a deliberately small amount
// of colored thin-wall transmission through OpenPBR's energy-aware lobe composition. Unlike
// glass and water, this closure never pushes or pops the path volume stack.
const float PRIME_FOLIAGE_TRANSMISSION_WEIGHT = 0.15;

PrimeRcMaterial primeMinecraftFoliageMaterial(
        vec3 baseColor,
        vec3 normal,
        uint packedNormal,
        uint packedSpecular,
        uint materialFlags) {
    PrimeRcMaterial material;
    if (primeHasLabPbrSpecular(materialFlags)) {
        material = primeOpaqueMaterial(
                baseColor, normal, packedNormal, packedSpecular, materialFlags);
    } else {
        material = primeRcMaterialFromMetallic(
                baseColor,
                primeDefaultLinearRoughness(),
                0.0,
                normal);
    }
    material.weight.transmission = PRIME_FOLIAGE_TRANSMISSION_WEIGHT;
    material.geometry.thinWalled = 1u;
    material.geometry.thickness = 0.0625;
    material.specular.ior = 1.45;
    material.transmission.color = clamp(baseColor, vec3(0.02), vec3(1.0));
    material.transmission.depth = 0.0;
    return material;
}

PrimeRcState primeMinecraftFoliageState(
        vec3 baseColor,
        vec3 outwardNormal,
        uint packedNormal,
        uint packedSpecular,
        uint materialFlags,
        vec3 viewDirection,
        float rayT,
        PrimeRcVolumeStack volumeStack) {
    vec3 closureNormal = dot(outwardNormal, viewDirection) < 0.0
            ? -outwardNormal
            : outwardNormal;
    PrimeRcMaterial material = primeMinecraftFoliageMaterial(
            baseColor, closureNormal, packedNormal, packedSpecular, materialFlags);
    vec3 localView = primeRcOnbToLocal(material.geometry.onb, viewDirection);
    float inverseOutsideIor = primeRcInverseOutsideIor(localView.z, volumeStack);
    return primeRcPrimeThinWallStateInit(
            material,
            localView,
            inverseOutsideIor,
            rayT,
            PRIME_REC2020_PRIMARY_WAVELENGTHS_NM,
            0u,
            PRIME_RC_DETAIL_DEFAULT,
            0u);
}

BsdfEvaluation primeEvaluateMinecraftFoliage(
        vec3 baseColor,
        vec3 outwardNormal,
        uint packedNormal,
        uint packedSpecular,
        uint materialFlags,
        vec3 viewDirection,
        vec3 scatterDirection,
        float rayT,
        PrimeRcVolumeStack volumeStack) {
    PrimeRcState state = primeMinecraftFoliageState(
            baseColor,
            outwardNormal,
            packedNormal,
            packedSpecular,
            materialFlags,
            viewDirection,
            rayT,
            volumeStack);
    vec3 localView = primeRcOnbToLocal(state.material.geometry.onb, viewDirection);
    vec3 localScatter = primeRcOnbToLocal(state.material.geometry.onb, scatterDirection);
    PrimeRcEval evaluation = primeRcPrimeThinWallEvaluate(localView, localScatter, state);
    BsdfEvaluation result = primeInvalidBsdfEvaluation();
    if (evaluation.throughput.flags != PRIME_RC_FLAG_NONE) {
        result.response = evaluation.throughput.value;
        result.pdf = evaluation.pdf;
    }
    result = primeSanitizeBsdfEvaluation(result);
    primeRecordNonnegative(result.response);
    primeRecordNonnegative(result.pdf);
    return result;
}

PrimeBsdfComponents primeEvaluateMinecraftFoliageComponents(
        vec3 baseColor,
        vec3 outwardNormal,
        uint packedNormal,
        uint packedSpecular,
        uint materialFlags,
        vec3 viewDirection,
        vec3 scatterDirection,
        float rayT,
        PrimeRcVolumeStack volumeStack) {
    PrimeBsdfComponents result;
    result.diffuseResponse = vec3(0.0);
    result.specularResponse = vec3(0.0);
    result.pdf = 0.0;
    PrimeRcState state = primeMinecraftFoliageState(
            baseColor,
            outwardNormal,
            packedNormal,
            packedSpecular,
            materialFlags,
            viewDirection,
            rayT,
            volumeStack);
    vec3 localView = primeRcOnbToLocal(state.material.geometry.onb, viewDirection);
    vec3 localScatter = primeRcOnbToLocal(state.material.geometry.onb, scatterDirection);
    result.pdf = primeRcPrimeThinWallPdf(localView, localScatter, state);
    PrimeRcState diffuseState = state;
    diffuseState.samplingFlags &= PRIME_RC_FLAG_DIFFUSE;
    PrimeRcState specularState = state;
    specularState.samplingFlags &= PRIME_RC_FLAG_SPECULAR | PRIME_RC_FLAG_DELTA;
    result.diffuseResponse = primeRcPrimeThinWallEval(
            localView, localScatter, diffuseState).value;
    result.specularResponse = primeRcPrimeThinWallEval(
            localView, localScatter, specularState).value;
    result = primeSanitizeBsdfComponents(result);
    primeRecordNonnegative(result.diffuseResponse);
    primeRecordNonnegative(result.specularResponse);
    primeRecordNonnegative(result.pdf);
    return result;
}

BsdfSample primeSampleMinecraftFoliageFromState(
        PrimeRcState state,
        vec3 viewDirection,
        vec3 sampleValue,
        PrimeRcVolumeStack volumeStack) {
    BsdfSample result = primeInvalidBsdfSample();
    vec3 localView = primeRcOnbToLocal(state.material.geometry.onb, viewDirection);
    PrimeRcSampleResult sampled = primeRcPrimeThinWallSample(
            localView, sampleValue, state, volumeStack);
    if (!primeRcHasSample(sampled.bsdfSample)) {
        return result;
    }
    result.direction = primeRcOnbToWorld(
            state.material.geometry.onb, sampled.bsdfSample.wo);
    result.response = sampled.bsdfSample.throughput.value;
    result.pdf = sampled.bsdfSample.pdf;
    result.relativeEta = 1.0;
    result.eventFlags = primeRcToBsdfEventFlags(sampled.bsdfSample.throughput.flags);
    return primeSanitizeBsdfSample(result);
}

BsdfSample primeSampleMinecraftFoliage(
        vec3 baseColor,
        vec3 outwardNormal,
        uint packedNormal,
        uint packedSpecular,
        uint materialFlags,
        vec3 viewDirection,
        vec3 sampleValue,
        float rayT,
        PrimeRcVolumeStack volumeStack) {
    PrimeRcState state = primeMinecraftFoliageState(
            baseColor,
            outwardNormal,
            packedNormal,
            packedSpecular,
            materialFlags,
            viewDirection,
            rayT,
            volumeStack);
    return primeSampleMinecraftFoliageFromState(
            state, viewDirection, sampleValue, volumeStack);
}

const uint PRIME_DENOISE_CLOSURE_OPAQUE = 0u;
const uint PRIME_DENOISE_CLOSURE_FOLIAGE = 1u;
const uint PRIME_DENOISE_CLOSURE_TRANSMISSIVE = 2u;

struct PrimeDenoiseAlbedos {
    vec3 diffuse;
    vec3 specular;
};

vec3 primeSanitizeDenoiseAlbedo(vec3 albedo) {
    vec3 result = vec3(
            primeBsdfFinite(albedo.x) ? clamp(albedo.x, 0.0, 1.0) : 0.0,
            primeBsdfFinite(albedo.y) ? clamp(albedo.y, 0.0, 1.0) : 0.0,
            primeBsdfFinite(albedo.z) ? clamp(albedo.z, 0.0, 1.0) : 0.0);
    primeRecordUnit(result);
    return result;
}

vec3 primeRcDenoiseClosureEnergy(
        vec3 localView,
        PrimeRcState state,
        uint closureKind) {
    if (closureKind == PRIME_DENOISE_CLOSURE_OPAQUE) {
        return state.material.weight.subsurface > 0.0
                ? primeRcSubsurfaceGlossyEnergy(localView, state)
                : primeRcBasicMetallicEnergy(localView, state);
    }
    if (closureKind == PRIME_DENOISE_CLOSURE_FOLIAGE) {
        return primeRcBaseSubstrateEnergy(localView, state);
    }
    return primeRcTransmissionEnergy(localView, state);
}

PrimeDenoiseAlbedos primeDenoiseAlbedosFromState(
        PrimeRcState state,
        vec3 viewDirection,
        uint closureKind) {
    vec3 localView = primeRcOnbToLocal(state.material.geometry.onb, viewDirection);
    uint materialFlags = state.samplingFlags;
    state.spectrumed = 0u;

    PrimeDenoiseAlbedos result;
    if (closureKind == PRIME_DENOISE_CLOSURE_TRANSMISSIVE) {
        // REBLUR exposes two independently filtered lanes. At a transparent primary surface they
        // represent transmission and reflection rather than diffuse and specular BRDF classes.
        state.samplingFlags = PRIME_RC_FLAG_TRANSMISSION;
        result.diffuse = primeSanitizeDenoiseAlbedo(
                primeRcDenoiseClosureEnergy(localView, state, closureKind));
        state.samplingFlags = PRIME_RC_FLAG_REFLECTION;
        result.specular = primeSanitizeDenoiseAlbedo(
                primeRcDenoiseClosureEnergy(localView, state, closureKind));
        return result;
    }
    state.samplingFlags = materialFlags & PRIME_RC_FLAG_DIFFUSE;
    result.diffuse = primeSanitizeDenoiseAlbedo(
            primeRcDenoiseClosureEnergy(localView, state, closureKind));
    state.samplingFlags = materialFlags & (PRIME_RC_FLAG_SPECULAR | PRIME_RC_FLAG_DELTA);
    result.specular = primeSanitizeDenoiseAlbedo(
            primeRcDenoiseClosureEnergy(localView, state, closureKind));
    return result;
}

struct PrimeTransmissivePrimarySample {
    PrimeTransmissiveBsdfSplit paths;
    PrimeDenoiseAlbedos albedos;
};

PrimeTransmissivePrimarySample primeSampleMinecraftTransmissionPrimary(
        vec3 baseColor,
        float opacity,
        vec3 outwardNormal,
        uint materialFlags,
        uint packedNormal,
        uint packedSpecular,
        vec3 viewDirection,
        vec3 reflectionSample,
        float rayT,
        PrimeRcVolumeStack volumeStack) {
    // This is the only fixed-split entry point. Translation, closure initialization, local-view
    // conversion and guide energy are shared before reflection and refraction diverge.
    PrimeRcState state = primeMinecraftTransmissionState(
            baseColor,
            opacity,
            outwardNormal,
            materialFlags,
            packedNormal,
            packedSpecular,
            viewDirection,
            rayT,
            volumeStack);
    vec3 localView = primeRcOnbToLocal(state.material.geometry.onb, viewDirection);
    // One directional-energy lookup supplies the reflection guide, the smooth-mirror response and
    // the complementary transmission weight. Fresnel remains in radiance.
    PrimeMinecraftMirrorSplit mirror = primeMinecraftMirrorSplit(state, localView);

    PrimeTransmissivePrimarySample result;
    result.albedos.diffuse = primeSanitizeDenoiseAlbedo(
            primeMinecraftSurfaceTransmittance(baseColor, opacity, materialFlags));
    result.albedos.specular = primeSanitizeDenoiseAlbedo(mirror.reflectance);
    result.paths = primeSampleMinecraftTransmissionSplitFromState(
            state,
            localView,
            mirror,
            outwardNormal,
            viewDirection,
            reflectionSample,
            baseColor,
            opacity,
            materialFlags,
            volumeStack);
    return result;
}

#endif
