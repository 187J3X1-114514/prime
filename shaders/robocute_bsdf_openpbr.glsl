#ifndef PRIME_ROBOCUTE_BSDF_OPENPBR_GLSL
#define PRIME_ROBOCUTE_BSDF_OPENPBR_GLSL

// The imported library remains complete by default so its standalone validation shader keeps
// compiling every OpenPBR layer. Prime's runtime adapter defines this as zero because its current
// material translation only reaches the basic-metallic, subsurface-glossy, transmission and
// thin-wall substrate specializations below.
#ifndef PRIME_RC_ENABLE_FULL_OPENPBR
#define PRIME_RC_ENABLE_FULL_OPENPBR 1
#endif

#include "robocute_bsdf_common.glsl"
#include "robocute_bsdf_closures.glsl"

PrimeRcMixState primeRcMakeMixState(float weight, vec3 firstEnergy, vec3 secondEnergy) {
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
        float weight, vec3 subEnergy, vec3 coatTransIn, vec3 coatEnergy) {
    PrimeRcLayerState result;
    if (weight == 0.0) {
        result.coatTransIn = vec3(0.0);
        result.subSampleWeight = 1.0;
        result.coatSampleWeight = 0.0;
        return result;
    }
    result.coatTransIn = coatTransIn;
    result.subSampleWeight = primeRcSpectrumToWeight(
            mix(vec3(1.0), coatTransIn, vec3(weight)) * subEnergy);
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

vec3 primeRcMixValues(vec3 first, vec3 second, float weight) {
    vec3 result = vec3(0.0);
    if (weight < 1.0) { result = first; }
    if (weight > 0.0) { result = result * (1.0 - weight) + second * weight; }
    return result;
}

PrimeRcThroughput primeRcLayerEvalValues(
        PrimeRcThroughput sub,
        PrimeRcThroughput coat,
        vec3 coatTintOut,
        PrimeRcLayerState layer,
        float weight) {
    PrimeRcThroughput result = sub;
    if (weight > 0.0) {
        result = primeRcThroughputScale(
                result,
                mix(vec3(1.0), layer.coatTransIn * coatTintOut, vec3(weight)));
        result = primeRcThroughputAdd(result, primeRcThroughputScale(coat, weight));
    }
    return result;
}

vec3 primeRcLayerEnergyValues(
        vec3 subEnergy, vec3 coatEnergy, PrimeRcLayerState layer, float weight) {
    if (weight == 0.0) { return subEnergy; }
    return coatEnergy * weight
            + subEnergy * mix(vec3(1.0), layer.coatTransIn, vec3(weight));
}

PrimeRcThroughput primeRcMixedDiffuseEval(vec3 wi, vec3 wo, PrimeRcState state) {
    float weight = state.material.weight.subsurface;
    return primeRcMixEvalValues(
            weight < 1.0 ? primeRcDiffuseEval(wi, wo, state) : primeRcZeroThroughput(),
            weight > 0.0 ? primeRcSubsurfaceEval(wi, wo, state) : primeRcZeroThroughput(),
            weight);
}

PrimeRcSampleResult primeRcMixedDiffuseSample(
        vec3 wi, vec3 randomValue, PrimeRcState state, PrimeRcVolumeStack stack) {
    PrimeRcMixState mixState = state.mixedDiffuse;
    if (mixState.firstSampleWeight + mixState.secondSampleWeight <= 0.0) {
        return primeRcZeroSampleResult(state, stack);
    }
    float weight = state.material.weight.subsurface;
    if (randomValue.z < mixState.secondSampleWeight) {
        PrimeRcSampleResult result = primeRcSubsurfaceSample(
                wi,
                vec3(randomValue.xy, randomValue.z / mixState.secondSampleWeight),
                state,
                stack);
        result.bsdfSample.throughput = primeRcThroughputScale(result.bsdfSample.throughput, weight);
        result.bsdfSample.pdf *= mixState.secondSampleWeight;
        return result;
    }
    PrimeRcSampleResult result = primeRcDiffuseSample(
            wi,
            vec3(randomValue.xy,
                    (randomValue.z - mixState.secondSampleWeight)
                    / mixState.firstSampleWeight),
            state,
            stack);
    result.bsdfSample.throughput = primeRcThroughputScale(
            result.bsdfSample.throughput, 1.0 - weight);
    result.bsdfSample.pdf *= mixState.firstSampleWeight;
    return result;
}

float primeRcMixedDiffusePdf(vec3 wi, vec3 wo, PrimeRcState state) {
    float weight = state.material.weight.subsurface;
    float first = weight < 1.0 ? primeRcDiffusePdf(wi, wo, state) : 0.0;
    float second = weight > 0.0 ? primeRcSubsurfacePdf(wi, wo, state) : 0.0;
    if (state.mixedDiffuse.firstSampleWeight + state.mixedDiffuse.secondSampleWeight <= 0.0) {
        return 0.0;
    }
    return mix(first, second, state.mixedDiffuse.secondSampleWeight);
}

vec3 primeRcMixedDiffuseTintOut(vec3 wo, PrimeRcState state) {
    return primeRcMixValues(
            primeRcDiffuseTintOut(wo, state),
            primeRcSubsurfaceTintOut(wo, state),
            state.material.weight.subsurface);
}
vec3 primeRcMixedDiffuseTrans(vec3 wi, PrimeRcState state, vec3 baseEnergy) {
    return primeRcMixValues(
            primeRcDiffuseTrans(wi, state, baseEnergy),
            primeRcSubsurfaceTrans(wi, state, baseEnergy),
            state.material.weight.subsurface);
}
vec3 primeRcMixedDiffuseEnergy(vec3 wi, PrimeRcState state) {
    return primeRcMixValues(
            primeRcDiffuseEnergy(wi, state),
            primeRcSubsurfaceEnergy(wi, state),
            state.material.weight.subsurface);
}

PrimeRcThroughput primeRcGlossyDiffuseEval(vec3 wi, vec3 wo, PrimeRcState state) {
    float weight = state.material.weight.specular > 0.0 ? 1.0 : 0.0;
    return primeRcLayerEvalValues(
            primeRcMixedDiffuseEval(wi, wo, state),
            weight > 0.0 ? primeRcSpecularEval(wi, wo, state) : primeRcZeroThroughput(),
            weight > 0.0 ? primeRcSpecularTintOut(wo, state) : vec3(0.0),
            state.glossyDiffuse,
            weight);
}

PrimeRcSampleResult primeRcGlossyDiffuseSample(
        vec3 wi, vec3 randomValue, PrimeRcState state, PrimeRcVolumeStack stack) {
    PrimeRcLayerState layer = state.glossyDiffuse;
    if (layer.subSampleWeight + layer.coatSampleWeight <= 0.0) {
        return primeRcZeroSampleResult(state, stack);
    }
    float weight = state.material.weight.specular > 0.0 ? 1.0 : 0.0;
    if (randomValue.z < layer.coatSampleWeight) {
        PrimeRcSampleResult result = primeRcSpecularSample(
                wi, vec3(randomValue.xy, randomValue.z / layer.coatSampleWeight),
                state, stack);
        result.bsdfSample.throughput = primeRcThroughputScale(result.bsdfSample.throughput, weight);
        result.bsdfSample.pdf *= layer.coatSampleWeight;
        return result;
    }
    PrimeRcSampleResult result = primeRcMixedDiffuseSample(
            wi,
            vec3(randomValue.xy,
                    (randomValue.z - layer.coatSampleWeight) / layer.subSampleWeight),
            state,
            stack);
    result.bsdfSample.throughput = primeRcThroughputScale(
            result.bsdfSample.throughput,
            mix(vec3(1.0),
                    layer.coatTransIn * primeRcSpecularTintOut(result.bsdfSample.wo, state),
                    vec3(weight)));
    result.bsdfSample.pdf *= layer.subSampleWeight;
    return result;
}

float primeRcGlossyDiffusePdf(vec3 wi, vec3 wo, PrimeRcState state) {
    float subPdf = primeRcMixedDiffusePdf(wi, wo, state);
    if (state.material.weight.specular <= 0.0) { return subPdf; }
    PrimeRcLayerState layer = state.glossyDiffuse;
    if (layer.subSampleWeight + layer.coatSampleWeight <= 0.0) { return 0.0; }
    return mix(subPdf, primeRcSpecularPdf(wi, wo, state), layer.coatSampleWeight);
}

vec3 primeRcGlossyDiffuseTintOut(vec3 wo, PrimeRcState state) {
    float weight = state.material.weight.specular > 0.0 ? 1.0 : 0.0;
    vec3 sub = primeRcMixedDiffuseTintOut(wo, state);
    if (weight == 0.0) { return sub; }
    return sub * mix(vec3(1.0), primeRcSpecularTintOut(wo, state), vec3(weight));
}
vec3 primeRcGlossyDiffuseTrans(vec3 wi, PrimeRcState state, vec3 baseEnergy) {
    float weight = state.material.weight.specular > 0.0 ? 1.0 : 0.0;
    vec3 sub = primeRcMixedDiffuseTrans(wi, state, baseEnergy);
    if (weight == 0.0) { return sub; }
    return sub * mix(vec3(1.0), primeRcSpecularTrans(wi, state, baseEnergy), vec3(weight));
}
vec3 primeRcGlossyDiffuseEnergy(vec3 wi, PrimeRcState state) {
    float weight = state.material.weight.specular > 0.0 ? 1.0 : 0.0;
    vec3 sub = primeRcMixedDiffuseEnergy(wi, state);
    return primeRcLayerEnergyValues(
            sub, primeRcSpecularEnergy(wi, state), state.glossyDiffuse, weight);
}

PrimeRcThroughput primeRcDielectricBaseEval(vec3 wi, vec3 wo, PrimeRcState state) {
    float weight = state.material.weight.transmission;
    return primeRcMixEvalValues(
            weight < 1.0 ? primeRcGlossyDiffuseEval(wi, wo, state) : primeRcZeroThroughput(),
            weight > 0.0 ? primeRcTransmissionEval(wi, wo, state) : primeRcZeroThroughput(),
            weight);
}

PrimeRcSampleResult primeRcDielectricBaseSample(
        vec3 wi, vec3 randomValue, PrimeRcState state, PrimeRcVolumeStack stack) {
    PrimeRcMixState mixState = state.dielectricBase;
    if (mixState.firstSampleWeight + mixState.secondSampleWeight <= 0.0) {
        return primeRcZeroSampleResult(state, stack);
    }
    float weight = state.material.weight.transmission;
    if (randomValue.z < mixState.secondSampleWeight) {
        PrimeRcSampleResult result = primeRcTransmissionSample(
                wi, vec3(randomValue.xy, randomValue.z / mixState.secondSampleWeight),
                state, stack);
        result.bsdfSample.throughput = primeRcThroughputScale(result.bsdfSample.throughput, weight);
        result.bsdfSample.pdf *= mixState.secondSampleWeight;
        return result;
    }
    PrimeRcSampleResult result = primeRcGlossyDiffuseSample(
            wi,
            vec3(randomValue.xy,
                    (randomValue.z - mixState.secondSampleWeight)
                    / mixState.firstSampleWeight),
            state,
            stack);
    result.bsdfSample.throughput = primeRcThroughputScale(
            result.bsdfSample.throughput, 1.0 - weight);
    result.bsdfSample.pdf *= mixState.firstSampleWeight;
    return result;
}

float primeRcDielectricBasePdf(vec3 wi, vec3 wo, PrimeRcState state) {
    float weight = state.material.weight.transmission;
    float first = weight < 1.0 ? primeRcGlossyDiffusePdf(wi, wo, state) : 0.0;
    float second = weight > 0.0 ? primeRcTransmissionPdf(wi, wo, state) : 0.0;
    if (state.dielectricBase.firstSampleWeight + state.dielectricBase.secondSampleWeight <= 0.0) {
        return 0.0;
    }
    return mix(first, second, state.dielectricBase.secondSampleWeight);
}

vec3 primeRcDielectricBaseTintOut(vec3 wo, PrimeRcState state) {
    return primeRcMixValues(
            primeRcGlossyDiffuseTintOut(wo, state),
            primeRcTransmissionTintOut(wo, state),
            state.material.weight.transmission);
}
vec3 primeRcDielectricBaseTrans(vec3 wi, PrimeRcState state, vec3 baseEnergy) {
    return primeRcMixValues(
            primeRcGlossyDiffuseTrans(wi, state, baseEnergy),
            primeRcTransmissionTrans(wi, state, baseEnergy),
            state.material.weight.transmission);
}
vec3 primeRcDielectricBaseEnergy(vec3 wi, PrimeRcState state) {
    return primeRcMixValues(
            primeRcGlossyDiffuseEnergy(wi, state),
            primeRcTransmissionEnergy(wi, state),
            state.material.weight.transmission);
}

PrimeRcThroughput primeRcBaseSubstrateEval(vec3 wi, vec3 wo, PrimeRcState state) {
    float weight = state.material.weight.metalness;
    return primeRcMixEvalValues(
            weight < 1.0 ? primeRcDielectricBaseEval(wi, wo, state) : primeRcZeroThroughput(),
            weight > 0.0 ? primeRcConductorEval(wi, wo, state) : primeRcZeroThroughput(),
            weight);
}

PrimeRcSampleResult primeRcBaseSubstrateSample(
        vec3 wi, vec3 randomValue, PrimeRcState state, PrimeRcVolumeStack stack) {
    PrimeRcMixState mixState = state.baseSubstrate;
    if (mixState.firstSampleWeight + mixState.secondSampleWeight <= 0.0) {
        return primeRcZeroSampleResult(state, stack);
    }
    float weight = state.material.weight.metalness;
    if (randomValue.z < mixState.secondSampleWeight) {
        PrimeRcSampleResult result = primeRcConductorSample(
                wi, vec3(randomValue.xy, randomValue.z / mixState.secondSampleWeight),
                state, stack);
        result.bsdfSample.throughput = primeRcThroughputScale(result.bsdfSample.throughput, weight);
        result.bsdfSample.pdf *= mixState.secondSampleWeight;
        return result;
    }
    PrimeRcSampleResult result = primeRcDielectricBaseSample(
            wi,
            vec3(randomValue.xy,
                    (randomValue.z - mixState.secondSampleWeight)
                    / mixState.firstSampleWeight),
            state,
            stack);
    result.bsdfSample.throughput = primeRcThroughputScale(
            result.bsdfSample.throughput, 1.0 - weight);
    result.bsdfSample.pdf *= mixState.firstSampleWeight;
    return result;
}

float primeRcBaseSubstratePdf(vec3 wi, vec3 wo, PrimeRcState state) {
    float weight = state.material.weight.metalness;
    float first = weight < 1.0 ? primeRcDielectricBasePdf(wi, wo, state) : 0.0;
    float second = weight > 0.0 ? primeRcConductorPdf(wi, wo, state) : 0.0;
    if (state.baseSubstrate.firstSampleWeight + state.baseSubstrate.secondSampleWeight <= 0.0) {
        return 0.0;
    }
    return mix(first, second, state.baseSubstrate.secondSampleWeight);
}

vec3 primeRcBaseSubstrateTintOut(vec3 wo, PrimeRcState state) {
    return primeRcMixValues(
            primeRcDielectricBaseTintOut(wo, state),
            primeRcConductorTintOut(wo, state),
            state.material.weight.metalness);
}
vec3 primeRcBaseSubstrateTrans(vec3 wi, PrimeRcState state, vec3 baseEnergy) {
    return primeRcMixValues(
            primeRcDielectricBaseTrans(wi, state, baseEnergy),
            primeRcConductorTrans(wi, state, baseEnergy),
            state.material.weight.metalness);
}
vec3 primeRcBaseSubstrateEnergy(vec3 wi, PrimeRcState state) {
    return primeRcMixValues(
            primeRcDielectricBaseEnergy(wi, state),
            primeRcConductorEnergy(wi, state),
            state.material.weight.metalness);
}

#if PRIME_RC_ENABLE_FULL_OPENPBR
PrimeRcThroughput primeRcDiffractionBaseEval(vec3 wi, vec3 wo, PrimeRcState state) {
    float weight = state.material.weight.diffraction;
    return primeRcLayerEvalValues(
            primeRcBaseSubstrateEval(wi, wo, state),
            weight > 0.0 ? primeRcDiffractionEval(wi, wo, state) : primeRcZeroThroughput(),
            weight > 0.0 ? primeRcDiffractionTintOut(wo, state) : vec3(0.0),
            state.diffractionBase,
            weight);
}

PrimeRcSampleResult primeRcDiffractionBaseSample(
        vec3 wi, vec3 randomValue, PrimeRcState state, PrimeRcVolumeStack stack) {
    PrimeRcLayerState layer = state.diffractionBase;
    if (layer.subSampleWeight + layer.coatSampleWeight <= 0.0) {
        return primeRcZeroSampleResult(state, stack);
    }
    float weight = state.material.weight.diffraction;
    if (randomValue.z < layer.coatSampleWeight) {
        PrimeRcSampleResult result = primeRcDiffractionSample(
                wi, vec3(randomValue.xy, randomValue.z / layer.coatSampleWeight),
                state, stack);
        result.bsdfSample.throughput = primeRcThroughputScale(result.bsdfSample.throughput, weight);
        result.bsdfSample.pdf *= layer.coatSampleWeight;
        return result;
    }
    PrimeRcSampleResult result = primeRcBaseSubstrateSample(
            wi,
            vec3(randomValue.xy,
                    (randomValue.z - layer.coatSampleWeight) / layer.subSampleWeight),
            state,
            stack);
    result.bsdfSample.throughput = primeRcThroughputScale(
            result.bsdfSample.throughput,
            mix(vec3(1.0),
                    layer.coatTransIn * primeRcDiffractionTintOut(result.bsdfSample.wo, state),
                    vec3(weight)));
    result.bsdfSample.pdf *= layer.subSampleWeight;
    return result;
}

float primeRcDiffractionBasePdf(vec3 wi, vec3 wo, PrimeRcState state) {
    float subPdf = primeRcBaseSubstratePdf(wi, wo, state);
    if (state.material.weight.diffraction == 0.0) { return subPdf; }
    PrimeRcLayerState layer = state.diffractionBase;
    if (layer.subSampleWeight + layer.coatSampleWeight <= 0.0) { return 0.0; }
    return mix(subPdf, primeRcDiffractionPdf(wi, wo, state), layer.coatSampleWeight);
}

vec3 primeRcDiffractionBaseTintOut(vec3 wo, PrimeRcState state) {
    float weight = state.material.weight.diffraction;
    vec3 sub = primeRcBaseSubstrateTintOut(wo, state);
    if (weight == 0.0) { return sub; }
    return sub * mix(vec3(1.0), primeRcDiffractionTintOut(wo, state), vec3(weight));
}
vec3 primeRcDiffractionBaseTrans(vec3 wi, PrimeRcState state, vec3 baseEnergy) {
    float weight = state.material.weight.diffraction;
    vec3 sub = primeRcBaseSubstrateTrans(wi, state, baseEnergy);
    if (weight == 0.0) { return sub; }
    return sub * mix(
            vec3(1.0), primeRcDiffractionTrans(wi, state, baseEnergy), vec3(weight));
}
vec3 primeRcDiffractionBaseEnergy(vec3 wi, PrimeRcState state) {
    return primeRcLayerEnergyValues(
            primeRcBaseSubstrateEnergy(wi, state),
            primeRcDiffractionEnergy(wi, state),
            state.diffractionBase,
            state.material.weight.diffraction);
}

PrimeRcThroughput primeRcCoatedBaseEval(vec3 wi, vec3 wo, PrimeRcState state) {
    float weight = state.material.weight.coat;
    return primeRcLayerEvalValues(
            primeRcDiffractionBaseEval(wi, wo, state),
            weight > 0.0 ? primeRcCoatEval(wi, wo, state) : primeRcZeroThroughput(),
            weight > 0.0 ? primeRcCoatTintOut(wo, state) : vec3(0.0),
            state.coatedBase,
            weight);
}

PrimeRcSampleResult primeRcCoatedBaseSample(
        vec3 wi, vec3 randomValue, PrimeRcState state, PrimeRcVolumeStack stack) {
    PrimeRcLayerState layer = state.coatedBase;
    if (layer.subSampleWeight + layer.coatSampleWeight <= 0.0) {
        return primeRcZeroSampleResult(state, stack);
    }
    float weight = state.material.weight.coat;
    if (randomValue.z < layer.coatSampleWeight) {
        PrimeRcSampleResult result = primeRcCoatSample(
                wi, vec3(randomValue.xy, randomValue.z / layer.coatSampleWeight),
                state, stack);
        result.bsdfSample.throughput = primeRcThroughputScale(result.bsdfSample.throughput, weight);
        result.bsdfSample.pdf *= layer.coatSampleWeight;
        return result;
    }
    PrimeRcSampleResult result = primeRcDiffractionBaseSample(
            wi,
            vec3(randomValue.xy,
                    (randomValue.z - layer.coatSampleWeight) / layer.subSampleWeight),
            state,
            stack);
    result.bsdfSample.throughput = primeRcThroughputScale(
            result.bsdfSample.throughput,
            mix(vec3(1.0),
                    layer.coatTransIn * primeRcCoatTintOut(result.bsdfSample.wo, state),
                    vec3(weight)));
    result.bsdfSample.pdf *= layer.subSampleWeight;
    return result;
}

float primeRcCoatedBasePdf(vec3 wi, vec3 wo, PrimeRcState state) {
    float subPdf = primeRcDiffractionBasePdf(wi, wo, state);
    if (state.material.weight.coat == 0.0) { return subPdf; }
    PrimeRcLayerState layer = state.coatedBase;
    if (layer.subSampleWeight + layer.coatSampleWeight <= 0.0) { return 0.0; }
    return mix(subPdf, primeRcCoatPdf(wi, wo, state), layer.coatSampleWeight);
}

vec3 primeRcCoatedBaseTintOut(vec3 wo, PrimeRcState state) {
    float weight = state.material.weight.coat;
    vec3 sub = primeRcDiffractionBaseTintOut(wo, state);
    if (weight == 0.0) { return sub; }
    return sub * mix(vec3(1.0), primeRcCoatTintOut(wo, state), vec3(weight));
}
vec3 primeRcCoatedBaseTrans(vec3 wi, PrimeRcState state, vec3 baseEnergy) {
    float weight = state.material.weight.coat;
    vec3 sub = primeRcDiffractionBaseTrans(wi, state, baseEnergy);
    if (weight == 0.0) { return sub; }
    return sub * mix(vec3(1.0), primeRcCoatTrans(wi, state, baseEnergy), vec3(weight));
}
vec3 primeRcCoatedBaseEnergy(vec3 wi, PrimeRcState state) {
    return primeRcLayerEnergyValues(
            primeRcDiffractionBaseEnergy(wi, state),
            primeRcCoatEnergy(wi, state),
            state.coatedBase,
            state.material.weight.coat);
}

PrimeRcThroughput primeRcOpenPbrEval(vec3 wi, vec3 wo, PrimeRcState state) {
    float weight = state.material.weight.fuzz;
    return primeRcLayerEvalValues(
            primeRcCoatedBaseEval(wi, wo, state),
            weight > 0.0 ? primeRcFuzzEval(wi, wo, state) : primeRcZeroThroughput(),
            weight > 0.0 ? primeRcFuzzTintOut(wo, state) : vec3(0.0),
            state.surface,
            weight);
}

PrimeRcSampleResult primeRcOpenPbrSample(
        vec3 wi, vec3 randomValue, PrimeRcState state, PrimeRcVolumeStack stack) {
    PrimeRcLayerState layer = state.surface;
    if (layer.subSampleWeight + layer.coatSampleWeight <= 0.0) {
        return primeRcZeroSampleResult(state, stack);
    }
    float weight = state.material.weight.fuzz;
    if (randomValue.z < layer.coatSampleWeight) {
        PrimeRcSampleResult result = primeRcFuzzSample(
                wi, vec3(randomValue.xy, randomValue.z / layer.coatSampleWeight),
                state, stack);
        result.bsdfSample.throughput = primeRcThroughputScale(result.bsdfSample.throughput, weight);
        result.bsdfSample.pdf *= layer.coatSampleWeight;
        return result;
    }
    PrimeRcSampleResult result = primeRcCoatedBaseSample(
            wi,
            vec3(randomValue.xy,
                    (randomValue.z - layer.coatSampleWeight) / layer.subSampleWeight),
            state,
            stack);
    result.bsdfSample.throughput = primeRcThroughputScale(
            result.bsdfSample.throughput,
            mix(vec3(1.0),
                    layer.coatTransIn * primeRcFuzzTintOut(result.bsdfSample.wo, state),
                    vec3(weight)));
    result.bsdfSample.pdf *= layer.subSampleWeight;
    return result;
}

float primeRcOpenPbrPdf(vec3 wi, vec3 wo, PrimeRcState state) {
    float subPdf = primeRcCoatedBasePdf(wi, wo, state);
    if (state.material.weight.fuzz == 0.0) { return subPdf; }
    PrimeRcLayerState layer = state.surface;
    if (layer.subSampleWeight + layer.coatSampleWeight <= 0.0) { return 0.0; }
    return mix(subPdf, primeRcFuzzPdf(wi, wo, state), layer.coatSampleWeight);
}

vec3 primeRcOpenPbrTintOut(vec3 wo, PrimeRcState state) {
    float weight = state.material.weight.fuzz;
    vec3 sub = primeRcCoatedBaseTintOut(wo, state);
    if (weight == 0.0) { return sub; }
    return sub * mix(vec3(1.0), primeRcFuzzTintOut(wo, state), vec3(weight));
}
vec3 primeRcOpenPbrTrans(vec3 wi, PrimeRcState state, vec3 baseEnergy) {
    float weight = state.material.weight.fuzz;
    vec3 sub = primeRcCoatedBaseTrans(wi, state, baseEnergy);
    if (weight == 0.0) { return sub; }
    return sub * mix(vec3(1.0), primeRcFuzzTrans(wi, state, baseEnergy), vec3(weight));
}
vec3 primeRcOpenPbrEnergy(vec3 wi, PrimeRcState state) {
    return primeRcLayerEnergyValues(
            primeRcCoatedBaseEnergy(wi, state),
            primeRcFuzzEnergy(wi, state),
            state.surface,
            state.material.weight.fuzz);
}

PrimeRcEval primeRcOpenPbrEvaluate(vec3 wi, vec3 wo, PrimeRcState state) {
    PrimeRcEval result;
    result.throughput = primeRcOpenPbrEval(wi, wo, state);
    result.pdf = primeRcOpenPbrPdf(wi, wo, state);
    return result;
}
#endif

PrimeRcMaterial primeRcMaterialFromMetallic(
        vec3 baseColor, float roughness, float metalness, vec3 normal) {
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
    material.specular.color = vec3(1.0);
    material.specular.roughness = roughness;
    material.specular.roughnessAnisotropy = 0.0;
    material.specular.ior = 1.5;
    material.emission.luminance = vec3(0.0);
    material.base.color = baseColor;
    material.subsurface.color = vec3(0.8);
    material.subsurface.radius = vec3(0.05, 0.025, 0.0125);
    material.subsurface.scatterAnisotropy = 0.0;
    material.transmission.color = vec3(1.0);
    material.transmission.depth = 0.0;
    material.transmission.scatter = vec3(0.0);
    material.transmission.scatterAnisotropy = 0.0;
    material.transmission.dispersionScale = 0.0;
    material.transmission.dispersionAbbeNumber = 20.0;
    material.coat.color = vec3(1.0);
    material.coat.roughness = 0.0;
    material.coat.roughnessAnisotropy = 0.0;
    material.coat.ior = 1.6;
    material.coat.darkening = 1.0;
    material.coat.roughening = 1.0;
    material.coat.onb = onb;
    material.fuzz.color = vec3(1.0);
    material.fuzz.roughness = 0.5;
    material.thinFilm.thickness = 0.5;
    material.thinFilm.ior = 1.4;
    material.diffraction.color = vec3(1.0);
    material.diffraction.thickness = 0.5;
    material.diffraction.invPitch = vec2(1.0 / 3.0, 0.0);
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
        float inverseOutsideIor,
        float rayT,
        vec3 wavelengthsNm,
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
        material.specular.roughness = mix(
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
    state.selectedWavelength = 0u;
    state.wavelengthsNm = wavelengthsNm;
    state.rayT = rayT;
    state.invOutIor = inverseOutsideIor;
    state.originalIor = material.specular.ior;
    state.heroWavelengthIndex = min(heroWavelengthIndex, 2u);
    state.samplingFlags = PRIME_RC_FLAG_ALL;
    state.randomValue = vec3(0.0);

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
            ? material.base.color * material.weight.base : vec3(0.0);
    state.conductorFresnel.f82 = material.specular.color;
    state.conductorFresnel.f90 = vec3(1.0);
    state.conductorFresnel.weight = material.weight.specular;
    state.conductorFresnel.energyF0 = material.weight.base > 0.0
            ? clamp(material.base.color * material.weight.base
                    * material.weight.specular, vec3(0.0), vec3(1.0))
            : vec3(0.0);
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

    if (fullOpenPbr) {
        vec3 coatNormal = primeRcOnbToLocal(
                material.geometry.onb, material.coat.onb.normal);
        vec3 coatTangent = primeRcOnbToLocal(
                material.geometry.onb, material.coat.onb.tangent);
        state.coatLocalOnb.tangent = coatTangent;
        state.coatLocalOnb.bitangent = cross(coatNormal, coatTangent);
        state.coatLocalOnb.normal = coatNormal;
    } else {
        state.coatLocalOnb.tangent = vec3(1.0, 0.0, 0.0);
        state.coatLocalOnb.bitangent = vec3(0.0, 1.0, 0.0);
        state.coatLocalOnb.normal = vec3(0.0, 0.0, 1.0);
    }
    state.conductorEss = 1.0;
    state.transmissionMicrofacet.alpha = vec2(0.0);
    state.transmissionTint = vec3(1.0);
    state.transmissionVolume = primeRcVolumeFromTransmission(material.transmission);
    state.coatMicrofacet.alpha = vec2(0.0);
    state.coatFresnelIor = 1.0;
    state.coatTint = vec3(1.0);
    state.coatDarkening = 0.0;
    state.coatBaseRoughness = material.specular.roughness;
    state.diffractionState.directionalAlbedo = 0.0;
    state.diffractionState.h = vec3(0.0, 0.0, 1.0);
    state.diffractionState.angleCs = vec2(1.0, 0.0);
    state.basicGlossy = primeRcMakeLayerState(0.0, vec3(0.0), vec3(0.0), vec3(0.0));
    state.basicMetal = primeRcMakeMixState(0.0, vec3(0.0), vec3(0.0));
    state.subsurfaceGlossy = primeRcMakeLayerState(0.0, vec3(0.0), vec3(0.0), vec3(0.0));
    state.mixedDiffuse = primeRcMakeMixState(0.0, vec3(0.0), vec3(0.0));
    state.glossyDiffuse = primeRcMakeLayerState(0.0, vec3(0.0), vec3(0.0), vec3(0.0));
    state.dielectricBase = primeRcMakeMixState(0.0, vec3(0.0), vec3(0.0));
    state.baseSubstrate = primeRcMakeMixState(0.0, vec3(0.0), vec3(0.0));
    state.diffractionBase = primeRcMakeLayerState(0.0, vec3(0.0), vec3(0.0), vec3(0.0));
    state.coatedBase = primeRcMakeLayerState(0.0, vec3(0.0), vec3(0.0), vec3(0.0));
    state.surface = primeRcMakeLayerState(0.0, vec3(0.0), vec3(0.0), vec3(0.0));
    return state;
}

PrimeRcState primeRcInitializeConductor(vec3 wi, PrimeRcState state) {
    state.conductorEss = 1.0;
    if (!primeRcMicrofacetEffectivelySmooth(state.specularMicrofacet)) {
        state.conductorEss = primeRcMicrofacetDirectionalAlbedoReflection(
                state.specularMicrofacet, wi.z, vec3(1.0), vec3(1.0)).x;
    }
    return state;
}

PrimeRcState primeRcInitializeTransmission(PrimeRcState state) {
    state.transmissionTint = state.material.transmission.depth == 0.0
            ? state.material.transmission.color : vec3(1.0);
    state.transmissionVolume = primeRcVolumeFromTransmission(state.material.transmission);
    if (state.material.transmission.dispersionScale > 0.0) {
        state.selectedWavelength = 1u;
        state.specularFresnel.ior = primeRcDispersionIor(
                state.originalIor * state.invOutIor,
                state.material.transmission.dispersionAbbeNumber,
                state.material.transmission.dispersionScale,
                state.wavelengthsNm[state.heroWavelengthIndex]);
    }
    if (state.geometryThinWalled != 0u) {
        state.transmissionMicrofacet.alpha = clamp(
                state.specularMicrofacet.alpha
                * primeRcThinDielectricRoughnessScaler2(state.specularFresnel.ior),
                vec2(0.0), vec2(1.0));
    }
    return state;
}

// Prime's exact OpenPBR subset for thin-walled Minecraft surfaces. This is the same imported
// composition as the full graph through BaseSubstrate:
//   mix(diffuse, subsurface) -> dielectric specular -> transmission -> conductor mix.
// Prime does not author coat, fuzz, diffraction or thin-film weights, so stopping at this node
// removes no reachable lobe and preserves the full graph's evaluation and sampling mathematics.
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
            material, inverseOutsideIor, rayT, wavelengthsNm,
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
        state = primeRcInitializeTransmission(state);
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

#if PRIME_RC_ENABLE_FULL_OPENPBR
PrimeRcState primeRcInitializeCoat(PrimeRcState state) {
    state.coatMicrofacet.alpha = primeRcSpecularNdfRoughnesses(
            state.material.coat.roughness,
            state.material.coat.roughnessAnisotropy);
    state.coatFresnelIor = state.material.coat.ior * state.invOutIor;
    state.coatTint = sqrt(state.material.coat.color);
    state.coatDarkening = state.material.coat.darkening;
    float baseRoughness = state.material.specular.roughness;
    float dielectricRoughness = mix(
            1.0, baseRoughness, primeRcIorToF0(state.specularFresnel.energyIor));
    state.coatBaseRoughness = mix(
            dielectricRoughness, baseRoughness, state.material.weight.metalness);
    return state;
}

PrimeRcState primeRcInitializeDiffraction(
        vec3 inputWi, vec3 randomValue, PrimeRcState state) {
    float angle = state.material.diffraction.angle;
    state.diffractionState.angleCs = vec2(cos(angle), sin(angle));
    state.diffractionState.h = vec3(0.0, 0.0, 1.0);
    vec3 wi = inputWi;
    if (!primeRcMicrofacetEffectivelySmooth(state.specularMicrofacet)) {
        vec2 cs = state.diffractionState.angleCs;
        wi.xy = vec2(cs.x * wi.x - cs.y * wi.y, cs.y * wi.x + cs.x * wi.y);
        state.diffractionState.h = primeRcMicrofacetSample(
                state.specularMicrofacet, wi, randomValue.xy, false);
    }
    float wavelength = state.wavelengthsNm[state.heroWavelengthIndex];
    state.diffractionState.directionalAlbedo = 1.0 - primeRcDiffractionAlpha(
            abs(dot(state.diffractionState.h, wi)),
            1.0e3 * state.material.diffraction.thickness,
            wavelength);
    state.selectedWavelength = 1u;
    return state;
}

PrimeRcState primeRcOpenPbrStateInit(
        PrimeRcMaterial material,
        vec3 wi,
        vec3 randomValue,
        float inverseOutsideIor,
        float rayT,
        vec3 wavelengthsNm,
        uint heroWavelengthIndex,
        uint detail,
        uint spectrumed) {
    PrimeRcState state = primeRcBaseState(
            material,
            inverseOutsideIor,
            rayT,
            wavelengthsNm,
            heroWavelengthIndex,
            detail,
            spectrumed,
            true);
    state.randomValue = randomValue;

    vec3 diffuseEnergy = state.material.weight.subsurface < 1.0
            ? primeRcDiffuseEnergy(wi, state) : vec3(0.0);
    vec3 subsurfaceEnergy = state.material.weight.subsurface > 0.0
            ? primeRcSubsurfaceEnergy(wi, state) : vec3(0.0);
    state.mixedDiffuse = primeRcMakeMixState(
            state.material.weight.subsurface,
            diffuseEnergy,
            subsurfaceEnergy);

    vec3 mixedEnergy = primeRcMixedDiffuseEnergy(wi, state);
    if (state.material.weight.specular > 0.0) {
        vec3 coatTrans = primeRcSpecularTrans(wi, state, mixedEnergy);
        state.glossyDiffuse = primeRcMakeLayerState(
                1.0,
                mixedEnergy,
                coatTrans,
                primeRcSpecularEnergy(wi, state));
    } else {
        state.glossyDiffuse = primeRcMakeLayerState(
                0.0, vec3(0.0), vec3(0.0), vec3(0.0));
    }

    if (state.material.weight.transmission > 0.0) {
        state = primeRcInitializeTransmission(state);
    }
    vec3 glossyEnergy = state.material.weight.transmission < 1.0
            ? primeRcGlossyDiffuseEnergy(wi, state) : vec3(0.0);
    vec3 transmissionEnergy = state.material.weight.transmission > 0.0
            ? primeRcTransmissionEnergy(wi, state) : vec3(0.0);
    state.dielectricBase = primeRcMakeMixState(
            state.material.weight.transmission,
            glossyEnergy,
            transmissionEnergy);

    if (state.material.weight.metalness > 0.0) {
        state = primeRcInitializeConductor(wi, state);
    }
    vec3 dielectricEnergy = state.material.weight.metalness < 1.0
            ? primeRcDielectricBaseEnergy(wi, state) : vec3(0.0);
    vec3 conductorEnergy = state.material.weight.metalness > 0.0
            ? primeRcConductorEnergy(wi, state) : vec3(0.0);
    state.baseSubstrate = primeRcMakeMixState(
            state.material.weight.metalness,
            dielectricEnergy,
            conductorEnergy);

    vec3 substrateEnergy = primeRcBaseSubstrateEnergy(wi, state);
    if (state.material.weight.diffraction > 0.0) {
        state = primeRcInitializeDiffraction(wi, randomValue, state);
        vec3 coatTrans = primeRcDiffractionTrans(wi, state, substrateEnergy);
        state.diffractionBase = primeRcMakeLayerState(
                state.material.weight.diffraction,
                substrateEnergy,
                coatTrans,
                primeRcDiffractionEnergy(wi, state));
    } else {
        state.diffractionBase = primeRcMakeLayerState(
                0.0, vec3(0.0), vec3(0.0), vec3(0.0));
    }

    vec3 diffractionEnergy = primeRcDiffractionBaseEnergy(wi, state);
    if (state.material.weight.coat > 0.0) {
        state = primeRcInitializeCoat(state);
        vec3 coatTrans = primeRcCoatTrans(wi, state, diffractionEnergy);
        state.coatedBase = primeRcMakeLayerState(
                state.material.weight.coat,
                diffractionEnergy,
                coatTrans,
                primeRcCoatEnergy(wi, state));
    } else {
        state.coatedBase = primeRcMakeLayerState(
                0.0, vec3(0.0), vec3(0.0), vec3(0.0));
    }

    vec3 coatedEnergy = primeRcCoatedBaseEnergy(wi, state);
    if (state.material.weight.fuzz > 0.0) {
        vec3 fuzzTrans = primeRcFuzzTrans(wi, state, coatedEnergy);
        state.surface = primeRcMakeLayerState(
                state.material.weight.fuzz,
                coatedEnergy,
                fuzzTrans,
                primeRcFuzzEnergy(wi, state));
    } else {
        state.surface = primeRcMakeLayerState(
                0.0, vec3(0.0), vec3(0.0), vec3(0.0));
    }
    return state;
}
#endif

// RoboCute's polymorphic basic-metallic branch: Lambertian substrate under the identical
// dielectric specular layer, then energy-weighted mixing with the conductor closure.
PrimeRcThroughput primeRcBasicGlossyEval(vec3 wi, vec3 wo, PrimeRcState state) {
    float weight = state.material.weight.specular > 0.0 ? 1.0 : 0.0;
    return primeRcLayerEvalValues(
            primeRcLambertEval(wi, wo, state),
            weight > 0.0 ? primeRcSpecularEval(wi, wo, state) : primeRcZeroThroughput(),
            weight > 0.0 ? primeRcSpecularTintOut(wo, state) : vec3(0.0),
            state.basicGlossy,
            weight);
}

PrimeRcSampleResult primeRcBasicGlossySample(
        vec3 wi, vec3 randomValue, PrimeRcState state, PrimeRcVolumeStack stack) {
    PrimeRcLayerState layer = state.basicGlossy;
    if (layer.subSampleWeight + layer.coatSampleWeight <= 0.0) {
        return primeRcZeroSampleResult(state, stack);
    }
    float weight = state.material.weight.specular > 0.0 ? 1.0 : 0.0;
    if (randomValue.z < layer.coatSampleWeight) {
        PrimeRcSampleResult result = primeRcSpecularSample(
                wi, vec3(randomValue.xy, randomValue.z / layer.coatSampleWeight),
                state, stack);
        result.bsdfSample.throughput = primeRcThroughputScale(result.bsdfSample.throughput, weight);
        result.bsdfSample.pdf *= layer.coatSampleWeight;
        return result;
    }
    PrimeRcSampleResult result = primeRcLambertSample(
            wi,
            vec3(randomValue.xy,
                    (randomValue.z - layer.coatSampleWeight) / layer.subSampleWeight),
            state,
            stack);
    result.bsdfSample.throughput = primeRcThroughputScale(
            result.bsdfSample.throughput,
            mix(vec3(1.0),
                    layer.coatTransIn * primeRcSpecularTintOut(result.bsdfSample.wo, state),
                    vec3(weight)));
    result.bsdfSample.pdf *= layer.subSampleWeight;
    return result;
}

float primeRcBasicGlossyPdf(vec3 wi, vec3 wo, PrimeRcState state) {
    float subPdf = primeRcLambertPdf(wi, wo, state);
    if (state.material.weight.specular <= 0.0) { return subPdf; }
    if (state.basicGlossy.subSampleWeight + state.basicGlossy.coatSampleWeight <= 0.0) {
        return 0.0;
    }
    return mix(subPdf, primeRcSpecularPdf(wi, wo, state),
            state.basicGlossy.coatSampleWeight);
}

vec3 primeRcBasicGlossyTintOut(vec3 wo, PrimeRcState state) {
    vec3 sub = primeRcLambertTintOut(wo, state);
    if (state.material.weight.specular <= 0.0) { return sub; }
    return sub * mix(
            vec3(1.0), primeRcSpecularTintOut(wo, state),
            vec3(1.0));
}

vec3 primeRcBasicGlossyTrans(vec3 wi, PrimeRcState state, vec3 baseEnergy) {
    vec3 sub = primeRcLambertTrans(wi, state, baseEnergy);
    if (state.material.weight.specular <= 0.0) { return sub; }
    return sub * mix(
            vec3(1.0), primeRcSpecularTrans(wi, state, baseEnergy),
            vec3(1.0));
}

vec3 primeRcBasicGlossyEnergy(vec3 wi, PrimeRcState state) {
    float weight = state.material.weight.specular > 0.0 ? 1.0 : 0.0;
    return primeRcLayerEnergyValues(
            primeRcLambertEnergy(wi, state),
            primeRcSpecularEnergy(wi, state),
            state.basicGlossy,
            weight);
}

PrimeRcThroughput primeRcBasicMetallicEval(vec3 wi, vec3 wo, PrimeRcState state) {
    float weight = state.material.weight.metalness;
    return primeRcMixEvalValues(
            weight < 1.0 ? primeRcBasicGlossyEval(wi, wo, state) : primeRcZeroThroughput(),
            weight > 0.0 ? primeRcConductorEval(wi, wo, state) : primeRcZeroThroughput(),
            weight);
}

PrimeRcSampleResult primeRcBasicMetallicSample(
        vec3 wi, vec3 randomValue, PrimeRcState state, PrimeRcVolumeStack stack) {
    if (state.basicMetal.firstSampleWeight + state.basicMetal.secondSampleWeight <= 0.0) {
        return primeRcZeroSampleResult(state, stack);
    }
    float weight = state.material.weight.metalness;
    if (randomValue.z < state.basicMetal.secondSampleWeight) {
        PrimeRcSampleResult result = primeRcConductorSample(
                wi, vec3(randomValue.xy,
                        randomValue.z / state.basicMetal.secondSampleWeight),
                state, stack);
        result.bsdfSample.throughput = primeRcThroughputScale(result.bsdfSample.throughput, weight);
        result.bsdfSample.pdf *= state.basicMetal.secondSampleWeight;
        return result;
    }
    PrimeRcSampleResult result = primeRcBasicGlossySample(
            wi,
            vec3(randomValue.xy,
                    (randomValue.z - state.basicMetal.secondSampleWeight)
                    / state.basicMetal.firstSampleWeight),
            state,
            stack);
    result.bsdfSample.throughput = primeRcThroughputScale(
            result.bsdfSample.throughput, 1.0 - weight);
    result.bsdfSample.pdf *= state.basicMetal.firstSampleWeight;
    return result;
}

float primeRcBasicMetallicPdf(vec3 wi, vec3 wo, PrimeRcState state) {
    float weight = state.material.weight.metalness;
    float first = weight < 1.0 ? primeRcBasicGlossyPdf(wi, wo, state) : 0.0;
    float second = weight > 0.0 ? primeRcConductorPdf(wi, wo, state) : 0.0;
    if (state.basicMetal.firstSampleWeight + state.basicMetal.secondSampleWeight <= 0.0) {
        return 0.0;
    }
    return mix(first, second, state.basicMetal.secondSampleWeight);
}

vec3 primeRcBasicMetallicTintOut(vec3 wo, PrimeRcState state) {
    float weight = state.material.weight.metalness;
    return primeRcMixValues(
            weight < 1.0 ? primeRcBasicGlossyTintOut(wo, state) : vec3(0.0),
            weight > 0.0 ? primeRcConductorTintOut(wo, state) : vec3(0.0),
            weight);
}

vec3 primeRcBasicMetallicTrans(vec3 wi, PrimeRcState state, vec3 baseEnergy) {
    float weight = state.material.weight.metalness;
    return primeRcMixValues(
            weight < 1.0
                    ? primeRcBasicGlossyTrans(wi, state, baseEnergy) : vec3(0.0),
            weight > 0.0
                    ? primeRcConductorTrans(wi, state, baseEnergy) : vec3(0.0),
            weight);
}

vec3 primeRcBasicMetallicEnergy(vec3 wi, PrimeRcState state) {
    float weight = state.material.weight.metalness;
    return primeRcMixValues(
            weight < 1.0 ? primeRcBasicGlossyEnergy(wi, state) : vec3(0.0),
            weight > 0.0 ? primeRcConductorEnergy(wi, state) : vec3(0.0),
            weight);
}

PrimeRcEval primeRcBasicMetallicEvaluate(vec3 wi, vec3 wo, PrimeRcState state) {
    PrimeRcEval result;
    result.throughput = primeRcBasicMetallicEval(wi, wo, state);
    result.pdf = primeRcBasicMetallicPdf(wi, wo, state);
    return result;
}

PrimeRcState primeRcBasicMetallicStateInit(
        PrimeRcMaterial material,
        vec3 wi,
        float inverseOutsideIor,
        float rayT,
        vec3 wavelengthsNm,
        uint heroWavelengthIndex,
        uint detail,
        uint spectrumed) {
    PrimeRcState state = primeRcBaseState(
            material, inverseOutsideIor, rayT, wavelengthsNm,
            heroWavelengthIndex, detail, spectrumed, false);
    if (state.material.weight.metalness < 1.0) {
        vec3 subEnergy = primeRcLambertEnergy(wi, state);
        state.basicGlossy = state.material.weight.specular > 0.0
                ? primeRcMakeLayerState(
                        1.0,
                        subEnergy,
                        primeRcSpecularTrans(wi, state, subEnergy),
                        primeRcSpecularEnergy(wi, state))
                : primeRcMakeLayerState(
                        0.0, vec3(0.0), vec3(0.0), vec3(0.0));
    }
    if (state.material.weight.metalness > 0.0) {
        state = primeRcInitializeConductor(wi, state);
    }
    state.basicMetal = primeRcMakeMixState(
            state.material.weight.metalness,
            state.material.weight.metalness < 1.0
                    ? primeRcBasicGlossyEnergy(wi, state) : vec3(0.0),
            state.material.weight.metalness > 0.0
                    ? primeRcConductorEnergy(wi, state) : vec3(0.0));
    return state;
}

// Full-build RoboCute polymorphic branch:
// WeightedLayeringBSDF<SubsurfaceBSDF, DielectricSpecularBRDF, SpecularWeight>.
PrimeRcThroughput primeRcSubsurfaceGlossyEval(
        vec3 wi, vec3 wo, PrimeRcState state) {
    float weight = state.material.weight.specular > 0.0 ? 1.0 : 0.0;
    return primeRcLayerEvalValues(
            primeRcSubsurfaceEval(wi, wo, state),
            weight > 0.0 ? primeRcSpecularEval(wi, wo, state) : primeRcZeroThroughput(),
            weight > 0.0 ? primeRcSpecularTintOut(wo, state) : vec3(0.0),
            state.subsurfaceGlossy,
            weight);
}

PrimeRcSampleResult primeRcSubsurfaceGlossySample(
        vec3 wi, vec3 randomValue, PrimeRcState state, PrimeRcVolumeStack stack) {
    PrimeRcLayerState layer = state.subsurfaceGlossy;
    if (layer.subSampleWeight + layer.coatSampleWeight <= 0.0) {
        return primeRcZeroSampleResult(state, stack);
    }
    float weight = state.material.weight.specular > 0.0 ? 1.0 : 0.0;
    if (randomValue.z < layer.coatSampleWeight) {
        PrimeRcSampleResult result = primeRcSpecularSample(
                wi, vec3(randomValue.xy, randomValue.z / layer.coatSampleWeight),
                state, stack);
        result.bsdfSample.throughput = primeRcThroughputScale(
                result.bsdfSample.throughput, weight);
        result.bsdfSample.pdf *= layer.coatSampleWeight;
        return result;
    }
    PrimeRcSampleResult result = primeRcSubsurfaceSample(
            wi,
            vec3(randomValue.xy,
                    (randomValue.z - layer.coatSampleWeight) / layer.subSampleWeight),
            state,
            stack);
    result.bsdfSample.throughput = primeRcThroughputScale(
            result.bsdfSample.throughput,
            mix(vec3(1.0),
                    layer.coatTransIn * primeRcSpecularTintOut(result.bsdfSample.wo, state),
                    vec3(weight)));
    result.bsdfSample.pdf *= layer.subSampleWeight;
    return result;
}

float primeRcSubsurfaceGlossyPdf(vec3 wi, vec3 wo, PrimeRcState state) {
    float subPdf = primeRcSubsurfacePdf(wi, wo, state);
    if (state.material.weight.specular <= 0.0) { return subPdf; }
    if (state.subsurfaceGlossy.subSampleWeight
            + state.subsurfaceGlossy.coatSampleWeight <= 0.0) {
        return 0.0;
    }
    return mix(subPdf, primeRcSpecularPdf(wi, wo, state),
            state.subsurfaceGlossy.coatSampleWeight);
}

vec3 primeRcSubsurfaceGlossyTintOut(vec3 wo, PrimeRcState state) {
    vec3 sub = primeRcSubsurfaceTintOut(wo, state);
    if (state.material.weight.specular <= 0.0) { return sub; }
    return sub * mix(
            vec3(1.0), primeRcSpecularTintOut(wo, state), vec3(1.0));
}

vec3 primeRcSubsurfaceGlossyTrans(
        vec3 wi, PrimeRcState state, vec3 baseEnergy) {
    vec3 sub = primeRcSubsurfaceTrans(wi, state, baseEnergy);
    if (state.material.weight.specular <= 0.0) { return sub; }
    return sub * mix(
            vec3(1.0), primeRcSpecularTrans(wi, state, baseEnergy), vec3(1.0));
}

vec3 primeRcSubsurfaceGlossyEnergy(vec3 wi, PrimeRcState state) {
    float weight = state.material.weight.specular > 0.0 ? 1.0 : 0.0;
    return primeRcLayerEnergyValues(
            primeRcSubsurfaceEnergy(wi, state),
            primeRcSpecularEnergy(wi, state),
            state.subsurfaceGlossy,
            weight);
}

PrimeRcEval primeRcSubsurfaceGlossyEvaluate(
        vec3 wi, vec3 wo, PrimeRcState state) {
    PrimeRcEval result;
    result.throughput = primeRcSubsurfaceGlossyEval(wi, wo, state);
    result.pdf = primeRcSubsurfaceGlossyPdf(wi, wo, state);
    return result;
}

PrimeRcState primeRcSubsurfaceGlossyStateInit(
        PrimeRcMaterial material,
        vec3 wi,
        float inverseOutsideIor,
        float rayT,
        vec3 wavelengthsNm,
        uint heroWavelengthIndex,
        uint detail,
        uint spectrumed) {
    PrimeRcState state = primeRcBaseState(
            material, inverseOutsideIor, rayT, wavelengthsNm,
            heroWavelengthIndex, detail, spectrumed, false);
    vec3 subEnergy = primeRcSubsurfaceEnergy(wi, state);
    state.subsurfaceGlossy = state.material.weight.specular > 0.0
            ? primeRcMakeLayerState(
                    1.0,
                    subEnergy,
                    primeRcSpecularTrans(wi, state, subEnergy),
                    primeRcSpecularEnergy(wi, state))
            : primeRcMakeLayerState(
                    0.0, vec3(0.0), vec3(0.0), vec3(0.0));
    return state;
}

PrimeRcEval primeRcTransmissionEvaluate(vec3 wi, vec3 wo, PrimeRcState state) {
    PrimeRcEval result;
    result.throughput = primeRcTransmissionEval(wi, wo, state);
    result.pdf = primeRcTransmissionPdf(wi, wo, state);
    return result;
}

PrimeRcState primeRcTransmissionStateInit(
        PrimeRcMaterial material,
        float inverseOutsideIor,
        float rayT,
        vec3 wavelengthsNm,
        uint heroWavelengthIndex,
        uint detail,
        uint spectrumed) {
    PrimeRcState state = primeRcBaseState(
            material, inverseOutsideIor, rayT, wavelengthsNm,
            heroWavelengthIndex, detail, spectrumed, false);
    return primeRcInitializeTransmission(state);
}

#endif
