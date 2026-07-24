#ifndef PRIME_BSDF_SPECIALIZATIONS_GLSL
#define PRIME_BSDF_SPECIALIZATIONS_GLSL

#include "robocute_bsdf_openpbr.glsl"

// Prime adapter: the reachable OpenPBR subset for thin Minecraft surfaces stops at BaseSubstrate.
// The imported RoboCute implementation remains untouched; these wrappers only select its existing
// composition nodes when Prime has authored no coat, fuzz, diffraction, or thin-film layers.
PrimeRcThroughput primeRcPrimeThinWallEval(vec3 wi, vec3 wo, PrimeRcState state) {
    return primeRcBaseSubstrateEval(wi, wo, state);
}

PrimeRcSampleResult primeRcPrimeThinWallSample(
        vec3 wi, vec3 randomValue, PrimeRcState state, PrimeRcVolumeStack stack) {
    return primeRcBaseSubstrateSample(wi, randomValue, state, stack);
}

float primeRcPrimeThinWallPdf(vec3 wi, vec3 wo, PrimeRcState state) {
    return primeRcBaseSubstratePdf(wi, wo, state);
}

PrimeRcEval primeRcPrimeThinWallEvaluate(vec3 wi, vec3 wo, PrimeRcState state) {
    PrimeRcEval result;
    result.throughput = primeRcPrimeThinWallEval(wi, wo, state);
    result.pdf = primeRcPrimeThinWallPdf(wi, wo, state);
    return result;
}

PrimeRcState primeRcPrimeThinWallStateInit(
        PrimeRcMaterial material,
        vec3 wi,
        float inverseOutsideIor,
        float rayT,
        vec3 wavelengthsNm,
        uint heroWavelengthIndex,
        uint detail,
        uint spectrumed) {
    PrimeRcState state = primeRcBaseState(
            material, wi, inverseOutsideIor, rayT, wavelengthsNm,
            heroWavelengthIndex, detail, spectrumed, false);

    vec3 diffuseEnergy = state.material.weight.subsurface < 1.0
            ? primeRcDiffuseEnergy(wi, state) : vec3(0.0);
    vec3 subsurfaceEnergy = state.material.weight.subsurface > 0.0
            ? primeRcSubsurfaceEnergy(wi, state) : vec3(0.0);
    state.mixedDiffuse = primeRcMakeMixState(
            state.material.weight.subsurface, diffuseEnergy, subsurfaceEnergy);

    vec3 mixedEnergy = primeRcMixedDiffuseEnergy(wi, state);
    state.glossyDiffuse = state.material.weight.specular > 0.0
            ? primeRcMakeLayerState(
                    1.0,
                    mixedEnergy,
                    primeRcSpecularTrans(wi, state, mixedEnergy),
                    primeRcSpecularEnergy(wi, state))
            : primeRcMakeLayerState(
                    0.0, vec3(0.0), vec3(0.0), vec3(0.0));

    if (state.material.weight.transmission > 0.0) {
        state = primeRcInitializeTransmission(wi, state);
    }
    state.dielectricBase = primeRcMakeMixState(
            state.material.weight.transmission,
            state.material.weight.transmission < 1.0
                    ? primeRcGlossyDiffuseEnergy(wi, state) : vec3(0.0),
            state.material.weight.transmission > 0.0
                    ? primeRcTransmissionEnergy(wi, state) : vec3(0.0));

    if (state.material.weight.metalness > 0.0) {
        state = primeRcInitializeConductor(wi, state);
    }
    state.baseSubstrate = primeRcMakeMixState(
            state.material.weight.metalness,
            state.material.weight.metalness < 1.0
                    ? primeRcDielectricBaseEnergy(wi, state) : vec3(0.0),
            state.material.weight.metalness > 0.0
                    ? primeRcConductorEnergy(wi, state) : vec3(0.0));
    return state;
}

#endif
