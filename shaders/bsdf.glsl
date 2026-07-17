#ifndef PRIME_BSDF_GLSL
#define PRIME_BSDF_GLSL

#include "bsdf_common.glsl"
#include "bsdf_fresnel.glsl"
#include "bsdf_diffuse.glsl"
#include "bsdf_microfacet.glsl"
#include "bsdf_subsurface.glsl"
#include "bsdf_emission.glsl"
#include "default_material.glsl"
#define PRIME_RC_TRANSMISSION_GGX_SET 0
#define PRIME_RC_TRANSMISSION_GGX_BINDING PRIME_DESCRIPTOR_TRANSMISSION_GGX_ENERGY
#include "robocute_bsdf_openpbr.glsl"

// Normalized material parameters are deliberately separate from Minecraft texture decoding.
// LabPBR's packed channels will eventually populate this record, while every closure below keeps
// a stable physical contract. Alpha is the final GGX slope parameter, never perceptual roughness.
struct PrimeLabPbrMaterial {
    vec3 baseColor;
    float dielectricF0;
    vec3 conductorF0;
    vec3 conductorEta;
    vec3 conductorK;
    vec3 transmissionColor;
    vec3 subsurfaceColor;
    vec3 emissionRadiance;
    float ggxAlpha;
    float metalness;
    float transmissionWeight;
    float subsurfaceWeight;
    float subsurfaceAnisotropy;
    float relativeIor;
    uint useComplexConductorIor;
    uint thinWalled;
};

// The calibrated directional-energy table is exact at the previous default alpha. A fitted
// reflective-energy delta below extends it over the small inferred range while preserving the
// table's total resolved energy and its exact value at alpha=0.64.
const float PRIME_DEFAULT_GGX_ALPHA = PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS
        * PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS;
const uint PRIME_DEFAULT_LOBE_DIFFUSE = 0u;
const uint PRIME_DEFAULT_LOBE_SPECULAR = 1u;

// Single-scattering directional energies (reflection, transmission) for the calibrated
// alpha=0.64 and eta=1.5 reference interface. These are the exact 32 cosine samples selected from
// RoboCute's Apache-2.0-licensed GGX energy table. The sum is intentionally less than one: it
// measures energy lost to unresolved multiple microfacet scattering. Never replace this with
// smooth-surface Fresnel -- doing so incorrectly drives the substrate to black at grazing angles.
// The bounded vanilla-texture heuristic applies only the fitted reflection delta above; a future
// material decoder must replace this specialization with the complete parameterized lookup.
const vec2 PRIME_DEFAULT_GGX_DIRECTIONAL_ENERGY[32] = vec2[](
    vec2(0.105050949, 0.141424098),
    vec2(0.101618740, 0.177912561),
    vec2(0.092052501, 0.255356359),
    vec2(0.082015169, 0.326194899),
    vec2(0.075255580, 0.385283190),
    vec2(0.069562661, 0.435654352),
    vec2(0.065026574, 0.481349955),
    vec2(0.060138182, 0.516639700),
    vec2(0.055822648, 0.546962264),
    vec2(0.052415524, 0.571843207),
    vec2(0.048774748, 0.594311533),
    vec2(0.046079235, 0.613289194),
    vec2(0.043347252, 0.628604169),
    vec2(0.041271370, 0.642250667),
    vec2(0.038961432, 0.654930494),
    vec2(0.036981937, 0.664647269),
    vec2(0.035516171, 0.674041851),
    vec2(0.033806834, 0.681722658),
    vec2(0.032365771, 0.688765066),
    vec2(0.031207238, 0.694330635),
    vec2(0.029979644, 0.699656529),
    vec2(0.028958354, 0.703829794),
    vec2(0.027843981, 0.707299607),
    vec2(0.027112505, 0.710047934),
    vec2(0.026286324, 0.712231314),
    vec2(0.025693271, 0.713452229),
    vec2(0.025029263, 0.713788850),
    vec2(0.024538412, 0.713297400),
    vec2(0.024024045, 0.711565628),
    vec2(0.023714662, 0.708079364),
    vec2(0.023220258, 0.701350782),
    vec2(0.022881333, 0.677331905)
);

struct PrimeDefaultBsdfComponents {
    BsdfEvaluation diffuse;
    BsdfEvaluation specular;
};

// View-dependent quantities shared by evaluation, proposal selection and throughput recovery.
// Keeping this as an explicit value object lets the integrator build it once per default-material
// vertex without changing any closure or importance-sampling formula.
struct PrimeDefaultBsdfContext {
    float ggxAlpha;
    float resolvedEnergy;
    float diffuseEnergyScale;
    float specularProbability;
};

float primeDefaultDielectricIor() {
    return primeIorFromF0(PRIME_DEFAULT_DIELECTRIC_F0);
}

float primeDefaultGgxAlpha(vec3 baseColor) {
    float linearRoughness = primeDefaultLinearRoughness(baseColor);
    return linearRoughness * linearRoughness;
}

float primeDefaultReflectiveDirectionalEnergyFit(float cosineView, float ggxAlpha) {
    // Rational quadratic fit from RoboCute's reflective GGX directional-albedo model. Applying
    // only its delta relative to alpha=0.64 keeps Prime's calibrated dielectric table authoritative.
    float x = clamp(cosineView, 0.0, 1.0);
    float y = clamp(ggxAlpha, 0.0, 1.0);
    float x2 = x * x;
    float y2 = y * y;
    vec4 fit = vec4(0.1003, 0.9345, 1.0, 1.0)
            + vec4(-0.6303, -2.323, -1.765, 0.2281) * x
            + vec4(9.748, 2.229, 8.263, 15.94) * y
            + vec4(-2.038, -3.748, 11.53, -55.83) * x * y
            + vec4(29.34, 1.424, 28.96, 13.08) * x2
            + vec4(-8.245, -0.7684, -7.507, 41.26) * y2
            + vec4(-26.44, 1.436, -36.11, 54.9) * x2 * y
            + vec4(19.99, 0.2913, 15.86, 300.2) * x * y2
            + vec4(-5.448, 0.6286, 33.37, -285.1) * x2 * y2;
    vec2 coefficients = clamp(fit.xy / fit.zw, 0.0, 1.0);
    return PRIME_DEFAULT_DIELECTRIC_F0 * coefficients.x + coefficients.y;
}

vec2 primeDefaultGgxDirectionalEnergy(float cosineView, float ggxAlpha) {
    float coordinate = clamp(cosineView, 0.0, 1.0) * 31.0;
    int lowerIndex = int(floor(coordinate));
    int upperIndex = min(lowerIndex + 1, 31);
    vec2 calibrated = mix(
            PRIME_DEFAULT_GGX_DIRECTIONAL_ENERGY[lowerIndex],
            PRIME_DEFAULT_GGX_DIRECTIONAL_ENERGY[upperIndex],
            coordinate - float(lowerIndex));
    float reflectionDelta = primeDefaultReflectiveDirectionalEnergyFit(
            cosineView, ggxAlpha)
            - primeDefaultReflectiveDirectionalEnergyFit(
                    cosineView, PRIME_DEFAULT_GGX_ALPHA);
    float totalResolvedEnergy = calibrated.x + calibrated.y;
    float reflectedEnergy = clamp(
            calibrated.x + reflectionDelta, 0.0, totalResolvedEnergy);
    return vec2(reflectedEnergy, totalResolvedEnergy - reflectedEnergy);
}

PrimeDefaultBsdfContext primeMakeDefaultBsdfContext(
        vec3 baseColor,
        vec3 viewDirection,
        vec3 normal) {
    PrimeDefaultBsdfContext context;
    context.ggxAlpha = primeDefaultGgxAlpha(baseColor);
    vec2 directionalEnergy = primeDefaultGgxDirectionalEnergy(
            max(dot(normal, viewDirection), 0.0), context.ggxAlpha);
    context.resolvedEnergy = max(
            directionalEnergy.x + directionalEnergy.y, PRIME_BSDF_EPSILON);
    context.diffuseEnergyScale = directionalEnergy.y / context.resolvedEnergy;
    context.specularProbability = clamp(
            directionalEnergy.x / context.resolvedEnergy, 0.05, 0.95);
    return context;
}

float primeDefaultSpecularSampleProbability(
        vec3 baseColor,
        vec3 viewDirection,
        vec3 normal) {
    return primeMakeDefaultBsdfContext(baseColor, viewDirection, normal).specularProbability;
}

float primeNrdSpecularSampleProbability(
        vec3 baseColor,
        vec3 viewDirection,
        vec3 normal) {
    // The first-bounce split writes only the selected lobe. AREA_3X3 reconstruction therefore
    // requires a diffuse sample to remain common in every small neighborhood. The default
    // dielectric is diffuse-dominant; limiting its specular proposal to 25% keeps both estimators
    // unbiased through probability compensation while satisfying that reconstruction contract.
    return clamp(
            primeDefaultSpecularSampleProbability(baseColor, viewDirection, normal),
            0.05,
            0.25);
}

float primeNrdSpecularSampleProbability(PrimeDefaultBsdfContext context) {
    return clamp(context.specularProbability, 0.05, 0.25);
}

PrimeDefaultBsdfComponents primeEvaluateDefaultBsdfComponentsWithContext(
        vec3 baseColor,
        vec3 normal,
        vec3 viewDirection,
        vec3 scatterDirection,
        PrimeDefaultBsdfContext context) {
    PrimeDefaultBsdfComponents components;
    components.diffuse = primeInvalidBsdfEvaluation();
    components.specular = primeInvalidBsdfEvaluation();
    if (dot(normal, viewDirection) <= 0.0 || dot(normal, scatterDirection) <= 0.0) {
        return components;
    }
    components.diffuse = primeEvaluateDiffuseReflection(
            baseColor, normal, viewDirection, scatterDirection);
    // Match the source weighted-layer model: transmission into the substrate is a directional
    // rough-interface quantity. Its outgoing tint is unity, so a second smooth Fresnel factor is
    // neither part of the model nor energy preserving.
    components.diffuse.value *= context.diffuseEnergyScale;
    components.specular = primeEvaluateGgxDielectricReflection(
            primeDefaultDielectricIor(),
            context.ggxAlpha,
            normal,
            viewDirection,
            scatterDirection);
    // Turquin-style multiple-scattering compensation restores the energy missing from the
    // single-scattering GGX interface. This divisor and the substrate transmission ratio above
    // are a coupled energy partition and must change together.
    components.specular.value /= context.resolvedEnergy;
    return components;
}

PrimeDefaultBsdfComponents primeEvaluateDefaultBsdfComponents(
        vec3 baseColor,
        vec3 normal,
        vec3 viewDirection,
        vec3 scatterDirection) {
    PrimeDefaultBsdfContext context = primeMakeDefaultBsdfContext(
            baseColor, viewDirection, normal);
    return primeEvaluateDefaultBsdfComponentsWithContext(
            baseColor, normal, viewDirection, scatterDirection, context);
}

// Default Minecraft material: a rough dielectric boundary over a diffuse substrate. The two
// smooth-interface Fresnel transmission factors prevent diffuse energy from being counted on top
// of reflected specular energy. This is intentionally the only newly connected material subset;
// conductor, dielectric transmission, thin SSS, volume SSS and EDF closures above remain ready for
// a later LabPBR decoder without affecting today's atlas contract.
BsdfEvaluation primeEvaluateDefaultBsdfWithContext(
        vec3 baseColor,
        vec3 normal,
        vec3 viewDirection,
        vec3 scatterDirection,
        PrimeDefaultBsdfContext context) {
    PrimeDefaultBsdfComponents components = primeEvaluateDefaultBsdfComponentsWithContext(
            baseColor, normal, viewDirection, scatterDirection, context);
    BsdfEvaluation result;
    result.value = components.diffuse.value + components.specular.value;
    result.pdf = mix(
            components.diffuse.pdf, components.specular.pdf, context.specularProbability);
    return result;
}

BsdfEvaluation primeEvaluateDefaultBsdf(
        vec3 baseColor,
        vec3 normal,
        vec3 viewDirection,
        vec3 scatterDirection) {
    PrimeDefaultBsdfContext context = primeMakeDefaultBsdfContext(
            baseColor, viewDirection, normal);
    return primeEvaluateDefaultBsdfWithContext(
            baseColor, normal, viewDirection, scatterDirection, context);
}

BsdfSample primeSampleDefaultBsdfSeparatedWithContext(
        vec3 baseColor,
        vec3 normal,
        vec3 viewDirection,
        vec3 sampleValue,
        out uint selectedLobe,
        PrimeDefaultBsdfContext context) {
    float specularProbability = primeNrdSpecularSampleProbability(context);
    BsdfSample proposal;
    float selectionProbability;
    if (sampleValue.z < specularProbability) {
        selectedLobe = PRIME_DEFAULT_LOBE_SPECULAR;
        selectionProbability = specularProbability;
        proposal = primeSampleGgxDielectricReflection(
                primeDefaultDielectricIor(),
                context.ggxAlpha,
                normal,
                viewDirection,
                sampleValue.xy);
    } else {
        selectedLobe = PRIME_DEFAULT_LOBE_DIFFUSE;
        selectionProbability = 1.0 - specularProbability;
        proposal = primeSampleDiffuseReflection(
                baseColor, normal, viewDirection, sampleValue.xy);
    }
    if (proposal.pdf <= 0.0 || selectionProbability <= 0.0) {
        return primeInvalidBsdfSample();
    }
    PrimeDefaultBsdfComponents components = primeEvaluateDefaultBsdfComponentsWithContext(
            baseColor, normal, viewDirection, proposal.direction, context);
    BsdfEvaluation selected = selectedLobe == PRIME_DEFAULT_LOBE_DIFFUSE
            ? components.diffuse
            : components.specular;
    proposal.pdf = selected.pdf * selectionProbability;
    if (proposal.pdf <= 0.0) {
        return primeInvalidBsdfSample();
    }
    proposal.weight = selected.value
            * (max(dot(normal, proposal.direction), 0.0) / proposal.pdf);
    proposal.relativeEta = 1.0;
    proposal.eventFlags &= ~PRIME_BSDF_EVENT_DELTA;
    return proposal;
}

BsdfSample primeSampleDefaultBsdfSeparated(
        vec3 baseColor,
        vec3 normal,
        vec3 viewDirection,
        vec3 sampleValue,
        out uint selectedLobe) {
    PrimeDefaultBsdfContext context = primeMakeDefaultBsdfContext(
            baseColor, viewDirection, normal);
    return primeSampleDefaultBsdfSeparatedWithContext(
            baseColor, normal, viewDirection, sampleValue, selectedLobe, context);
}

BsdfSample primeSampleDefaultBsdfWithContext(
        vec3 baseColor,
        vec3 normal,
        vec3 viewDirection,
        vec3 sampleValue,
        PrimeDefaultBsdfContext context) {
    float specularProbability = context.specularProbability;
    BsdfSample proposal;
    if (sampleValue.z < specularProbability) {
        proposal = primeSampleGgxDielectricReflection(
                primeDefaultDielectricIor(),
                context.ggxAlpha,
                normal,
                viewDirection,
                sampleValue.xy);
    } else {
        proposal = primeSampleDiffuseReflection(
                baseColor, normal, viewDirection, sampleValue.xy);
    }
    if (proposal.pdf <= 0.0) {
        return primeInvalidBsdfSample();
    }
    BsdfEvaluation combined = primeEvaluateDefaultBsdfWithContext(
            baseColor, normal, viewDirection, proposal.direction, context);
    if (combined.pdf <= 0.0) {
        return primeInvalidBsdfSample();
    }
    proposal.pdf = combined.pdf;
    proposal.weight = combined.value
            * (max(dot(normal, proposal.direction), 0.0) / combined.pdf);
    // Both connected lobes are non-delta reflection. Preserve that combined event classification
    // even when the selected proposal came from the diffuse component.
    proposal.relativeEta = 1.0;
    proposal.eventFlags &= ~PRIME_BSDF_EVENT_DELTA;
    return proposal;
}

BsdfSample primeSampleDefaultBsdf(
        vec3 baseColor,
        vec3 normal,
        vec3 viewDirection,
        vec3 sampleValue) {
    PrimeDefaultBsdfContext context = primeMakeDefaultBsdfContext(
            baseColor, viewDirection, normal);
    return primeSampleDefaultBsdfWithContext(
            baseColor, normal, viewDirection, sampleValue, context);
}

// Minecraft's translucent render layer is adapted to RoboCute's complete dielectric
// transmission closure. The imported closure owns Fresnel, rough reflection/refraction,
// importance sampling and medium transitions; this adapter only supplies the vanilla fallback
// material parameters and converts its f*|cos| convention to Prime's public BSDF contract.
struct PrimeTransmissiveBsdfSample {
    BsdfSample bsdfSample;
    PrimeRcVolumeStack volumeStack;
};

const float PRIME_GLASS_MINIMUM_TINT_WEIGHT = 0.75;
const float PRIME_WATER_REFERENCE_DEPTH = 16.0;

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
        uint materialFlags) {
    bool water = (materialFlags & PRIME_MATERIAL_FLAG_WATER) != 0u;
    bool thinWalled = (materialFlags & PRIME_MATERIAL_FLAG_THIN_WALLED) != 0u;
    // Vanilla translucent materials have no authored rough interface and are therefore exact
    // smooth dielectrics. This is a material parameter, not a shortcut inside RoboCute: Fresnel,
    // eta^2 radiance transport, absorption and the volume stack remain the library's full model.
    // A future decoder may supply non-zero roughness; raygen must then use one unsplit BSDF path.
    float roughness = primeMaterialLinearRoughness(baseColor, materialFlags);
    PrimeRcMaterial material = primeRcMaterialFromMetallic(
            vec3(1.0), roughness, 0.0, outwardNormal);
    material.weight.transmission = 1.0;
    material.geometry.thinWalled = thinWalled ? 1u : 0u;
    material.geometry.thickness = thinWalled ? 0.0625 : 1.0;
    material.specular.ior = water ? 1.333 : 1.5;

    vec3 decodedColor = max(baseColor, vec3(0.0));
    float coverage = clamp(opacity, 0.0, 1.0);
    vec3 transmissionColor;
    if (water) {
        // Water keeps the authored spectral ratio. Its lower density is expressed by the physical
        // reference distance below, rather than washing the color toward white at every surface.
        transmissionColor = mix(vec3(1.0), decodedColor, coverage);
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
    // One block is the authored depth for solid glass-like models. Water uses a longer artistic
    // reference depth so vanilla biome tint remains visible without becoming opaque after a few
    // cells. True zero-volume surfaces use the closure's explicit zero-depth tint path.
    material.transmission.depth = thinWalled
            ? 0.0
            : (water ? PRIME_WATER_REFERENCE_DEPTH : 1.0);
    material.transmission.scatter = vec3(0.0);
    material.transmission.scatterAnisotropy = 0.0;
    material.transmission.dispersionScale = 0.0;
    return material;
}

PrimeRcVolumeStack primeCameraWaterVolumeStack() {
    PrimeRcVolumeStack result = primeEmptyVolumeStack();
    // This is the vanilla default water tint (#3f76e4), decoded from sRGB and transformed to
    // Prime's linear Rec.2020 working space. It is only the fallback for a ray whose camera starts
    // below the water surface; ordinary air/water boundaries still use the captured biome tint.
    const vec3 defaultWaterRec2020 = vec3(0.124443665, 0.178837556, 0.711582356);
    PrimeRcMaterial material = primeMinecraftTransmissionMaterial(
            defaultWaterRec2020,
            1.0,
            vec3(0.0, 1.0, 0.0),
            PRIME_MATERIAL_FLAG_TRANSMISSIVE | PRIME_MATERIAL_FLAG_WATER);
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

PrimeRcState primeMinecraftTransmissionState(
        vec3 baseColor,
        float opacity,
        vec3 outwardNormal,
        uint materialFlags,
        vec3 viewDirection,
        float rayT,
        PrimeRcVolumeStack volumeStack) {
    bool thinWalled = (materialFlags & PRIME_MATERIAL_FLAG_THIN_WALLED) != 0u;
    vec3 closureNormal = thinWalled && dot(outwardNormal, viewDirection) < 0.0
            ? -outwardNormal
            : outwardNormal;
    PrimeRcMaterial material = primeMinecraftTransmissionMaterial(
            baseColor, opacity, closureNormal, materialFlags);
    vec3 localView = primeRcOnbToLocal(material.geometry.onb, viewDirection);
    float inverseOutsideIor = primeRcInverseOutsideIor(localView.z, volumeStack);
    return primeRcTransmissionStateInit(
            material,
            inverseOutsideIor,
            rayT,
            vec3(630.0, 532.0, 465.0),
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
        vec3 viewDirection,
        vec3 scatterDirection,
        float rayT,
        PrimeRcVolumeStack volumeStack) {
    PrimeRcState state = primeMinecraftTransmissionState(
            baseColor,
            opacity,
            outwardNormal,
            materialFlags,
            viewDirection,
            rayT,
            volumeStack);
    vec3 localView = primeRcOnbToLocal(state.material.geometry.onb, viewDirection);
    vec3 localScatter = primeRcOnbToLocal(state.material.geometry.onb, scatterDirection);
    if (state.geometryThinWalled == 0u && localView.z * localScatter.z >= 0.0) {
        // Closed-volume reflection is a delta lobe and has no finite solid-angle evaluation.
        return primeInvalidBsdfEvaluation();
    }
    PrimeMinecraftMirrorSplit mirror = primeMinecraftMirrorSplit(localView, state);
    if (state.geometryThinWalled == 0u) {
        state.samplingFlags = PRIME_RC_FLAG_TRANSMISSION;
    }
    PrimeRcEval evaluation = primeRcTransmissionEvaluate(localView, localScatter, state);
    BsdfEvaluation result = primeInvalidBsdfEvaluation();
    float cosine = abs(localScatter.z);
    if (evaluation.pdf > 0.0 && cosine > PRIME_BSDF_EPSILON
            && evaluation.throughput.flags != PRIME_RC_FLAG_NONE) {
        result.value = evaluation.throughput.value / cosine;
        result.pdf = evaluation.pdf * (state.geometryThinWalled == 0u
                ? 1.0 - mirror.probability
                : 1.0);
    }
    return result;
}

PrimeTransmissiveBsdfSample primeSampleMinecraftTransmission(
        vec3 baseColor,
        float opacity,
        vec3 outwardNormal,
        uint materialFlags,
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
            viewDirection,
            rayT,
            volumeStack);
    vec3 localView = primeRcOnbToLocal(state.material.geometry.onb, viewDirection);
    PrimeMinecraftMirrorSplit mirror = primeMinecraftMirrorSplit(localView, state);
    if (state.geometryThinWalled == 0u && sampleValue.z < mirror.probability) {
        result.bsdfSample.direction = reflect(-viewDirection, outwardNormal);
        result.bsdfSample.weight = mirror.reflectance
                / max(mirror.probability, PRIME_BSDF_EPSILON);
        result.bsdfSample.pdf = mirror.probability;
        result.bsdfSample.relativeEta = 1.0;
        result.bsdfSample.eventFlags = PRIME_BSDF_EVENT_REFLECTION
                | PRIME_BSDF_EVENT_DELTA;
        return result;
    }
    float transmissionProbability = state.geometryThinWalled == 0u
            ? 1.0 - mirror.probability
            : 1.0;
    if (transmissionProbability <= PRIME_BSDF_EPSILON) {
        return result;
    }
    vec3 transmissionSample = sampleValue;
    if (state.geometryThinWalled == 0u) {
        state.samplingFlags = PRIME_RC_FLAG_TRANSMISSION;
        transmissionSample.z = clamp(
                (sampleValue.z - mirror.probability) / transmissionProbability,
                0.0,
                0.99999994);
    }
    PrimeRcSampleResult sampled = primeRcTransmissionSample(
            localView, transmissionSample, state, volumeStack);
    if (sampled.bsdfSample.pdf <= 0.0
            || sampled.bsdfSample.throughput.flags == PRIME_RC_FLAG_NONE) {
        return result;
    }
    result.bsdfSample.direction = primeRcOnbToWorld(
            state.material.geometry.onb, sampled.bsdfSample.wo);
    result.bsdfSample.weight = sampled.bsdfSample.throughput.value
            / (sampled.bsdfSample.pdf * transmissionProbability);
    result.bsdfSample.pdf = sampled.bsdfSample.pdf * transmissionProbability;
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

    if (state.geometryThinWalled == 0u && reflectionBranch) {
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

PrimeTransmissiveBsdfSample primeSampleMinecraftTransmissionCheckerBranch(
        vec3 baseColor,
        float opacity,
        vec3 outwardNormal,
        uint materialFlags,
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
            viewDirection,
            rayT,
            volumeStack);
    vec3 localView = primeRcOnbToLocal(state.material.geometry.onb, viewDirection);
    PrimeMinecraftMirrorSplit mirror = primeMinecraftMirrorSplit(localView, state);
    PrimeTransmissiveBsdfSample result =
            primeSampleMinecraftTransmissionBranchFromState(
                    state,
                    mirror,
                    outwardNormal,
                    viewDirection,
                    sampleValue,
                    reflectionBranch,
                    volumeStack);
    // The checker field chooses each exact physical lobe with probability 1/2 independently of
    // Fresnel. Fresnel remains entirely inside the conditional branch, while this fixed proposal
    // compensation keeps the raw estimator unbiased and caps amplification at 2x.
    result.bsdfSample.weight *= 2.0;
    result.bsdfSample.pdf *= 0.5;
    return result;
}

// Vanilla grass blades and leaf texels are zero-thickness surfaces rather than dielectric
// volumes. Keep most of the ordinary rough terrain response and mix a deliberately small amount
// of colored thin-wall transmission through OpenPBR's energy-aware lobe composition. Unlike
// glass and water, this closure never pushes or pops the path volume stack.
const float PRIME_FOLIAGE_TRANSMISSION_WEIGHT = 0.15;

PrimeRcMaterial primeMinecraftFoliageMaterial(vec3 baseColor, vec3 normal) {
    PrimeRcMaterial material = primeRcMaterialFromMetallic(
            baseColor,
            primeDefaultLinearRoughness(baseColor),
            0.0,
            normal);
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
        vec3 viewDirection,
        vec3 randomValue,
        float rayT,
        PrimeRcVolumeStack volumeStack) {
    vec3 closureNormal = dot(outwardNormal, viewDirection) < 0.0
            ? -outwardNormal
            : outwardNormal;
    PrimeRcMaterial material = primeMinecraftFoliageMaterial(baseColor, closureNormal);
    vec3 localView = primeRcOnbToLocal(material.geometry.onb, viewDirection);
    float inverseOutsideIor = primeRcInverseOutsideIor(localView.z, volumeStack);
    return primeRcOpenPbrStateInit(
            material,
            localView,
            randomValue,
            inverseOutsideIor,
            rayT,
            vec3(630.0, 532.0, 465.0),
            0u,
            PRIME_RC_DETAIL_DEFAULT,
            0u);
}

BsdfEvaluation primeEvaluateMinecraftFoliage(
        vec3 baseColor,
        vec3 outwardNormal,
        vec3 viewDirection,
        vec3 scatterDirection,
        float rayT,
        PrimeRcVolumeStack volumeStack) {
    PrimeRcState state = primeMinecraftFoliageState(
            baseColor,
            outwardNormal,
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
        vec3 viewDirection,
        vec3 sampleValue,
        float rayT,
        PrimeRcVolumeStack volumeStack) {
    BsdfSample result = primeInvalidBsdfSample();
    PrimeRcState state = primeMinecraftFoliageState(
            baseColor,
            outwardNormal,
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
