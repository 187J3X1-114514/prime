#ifndef PRIME_BSDF_GLSL
#define PRIME_BSDF_GLSL

#include "bsdf_common.glsl"
#include "bsdf_fresnel.glsl"
#include "bsdf_diffuse.glsl"
#include "bsdf_microfacet.glsl"
#include "bsdf_subsurface.glsl"
#include "bsdf_emission.glsl"

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

const float PRIME_DEFAULT_DIELECTRIC_F0 = 0.04;
// Vanilla textures do not carry LabPBR smoothness. A broad alpha=0.64 highlight adds the missing
// dielectric response without making unclassified terrain look polished or metallic.
const float PRIME_DEFAULT_GGX_ALPHA = 0.64;
const uint PRIME_DEFAULT_LOBE_DIFFUSE = 0u;
const uint PRIME_DEFAULT_LOBE_SPECULAR = 1u;

struct PrimeDefaultBsdfComponents {
    BsdfEvaluation diffuse;
    BsdfEvaluation specular;
};

float primeDefaultDielectricIor() {
    return primeIorFromF0(PRIME_DEFAULT_DIELECTRIC_F0);
}

float primeDefaultSpecularSampleProbability(vec3 viewDirection, vec3 normal) {
    float viewFresnel = primeFresnelDielectric(
            max(dot(normal, viewDirection), 0.0), 1.0, primeDefaultDielectricIor());
    return clamp(viewFresnel, 0.05, 0.95);
}

float primeNrdSpecularSampleProbability(vec3 viewDirection, vec3 normal) {
    // The first-bounce split writes only the selected lobe. AREA_3X3 reconstruction therefore
    // requires a diffuse sample to remain common in every small neighborhood. The default
    // dielectric is diffuse-dominant; limiting its specular proposal to 25% keeps both estimators
    // unbiased through probability compensation while satisfying that reconstruction contract.
    return clamp(primeDefaultSpecularSampleProbability(viewDirection, normal), 0.05, 0.25);
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
    float fresnelIn = primeFresnelDielectric(
            dot(normal, viewDirection), 1.0, primeDefaultDielectricIor());
    float fresnelOut = primeFresnelDielectric(
            dot(normal, scatterDirection), 1.0, primeDefaultDielectricIor());
    components.diffuse.value *= (1.0 - fresnelIn) * (1.0 - fresnelOut);
    components.specular = primeEvaluateGgxDielectricReflection(
            primeDefaultDielectricIor(),
            PRIME_DEFAULT_GGX_ALPHA,
            normal,
            viewDirection,
            scatterDirection);
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
    float specularProbability = primeDefaultSpecularSampleProbability(viewDirection, normal);
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
    float specularProbability = primeNrdSpecularSampleProbability(viewDirection, normal);
    BsdfSample proposal;
    float selectionProbability;
    if (sampleValue.z < specularProbability) {
        selectedLobe = PRIME_DEFAULT_LOBE_SPECULAR;
        selectionProbability = specularProbability;
        proposal = primeSampleGgxDielectricReflection(
                primeDefaultDielectricIor(),
                PRIME_DEFAULT_GGX_ALPHA,
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
    float specularProbability = primeDefaultSpecularSampleProbability(viewDirection, normal);
    BsdfSample proposal;
    if (sampleValue.z < specularProbability) {
        proposal = primeSampleGgxDielectricReflection(
                primeDefaultDielectricIor(),
                PRIME_DEFAULT_GGX_ALPHA,
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
