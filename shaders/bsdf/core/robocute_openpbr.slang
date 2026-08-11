#ifndef PRIME_ROBOCUTE_OPENPBR_SLANG
#define PRIME_ROBOCUTE_OPENPBR_SLANG

#include "robocute_common.slang"
#include "robocute_closures.slang"

// Mechanical Slang port of the production-reachable declaration closure from
// robocute_bsdf_openpbr.glsl at RoboCute commit 0d982c77b3fd26c2c5a3c0852be3bd05e5860bd8.

PrimeRcMixState primeRcMakeMixState(float weight, float3 firstEnergy, float3 secondEnergy) {
    PrimeRcMixState result;
    if (weight == 0.0) {
        result.firstSampleWeight = 1.0;
        result.secondSampleWeight = 0.0;
        return result;
    }
    if (weight == 1.0) {
        result.firstSampleWeight = 0.0;
        result.secondSampleWeight = 1.0;
        return result;
    }
    result.firstSampleWeight = (1.0 - weight) * primeRcSpectrumToWeight(firstEnergy);
    result.secondSampleWeight = weight * primeRcSpectrumToWeight(secondEnergy);
    float sum = result.firstSampleWeight + result.secondSampleWeight;
    if (sum > 0.0) {
        result.firstSampleWeight /= sum;
        result.secondSampleWeight /= sum;
    }
    return result;
}

PrimeRcLayerState primeRcMakeLayerState(
        float weight, float3 subEnergy, float3 coatTransIn, float3 coatEnergy) {
    PrimeRcLayerState result;
    if (weight == 0.0) {
        result.coatTransIn = float3(0.0);
        result.subSampleWeight = 1.0;
        result.coatSampleWeight = 0.0;
        return result;
    }
    result.coatTransIn = coatTransIn;
    result.subSampleWeight = primeRcSpectrumToWeight(
            lerp(float3(1.0), coatTransIn, float3(weight)) * subEnergy);
    result.coatSampleWeight = weight * primeRcSpectrumToWeight(coatEnergy);
    float sum = result.subSampleWeight + result.coatSampleWeight;
    if (sum > 0.0) {
        result.subSampleWeight /= sum;
        result.coatSampleWeight /= sum;
    }
    return result;
}

PrimeRcThroughput primeRcMixEvalValues(
        PrimeRcThroughput first, PrimeRcThroughput second, float weight) {
    PrimeRcThroughput result = primeRcZeroThroughput();
    if (weight < 1.0) { result = first; }
    if (weight > 0.0) {
        result = primeRcThroughputScale(result, 1.0 - weight);
        result = primeRcThroughputAdd(result, primeRcThroughputScale(second, weight));
    }
    return result;
}

float3 primeRcMixValues(float3 first, float3 second, float weight) {
    float3 result = float3(0.0);
    if (weight < 1.0) { result = first; }
    if (weight > 0.0) { result = result * (1.0 - weight) + second * weight; }
    return result;
}

PrimeRcThroughput primeRcLayerEvalValues(
        PrimeRcThroughput sub,
        PrimeRcThroughput coat,
        float3 coatTintOut,
        PrimeRcLayerState layer,
        float weight) {
    PrimeRcThroughput result = sub;
    if (weight > 0.0) {
        result = primeRcThroughputScale(
                result,
                lerp(float3(1.0), layer.coatTransIn * coatTintOut, float3(weight)));
        result = primeRcThroughputAdd(result, primeRcThroughputScale(coat, weight));
    }
    return result;
}

float3 primeRcLayerEnergyValues(
        float3 subEnergy, float3 coatEnergy, PrimeRcLayerState layer, float weight) {
    if (weight == 0.0) { return subEnergy; }
    return coatEnergy * weight
            + subEnergy * lerp(float3(1.0), layer.coatTransIn, float3(weight));
}

PrimeRcThroughput primeRcMixedDiffuseEval(float3 wi, float3 wo, PrimeRcState state) {
    float weight = state.material.weight.subsurface;
    return primeRcMixEvalValues(
            weight < 1.0 ? primeRcDiffuseEval(wi, wo, state) : primeRcZeroThroughput(),
            weight > 0.0 ? primeRcSubsurfaceEval(wi, wo, state) : primeRcZeroThroughput(),
            weight);
}

PrimeRcSampleResult primeRcMixedDiffuseSample(
        float3 wi, float3 randomValue, PrimeRcState state, PrimeRcVolumeStack stack) {
    PrimeRcMixState mixState = state.mixedDiffuse;
    if (mixState.firstSampleWeight + mixState.secondSampleWeight <= 0.0) {
        return primeRcZeroSampleResult(state, stack);
    }
    float weight = state.material.weight.subsurface;
    if (randomValue.z <= mixState.secondSampleWeight && mixState.secondSampleWeight > 0.0) {
        PrimeRcSampleResult result = primeRcSubsurfaceSample(
                wi,
                float3(randomValue.xy, randomValue.z / mixState.secondSampleWeight),
                state,
                stack);
        result.bsdfSample.throughput = primeRcThroughputScale(result.bsdfSample.throughput, weight);
        result.bsdfSample.pdf *= mixState.secondSampleWeight;
        return result;
    }
    PrimeRcSampleResult result = primeRcDiffuseSample(
            wi,
            float3(randomValue.xy,
                    (randomValue.z - mixState.secondSampleWeight)
                    / mixState.firstSampleWeight),
            state,
            stack);
    result.bsdfSample.throughput = primeRcThroughputScale(
            result.bsdfSample.throughput, 1.0 - weight);
    result.bsdfSample.pdf *= mixState.firstSampleWeight;
    return result;
}

float primeRcMixedDiffusePdf(float3 wi, float3 wo, PrimeRcState state) {
    float weight = state.material.weight.subsurface;
    float first = weight < 1.0 ? primeRcDiffusePdf(wi, wo, state) : 0.0;
    float second = weight > 0.0 ? primeRcSubsurfacePdf(wi, wo, state) : 0.0;
    if (state.mixedDiffuse.firstSampleWeight + state.mixedDiffuse.secondSampleWeight <= 0.0) {
        return 0.0;
    }
    return lerp(first, second, state.mixedDiffuse.secondSampleWeight);
}

float3 primeRcMixedDiffuseEnergy(float3 wi, PrimeRcState state) {
    return primeRcMixValues(
            primeRcDiffuseEnergy(wi, state),
            primeRcSubsurfaceEnergy(wi, state),
            state.material.weight.subsurface);
}

PrimeRcSampleResult primeRcGlossyDiffuseSample(
        float3 wi, float3 randomValue, PrimeRcState state, PrimeRcVolumeStack stack) {
    PrimeRcLayerState layer = state.glossyDiffuse;
    if (layer.subSampleWeight + layer.coatSampleWeight <= 0.0) {
        return primeRcZeroSampleResult(state, stack);
    }
    float weight = state.material.weight.specular > 0.0 ? 1.0 : 0.0;
    if (randomValue.z <= layer.coatSampleWeight && layer.coatSampleWeight > 0.0) {
        PrimeRcSampleResult result = primeRcSpecularSample(
                wi, float3(randomValue.xy, randomValue.z / layer.coatSampleWeight),
                state, stack);
        result.bsdfSample.throughput = primeRcThroughputScale(result.bsdfSample.throughput, weight);
        result.bsdfSample.pdf *= layer.coatSampleWeight;
        return result;
    }
    PrimeRcSampleResult result = primeRcMixedDiffuseSample(
            wi,
            float3(randomValue.xy,
                    (randomValue.z - layer.coatSampleWeight) / layer.subSampleWeight),
            state,
            stack);
    result.bsdfSample.throughput = primeRcThroughputScale(
            result.bsdfSample.throughput,
            lerp(float3(1.0),
                    layer.coatTransIn * primeRcSpecularTintOut(result.bsdfSample.wo, state),
                    float3(weight)));
    result.bsdfSample.pdf *= layer.subSampleWeight;
    return result;
}

float primeRcGlossyDiffusePdf(float3 wi, float3 wo, PrimeRcState state) {
    float subPdf = primeRcMixedDiffusePdf(wi, wo, state);
    if (state.material.weight.specular <= 0.0) { return subPdf; }
    PrimeRcLayerState layer = state.glossyDiffuse;
    if (layer.subSampleWeight + layer.coatSampleWeight <= 0.0) { return 0.0; }
    return lerp(subPdf, primeRcSpecularPdf(wi, wo, state), layer.coatSampleWeight);
}

float3 primeRcGlossyDiffuseEnergy(float3 wi, PrimeRcState state) {
    float weight = state.material.weight.specular > 0.0 ? 1.0 : 0.0;
    float3 sub = primeRcMixedDiffuseEnergy(wi, state);
    return primeRcLayerEnergyValues(
            sub, primeRcSpecularEnergy(wi, state), state.glossyDiffuse, weight);
}

PrimeRcSampleResult primeRcDielectricBaseSample(
        float3 wi, float3 randomValue, PrimeRcState state, PrimeRcVolumeStack stack) {
    PrimeRcMixState mixState = state.dielectricBase;
    if (mixState.firstSampleWeight + mixState.secondSampleWeight <= 0.0) {
        return primeRcZeroSampleResult(state, stack);
    }
    float weight = state.material.weight.transmission;
    if (randomValue.z <= mixState.secondSampleWeight && mixState.secondSampleWeight > 0.0) {
        PrimeRcSampleResult result = primeRcTransmissionSample(
                wi, float3(randomValue.xy, randomValue.z / mixState.secondSampleWeight),
                state, stack);
        result.bsdfSample.throughput = primeRcThroughputScale(result.bsdfSample.throughput, weight);
        result.bsdfSample.pdf *= mixState.secondSampleWeight;
        return result;
    }
    PrimeRcSampleResult result = primeRcGlossyDiffuseSample(
            wi,
            float3(randomValue.xy,
                    (randomValue.z - mixState.secondSampleWeight)
                    / mixState.firstSampleWeight),
            state,
            stack);
    result.bsdfSample.throughput = primeRcThroughputScale(
            result.bsdfSample.throughput, 1.0 - weight);
    result.bsdfSample.pdf *= mixState.firstSampleWeight;
    return result;
}

float primeRcDielectricBasePdf(float3 wi, float3 wo, PrimeRcState state) {
    float weight = state.material.weight.transmission;
    float first = weight < 1.0 ? primeRcGlossyDiffusePdf(wi, wo, state) : 0.0;
    float second = weight > 0.0 ? primeRcTransmissionPdf(wi, wo, state) : 0.0;
    if (state.dielectricBase.firstSampleWeight + state.dielectricBase.secondSampleWeight <= 0.0) {
        return 0.0;
    }
    return lerp(first, second, state.dielectricBase.secondSampleWeight);
}

float3 primeRcDielectricBaseEnergy(float3 wi, PrimeRcState state) {
    return primeRcMixValues(
            primeRcGlossyDiffuseEnergy(wi, state),
            primeRcTransmissionEnergy(wi, state),
            state.material.weight.transmission);
}

PrimeRcSampleResult primeRcBaseSubstrateSample(
        float3 wi, float3 randomValue, PrimeRcState state, PrimeRcVolumeStack stack) {
    PrimeRcMixState mixState = state.baseSubstrate;
    if (mixState.firstSampleWeight + mixState.secondSampleWeight <= 0.0) {
        return primeRcZeroSampleResult(state, stack);
    }
    float weight = state.material.weight.metalness;
    if (randomValue.z <= mixState.secondSampleWeight && mixState.secondSampleWeight > 0.0) {
        PrimeRcSampleResult result = primeRcConductorSample(
                wi, float3(randomValue.xy, randomValue.z / mixState.secondSampleWeight),
                state, stack);
        result.bsdfSample.throughput = primeRcThroughputScale(result.bsdfSample.throughput, weight);
        result.bsdfSample.pdf *= mixState.secondSampleWeight;
        return result;
    }
    PrimeRcSampleResult result = primeRcDielectricBaseSample(
            wi,
            float3(randomValue.xy,
                    (randomValue.z - mixState.secondSampleWeight)
                    / mixState.firstSampleWeight),
            state,
            stack);
    result.bsdfSample.throughput = primeRcThroughputScale(
            result.bsdfSample.throughput, 1.0 - weight);
    result.bsdfSample.pdf *= mixState.firstSampleWeight;
    return result;
}

float primeRcBaseSubstratePdf(float3 wi, float3 wo, PrimeRcState state) {
    float weight = state.material.weight.metalness;
    float first = weight < 1.0 ? primeRcDielectricBasePdf(wi, wo, state) : 0.0;
    float second = weight > 0.0 ? primeRcConductorPdf(wi, wo, state) : 0.0;
    if (state.baseSubstrate.firstSampleWeight + state.baseSubstrate.secondSampleWeight <= 0.0) {
        return 0.0;
    }
    return lerp(first, second, state.baseSubstrate.secondSampleWeight);
}

float3 primeRcBaseSubstrateEnergy(float3 wi, PrimeRcState state) {
    return primeRcMixValues(
            primeRcDielectricBaseEnergy(wi, state),
            primeRcConductorEnergy(wi, state),
            state.material.weight.metalness);
}

PrimeRcMaterial primeRcMaterialFromMetallic(
        float3 baseColor, float roughness, float metalness, float3 normal) {
    PrimeRcOnb onb = primeRcOnbFromNormal(normal);
    PrimeRcMaterial material;
    material.weight.base = 1.0;
    material.weight.diffuseRoughness = 0.0;
    material.weight.specular = 1.0;
    material.weight.metalness = metalness;
    material.weight.subsurface = 0.0;
    material.weight.transmission = 0.0;
    material.weight.coat = 0.0;
    material.weight.fuzz = 0.0;
    material.weight.thinFilm = 0.0;
    material.weight.diffraction = 0.0;
    material.geometry.thinWalled = 0u;
    material.geometry.thickness = 0.005;
    material.geometry.onb = onb;
    material.specular.color = float3(1.0);
    material.specular.roughness = roughness;
    material.specular.roughnessAnisotropy = 0.0;
    material.specular.ior = 1.5;
    material.emission.luminance = float3(0.0);
    material.base.color = baseColor;
    material.subsurface.color = float3(0.8);
    material.subsurface.radius = float3(0.05, 0.025, 0.0125);
    material.subsurface.scatterAnisotropy = 0.0;
    material.transmission.color = float3(1.0);
    material.transmission.depth = 0.0;
    material.transmission.scatter = float3(0.0);
    material.transmission.scatterAnisotropy = 0.0;
    material.transmission.dispersionScale = 0.0;
    material.transmission.dispersionAbbeNumber = 20.0;
    material.coat.color = float3(1.0);
    material.coat.roughness = 0.0;
    material.coat.roughnessAnisotropy = 0.0;
    material.coat.ior = 1.6;
    material.coat.darkening = 1.0;
    material.coat.roughening = 1.0;
    material.coat.onb = onb;
    material.fuzz.color = float3(1.0);
    material.fuzz.roughness = 0.5;
    material.thinFilm.thickness = 0.5;
    material.thinFilm.ior = 1.4;
    material.diffraction.color = float3(1.0);
    material.diffraction.thickness = 0.5;
    material.diffraction.invPitch = float2(1.0 / 3.0, 0.0);
    material.diffraction.angle = 0.0;
    material.diffraction.lobeCount = 5u;
    material.diffraction.kind = PRIME_RC_DIFFRACTION_RECTANGULAR;
    return material;
}

float primeRcInverseOutsideIor(float wiZ, PrimeRcVolumeStack volumeStack) {
    if (wiZ > 0.0) {
        if (volumeStack.count > 0u) {
            return 1.0 / volumeStack.values[volumeStack.count - 1u].ior;
        }
    } else if (volumeStack.count > 1u) {
        return 1.0 / volumeStack.values[volumeStack.count - 2u].ior;
    }
    return 1.0;
}

PrimeRcState primeRcBaseState(
        PrimeRcMaterial inputMaterial,
        float3 wi,
        float inverseOutsideIor,
        float rayT,
        float3 wavelengthsNm,
        uint heroWavelengthIndex,
        uint detail,
        uint spectrumed,
        bool fullOpenPbr) {
    PrimeRcMaterial material = inputMaterial;
    if (detail != PRIME_RC_DETAIL_DEFAULT) {
        if (detail == PRIME_RC_DETAIL_INDIRECT_DIFFUSE) {
            material.specular.roughness = max(material.specular.roughness, 0.25);
        }
        material.specular.roughnessAnisotropy *= 0.8;
        material.specular.roughness = lerp(
                material.specular.roughness,
                1.0,
                clamp(material.specular.roughness * 1.5, 0.0, 1.0) * 0.5);
    }
    if (material.geometry.thinWalled != 0u) {
        material.transmission.depth /= max(
                PRIME_RC_DENOM_TOLERANCE, material.geometry.thickness);
    }
    PrimeRcState state;
    state.material = material;
    state.detail = detail;
    state.spectrumed = spectrumed;
    state.geometryThinWalled = material.geometry.thinWalled;
    state.entering = wi.z >= 0.0 ? 1u : 0u;
    state.selectedWavelength = 0u;
    state.wavelengthsNm = wavelengthsNm;
    state.rayT = rayT;
    state.invOutIor = inverseOutsideIor;
    state.originalIor = material.specular.ior;
    state.heroWavelengthIndex = min(heroWavelengthIndex, 2u);
    state.samplingFlags = PRIME_RC_FLAG_ALL;
    state.randomValue = float3(0.0);

    float specularIor = material.specular.ior * inverseOutsideIor;
    float thinFilmIor = material.thinFilm.ior * inverseOutsideIor;
    if (fullOpenPbr) {
        specularIor = primeRcIorAdjustment(
                specularIor,
                material.coat.ior * inverseOutsideIor,
                material.weight.coat);
        thinFilmIor = primeRcIorAdjustment(
                thinFilmIor,
                material.coat.ior * inverseOutsideIor,
                material.weight.coat);
    }
    specularIor = primeRcIorAdjustment(specularIor, material.weight.specular);
    thinFilmIor = primeRcIorAdjustment(thinFilmIor, material.weight.specular);
    state.specularFresnel.ior = material.specular.ior * inverseOutsideIor;
    state.specularFresnel.energyIor = specularIor;
    state.specularFresnel.color = material.specular.color;
    state.specularFresnel.thinFilmWeight = fullOpenPbr ? material.weight.thinFilm : 0.0;
    state.specularFresnel.thinFilmThickness = material.thinFilm.thickness;
    state.specularFresnel.thinFilmIor = thinFilmIor;

    state.conductorFresnel.f0 = material.weight.base > 0.0
            ? material.base.color * material.weight.base : float3(0.0);
    state.conductorFresnel.f82 = material.specular.color;
    state.conductorFresnel.f90 = float3(1.0);
    state.conductorFresnel.weight = material.weight.specular;
    state.conductorFresnel.energyF0 = material.weight.base > 0.0
            ? clamp(material.base.color * material.weight.base
                    * material.weight.specular, float3(0.0), float3(1.0))
            : float3(0.0);
    state.conductorFresnel.thinFilmWeight = fullOpenPbr
            ? material.weight.thinFilm : 0.0;
    state.conductorFresnel.thinFilmThickness = material.thinFilm.thickness;
    state.conductorFresnel.thinFilmIor = material.thinFilm.ior * inverseOutsideIor;

    float specularRoughness = material.specular.roughness;
    if (fullOpenPbr) {
        specularRoughness = primeRcRoughnessOverlap(
                specularRoughness,
                material.coat.roughness,
                material.weight.coat * material.coat.roughening);
    }
    state.specularMicrofacet.alpha = primeRcSpecularNdfRoughnesses(
            specularRoughness, material.specular.roughnessAnisotropy);
    state.specularMultipleScattering = primeRcMicrofacetDielectricMsCompensation(
            state.specularMicrofacet, wi.z, state.specularFresnel.energyIor);

    if (fullOpenPbr) {
        float3 coatNormal = primeRcOnbToLocal(
                material.geometry.onb, material.coat.onb.normal);
        float3 coatTangent = primeRcOnbToLocal(
                material.geometry.onb, material.coat.onb.tangent);
        state.coatLocalOnb.tangent = coatTangent;
        state.coatLocalOnb.bitangent = cross(coatNormal, coatTangent);
        state.coatLocalOnb.normal = coatNormal;
    } else {
        state.coatLocalOnb.tangent = float3(1.0, 0.0, 0.0);
        state.coatLocalOnb.bitangent = float3(0.0, 1.0, 0.0);
        state.coatLocalOnb.normal = float3(0.0, 0.0, 1.0);
    }
    state.conductorEss = 1.0;
    state.transmissionMicrofacet.alpha = float2(0.0);
    state.transmissionTint = float3(1.0);
    state.transmissionVolume = primeRcVolumeFromTransmission(material.transmission);
    state.transmissionMultipleScattering = float3(1.0, 1.0, 0.0);
    state.coatMicrofacet.alpha = float2(0.0);
    state.coatFresnelIor = 1.0;
    state.coatTint = float3(1.0);
    state.coatDarkening = 0.0;
    state.coatBaseRoughness = material.specular.roughness;
    state.coatMultipleScattering = float3(1.0, 1.0, 0.0);
    state.diffractionState.directionalAlbedo = 0.0;
    state.diffractionState.h = float3(0.0, 0.0, 1.0);
    state.diffractionState.angleCs = float2(1.0, 0.0);
    state.basicGlossy = primeRcMakeLayerState(0.0, float3(0.0), float3(0.0), float3(0.0));
    state.basicMetal = primeRcMakeMixState(0.0, float3(0.0), float3(0.0));
    state.subsurfaceGlossy = primeRcMakeLayerState(0.0, float3(0.0), float3(0.0), float3(0.0));
    state.mixedDiffuse = primeRcMakeMixState(0.0, float3(0.0), float3(0.0));
    state.glossyDiffuse = primeRcMakeLayerState(0.0, float3(0.0), float3(0.0), float3(0.0));
    state.dielectricBase = primeRcMakeMixState(0.0, float3(0.0), float3(0.0));
    state.baseSubstrate = primeRcMakeMixState(0.0, float3(0.0), float3(0.0));
    state.diffractionBase = primeRcMakeLayerState(0.0, float3(0.0), float3(0.0), float3(0.0));
    state.coatedBase = primeRcMakeLayerState(0.0, float3(0.0), float3(0.0), float3(0.0));
    state.surface = primeRcMakeLayerState(0.0, float3(0.0), float3(0.0), float3(0.0));
    return state;
}

PrimeRcState primeRcInitializeConductor(float3 wi, PrimeRcState state) {
    state.conductorEss = 1.0;
    if (!primeRcMicrofacetEffectivelySmooth(state.specularMicrofacet)) {
        state.conductorEss = primeRcMicrofacetDirectionalAlbedoReflection(
                state.specularMicrofacet, wi.z, float3(1.0), float3(1.0)).x;
    }
    return state;
}

PrimeRcState primeRcInitializeTransmission(float3 wi, PrimeRcState state) {
    state.transmissionTint = state.material.transmission.depth == 0.0
            ? state.material.transmission.color : float3(1.0);
    state.transmissionVolume = primeRcVolumeFromTransmission(state.material.transmission);
    if (state.material.transmission.dispersionScale > 0.0) {
        state.selectedWavelength = 1u;
        state.specularFresnel.ior = state.invOutIor * primeRcDispersionIor(
                state.originalIor,
                state.material.transmission.dispersionAbbeNumber,
                state.material.transmission.dispersionScale,
                state.wavelengthsNm[state.heroWavelengthIndex]);
    }
    if (state.geometryThinWalled != 0u) {
        state.transmissionMicrofacet.alpha = clamp(
                state.specularMicrofacet.alpha
                * primeRcThinDielectricRoughnessScaler2(state.specularFresnel.ior),
                float2(0.0), float2(1.0));
    } else {
        wi = primeRcTransmissionMaterialFrame(wi, state);
        state.transmissionMultipleScattering = primeRcMicrofacetDielectricMsCompensation(
                state.specularMicrofacet, wi.z, state.specularFresnel.ior);
    }
    return state;
}

PrimeRcSampleResult primeRcBasicGlossySample(
        float3 wi, float3 randomValue, PrimeRcState state, PrimeRcVolumeStack stack) {
    PrimeRcLayerState layer = state.basicGlossy;
    if (layer.subSampleWeight + layer.coatSampleWeight <= 0.0) {
        return primeRcZeroSampleResult(state, stack);
    }
    float weight = state.material.weight.specular > 0.0 ? 1.0 : 0.0;
    if (randomValue.z <= layer.coatSampleWeight && layer.coatSampleWeight > 0.0) {
        PrimeRcSampleResult result = primeRcSpecularSample(
                wi, float3(randomValue.xy, randomValue.z / layer.coatSampleWeight),
                state, stack);
        result.bsdfSample.throughput = primeRcThroughputScale(result.bsdfSample.throughput, weight);
        result.bsdfSample.pdf *= layer.coatSampleWeight;
        return result;
    }
    PrimeRcSampleResult result = primeRcLambertSample(
            wi,
            float3(randomValue.xy,
                    (randomValue.z - layer.coatSampleWeight) / layer.subSampleWeight),
            state,
            stack);
    result.bsdfSample.throughput = primeRcThroughputScale(
            result.bsdfSample.throughput,
            lerp(float3(1.0),
                    layer.coatTransIn * primeRcSpecularTintOut(result.bsdfSample.wo, state),
                    float3(weight)));
    result.bsdfSample.pdf *= layer.subSampleWeight;
    return result;
}

float primeRcBasicGlossyPdf(float3 wi, float3 wo, PrimeRcState state) {
    float subPdf = primeRcLambertPdf(wi, wo, state);
    if (state.material.weight.specular <= 0.0) { return subPdf; }
    if (state.basicGlossy.subSampleWeight + state.basicGlossy.coatSampleWeight <= 0.0) {
        return 0.0;
    }
    return lerp(subPdf, primeRcSpecularPdf(wi, wo, state),
            state.basicGlossy.coatSampleWeight);
}

float3 primeRcBasicGlossyEnergy(float3 wi, PrimeRcState state) {
    float weight = state.material.weight.specular > 0.0 ? 1.0 : 0.0;
    return primeRcLayerEnergyValues(
            primeRcLambertEnergy(wi, state),
            primeRcSpecularEnergy(wi, state),
            state.basicGlossy,
            weight);
}

PrimeRcSampleResult primeRcBasicMetallicSample(
        float3 wi, float3 randomValue, PrimeRcState state, PrimeRcVolumeStack stack) {
    if (state.basicMetal.firstSampleWeight + state.basicMetal.secondSampleWeight <= 0.0) {
        return primeRcZeroSampleResult(state, stack);
    }
    float weight = state.material.weight.metalness;
    if (randomValue.z <= state.basicMetal.secondSampleWeight && state.basicMetal.secondSampleWeight > 0.0) {
        PrimeRcSampleResult result = primeRcConductorSample(
                wi, float3(randomValue.xy,
                        randomValue.z / state.basicMetal.secondSampleWeight),
                state, stack);
        result.bsdfSample.throughput = primeRcThroughputScale(result.bsdfSample.throughput, weight);
        result.bsdfSample.pdf *= state.basicMetal.secondSampleWeight;
        return result;
    }
    PrimeRcSampleResult result = primeRcBasicGlossySample(
            wi,
            float3(randomValue.xy,
                    (randomValue.z - state.basicMetal.secondSampleWeight)
                    / state.basicMetal.firstSampleWeight),
            state,
            stack);
    result.bsdfSample.throughput = primeRcThroughputScale(
            result.bsdfSample.throughput, 1.0 - weight);
    result.bsdfSample.pdf *= state.basicMetal.firstSampleWeight;
    return result;
}

float primeRcBasicMetallicPdf(float3 wi, float3 wo, PrimeRcState state) {
    float weight = state.material.weight.metalness;
    float first = weight < 1.0 ? primeRcBasicGlossyPdf(wi, wo, state) : 0.0;
    float second = weight > 0.0 ? primeRcConductorPdf(wi, wo, state) : 0.0;
    if (state.basicMetal.firstSampleWeight + state.basicMetal.secondSampleWeight <= 0.0) {
        return 0.0;
    }
    return lerp(first, second, state.basicMetal.secondSampleWeight);
}

float3 primeRcBasicMetallicEnergy(float3 wi, PrimeRcState state) {
    float weight = state.material.weight.metalness;
    return primeRcMixValues(
            weight < 1.0 ? primeRcBasicGlossyEnergy(wi, state) : float3(0.0),
            weight > 0.0 ? primeRcConductorEnergy(wi, state) : float3(0.0),
            weight);
}

PrimeRcState primeRcBasicMetallicStateInit(
        PrimeRcMaterial material,
        float3 wi,
        float inverseOutsideIor,
        float rayT,
        float3 wavelengthsNm,
        uint heroWavelengthIndex,
        uint detail,
        uint spectrumed) {
    PrimeRcState state = primeRcBaseState(
            material, wi, inverseOutsideIor, rayT, wavelengthsNm,
            heroWavelengthIndex, detail, spectrumed, false);
    if (state.material.weight.metalness < 1.0) {
        float3 subEnergy = primeRcLambertEnergy(wi, state);
        state.basicGlossy = state.material.weight.specular > 0.0
                ? primeRcMakeLayerState(
                        1.0,
                        subEnergy,
                        primeRcSpecularTrans(wi, state, subEnergy),
                        primeRcSpecularEnergy(wi, state))
                : primeRcMakeLayerState(
                        0.0, float3(0.0), float3(0.0), float3(0.0));
    }
    if (state.material.weight.metalness > 0.0) {
        state = primeRcInitializeConductor(wi, state);
    }
    state.basicMetal = primeRcMakeMixState(
            state.material.weight.metalness,
            state.material.weight.metalness < 1.0
                    ? primeRcBasicGlossyEnergy(wi, state) : float3(0.0),
            state.material.weight.metalness > 0.0
                    ? primeRcConductorEnergy(wi, state) : float3(0.0));
    return state;
}

PrimeRcSampleResult primeRcSubsurfaceGlossySample(
        float3 wi, float3 randomValue, PrimeRcState state, PrimeRcVolumeStack stack) {
    PrimeRcLayerState layer = state.subsurfaceGlossy;
    if (layer.subSampleWeight + layer.coatSampleWeight <= 0.0) {
        return primeRcZeroSampleResult(state, stack);
    }
    float weight = state.material.weight.specular > 0.0 ? 1.0 : 0.0;
    if (randomValue.z <= layer.coatSampleWeight && layer.coatSampleWeight > 0.0) {
        PrimeRcSampleResult result = primeRcSpecularSample(
                wi, float3(randomValue.xy, randomValue.z / layer.coatSampleWeight),
                state, stack);
        result.bsdfSample.throughput = primeRcThroughputScale(
                result.bsdfSample.throughput, weight);
        result.bsdfSample.pdf *= layer.coatSampleWeight;
        return result;
    }
    PrimeRcSampleResult result = primeRcSubsurfaceSample(
            wi,
            float3(randomValue.xy,
                    (randomValue.z - layer.coatSampleWeight) / layer.subSampleWeight),
            state,
            stack);
    result.bsdfSample.throughput = primeRcThroughputScale(
            result.bsdfSample.throughput,
            lerp(float3(1.0),
                    layer.coatTransIn * primeRcSpecularTintOut(result.bsdfSample.wo, state),
                    float3(weight)));
    result.bsdfSample.pdf *= layer.subSampleWeight;
    return result;
}

float primeRcSubsurfaceGlossyPdf(float3 wi, float3 wo, PrimeRcState state) {
    float subPdf = primeRcSubsurfacePdf(wi, wo, state);
    if (state.material.weight.specular <= 0.0) { return subPdf; }
    if (state.subsurfaceGlossy.subSampleWeight
            + state.subsurfaceGlossy.coatSampleWeight <= 0.0) {
        return 0.0;
    }
    return lerp(subPdf, primeRcSpecularPdf(wi, wo, state),
            state.subsurfaceGlossy.coatSampleWeight);
}

float3 primeRcSubsurfaceGlossyEnergy(float3 wi, PrimeRcState state) {
    float weight = state.material.weight.specular > 0.0 ? 1.0 : 0.0;
    return primeRcLayerEnergyValues(
            primeRcSubsurfaceEnergy(wi, state),
            primeRcSpecularEnergy(wi, state),
            state.subsurfaceGlossy,
            weight);
}

PrimeRcState primeRcSubsurfaceGlossyStateInit(
        PrimeRcMaterial material,
        float3 wi,
        float inverseOutsideIor,
        float rayT,
        float3 wavelengthsNm,
        uint heroWavelengthIndex,
        uint detail,
        uint spectrumed) {
    PrimeRcState state = primeRcBaseState(
            material, wi, inverseOutsideIor, rayT, wavelengthsNm,
            heroWavelengthIndex, detail, spectrumed, false);
    float3 subEnergy = primeRcSubsurfaceEnergy(wi, state);
    state.subsurfaceGlossy = state.material.weight.specular > 0.0
            ? primeRcMakeLayerState(
                    1.0,
                    subEnergy,
                    primeRcSpecularTrans(wi, state, subEnergy),
                    primeRcSpecularEnergy(wi, state))
            : primeRcMakeLayerState(
                    0.0, float3(0.0), float3(0.0), float3(0.0));
    return state;
}

PrimeRcState primeRcTransmissionStateInit(
        PrimeRcMaterial material,
        float3 wi,
        float inverseOutsideIor,
        float rayT,
        float3 wavelengthsNm,
        uint heroWavelengthIndex,
        uint detail,
        uint spectrumed) {
    PrimeRcState state = primeRcBaseState(
            material, wi, inverseOutsideIor, rayT, wavelengthsNm,
            heroWavelengthIndex, detail, spectrumed, false);
    return primeRcInitializeTransmission(wi, state);
}

#endif
