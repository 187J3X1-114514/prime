#ifndef PRIME_BSDF_GLSL
#define PRIME_BSDF_GLSL

#include "bsdf_common.glsl"
#include "default_material.glsl"
#include "labpbr.glsl"
#include "material_translation.glsl"
#define PRIME_RC_TRANSMISSION_GGX_SET 0
#define PRIME_RC_TRANSMISSION_GGX_BINDING PRIME_DESCRIPTOR_TRANSMISSION_GGX_ENERGY
#include "prime_bsdf_specializations.glsl"

// Minecraft's translucent render layer is adapted to RoboCute's complete dielectric
// transmission closure. The imported closure owns Fresnel, rough reflection/refraction,
// importance sampling and medium transitions; this adapter only supplies the vanilla fallback
// material parameters and preserves its f*|cos| response in Prime's public BSDF contract.
struct PrimeTransmissiveBsdfSample {
    BsdfSample bsdfSample;
    PrimeRcVolumeStack volumeStack;
};

struct PrimeTransmissiveBsdfSplit {
    PrimeTransmissiveBsdfSample reflection;
    PrimeTransmissiveBsdfSample transmission;
};

const float PRIME_GLASS_MINIMUM_TINT_WEIGHT = 0.75;
// Rec.2020's near-monochromatic primaries are 630, 532 and 467 nm. Pope and Fry's measured
// absorption coefficients for pure water at 22 C are 0.2916 m^-1 at 630 nm and, by linear
// interpolation of their Table 3, 0.04444 m^-1 at 532 nm and 0.010182 m^-1 at 467 nm.
// Minecraft and Prime's atmosphere both define one world unit as one metre. Do not retint these
// values with biome color: that would turn a surface-art direction input into fictitious volume
// absorption and break the Beer-Lambert medium contract.
const vec3 PRIME_REC2020_PRIMARY_WAVELENGTHS_NM = vec3(630.0, 532.0, 467.0);
const vec3 PRIME_PURE_WATER_ABSORPTION_M_INV = vec3(0.2916, 0.04444, 0.010182);

bool primeRcHasSample(PrimeRcSample sampleValue) {
    primeRecordNonnegative(sampleValue.throughput.value);
    primeRecordNonnegative(sampleValue.pdf);
    if (sampleValue.throughput.flags != PRIME_RC_FLAG_NONE) {
        primeRecordDirection(sampleValue.wo);
    }
    return sampleValue.throughput.flags != PRIME_RC_FLAG_NONE;
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
    // smooth dielectrics. This is a material parameter, not a shortcut inside RoboCute: Fresnel,
    // eta^2 radiance transport, absorption and the volume stack remain the library's full model.
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

    vec3 decodedColor = baseColor;
    float coverage = clamp(opacity, 0.0, 1.0);
    vec3 transmissionColor;
    if (water) {
        // RoboCute's transmission ABI stores transmittance at a reference depth and recovers
        // extinction as -log(T) / depth. Supplying the measured one-metre transmittance therefore
        // reconstructs PRIME_PURE_WATER_ABSORPTION_M_INV without changing its volume-stack code.
        transmissionColor = exp(-PRIME_PURE_WATER_ABSORPTION_M_INV);
    } else {
        // Vanilla stained-glass RGB contains display brightness as well as hue. A transmission
        // filter should preserve the dominant channel and attenuate the others, otherwise low
        // raster alpha mixes panes and blocks almost back to clear white. Normalizing by the peak
        // retains energy in that dominant channel while making the authored color legible.
        float peak = max(decodedColor.r, max(decodedColor.g, decodedColor.b));
        vec3 filterColor = peak > 0.0
                ? decodedColor / peak
                : vec3(1.0);
        float tintWeight = mix(PRIME_GLASS_MINIMUM_TINT_WEIGHT, 1.0, coverage);
        transmissionColor = mix(vec3(1.0), filterColor, tintWeight);
    }
    material.transmission.color = transmissionColor;
    // One block is one metre for measured water and the authored depth for glass-like models.
    // True zero-volume surfaces use the closure's explicit zero-depth tint path.
    material.transmission.depth = thinWalled
            ? 0.0
            : 1.0;
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
    primeRecordNonnegative(result.diffuseResponse);
    primeRecordNonnegative(result.specularResponse);
    primeRecordNonnegative(result.pdf);
    return result;
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
    BsdfSample result = primeInvalidBsdfSample();
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
    return result;
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
    return primeRcTransmissionStateInit(
            material,
            localView,
            inverseOutsideIor,
            rayT,
            PRIME_REC2020_PRIMARY_WAVELENGTHS_NM,
            0u,
            PRIME_RC_DETAIL_DEFAULT,
            0u);
}

struct PrimeMinecraftMirrorSplit {
    vec3 reflectance;
    float probability;
};

PrimeMinecraftMirrorSplit primeMinecraftMirrorSplit(PrimeRcState state) {
    PrimeMinecraftMirrorSplit result;
    // The default Minecraft adapter keeps dielectric reflection achromatic. Retaining the color
    // term here makes the split remain correct if a future material decoder tints the interface.
    result.reflectance = state.transmissionMultipleScattering.z * state.specularFresnel.color;
    result.probability = primeRcSpectrumToWeight(result.reflectance);
    primeRecordUnit(result.reflectance);
    primeRecordUnit(result.probability);
    return result;
}

BsdfEvaluation primeEvaluateMinecraftTransmissionImpl(
        vec3 baseColor,
        float opacity,
        vec3 outwardNormal,
        uint materialFlags,
        uint packedNormal,
        uint packedSpecular,
        vec3 viewDirection,
        vec3 scatterDirection,
        float rayT,
        PrimeRcVolumeStack volumeStack,
        bool conditionalBranch) {
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
    bool closedReflection = state.geometryThinWalled == 0u
            && localView.z * localScatter.z >= 0.0;
    if (closedReflection
            && primeRcMicrofacetEffectivelySmooth(state.specularMicrofacet)) {
        // A coherent reflection has no finite solid-angle evaluation.
        return primeInvalidBsdfEvaluation();
    }
    PrimeMinecraftMirrorSplit mirror;
    mirror.reflectance = vec3(0.0);
    mirror.probability = 0.0;
    if (state.geometryThinWalled == 0u && !conditionalBranch) {
        mirror = primeMinecraftMirrorSplit(state);
    }
    if (state.geometryThinWalled == 0u || conditionalBranch) {
        state.samplingFlags = closedReflection
                ? PRIME_RC_FLAG_REFLECTION
                : PRIME_RC_FLAG_TRANSMISSION;
    }
    PrimeRcEval evaluation = primeRcTransmissionEvaluate(localView, localScatter, state);
    BsdfEvaluation result = primeInvalidBsdfEvaluation();
    if (evaluation.throughput.flags != PRIME_RC_FLAG_NONE) {
        result.response = evaluation.throughput.value;
        result.pdf = evaluation.pdf * (state.geometryThinWalled == 0u && !conditionalBranch
                ? (closedReflection ? mirror.probability : 1.0 - mirror.probability)
                : 1.0);
    }
    primeRecordNonnegative(result.response);
    primeRecordNonnegative(result.pdf);
    return result;
}

PrimeTransmissiveBsdfSample primeSampleMinecraftTransmissionBranchFromState(
        PrimeRcState state,
        PrimeMinecraftMirrorSplit mirror,
        vec3 outwardNormal,
        vec3 viewDirection,
        vec3 localView,
        vec3 sampleValue,
        bool reflectionBranch,
        PrimeRcVolumeStack volumeStack);

PrimeTransmissiveBsdfSample primeSampleMinecraftTransmission(
        vec3 baseColor,
        float opacity,
        vec3 outwardNormal,
        uint materialFlags,
        uint packedNormal,
        uint packedSpecular,
        vec3 viewDirection,
        vec3 sampleValue,
        float rayT,
        PrimeRcVolumeStack volumeStack) {
    PrimeTransmissiveBsdfSample result;
    result.bsdfSample = primeInvalidBsdfSample();
    result.volumeStack = volumeStack;
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
    PrimeMinecraftMirrorSplit mirror = primeMinecraftMirrorSplit(state);
    if (state.geometryThinWalled == 0u) {
        bool reflectionBranch = sampleValue.z < mirror.probability;
        float branchStart = reflectionBranch ? 0.0 : mirror.probability;
        float branchProbability = reflectionBranch
                ? mirror.probability
                : 1.0 - mirror.probability;
        vec3 branchSample = sampleValue;
        float remappedBranchSample =
                (sampleValue.z - branchStart) / branchProbability;
        branchSample.z = remappedBranchSample;
        result = primeSampleMinecraftTransmissionBranchFromState(
                state,
                mirror,
                outwardNormal,
                viewDirection,
                localView,
                branchSample,
                reflectionBranch,
                volumeStack);
        result.bsdfSample.pdf *= branchProbability;
        primeRecordNonnegative(result.bsdfSample.response);
        primeRecordNonnegative(result.bsdfSample.pdf);
        return result;
    }
    PrimeRcSampleResult sampled = primeRcPrimeTransmissionSample(
            localView, sampleValue, state, volumeStack);
    if (!primeRcHasSample(sampled.bsdfSample)) {
        return result;
    }
    result.bsdfSample.direction = primeRcOnbToWorld(
            state.material.geometry.onb, sampled.bsdfSample.wo);
    result.bsdfSample.response = sampled.bsdfSample.throughput.value;
    result.bsdfSample.pdf = sampled.bsdfSample.pdf;
    bool transmitted = primeRcIsTransmissive(sampled.bsdfSample.throughput.flags);
    result.bsdfSample.relativeEta = transmitted && state.geometryThinWalled == 0u
            ? (localView.z > 0.0
                    ? state.specularFresnel.ior
                    : 1.0 / state.specularFresnel.ior)
            : 1.0;
    result.bsdfSample.eventFlags = primeRcToBsdfEventFlags(
            sampled.bsdfSample.throughput.flags);
    result.volumeStack = sampled.volumeStack;
    return result;
}

BsdfEvaluation primeEvaluateMinecraftTransmission(
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
    return primeEvaluateMinecraftTransmissionImpl(
            baseColor,
            opacity,
            outwardNormal,
            materialFlags,
            packedNormal,
            packedSpecular,
            viewDirection,
            scatterDirection,
            rayT,
            volumeStack,
            false);
}

BsdfEvaluation primeEvaluateMinecraftTransmissionBranch(
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
    // Fixed stratification samples each hemisphere conditionally. Its NEE weight must compare
    // against that same conditional PDF rather than the stochastic mixture PDF.
    return primeEvaluateMinecraftTransmissionImpl(
            baseColor,
            opacity,
            outwardNormal,
            materialFlags,
            packedNormal,
            packedSpecular,
            viewDirection,
            scatterDirection,
            rayT,
            volumeStack,
            true);
}

PrimeTransmissiveBsdfSplit primeSampleMinecraftTransmissionSplitFromState(
        PrimeRcState state,
        vec3 localView,
        PrimeMinecraftMirrorSplit mirror,
        vec3 outwardNormal,
        vec3 viewDirection,
        vec3 reflectionSample,
        vec3 transmissionSample,
        PrimeRcVolumeStack volumeStack) {
    // The first visible transparent interface is stratified over its two disjoint domains.
    PrimeTransmissiveBsdfSplit result;
    result.reflection = primeSampleMinecraftTransmissionBranchFromState(
            state,
            mirror,
            outwardNormal,
            viewDirection,
            localView,
            reflectionSample,
            true,
            volumeStack);
    result.transmission = primeSampleMinecraftTransmissionBranchFromState(
            state,
            mirror,
            outwardNormal,
            viewDirection,
            localView,
            transmissionSample,
            false,
            volumeStack);
    // These are conditional proposals over disjoint physical lobes. Because both are evaluated,
    // neither PDF includes a branch-selection probability; summing their estimates is unbiased.
    return result;
}

PrimeTransmissiveBsdfSample primeSampleMinecraftTransmissionBranchFromState(
        PrimeRcState state,
        PrimeMinecraftMirrorSplit mirror,
        vec3 outwardNormal,
        vec3 viewDirection,
        vec3 localView,
        vec3 sampleValue,
        bool reflectionBranch,
        PrimeRcVolumeStack volumeStack) {
    PrimeTransmissiveBsdfSample result;
    result.bsdfSample = primeInvalidBsdfSample();
    result.volumeStack = volumeStack;
    if (state.geometryThinWalled == 0u
            && reflectionBranch
            && primeRcMicrofacetEffectivelySmooth(state.specularMicrofacet)) {
        // Closed Minecraft glass deliberately models the reflected interface as a delta mirror.
        // This conditional branch carries the physical Fresnel energy itself. No selection
        // probability belongs inside this helper: a stochastic caller adds its proposal
        // probability, while a caller evaluating both disjoint branches does not.
        if (all(lessThanEqual(mirror.reflectance, vec3(0.0)))) {
            return result;
        }
        result.bsdfSample.direction = reflect(-viewDirection, outwardNormal);
        result.bsdfSample.response = mirror.reflectance;
        result.bsdfSample.pdf = 1.0;
        result.bsdfSample.relativeEta = 1.0;
        result.bsdfSample.eventFlags = PRIME_BSDF_EVENT_REFLECTION
                | PRIME_BSDF_EVENT_DELTA;
        return result;
    }

    state.samplingFlags = reflectionBranch
            ? PRIME_RC_FLAG_REFLECTION
            : PRIME_RC_FLAG_TRANSMISSION;
    PrimeRcSampleResult sampled = primeRcPrimeTransmissionSample(
            localView, sampleValue, state, volumeStack);
    if (!primeRcHasSample(sampled.bsdfSample)) {
        return result;
    }
    // Forcing a branch renormalizes RoboCute's internal proposal onto that branch. The returned
    // throughput still contains its complete physical Fresnel/transmission response; only a
    // stochastic outer branch selection belongs in this conditional PDF.
    result.bsdfSample.direction = primeRcOnbToWorld(
            state.material.geometry.onb, sampled.bsdfSample.wo);
    result.bsdfSample.response = sampled.bsdfSample.throughput.value;
    result.bsdfSample.pdf = sampled.bsdfSample.pdf;
    // RoboCute keeps eta in the closure state rather than its sample record. Prime's transport
    // ABI defines relativeEta as n_transmitted / n_incident (the inverse of GLSL refract's eta).
    // Preserve it here: transparent reprojection needs the same interface contract as the BSDF,
    // and replacing it with 1 silently turns every refractive path into straight-through motion.
    bool transmitted = primeRcIsTransmissive(sampled.bsdfSample.throughput.flags);
    result.bsdfSample.relativeEta = transmitted && state.geometryThinWalled == 0u
            ? (localView.z > 0.0
                    ? state.specularFresnel.ior
                    : 1.0 / state.specularFresnel.ior)
            : 1.0;
    result.bsdfSample.eventFlags = primeRcToBsdfEventFlags(
            sampled.bsdfSample.throughput.flags);
    result.volumeStack = sampled.volumeStack;
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
    primeRecordNonnegative(result.diffuseResponse);
    primeRecordNonnegative(result.specularResponse);
    primeRecordNonnegative(result.pdf);
    return result;
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
    BsdfSample result = primeInvalidBsdfSample();
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
    return result;
}

const uint PRIME_DENOISE_CLOSURE_OPAQUE = 0u;
const uint PRIME_DENOISE_CLOSURE_FOLIAGE = 1u;
const uint PRIME_DENOISE_CLOSURE_TRANSMISSIVE = 2u;

struct PrimeDenoiseAlbedos {
    vec3 diffuse;
    vec3 specular;
};

vec3 primeSanitizeDenoiseAlbedo(vec3 albedo) {
    primeRecordUnit(albedo);
    return clamp(albedo, vec3(0.0), vec3(1.0));
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
        vec3 transmissionSample,
        float rayT,
        PrimeRcVolumeStack volumeStack) {
    // This is the only fixed-split entry point. Translation, closure initialization, local-view
    // conversion and guide energy are shared before the unavoidable two conditional samples.
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
    // One directional-energy lookup supplies both the reflection demodulation factor and the
    // smooth-mirror sample. The conditional transmission factor is exactly the closure tint;
    // Fresnel remains in illumination and is restored without losing energy after denoising.
    PrimeMinecraftMirrorSplit mirror = primeMinecraftMirrorSplit(state);

    PrimeTransmissivePrimarySample result;
    result.albedos.diffuse = primeSanitizeDenoiseAlbedo(state.transmissionTint);
    result.albedos.specular = primeSanitizeDenoiseAlbedo(mirror.reflectance);
    result.paths = primeSampleMinecraftTransmissionSplitFromState(
            state,
            localView,
            mirror,
            outwardNormal,
            viewDirection,
            reflectionSample,
            transmissionSample,
            volumeStack);
    return result;
}

#endif
