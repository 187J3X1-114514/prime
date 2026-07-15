#ifndef PRIME_BSDF_GLSL
#define PRIME_BSDF_GLSL

#include "bsdf_common.glsl"
#include "bsdf_fresnel.glsl"
#include "bsdf_diffuse.glsl"
#include "bsdf_microfacet.glsl"
#include "bsdf_subsurface.glsl"
#include "bsdf_emission.glsl"
#include "default_material.glsl"

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

float primeDefaultSpecularSampleProbability(
        vec3 baseColor,
        vec3 viewDirection,
        vec3 normal) {
    vec2 directionalEnergy = primeDefaultGgxDirectionalEnergy(
            max(dot(normal, viewDirection), 0.0),
            primeDefaultGgxAlpha(baseColor));
    return clamp(
            directionalEnergy.x / max(directionalEnergy.x + directionalEnergy.y, PRIME_BSDF_EPSILON),
            0.05,
            0.95);
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

PrimeDefaultBsdfComponents primeEvaluateDefaultBsdfComponents(
        vec3 baseColor,
        vec3 normal,
        vec3 viewDirection,
        vec3 scatterDirection) {
    PrimeDefaultBsdfComponents components;
    components.diffuse = primeInvalidBsdfEvaluation();
    components.specular = primeInvalidBsdfEvaluation();
    if (dot(normal, viewDirection) <= 0.0 || dot(normal, scatterDirection) <= 0.0) {
        return components;
    }
    components.diffuse = primeEvaluateDiffuseReflection(
            baseColor, normal, viewDirection, scatterDirection);
    float ggxAlpha = primeDefaultGgxAlpha(baseColor);
    vec2 directionalEnergy = primeDefaultGgxDirectionalEnergy(
            dot(normal, viewDirection), ggxAlpha);
    float resolvedEnergy = max(
            directionalEnergy.x + directionalEnergy.y, PRIME_BSDF_EPSILON);
    // Match the source weighted-layer model: transmission into the substrate is a directional
    // rough-interface quantity. Its outgoing tint is unity, so a second smooth Fresnel factor is
    // neither part of the model nor energy preserving.
    components.diffuse.value *= directionalEnergy.y / resolvedEnergy;
    components.specular = primeEvaluateGgxDielectricReflection(
            primeDefaultDielectricIor(),
            ggxAlpha,
            normal,
            viewDirection,
            scatterDirection);
    // Turquin-style multiple-scattering compensation restores the energy missing from the
    // single-scattering GGX interface. This divisor and the substrate transmission ratio above
    // are a coupled energy partition and must change together.
    components.specular.value /= resolvedEnergy;
    return components;
}

// Default Minecraft material: a rough dielectric boundary over a diffuse substrate. The two
// smooth-interface Fresnel transmission factors prevent diffuse energy from being counted on top
// of reflected specular energy. This is intentionally the only newly connected material subset;
// conductor, dielectric transmission, thin SSS, volume SSS and EDF closures above remain ready for
// a later LabPBR decoder without affecting today's atlas contract.
BsdfEvaluation primeEvaluateDefaultBsdf(
        vec3 baseColor,
        vec3 normal,
        vec3 viewDirection,
        vec3 scatterDirection) {
    PrimeDefaultBsdfComponents components = primeEvaluateDefaultBsdfComponents(
            baseColor, normal, viewDirection, scatterDirection);
    float specularProbability = primeDefaultSpecularSampleProbability(
            baseColor, viewDirection, normal);
    BsdfEvaluation result;
    result.value = components.diffuse.value + components.specular.value;
    result.pdf = mix(components.diffuse.pdf, components.specular.pdf, specularProbability);
    return result;
}

BsdfSample primeSampleDefaultBsdfSeparated(
        vec3 baseColor,
        vec3 normal,
        vec3 viewDirection,
        vec3 sampleValue,
        out uint selectedLobe) {
    float specularProbability = primeNrdSpecularSampleProbability(
            baseColor, viewDirection, normal);
    float ggxAlpha = primeDefaultGgxAlpha(baseColor);
    BsdfSample proposal;
    float selectionProbability;
    if (sampleValue.z < specularProbability) {
        selectedLobe = PRIME_DEFAULT_LOBE_SPECULAR;
        selectionProbability = specularProbability;
        proposal = primeSampleGgxDielectricReflection(
                primeDefaultDielectricIor(),
                ggxAlpha,
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
    PrimeDefaultBsdfComponents components = primeEvaluateDefaultBsdfComponents(
            baseColor, normal, viewDirection, proposal.direction);
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

BsdfSample primeSampleDefaultBsdf(
        vec3 baseColor,
        vec3 normal,
        vec3 viewDirection,
        vec3 sampleValue) {
    float specularProbability = primeDefaultSpecularSampleProbability(
            baseColor, viewDirection, normal);
    float ggxAlpha = primeDefaultGgxAlpha(baseColor);
    BsdfSample proposal;
    if (sampleValue.z < specularProbability) {
        proposal = primeSampleGgxDielectricReflection(
                primeDefaultDielectricIor(),
                ggxAlpha,
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
    BsdfEvaluation combined = primeEvaluateDefaultBsdf(
            baseColor, normal, viewDirection, proposal.direction);
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

#endif
