#language slang 2026
module "bsdf/compact/openpbr_transmission.slang";

import "bsdf/core/robocute_common.slang";
import "bsdf/core/robocute_fresnel.slang";
import "bsdf/core/robocute_microfacet.slang";
import "bsdf/core/transmission_ggx_energy.slang";

// Exact compact specialization for Prime's dielectric transmission materials.
// The state excludes the inactive OpenPBR composition graph and keeps medium
// mutation at the sample boundary.

static const float3 PRIME_COMPACT_TRANSMISSION_WAVELENGTHS_NM =
        float3(630.0, 532.0, 467.0);

public struct PrimeCompactTransmissionState {
    public PrimeRcOnb onb;
    public uint geometryThinWalled;
    public uint entering;
    public float rayT;
    public float originalIor;
    public float3 transmissionColor;
    public PrimeRcMicrofacet specularMicrofacet;
    public PrimeRcSpecularFresnel specularFresnel;
    public PrimeRcMicrofacet transmissionMicrofacet;
    public float3 transmissionTint;
    public PrimeRcVolume transmissionVolume;
    public float3 transmissionMultipleScattering;
};

public struct PrimeCompactTransmissionEvaluation {
    public PrimeRcThroughput throughput;
    public float pdf;
};

PrimeRcSampleResult primeCompactTransmissionZeroResult(
        PrimeCompactTransmissionState state,
        PrimeRcVolumeStack stack) {
    PrimeRcSampleResult result;
    result.bsdfSample = primeRcZeroSample();
    result.volumeStack = stack;
    result.rayT = state.rayT;
    return result;
}

float3 primeCompactGuardedThinWallSeriesReciprocal(float3 roundTrip) {
    return float3(1.0) / max(
            float3(PRIME_RC_DENOM_TOLERANCE),
            float3(1.0) - primeRcSquare(roundTrip));
}

PrimeRcVecPair primeCompactTransmissionThinWallRt(
        float cosineTheta,
        PrimeCompactTransmissionState state) {
    PrimeRcVecPair fresnel = primeRcSpecularFresnelRt(
            state.specularFresnel,
            PRIME_COMPACT_TRANSMISSION_WAVELENGTHS_NM,
            0u,
            cosineTheta);
    float t0 = 1.0 - (1.0 - cosineTheta * cosineTheta)
            / primeRcSquare(state.specularFresnel.ior);
    if (t0 <= 0.0) { return fresnel; }
    float cosineThetaI = sqrt(t0);
    float3 absorption = state.transmissionTint
            * exp(-state.transmissionVolume.extinction / cosineThetaI);
    float3 inverseSeries = primeCompactGuardedThinWallSeriesReciprocal(
            fresnel.first * absorption);
    PrimeRcVecPair result;
    result.first = fresnel.first
            * (float3(1.0) + primeRcSquare(fresnel.second * absorption) * inverseSeries);
    result.second = primeRcSquare(fresnel.second) * absorption * inverseSeries;
    return result;
}

float primeCompactMicrofacetG2r(
        PrimeRcMicrofacet microfacet,
        float3 wi,
        float3 wo) {
    if (wi.z < 0.0) {
        wi = -wi;
        wo = -wo;
    }
    return 1.0 / (primeRcMicrofacetLambda(microfacet, -wi)
            + primeRcMicrofacetLambda(microfacet, wo));
}

public float3 primeCompactTransmissionMaterialFrame(
        float3 w,
        PrimeCompactTransmissionState state) {
    return state.entering != 0u ? w : float3(-w.x, w.y, -w.z);
}

PrimeRcThroughput primeCompactRefractiveEval(
        float3 wi,
        float3 wo,
        PrimeCompactTransmissionState state,
        uint samplingFlags) {
    PrimeRcThroughput result = primeRcZeroThroughput();
    PrimeRcMicrofacet microfacet = state.specularMicrofacet;
    PrimeRcSpecularFresnel fresnel = state.specularFresnel;
    if (primeRcMicrofacetEffectivelySmooth(microfacet) || fresnel.ior == 1.0) {
        return result;
    }
    bool reflected = wi.z * wo.z > 0.0;
    float etaPath = reflected ? 1.0 : (wi.z > 0.0 ? fresnel.ior : 1.0 / fresnel.ior);
    float3 halfVector = wi + etaPath * wo;
    float lengthSquared = dot(halfVector, halfVector);
    if (wo.z == 0.0 || wi.z == 0.0 || lengthSquared == 0.0) {
        return result;
    }
    halfVector *= rsqrt(lengthSquared);
    if (halfVector.z <= 0.0) { halfVector = -halfVector; }
    float microIncident = dot(wi, halfVector);
    float microOutgoing = dot(wo, halfVector);
    if (microIncident * wi.z < 0.0 || microOutgoing * wo.z < 0.0) {
        return result;
    }
    PrimeRcVecPair rt = primeRcSpecularFresnelRt(
            fresnel,
            PRIME_COMPACT_TRANSMISSION_WAVELENGTHS_NM,
            0u,
            microIncident);
    float distribution = primeRcMicrofacetD(microfacet, halfVector);
    if (reflected && (samplingFlags & PRIME_RC_FLAG_SPECULAR_REFLECTION) != 0u) {
        result.value = rt.first * distribution
                * primeCompactMicrofacetG2r(microfacet, wi, wo) / (4.0 * abs(wi.z));
        result.flags = PRIME_RC_FLAG_SPECULAR_REFLECTION;
    } else if (!reflected
            && (samplingFlags & PRIME_RC_FLAG_SPECULAR_TRANSMISSION) != 0u) {
        float denominator = primeRcSquare(microOutgoing + microIncident / etaPath) * wi.z;
        result.value = rt.second * distribution
                * primeRcMicrofacetG2t(microfacet, wi, wo)
                * abs(microIncident * microOutgoing / denominator);
        result.value *= 1.0 / primeRcSquare(etaPath);
        result.flags = PRIME_RC_FLAG_SPECULAR_TRANSMISSION;
    }
    return result;
}

PrimeRcSample primeCompactRefractiveSample(
        float3 wi,
        float3 randomValue,
        PrimeCompactTransmissionState state,
        uint samplingFlags) {
    PrimeRcMicrofacet microfacet = state.specularMicrofacet;
    PrimeRcSpecularFresnel fresnel = state.specularFresnel;
    float3 halfVector = float3(0.0, 0.0, 1.0);
    float microfacetPdf = 1.0;
    float distribution = 1.0;
    if (!primeRcMicrofacetEffectivelySmooth(microfacet) && fresnel.ior != 1.0) {
        if (!primeRcIsSpecular(samplingFlags)) {
            return primeRcZeroSample();
        }
        float3 sampleDirection = wi.z > 0.0 ? wi : -wi;
        halfVector = primeRcMicrofacetSample(microfacet, sampleDirection, randomValue.xy, false);
        microfacetPdf = primeRcMicrofacetPdf(
                microfacet, sampleDirection, halfVector, false);
        distribution = primeRcMicrofacetD(microfacet, halfVector);
    } else if (!primeRcIsDelta(samplingFlags)) {
        return primeRcZeroSample();
    }
    float microIncident = dot(halfVector, wi);
    PrimeRcVecPair rt = primeRcSpecularFresnelRt(
            fresnel,
            PRIME_COMPACT_TRANSMISSION_WAVELENGTHS_NM,
            0u,
            microIncident);
    float3 reflection = rt.first;
    float3 transmission = rt.second;
    float reflectionProbability = primeRcSpectrumToWeight(reflection);
    float transmissionProbability = primeRcSpectrumToWeight(transmission);
    if (!primeRcIsReflective(samplingFlags)) { reflectionProbability = 0.0; }
    if (!primeRcIsTransmissive(samplingFlags)) { transmissionProbability = 0.0; }
    PrimeRcRefractResult refracted = primeRcSpecularFresnelRefract(
            fresnel, -wi, halfVector);
    if (refracted.valid == 0u) {
        transmissionProbability = 0.0;
        reflection = float3(1.0);
    }
    float probabilitySum = reflectionProbability + transmissionProbability;
    if (probabilitySum == 0.0) {
        return primeRcZeroSample();
    }
    PrimeRcSample result = primeRcZeroSample();
    if (randomValue.z < transmissionProbability / probabilitySum) {
        float3 wo = refracted.wo;
        if (wi.z * wo.z >= 0.0) { return primeRcZeroSample(); }
        float etaPath = refracted.relativeIor;
        result.wo = wo;
        result.eta = etaPath;
        result.throughput.value = transmission;
        result.throughput.flags = PRIME_RC_FLAG_DELTA_TRANSMISSION;
        result.pdf = transmissionProbability / probabilitySum;
        if (!primeRcMicrofacetEffectivelySmooth(microfacet) && fresnel.ior != 1.0) {
            float microOutgoing = dot(wo, halfVector);
            float denominator = primeRcSquare(microOutgoing + microIncident / etaPath);
            result.pdf *= microfacetPdf * abs(microOutgoing) / denominator;
            result.throughput.value = transmission * distribution
                    * primeRcMicrofacetG2t(microfacet, wi, wo)
                    * abs(microIncident * microOutgoing / (wi.z * denominator));
            result.throughput.flags = PRIME_RC_FLAG_SPECULAR_TRANSMISSION;
        }
        result.throughput.value *= 1.0 / primeRcSquare(etaPath);
    } else {
        float3 wo = reflect(-wi, halfVector);
        if (wi.z * wo.z < 0.0) { return primeRcZeroSample(); }
        result.wo = wo;
        result.throughput.value = reflection;
        result.throughput.flags = PRIME_RC_FLAG_DELTA_REFLECTION;
        result.pdf = reflectionProbability / probabilitySum;
        if (!primeRcMicrofacetEffectivelySmooth(microfacet) && fresnel.ior != 1.0) {
            result.pdf *= microfacetPdf / (4.0 * abs(microIncident));
            result.throughput.value = reflection * distribution
                    * primeCompactMicrofacetG2r(microfacet, wi, wo) / abs(4.0 * wi.z);
            result.throughput.flags = PRIME_RC_FLAG_SPECULAR_REFLECTION;
        }
    }
    return result;
}

float primeCompactRefractivePdf(
        float3 wi,
        float3 wo,
        PrimeCompactTransmissionState state,
        uint samplingFlags) {
    PrimeRcMicrofacet microfacet = state.specularMicrofacet;
    PrimeRcSpecularFresnel fresnel = state.specularFresnel;
    if (primeRcMicrofacetEffectivelySmooth(microfacet)
            || fresnel.ior == 1.0 || !primeRcIsSpecular(samplingFlags)) {
        return 0.0;
    }
    bool reflected = wi.z * wo.z > 0.0;
    float etaPath = reflected ? 1.0 : (wi.z > 0.0 ? fresnel.ior : 1.0 / fresnel.ior);
    float3 halfVector = wi + etaPath * wo;
    float lengthSquared = dot(halfVector, halfVector);
    if (wo.z == 0.0 || wi.z == 0.0 || lengthSquared == 0.0) { return 0.0; }
    halfVector *= rsqrt(lengthSquared);
    if (halfVector.z <= 0.0) { halfVector = -halfVector; }
    float microIncident = dot(wi, halfVector);
    float microOutgoing = dot(wo, halfVector);
    if (microIncident * wi.z < 0.0 || microOutgoing * wo.z < 0.0) { return 0.0; }
    PrimeRcVecPair rt = primeRcSpecularFresnelRt(
            fresnel,
            PRIME_COMPACT_TRANSMISSION_WAVELENGTHS_NM,
            0u,
            microIncident);
    float reflectionProbability = primeRcSpectrumToWeight(rt.first);
    float transmissionProbability = primeRcSpectrumToWeight(rt.second);
    if (!primeRcIsReflective(samplingFlags)) { reflectionProbability = 0.0; }
    if (!primeRcIsTransmissive(samplingFlags)) { transmissionProbability = 0.0; }
    float probabilitySum = reflectionProbability + transmissionProbability;
    if (probabilitySum == 0.0) { return 0.0; }
    float microfacetPdf = primeRcMicrofacetPdf(
            microfacet, wi.z > 0.0 ? wi : -wi, halfVector, false);
    if (reflected) {
        return microfacetPdf / (4.0 * abs(microIncident))
                * reflectionProbability / probabilitySum;
    }
    float denominator = primeRcSquare(microOutgoing + microIncident / etaPath);
    return microfacetPdf * abs(microOutgoing) / denominator
            * transmissionProbability / probabilitySum;
}

PrimeRcThroughput primeCompactTransmissionEvalRaw(
        float3 wi,
        float3 inputWo,
        PrimeCompactTransmissionState state,
        uint samplingFlags) {
    if (state.geometryThinWalled != 0u) {
        float3 wo = inputWo;
        bool transmitted = false;
        if (wo.z < 0.0) {
            wo.z = -wo.z;
            transmitted = true;
            if (!primeRcIsTransmissive(samplingFlags)) {
                return primeRcZeroThroughput();
            }
        } else if (!primeRcIsReflective(samplingFlags)) {
            return primeRcZeroThroughput();
        }
        PrimeRcReflectiveEvalBase base = primeRcReflectiveEvalBase(
                wi, wo, state.transmissionMicrofacet,
                samplingFlags, PRIME_RC_FLAG_ALL);
        PrimeRcThroughput result;
        result.value = float3(base.factor);
        result.flags = base.flags;
        if (result.flags != PRIME_RC_FLAG_NONE) {
            PrimeRcVecPair rt = primeCompactTransmissionThinWallRt(
                    base.microCosine, state);
            if (transmitted) {
                result.value *= rt.second;
                result.flags = primeRcIsDelta(result.flags)
                        ? PRIME_RC_FLAG_DELTA_TRANSMISSION
                        : PRIME_RC_FLAG_SPECULAR_TRANSMISSION;
            } else {
                result.value *= rt.first;
            }
            if (primeRcIsNonDelta(result.flags)) {
                result.value /= primeRcMicrofacetDirectionalAlbedoUnity(
                        state.transmissionMicrofacet, wi.z);
            }
        }
        return result;
    }
    float3 materialWi = primeCompactTransmissionMaterialFrame(wi, state);
    float3 materialWo = primeCompactTransmissionMaterialFrame(inputWo, state);
    PrimeRcThroughput result = primeCompactRefractiveEval(
            materialWi, materialWo, state, samplingFlags);
    if (result.flags != PRIME_RC_FLAG_NONE) {
        if (primeRcIsTransmissive(result.flags)) {
            result.value *= state.transmissionTint;
        }
        if (primeRcIsNonDelta(result.flags)) {
            result.value *= primeRcIsTransmissive(result.flags)
                    ? state.transmissionMultipleScattering.y
                    : state.transmissionMultipleScattering.x;
        }
    }
    return result;
}

PrimeRcSampleResult primeCompactTransmissionSampleRaw(
        float3 wi,
        float3 randomValue,
        PrimeCompactTransmissionState state,
        uint samplingFlags,
        PrimeRcVolumeStack stack) {
    PrimeRcSampleResult result = primeCompactTransmissionZeroResult(state, stack);
    if (state.geometryThinWalled != 0u) {
        PrimeRcReflectiveSampleBase base = primeRcReflectiveSampleBase(
                wi, randomValue, state.transmissionMicrofacet,
                samplingFlags, PRIME_RC_FLAG_ALL);
        result.bsdfSample = base.bsdfSample;
        if (result.bsdfSample.throughput.flags != PRIME_RC_FLAG_NONE) {
            PrimeRcVecPair rt = primeCompactTransmissionThinWallRt(
                    base.microCosine, state);
            float reflectionProbability = primeRcSpectrumToWeight(rt.first);
            float transmissionProbability = primeRcSpectrumToWeight(rt.second);
            if (!primeRcIsReflective(samplingFlags)) { reflectionProbability = 0.0; }
            if (!primeRcIsTransmissive(samplingFlags)) { transmissionProbability = 0.0; }
            float probabilitySum = reflectionProbability + transmissionProbability;
            if (probabilitySum == 0.0) {
                result.bsdfSample = primeRcZeroSample();
                return result;
            }
            if (randomValue.z < transmissionProbability / probabilitySum) {
                result.bsdfSample.throughput.value *= rt.second;
                result.bsdfSample.pdf *= transmissionProbability / probabilitySum;
                result.bsdfSample.throughput.flags =
                        primeRcIsDelta(result.bsdfSample.throughput.flags)
                        ? PRIME_RC_FLAG_DELTA_TRANSMISSION
                        : PRIME_RC_FLAG_SPECULAR_TRANSMISSION;
                result.bsdfSample.wo.z = -result.bsdfSample.wo.z;
            } else {
                result.bsdfSample.throughput.value *= rt.first;
                result.bsdfSample.pdf *= reflectionProbability / probabilitySum;
            }
            if (primeRcIsNonDelta(result.bsdfSample.throughput.flags)) {
                result.bsdfSample.throughput.value /=
                        primeRcMicrofacetDirectionalAlbedoUnity(
                                state.transmissionMicrofacet, wi.z);
            }
        }
        return result;
    }

    float3 materialWi = primeCompactTransmissionMaterialFrame(wi, state);
    result.bsdfSample = primeCompactRefractiveSample(
            materialWi, randomValue, state, samplingFlags);
    if (result.bsdfSample.throughput.flags == PRIME_RC_FLAG_NONE) { return result; }
    result.bsdfSample.wo = primeCompactTransmissionMaterialFrame(
            result.bsdfSample.wo, state);
    if (primeRcIsTransmissive(result.bsdfSample.throughput.flags)) {
        result.bsdfSample.throughput.value *= state.transmissionTint;
    }
    if (primeRcIsNonDelta(result.bsdfSample.throughput.flags)) {
        result.bsdfSample.throughput.value *=
                primeRcIsTransmissive(result.bsdfSample.throughput.flags)
                ? state.transmissionMultipleScattering.y
                : state.transmissionMultipleScattering.x;
    }
    return result;
}

float primeCompactTransmissionPdfRaw(
        float3 wi,
        float3 inputWo,
        PrimeCompactTransmissionState state,
        uint samplingFlags) {
    if (state.geometryThinWalled != 0u) {
        float3 wo = inputWo;
        bool transmitted = false;
        if (wo.z < 0.0) {
            wo.z = -wo.z;
            transmitted = true;
        }
        float3 halfVector = normalize(wi + wo);
        PrimeRcVecPair rt = primeCompactTransmissionThinWallRt(
                dot(wi, halfVector), state);
        float reflectionProbability = primeRcSpectrumToWeight(rt.first);
        float transmissionProbability = primeRcSpectrumToWeight(rt.second);
        if (!primeRcIsReflective(samplingFlags)) { reflectionProbability = 0.0; }
        if (!primeRcIsTransmissive(samplingFlags)) { transmissionProbability = 0.0; }
        float probabilitySum = reflectionProbability + transmissionProbability;
        if (probabilitySum == 0.0) { return 0.0; }
        float microfacetPdf = primeRcReflectivePdf(
                wi, wo, state.transmissionMicrofacet,
                samplingFlags, PRIME_RC_FLAG_ALL);
        return transmitted
                ? microfacetPdf * transmissionProbability / probabilitySum
                : microfacetPdf * reflectionProbability / probabilitySum;
    }
    return primeCompactRefractivePdf(
            primeCompactTransmissionMaterialFrame(wi, state),
            primeCompactTransmissionMaterialFrame(inputWo, state),
            state,
            samplingFlags);
}

PrimeCompactTransmissionState primeCompactTransmissionClosureState(
        PrimeCompactTransmissionState state) {
    if (state.geometryThinWalled == 0u) {
        state.entering = 1u;
    }
    return state;
}

public bool primeCompactTransmissionSupports(PrimeRcMaterial material) {
    return material.weight.base == 1.0
            && material.weight.diffuseRoughness == 0.0
            && material.weight.specular == 1.0
            && material.weight.metalness == 0.0
            && material.weight.subsurface == 0.0
            && material.weight.transmission == 1.0
            && material.weight.coat == 0.0
            && material.weight.fuzz == 0.0
            && material.weight.thinFilm == 0.0
            && material.weight.diffraction == 0.0
            && material.transmission.dispersionScale == 0.0;
}

public PrimeCompactTransmissionState primeCompactTransmissionStateInit<TransmissionGgxEnergy>(
        TransmissionGgxEnergy transmissionGgxEnergy,
        PrimeRcMaterial material,
        float3 wi,
        float inverseOutsideIor,
        float rayT)
    where TransmissionGgxEnergy : IPrimeRcTransmissionGgxEnergy
{
    PrimeCompactTransmissionState state;
    state.onb = material.geometry.onb;
    state.geometryThinWalled = material.geometry.thinWalled;
    state.entering = wi.z >= 0.0 ? 1u : 0u;
    state.rayT = rayT;
    state.originalIor = material.specular.ior;
    state.transmissionColor = material.transmission.color;
    state.specularMicrofacet.alpha = primeRcSpecularNdfRoughnesses(
            material.specular.roughness,
            material.specular.roughnessAnisotropy);
    state.specularFresnel.ior = material.specular.ior * inverseOutsideIor;
    state.specularFresnel.energyIor = primeRcIorAdjustment(
            state.specularFresnel.ior, material.weight.specular);
    state.specularFresnel.color = material.specular.color;
    state.specularFresnel.thinFilmWeight = 0.0;
    state.specularFresnel.thinFilmThickness = material.thinFilm.thickness;
    state.specularFresnel.thinFilmIor = material.thinFilm.ior * inverseOutsideIor;
    state.transmissionTint = material.transmission.depth == 0.0
            ? material.transmission.color : float3(1.0);
    state.transmissionVolume = primeRcVolumeFromTransmission(material.transmission);
    state.transmissionMicrofacet.alpha = float2(0.0);
    state.transmissionMultipleScattering = float3(1.0, 1.0, 0.0);
    if (state.geometryThinWalled != 0u) {
        state.transmissionMicrofacet.alpha = clamp(
                state.specularMicrofacet.alpha
                * primeRcThinDielectricRoughnessScaler2(state.specularFresnel.ior),
                float2(0.0),
                float2(1.0));
    } else {
        float3 materialWi = primeCompactTransmissionMaterialFrame(wi, state);
        state.transmissionMultipleScattering = primeRcMicrofacetDielectricMsCompensation(
                transmissionGgxEnergy,
                state.specularMicrofacet,
                materialWi.z,
                state.specularFresnel.ior);
    }
    return state;
}

public PrimeCompactTransmissionState primeCompactTransmissionInterfaceState<TransmissionGgxEnergy>(
        TransmissionGgxEnergy transmissionGgxEnergy,
        float3 wi,
        PrimeCompactTransmissionState state)
    where TransmissionGgxEnergy : IPrimeRcTransmissionGgxEnergy
{
    if (state.geometryThinWalled != 0u || state.entering != 0u) {
        return state;
    }
    state.transmissionMultipleScattering = primeRcMicrofacetDielectricMsCompensation(
            transmissionGgxEnergy,
            state.specularMicrofacet,
            wi.z,
            state.specularFresnel.ior);
    return state;
}

public PrimeCompactTransmissionEvaluation primeCompactTransmissionEvaluate(
        float3 wi,
        float3 wo,
        PrimeCompactTransmissionState state,
        uint samplingFlags) {
    PrimeCompactTransmissionState closure = primeCompactTransmissionClosureState(state);
    PrimeCompactTransmissionEvaluation result;
    result.throughput = primeCompactTransmissionEvalRaw(
            wi, wo, closure, samplingFlags);
    result.pdf = primeCompactTransmissionPdfRaw(
            wi, wo, closure, samplingFlags);
    return result;
}

public PrimeRcSampleResult primeCompactTransmissionSample(
        float3 wi,
        float3 randomValue,
        PrimeCompactTransmissionState state,
        uint samplingFlags,
        PrimeRcVolumeStack stack) {
    PrimeRcSampleResult result = primeCompactTransmissionSampleRaw(
            wi,
            randomValue,
            primeCompactTransmissionClosureState(state),
            samplingFlags,
            stack);
    if (state.geometryThinWalled == 0u
            && primeRcIsTransmissive(result.bsdfSample.throughput.flags)) {
        if (state.entering != 0u) {
            PrimeRcVolume volume = state.transmissionVolume;
            volume.ior = state.originalIor;
            primeRcStackPush(result.volumeStack, volume);
        } else if (result.volumeStack.count > 0u) {
            result.rayT = 0.0;
            primeRcStackPop(result.volumeStack);
        }
    }
    return result;
}

public float3 primeCompactTransmissionEnergy<TransmissionGgxEnergy>(
        TransmissionGgxEnergy transmissionGgxEnergy,
        float3 wi,
        PrimeCompactTransmissionState state,
        uint samplingFlags)
    where TransmissionGgxEnergy : IPrimeRcTransmissionGgxEnergy
{
    if (!primeRcMicrofacetEffectivelySmooth(state.specularMicrofacet)
            && state.specularFresnel.ior != 1.0) {
        if (!primeRcIsSpecular(samplingFlags)) { return float3(0.0); }
    } else if (!primeRcIsDelta(samplingFlags)) {
        return float3(0.0);
    }
    float helperR = state.geometryThinWalled != 0u
            ? primeRcMicrofacetDielectricMsCompensation(
                    transmissionGgxEnergy,
                    state.specularMicrofacet,
                    abs(wi.z),
                    state.specularFresnel.ior).z
            : state.transmissionMultipleScattering.z;
    float reflected = primeRcIsReflective(samplingFlags) ? helperR : 0.0;
    float transmitted = primeRcIsTransmissive(samplingFlags) ? 1.0 - helperR : 0.0;
    return reflected * state.specularFresnel.color
            + transmitted * state.transmissionTint;
}
