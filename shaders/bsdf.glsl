#ifndef PRIME_BSDF_GLSL
#define PRIME_BSDF_GLSL

#include "bsdf_common.glsl"
#include "bsdf_fresnel.glsl"
#include "bsdf_diffuse.glsl"
#include "bsdf_microfacet.glsl"
#include "bsdf_subsurface.glsl"
#include "bsdf_emission.glsl"
#include "default_material.glsl"
#include "labpbr.glsl"
#include "material_translation.glsl"
#include "color_space.glsl"
#define PRIME_RC_TRANSMISSION_GGX_SET 0
#define PRIME_RC_TRANSMISSION_GGX_BINDING PRIME_DESCRIPTOR_TRANSMISSION_GGX_ENERGY
#include "robocute_bsdf_openpbr.glsl"

// Minecraft's translucent render layer is adapted to RoboCute's complete dielectric
// transmission closure. The imported closure owns Fresnel, rough reflection/refraction,
// importance sampling and medium transitions; this adapter only supplies the vanilla fallback
// material parameters and converts its f*|cos| convention to Prime's public BSDF contract.
struct PrimeTransmissiveBsdfSample {
    BsdfSample bsdfSample;
    PrimeRcVolumeStack volumeStack;
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

    vec3 decodedColor = max(baseColor, vec3(0.0));
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
        vec3 filterColor = peak > PRIME_BSDF_EPSILON
                ? decodedColor / peak
                : vec3(1.0);
        float tintWeight = mix(PRIME_GLASS_MINIMUM_TINT_WEIGHT, 1.0, coverage);
        transmissionColor = mix(vec3(1.0), filterColor, tintWeight);
    }
    transmissionColor = clamp(transmissionColor, vec3(1.0e-3), vec3(1.0));
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

// LabPBR stores predefined optical constants in linear-sRGB channel space. Prime evaluates all
// transport in linear Rec.2020, so reflectance is derived in the standard's source basis, tinted
// there as required, and only then crossed into the working space. Component-wise multiplication
// after the matrix conversion would describe a different spectrum.
bool primeLabPbrMetalOpticalConstants(uint metalId, out vec3 eta, out vec3 k) {
    if (metalId == 230u) {
        eta = vec3(2.9114, 2.9497, 2.5845);
        k = vec3(3.0893, 2.9318, 2.7670);
    } else if (metalId == 231u) {
        eta = vec3(0.18299, 0.42108, 1.3734);
        k = vec3(3.4242, 2.3459, 1.7704);
    } else if (metalId == 232u) {
        eta = vec3(1.3456, 0.96521, 0.61722);
        k = vec3(7.4746, 6.3995, 5.3031);
    } else if (metalId == 233u) {
        eta = vec3(3.1071, 3.1812, 2.3230);
        k = vec3(3.3314, 3.3291, 3.1350);
    } else if (metalId == 234u) {
        eta = vec3(0.27105, 0.67693, 1.3164);
        k = vec3(3.6092, 2.6248, 2.2921);
    } else if (metalId == 235u) {
        eta = vec3(1.9100, 1.8300, 1.4400);
        k = vec3(3.5100, 3.4000, 3.1800);
    } else if (metalId == 236u) {
        eta = vec3(2.3757, 2.0847, 1.8453);
        k = vec3(4.2655, 3.7153, 3.1365);
    } else if (metalId == 237u) {
        eta = vec3(0.15943, 0.14512, 0.13547);
        k = vec3(3.9291, 3.1900, 2.3808);
    } else {
        eta = vec3(0.0);
        k = vec3(0.0);
        return false;
    }
    return true;
}

struct PrimeLabPbrFresnel {
    vec3 f0;
    vec3 f82Tint;
};

PrimeLabPbrFresnel primeLabPbrMetalFresnel(vec3 baseColor, uint metalId) {
    PrimeLabPbrFresnel result;
    vec3 sourceTint = clamp(primeLinearRec2020ToLinearBt709(baseColor), 0.0, 1.0);
    vec3 eta;
    vec3 k;
    // RoboCute names this F82, but deliberately anchors the fitted tint at cos(theta)=1/7.
    // Use that exact model constant on both sides of the conversion instead of the nearby literal
    // cosine of 82 degrees, otherwise the reconstructed endpoint is only approximately correct.
    const float f82AnchorCosine = 1.0 / 7.0;
    float schlick82Weight = pow(1.0 - f82AnchorCosine, 5.0);
    if (primeLabPbrMetalOpticalConstants(metalId, eta, k)) {
        vec3 sourceF0 = primeFresnelConductor(1.0, eta, k) * sourceTint;
        vec3 sourceF82 = primeFresnelConductor(f82AnchorCosine, eta, k) * sourceTint;
        result.f0 = clamp(primeLinearSrgbToLinearRec2020(sourceF0), 0.0, 1.0);
        vec3 targetF82 = clamp(
                primeLinearSrgbToLinearRec2020(sourceF82), 0.0, 1.0);
        vec3 untintedSchlickF82 = mix(result.f0, vec3(1.0), schlick82Weight);
        // RoboCute's SchlickF82tintFresnel does not store absolute F(82 degrees): its f82
        // parameter multiplies the ordinary Schlick value at that angle. Supplying targetF82
        // directly applies the attenuation twice and makes smooth metals incorrectly black at
        // grazing angles. Solve the tint in Prime's working basis so the closure reconstructs the
        // converted physical F82 exactly.
        result.f82Tint = clamp(
                targetF82 / max(untintedSchlickF82, vec3(PRIME_BSDF_EPSILON)),
                0.0,
                1.0);
    } else if (primeLabPbrIsCustomMetalId(metalId)) {
        // LabPBR custom metal stores F0 directly in the albedo texture and has no authored edge
        // tint. One is RoboCute's neutral F82-tint value and therefore restores ordinary Schlick
        // grazing behaviour without inventing another material parameter.
        result.f0 = clamp(baseColor, 0.0, 1.0);
        result.f82Tint = vec3(1.0);
    } else {
        // Reserved values remain unreachable through the conservative translator. Keep a neutral
        // dielectric fallback so a future caller cannot accidentally revive undefined metal IDs.
        result.f0 = vec3(PRIME_DEFAULT_DIELECTRIC_F0);
        result.f82Tint = vec3(1.0);
    }
    return result;
}

float primeLabPbrLinearRoughness(uint packedNormal, uint packedSpecular, uint flags) {
    return primeDecodeAndTranslateLabPbr(
            packedNormal, packedSpecular, flags).linearRoughness;
}

vec3 primeLabPbrSpecularF0(
        vec3 baseColor, uint packedNormal, uint packedSpecular, uint flags) {
    PrimeTranslatedLabPbrMaterial translated = primeDecodeAndTranslateLabPbr(
            packedNormal, packedSpecular, flags);
    if (primeTranslatedLabPbrIsMetal(translated)) {
        return primeLabPbrMetalFresnel(baseColor, translated.metalId).f0;
    }
    return vec3(translated.dielectricF0);
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
        vec3 randomValue,
        float rayT,
        PrimeRcVolumeStack volumeStack) {
    PrimeRcMaterial material = primeOpaqueMaterial(
            baseColor, normal, packedNormal, packedSpecular, flags);
    vec3 localView = primeRcOnbToLocal(material.geometry.onb, viewDirection);
    float inverseOutsideIor = primeRcInverseOutsideIor(localView.z, volumeStack);
    if (material.weight.subsurface > 0.0) {
        // Only the conservatively translated alpha-cut SSS semantic needs the complete OpenPBR
        // graph. Ordinary LabPBR terrain follows RoboCute's active RBC_LITE_PBR_MATERIAL
        // polymorphic dispatch and therefore uses its white-furnace-tested BasicMetallic closure.
        return primeRcOpenPbrStateInit(
                material,
                localView,
                randomValue,
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

struct PrimeOpaqueBsdfComponents {
    vec3 diffuseValue;
    vec3 specularValue;
    float pdf;
};

PrimeOpaqueBsdfComponents primeEvaluateOpaqueComponents(
        vec3 baseColor,
        vec3 normal,
        uint packedNormal,
        uint packedSpecular,
        uint flags,
        vec3 viewDirection,
        vec3 scatterDirection,
        float rayT,
        PrimeRcVolumeStack volumeStack) {
    PrimeOpaqueBsdfComponents result;
    result.diffuseValue = vec3(0.0);
    result.specularValue = vec3(0.0);
    result.pdf = 0.0;
    PrimeRcState state = primeOpaqueState(
            baseColor,
            normal,
            packedNormal,
            packedSpecular,
            flags,
            viewDirection,
            vec3(0.5),
            rayT,
            volumeStack);
    vec3 localView = primeRcOnbToLocal(state.material.geometry.onb, viewDirection);
    vec3 localScatter = primeRcOnbToLocal(state.material.geometry.onb, scatterDirection);
    float cosine = abs(localScatter.z);
    if (cosine <= PRIME_BSDF_EPSILON) {
        return result;
    }
    bool fullOpenPbr = state.material.weight.subsurface > 0.0;
    PrimeRcEval full = fullOpenPbr
            ? primeRcOpenPbrEvaluate(localView, localScatter, state)
            : primeRcBasicMetallicEvaluate(localView, localScatter, state);
    result.pdf = full.pdf;
    PrimeRcState diffuseState = state;
    diffuseState.samplingFlags = PRIME_RC_FLAG_DIFFUSE;
    PrimeRcThroughput diffuse = fullOpenPbr
            ? primeRcOpenPbrEval(localView, localScatter, diffuseState)
            : primeRcBasicMetallicEval(localView, localScatter, diffuseState);
    PrimeRcState specularState = state;
    specularState.samplingFlags = PRIME_RC_FLAG_SPECULAR | PRIME_RC_FLAG_DELTA;
    PrimeRcThroughput specular = fullOpenPbr
            ? primeRcOpenPbrEval(localView, localScatter, specularState)
            : primeRcBasicMetallicEval(localView, localScatter, specularState);
    result.diffuseValue = diffuse.value / cosine;
    result.specularValue = specular.value / cosine;
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
            sampleValue,
            rayT,
            volumeStack);
    vec3 localView = primeRcOnbToLocal(state.material.geometry.onb, viewDirection);
    PrimeRcSampleResult sampled = state.material.weight.subsurface > 0.0
            ? primeRcOpenPbrSample(localView, sampleValue, state, volumeStack)
            : primeRcBasicMetallicSample(localView, sampleValue, state, volumeStack);
    if (sampled.bsdfSample.pdf <= 0.0
            || sampled.bsdfSample.throughput.flags == PRIME_RC_FLAG_NONE) {
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
    result.weight = sampled.bsdfSample.throughput.value / sampled.bsdfSample.pdf;
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

PrimeMinecraftMirrorSplit primeMinecraftMirrorSplit(
        vec3 localView,
        PrimeRcState state) {
    PrimeMinecraftMirrorSplit result;
    vec2 directionalEnergy = primeRcMicrofacetDirectionalAlbedoTransmission(
            state.specularMicrofacet,
            localView.z,
            state.specularFresnel.ior);
    float resolvedEnergy = max(primeRcReduceSum(directionalEnergy), PRIME_BSDF_EPSILON);
    float reflectedFraction = clamp(directionalEnergy.x / resolvedEnergy, 0.0, 1.0);
    // The default Minecraft adapter keeps dielectric reflection achromatic. Retaining the color
    // term here makes the split remain correct if a future material decoder tints the interface.
    result.reflectance = reflectedFraction * state.specularFresnel.color;
    result.probability = clamp(
            primeRcSpectrumToWeight(result.reflectance), 0.0, 1.0);
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
    PrimeMinecraftMirrorSplit mirror = primeMinecraftMirrorSplit(localView, state);
    if (state.geometryThinWalled == 0u) {
        state.samplingFlags = closedReflection
                ? PRIME_RC_FLAG_REFLECTION
                : PRIME_RC_FLAG_TRANSMISSION;
    }
    PrimeRcEval evaluation = primeRcTransmissionEvaluate(localView, localScatter, state);
    BsdfEvaluation result = primeInvalidBsdfEvaluation();
    float cosine = abs(localScatter.z);
    if (evaluation.pdf > 0.0 && cosine > PRIME_BSDF_EPSILON
            && evaluation.throughput.flags != PRIME_RC_FLAG_NONE) {
        result.value = evaluation.throughput.value / cosine;
        result.pdf = evaluation.pdf * (state.geometryThinWalled == 0u
                ? (closedReflection ? mirror.probability : 1.0 - mirror.probability)
                : 1.0);
    }
    return result;
}

PrimeTransmissiveBsdfSample primeSampleMinecraftTransmissionBranchFromState(
        PrimeRcState state,
        PrimeMinecraftMirrorSplit mirror,
        vec3 outwardNormal,
        vec3 viewDirection,
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
    PrimeMinecraftMirrorSplit mirror = primeMinecraftMirrorSplit(localView, state);
    if (state.geometryThinWalled == 0u) {
        bool reflectionBranch = sampleValue.z < mirror.probability;
        float branchStart = reflectionBranch ? 0.0 : mirror.probability;
        float branchProbability = reflectionBranch
                ? mirror.probability
                : 1.0 - mirror.probability;
        if (branchProbability <= PRIME_BSDF_EPSILON) {
            return result;
        }
        vec3 branchSample = sampleValue;
        branchSample.z = clamp(
                (sampleValue.z - branchStart) / branchProbability,
                0.0,
                0.99999994);
        result = primeSampleMinecraftTransmissionBranchFromState(
                state,
                mirror,
                outwardNormal,
                viewDirection,
                branchSample,
                reflectionBranch,
                volumeStack);
        result.bsdfSample.weight /= branchProbability;
        result.bsdfSample.pdf *= branchProbability;
        return result;
    }
    PrimeRcSampleResult sampled = primeRcTransmissionSample(
            localView, sampleValue, state, volumeStack);
    if (sampled.bsdfSample.pdf <= 0.0
            || sampled.bsdfSample.throughput.flags == PRIME_RC_FLAG_NONE) {
        return result;
    }
    result.bsdfSample.direction = primeRcOnbToWorld(
            state.material.geometry.onb, sampled.bsdfSample.wo);
    result.bsdfSample.weight = sampled.bsdfSample.throughput.value
            / sampled.bsdfSample.pdf;
    result.bsdfSample.pdf = sampled.bsdfSample.pdf;
    bool transmitted = primeRcIsTransmissive(sampled.bsdfSample.throughput.flags);
    result.bsdfSample.relativeEta = transmitted && state.geometryThinWalled == 0u
            ? (localView.z > 0.0
                    ? state.specularFresnel.ior
                    : 1.0 / max(state.specularFresnel.ior, PRIME_BSDF_EPSILON))
            : 1.0;
    result.bsdfSample.eventFlags = primeRcToBsdfEventFlags(
            sampled.bsdfSample.throughput.flags);
    result.volumeStack = sampled.volumeStack;
    return result;
}

PrimeTransmissiveBsdfSample primeSampleMinecraftTransmissionBranchFromState(
        PrimeRcState state,
        PrimeMinecraftMirrorSplit mirror,
        vec3 outwardNormal,
        vec3 viewDirection,
        vec3 sampleValue,
        bool reflectionBranch,
        PrimeRcVolumeStack volumeStack) {
    PrimeTransmissiveBsdfSample result;
    result.bsdfSample = primeInvalidBsdfSample();
    result.volumeStack = volumeStack;
    vec3 localView = primeRcOnbToLocal(state.material.geometry.onb, viewDirection);

    if (state.geometryThinWalled == 0u
            && reflectionBranch
            && primeRcMicrofacetEffectivelySmooth(state.specularMicrofacet)) {
        // Closed Minecraft glass deliberately models the reflected interface as a delta mirror.
        // This conditional branch carries the physical Fresnel energy itself. No selection
        // probability belongs inside this helper; the fixed-proposal caller applies its
        // separate proposal probability exactly once after the conditional sample is complete.
        if (all(lessThanEqual(mirror.reflectance, vec3(0.0)))) {
            return result;
        }
        result.bsdfSample.direction = reflect(-viewDirection, outwardNormal);
        result.bsdfSample.weight = mirror.reflectance;
        result.bsdfSample.pdf = 1.0;
        result.bsdfSample.relativeEta = 1.0;
        result.bsdfSample.eventFlags = PRIME_BSDF_EVENT_REFLECTION
                | PRIME_BSDF_EVENT_DELTA;
        return result;
    }

    state.samplingFlags = reflectionBranch
            ? PRIME_RC_FLAG_REFLECTION
            : PRIME_RC_FLAG_TRANSMISSION;
    PrimeRcSampleResult sampled = primeRcTransmissionSample(
            localView, sampleValue, state, volumeStack);
    if (sampled.bsdfSample.pdf <= 0.0
            || sampled.bsdfSample.throughput.flags == PRIME_RC_FLAG_NONE) {
        return result;
    }
    // Forcing a branch renormalizes RoboCute's internal proposal onto that branch. The returned
    // throughput still contains its complete physical Fresnel/transmission energy, so f/pdf is
    // already the unbiased conditional estimator. No branch-selection probability belongs here.
    result.bsdfSample.direction = primeRcOnbToWorld(
            state.material.geometry.onb, sampled.bsdfSample.wo);
    result.bsdfSample.weight = sampled.bsdfSample.throughput.value
            / sampled.bsdfSample.pdf;
    result.bsdfSample.pdf = sampled.bsdfSample.pdf;
    // RoboCute keeps eta in the closure state rather than its sample record. Prime's transport
    // ABI defines relativeEta as n_transmitted / n_incident (the inverse of GLSL refract's eta).
    // Preserve it here: transparent reprojection needs the same interface contract as the BSDF,
    // and replacing it with 1 silently turns every refractive path into straight-through motion.
    bool transmitted = primeRcIsTransmissive(sampled.bsdfSample.throughput.flags);
    result.bsdfSample.relativeEta = transmitted && state.geometryThinWalled == 0u
            ? (localView.z > 0.0
                    ? state.specularFresnel.ior
                    : 1.0 / max(state.specularFresnel.ior, PRIME_BSDF_EPSILON))
            : 1.0;
    result.bsdfSample.eventFlags = primeRcToBsdfEventFlags(
            sampled.bsdfSample.throughput.flags);
    result.volumeStack = sampled.volumeStack;
    return result;
}

PrimeTransmissiveBsdfSample primeSampleMinecraftTransmissionBranch(
        vec3 baseColor,
        float opacity,
        vec3 outwardNormal,
        uint materialFlags,
        uint packedNormal,
        uint packedSpecular,
        vec3 viewDirection,
        vec3 sampleValue,
        bool reflectionBranch,
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
    PrimeMinecraftMirrorSplit mirror = primeMinecraftMirrorSplit(localView, state);
    return primeSampleMinecraftTransmissionBranchFromState(
            state,
            mirror,
            outwardNormal,
            viewDirection,
            sampleValue,
            reflectionBranch,
            volumeStack);
}

// Realtime-only transport proposal used by transparent.rgen. The first visible interface keeps
// physical reflection/refraction and Fresnel energy; later transparent interfaces redirect all
// surviving energy into an unbent continuation while retaining tint, absorption and volume-stack
// transitions. The unbiased screenshot integrator never calls this approximation.
PrimeTransmissiveBsdfSample primeSampleMinecraftRealtimeTransmissionBranch(
        vec3 baseColor,
        float opacity,
        vec3 outwardNormal,
        uint materialFlags,
        uint packedNormal,
        uint packedSpecular,
        vec3 viewDirection,
        vec3 sampleValue,
        bool reflectionBranch,
        bool redirectOmittedReflection,
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
    PrimeMinecraftMirrorSplit mirror = primeMinecraftMirrorSplit(localView, state);
    if (!reflectionBranch && redirectOmittedReflection) {
        // Past the visible interface realtime mode deliberately has one continuation only. Build
        // that straight-through event directly instead of asking the refractive closure for a
        // direction and discarding it: doing so also prevents artificial total-internal-reflection
        // failures caused by our intentionally unbent ray inside a closed dielectric.
        PrimeTransmissiveBsdfSample redirected;
        redirected.bsdfSample = primeInvalidBsdfSample();
        redirected.volumeStack = volumeStack;
        if (state.geometryThinWalled != 0u) {
            PrimeRcVecPair retained = primeRcTransmissionThinWallRt(
                    abs(localView.z), state);
            // Reflection is not traced here, so all non-absorbed thin-sheet energy continues.
            redirected.bsdfSample.weight = retained.first + retained.second;
        } else {
            // A closed volume has no surface tint; its color is Beer-Lambert attenuation along
            // the segment. Preserve the same stack transition as RoboCute without eta^2 because
            // this approximation does not change solid angle.
            redirected.bsdfSample.weight = state.transmissionTint;
            if (localView.z >= 0.0) {
                PrimeRcVolume volume = state.transmissionVolume;
                volume.ior = state.originalIor;
                primeRcStackPush(redirected.volumeStack, volume);
            } else if (redirected.volumeStack.count == 0u) {
                redirected.bsdfSample.weight *= exp(
                        -state.transmissionVolume.extinction * state.rayT);
            } else {
                primeRcStackPop(redirected.volumeStack);
            }
        }
        redirected.bsdfSample.direction = -viewDirection;
        redirected.bsdfSample.pdf = 1.0;
        redirected.bsdfSample.relativeEta = 1.0;
        redirected.bsdfSample.eventFlags = PRIME_BSDF_EVENT_TRANSMISSION
                | PRIME_BSDF_EVENT_DELTA;
        return redirected;
    }

    PrimeTransmissiveBsdfSample result =
            primeSampleMinecraftTransmissionBranchFromState(
                    state,
                    mirror,
                    outwardNormal,
                    viewDirection,
                    sampleValue,
                    reflectionBranch,
                    volumeStack);
    if (result.bsdfSample.pdf <= 0.0
            || all(lessThanEqual(result.bsdfSample.weight, vec3(0.0)))) {
        return result;
    }
    // At the first visible interface both deterministic branches keep RoboCute's physical
    // reflection/refraction directions and Fresnel energy. Only later interfaces take the
    // realtime straight-through path returned above.
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
        vec3 randomValue,
        float rayT,
        PrimeRcVolumeStack volumeStack) {
    vec3 closureNormal = dot(outwardNormal, viewDirection) < 0.0
            ? -outwardNormal
            : outwardNormal;
    PrimeRcMaterial material = primeMinecraftFoliageMaterial(
            baseColor, closureNormal, packedNormal, packedSpecular, materialFlags);
    vec3 localView = primeRcOnbToLocal(material.geometry.onb, viewDirection);
    float inverseOutsideIor = primeRcInverseOutsideIor(localView.z, volumeStack);
    return primeRcOpenPbrStateInit(
            material,
            localView,
            randomValue,
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
            vec3(0.5),
            rayT,
            volumeStack);
    vec3 localView = primeRcOnbToLocal(state.material.geometry.onb, viewDirection);
    vec3 localScatter = primeRcOnbToLocal(state.material.geometry.onb, scatterDirection);
    PrimeRcEval evaluation = primeRcOpenPbrEvaluate(localView, localScatter, state);
    BsdfEvaluation result = primeInvalidBsdfEvaluation();
    float cosine = abs(localScatter.z);
    if (evaluation.pdf > 0.0 && cosine > PRIME_BSDF_EPSILON
            && evaluation.throughput.flags != PRIME_RC_FLAG_NONE) {
        result.value = evaluation.throughput.value / cosine;
        result.pdf = evaluation.pdf;
    }
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
            sampleValue,
            rayT,
            volumeStack);
    vec3 localView = primeRcOnbToLocal(state.material.geometry.onb, viewDirection);
    PrimeRcSampleResult sampled = primeRcOpenPbrSample(
            localView, sampleValue, state, volumeStack);
    if (sampled.bsdfSample.pdf <= 0.0
            || sampled.bsdfSample.throughput.flags == PRIME_RC_FLAG_NONE) {
        return result;
    }
    result.direction = primeRcOnbToWorld(
            state.material.geometry.onb, sampled.bsdfSample.wo);
    result.weight = sampled.bsdfSample.throughput.value / sampled.bsdfSample.pdf;
    result.pdf = sampled.bsdfSample.pdf;
    result.relativeEta = 1.0;
    result.eventFlags = primeRcToBsdfEventFlags(sampled.bsdfSample.throughput.flags);
    return result;
}

#endif
