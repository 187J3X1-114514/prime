#language slang 2026
module "bsdf/compact/openpbr_transmission.slang";

import "bsdf/compact/openpbr_common.slang";
import "bsdf/compact/openpbr_fresnel.slang";
import "bsdf/compact/openpbr_microfacet.slang";
import "bsdf/core/transmission_ggx_energy.slang";

// Exact compact specialization for Prime's dielectric transmission materials.
// The state excludes the inactive OpenPBR composition graph and keeps medium
// mutation at the sample boundary.

static const float3 PRIME_COMPACT_TRANSMISSION_WAVELENGTHS_NM =
        float3(630.0, 532.0, 467.0);

public struct PrimeCompactTransmissionState {
    public PrimeOpenPbrOnb onb;
    public uint geometryThinWalled;
    public uint entering;
    public float rayT;
    public float originalIor;
    public float3 transmissionColor;
    public PrimeOpenPbrMicrofacet specularMicrofacet;
    public PrimeOpenPbrSpecularFresnel specularFresnel;
    public PrimeOpenPbrMicrofacet transmissionMicrofacet;
    public float3 transmissionTint;
    public PrimeOpenPbrVolume transmissionVolume;
    public float3 transmissionMultipleScattering;
};

public struct PrimeCompactTransmissionEvaluation {
    public PrimeOpenPbrThroughput throughput;
    public float pdf;
};

PrimeOpenPbrSampleResult primeCompactTransmissionZeroResult(
        PrimeCompactTransmissionState state,
        PrimeOpenPbrVolumeStack stack) {
    PrimeOpenPbrSampleResult result;
    result.bsdfSample = primeOpenPbrZeroSample();
    result.volumeStack = stack;
    result.rayT = state.rayT;
    return result;
}

float3 primeCompactGuardedThinWallSeriesReciprocal(float3 roundTrip) {
    return float3(1.0) / max(
            float3(PRIME_OPENPBR_DENOM_TOLERANCE),
            float3(1.0) - primeOpenPbrSquare(roundTrip));
}

PrimeOpenPbrVecPair primeCompactTransmissionThinWallRt(
        float cosineTheta,
        PrimeCompactTransmissionState state) {
    PrimeOpenPbrVecPair fresnel = primeOpenPbrSpecularFresnelRt(
            state.specularFresnel,
            PRIME_COMPACT_TRANSMISSION_WAVELENGTHS_NM,
            0u,
            cosineTheta);
    float t0 = 1.0 - (1.0 - cosineTheta * cosineTheta)
            / primeOpenPbrSquare(state.specularFresnel.ior);
    if (t0 <= 0.0) { return fresnel; }
    float cosineThetaI = sqrt(t0);
    float3 absorption = state.transmissionTint
            * exp(-state.transmissionVolume.extinction / cosineThetaI);
    float3 inverseSeries = primeCompactGuardedThinWallSeriesReciprocal(
            fresnel.first * absorption);
    PrimeOpenPbrVecPair result;
    result.first = fresnel.first
            * (float3(1.0) + primeOpenPbrSquare(fresnel.second * absorption) * inverseSeries);
    result.second = primeOpenPbrSquare(fresnel.second) * absorption * inverseSeries;
    return result;
}

float primeCompactMicrofacetG2r(
        PrimeOpenPbrMicrofacet microfacet,
        float3 wi,
        float3 wo) {
    if (wi.z < 0.0) {
        wi = -wi;
        wo = -wo;
    }
    return 1.0 / (primeOpenPbrMicrofacetLambda(microfacet, -wi)
            + primeOpenPbrMicrofacetLambda(microfacet, wo));
}

public float3 primeCompactTransmissionMaterialFrame(
        float3 w,
        PrimeCompactTransmissionState state) {
    return state.entering != 0u ? w : float3(-w.x, w.y, -w.z);
}

PrimeOpenPbrThroughput primeCompactRefractiveEval(
        float3 wi,
        float3 wo,
        PrimeCompactTransmissionState state,
        uint samplingFlags) {
    PrimeOpenPbrThroughput result = primeOpenPbrZeroThroughput();
    PrimeOpenPbrMicrofacet microfacet = state.specularMicrofacet;
    PrimeOpenPbrSpecularFresnel fresnel = state.specularFresnel;
    if (primeOpenPbrMicrofacetEffectivelySmooth(microfacet) || fresnel.ior == 1.0) {
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
    PrimeOpenPbrVecPair rt = primeOpenPbrSpecularFresnelRt(
            fresnel,
            PRIME_COMPACT_TRANSMISSION_WAVELENGTHS_NM,
            0u,
            microIncident);
    float distribution = primeOpenPbrMicrofacetD(microfacet, halfVector);
    if (reflected && (samplingFlags & PRIME_OPENPBR_FLAG_SPECULAR_REFLECTION) != 0u) {
        result.value = rt.first * distribution
                * primeCompactMicrofacetG2r(microfacet, wi, wo) / (4.0 * abs(wi.z));
        result.flags = PRIME_OPENPBR_FLAG_SPECULAR_REFLECTION;
    } else if (!reflected
            && (samplingFlags & PRIME_OPENPBR_FLAG_SPECULAR_TRANSMISSION) != 0u) {
        float denominator = primeOpenPbrSquare(microOutgoing + microIncident / etaPath) * wi.z;
        result.value = rt.second * distribution
                * primeOpenPbrMicrofacetG2t(microfacet, wi, wo)
                * abs(microIncident * microOutgoing / denominator);
        result.value *= 1.0 / primeOpenPbrSquare(etaPath);
        result.flags = PRIME_OPENPBR_FLAG_SPECULAR_TRANSMISSION;
    }
    return result;
}

PrimeOpenPbrSample primeCompactRefractiveSample(
        float3 wi,
        float3 randomValue,
        PrimeCompactTransmissionState state,
        uint samplingFlags) {
    PrimeOpenPbrMicrofacet microfacet = state.specularMicrofacet;
    PrimeOpenPbrSpecularFresnel fresnel = state.specularFresnel;
    float3 halfVector = float3(0.0, 0.0, 1.0);
    float microfacetPdf = 1.0;
    float distribution = 1.0;
    if (!primeOpenPbrMicrofacetEffectivelySmooth(microfacet) && fresnel.ior != 1.0) {
        if (!primeOpenPbrIsSpecular(samplingFlags)) {
            return primeOpenPbrZeroSample();
        }
        float3 sampleDirection = wi.z > 0.0 ? wi : -wi;
        halfVector = primeOpenPbrMicrofacetSample(microfacet, sampleDirection, randomValue.xy, false);
        microfacetPdf = primeOpenPbrMicrofacetPdf(
                microfacet, sampleDirection, halfVector, false);
        distribution = primeOpenPbrMicrofacetD(microfacet, halfVector);
    } else if (!primeOpenPbrIsDelta(samplingFlags)) {
        return primeOpenPbrZeroSample();
    }
    float microIncident = dot(halfVector, wi);
    PrimeOpenPbrVecPair rt = primeOpenPbrSpecularFresnelRt(
            fresnel,
            PRIME_COMPACT_TRANSMISSION_WAVELENGTHS_NM,
            0u,
            microIncident);
    float3 reflection = rt.first;
    float3 transmission = rt.second;
    float reflectionProbability = primeOpenPbrSpectrumToWeight(reflection);
    float transmissionProbability = primeOpenPbrSpectrumToWeight(transmission);
    if (!primeOpenPbrIsReflective(samplingFlags)) { reflectionProbability = 0.0; }
    if (!primeOpenPbrIsTransmissive(samplingFlags)) { transmissionProbability = 0.0; }
    PrimeOpenPbrRefractResult refracted = primeOpenPbrSpecularFresnelRefract(
            fresnel, -wi, halfVector);
    if (refracted.valid == 0u) {
        transmissionProbability = 0.0;
        reflection = float3(1.0);
    }
    float probabilitySum = reflectionProbability + transmissionProbability;
    if (probabilitySum == 0.0) {
        return primeOpenPbrZeroSample();
    }
    PrimeOpenPbrSample result = primeOpenPbrZeroSample();
    if (randomValue.z < transmissionProbability / probabilitySum) {
        float3 wo = refracted.wo;
        if (wi.z * wo.z >= 0.0) { return primeOpenPbrZeroSample(); }
        float etaPath = refracted.relativeIor;
        result.wo = wo;
        result.eta = etaPath;
        result.throughput.value = transmission;
        result.throughput.flags = PRIME_OPENPBR_FLAG_DELTA_TRANSMISSION;
        result.pdf = transmissionProbability / probabilitySum;
        if (!primeOpenPbrMicrofacetEffectivelySmooth(microfacet) && fresnel.ior != 1.0) {
            float microOutgoing = dot(wo, halfVector);
            float denominator = primeOpenPbrSquare(microOutgoing + microIncident / etaPath);
            result.pdf *= microfacetPdf * abs(microOutgoing) / denominator;
            result.throughput.value = transmission * distribution
                    * primeOpenPbrMicrofacetG2t(microfacet, wi, wo)
                    * abs(microIncident * microOutgoing / (wi.z * denominator));
            result.throughput.flags = PRIME_OPENPBR_FLAG_SPECULAR_TRANSMISSION;
        }
        result.throughput.value *= 1.0 / primeOpenPbrSquare(etaPath);
    } else {
        float3 wo = reflect(-wi, halfVector);
        if (wi.z * wo.z < 0.0) { return primeOpenPbrZeroSample(); }
        result.wo = wo;
        result.throughput.value = reflection;
        result.throughput.flags = PRIME_OPENPBR_FLAG_DELTA_REFLECTION;
        result.pdf = reflectionProbability / probabilitySum;
        if (!primeOpenPbrMicrofacetEffectivelySmooth(microfacet) && fresnel.ior != 1.0) {
            result.pdf *= microfacetPdf / (4.0 * abs(microIncident));
            result.throughput.value = reflection * distribution
                    * primeCompactMicrofacetG2r(microfacet, wi, wo) / abs(4.0 * wi.z);
            result.throughput.flags = PRIME_OPENPBR_FLAG_SPECULAR_REFLECTION;
        }
    }
    return result;
}

float primeCompactRefractivePdf(
        float3 wi,
        float3 wo,
        PrimeCompactTransmissionState state,
        uint samplingFlags) {
    PrimeOpenPbrMicrofacet microfacet = state.specularMicrofacet;
    PrimeOpenPbrSpecularFresnel fresnel = state.specularFresnel;
    if (primeOpenPbrMicrofacetEffectivelySmooth(microfacet)
            || fresnel.ior == 1.0 || !primeOpenPbrIsSpecular(samplingFlags)) {
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
    PrimeOpenPbrVecPair rt = primeOpenPbrSpecularFresnelRt(
            fresnel,
            PRIME_COMPACT_TRANSMISSION_WAVELENGTHS_NM,
            0u,
            microIncident);
    float reflectionProbability = primeOpenPbrSpectrumToWeight(rt.first);
    float transmissionProbability = primeOpenPbrSpectrumToWeight(rt.second);
    if (!primeOpenPbrIsReflective(samplingFlags)) { reflectionProbability = 0.0; }
    if (!primeOpenPbrIsTransmissive(samplingFlags)) { transmissionProbability = 0.0; }
    float probabilitySum = reflectionProbability + transmissionProbability;
    if (probabilitySum == 0.0) { return 0.0; }
    float microfacetPdf = primeOpenPbrMicrofacetPdf(
            microfacet, wi.z > 0.0 ? wi : -wi, halfVector, false);
    if (reflected) {
        return microfacetPdf / (4.0 * abs(microIncident))
                * reflectionProbability / probabilitySum;
    }
    float denominator = primeOpenPbrSquare(microOutgoing + microIncident / etaPath);
    return microfacetPdf * abs(microOutgoing) / denominator
            * transmissionProbability / probabilitySum;
}

PrimeOpenPbrThroughput primeCompactTransmissionEvalRaw(
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
            if (!primeOpenPbrIsTransmissive(samplingFlags)) {
                return primeOpenPbrZeroThroughput();
            }
        } else if (!primeOpenPbrIsReflective(samplingFlags)) {
            return primeOpenPbrZeroThroughput();
        }
        PrimeOpenPbrReflectiveEvalBase base = primeOpenPbrReflectiveEvalBase(
                wi, wo, state.transmissionMicrofacet,
                samplingFlags, PRIME_OPENPBR_FLAG_ALL);
        PrimeOpenPbrThroughput result;
        result.value = float3(base.factor);
        result.flags = base.flags;
        if (result.flags != PRIME_OPENPBR_FLAG_NONE) {
            PrimeOpenPbrVecPair rt = primeCompactTransmissionThinWallRt(
                    base.microCosine, state);
            if (transmitted) {
                result.value *= rt.second;
                result.flags = primeOpenPbrIsDelta(result.flags)
                        ? PRIME_OPENPBR_FLAG_DELTA_TRANSMISSION
                        : PRIME_OPENPBR_FLAG_SPECULAR_TRANSMISSION;
            } else {
                result.value *= rt.first;
            }
            if (primeOpenPbrIsNonDelta(result.flags)) {
                result.value /= primeOpenPbrMicrofacetDirectionalAlbedoUnity(
                        state.transmissionMicrofacet, wi.z);
            }
        }
        return result;
    }
    float3 materialWi = primeCompactTransmissionMaterialFrame(wi, state);
    float3 materialWo = primeCompactTransmissionMaterialFrame(inputWo, state);
    PrimeOpenPbrThroughput result = primeCompactRefractiveEval(
            materialWi, materialWo, state, samplingFlags);
    if (result.flags != PRIME_OPENPBR_FLAG_NONE) {
        if (primeOpenPbrIsTransmissive(result.flags)) {
            result.value *= state.transmissionTint;
        }
        if (primeOpenPbrIsNonDelta(result.flags)) {
            result.value *= primeOpenPbrIsTransmissive(result.flags)
                    ? state.transmissionMultipleScattering.y
                    : state.transmissionMultipleScattering.x;
        }
    }
    return result;
}

PrimeOpenPbrSampleResult primeCompactTransmissionSampleRaw(
        float3 wi,
        float3 randomValue,
        PrimeCompactTransmissionState state,
        uint samplingFlags,
        PrimeOpenPbrVolumeStack stack) {
    PrimeOpenPbrSampleResult result = primeCompactTransmissionZeroResult(state, stack);
    if (state.geometryThinWalled != 0u) {
        PrimeOpenPbrReflectiveSampleBase base = primeOpenPbrReflectiveSampleBase(
                wi, randomValue, state.transmissionMicrofacet,
                samplingFlags, PRIME_OPENPBR_FLAG_ALL);
        result.bsdfSample = base.bsdfSample;
        if (result.bsdfSample.throughput.flags != PRIME_OPENPBR_FLAG_NONE) {
            PrimeOpenPbrVecPair rt = primeCompactTransmissionThinWallRt(
                    base.microCosine, state);
            float reflectionProbability = primeOpenPbrSpectrumToWeight(rt.first);
            float transmissionProbability = primeOpenPbrSpectrumToWeight(rt.second);
            if (!primeOpenPbrIsReflective(samplingFlags)) { reflectionProbability = 0.0; }
            if (!primeOpenPbrIsTransmissive(samplingFlags)) { transmissionProbability = 0.0; }
            float probabilitySum = reflectionProbability + transmissionProbability;
            if (probabilitySum == 0.0) {
                result.bsdfSample = primeOpenPbrZeroSample();
                return result;
            }
            if (randomValue.z < transmissionProbability / probabilitySum) {
                result.bsdfSample.throughput.value *= rt.second;
                result.bsdfSample.pdf *= transmissionProbability / probabilitySum;
                result.bsdfSample.throughput.flags =
                        primeOpenPbrIsDelta(result.bsdfSample.throughput.flags)
                        ? PRIME_OPENPBR_FLAG_DELTA_TRANSMISSION
                        : PRIME_OPENPBR_FLAG_SPECULAR_TRANSMISSION;
                result.bsdfSample.wo.z = -result.bsdfSample.wo.z;
            } else {
                result.bsdfSample.throughput.value *= rt.first;
                result.bsdfSample.pdf *= reflectionProbability / probabilitySum;
            }
            if (primeOpenPbrIsNonDelta(result.bsdfSample.throughput.flags)) {
                result.bsdfSample.throughput.value /=
                        primeOpenPbrMicrofacetDirectionalAlbedoUnity(
                                state.transmissionMicrofacet, wi.z);
            }
        }
        return result;
    }

    float3 materialWi = primeCompactTransmissionMaterialFrame(wi, state);
    result.bsdfSample = primeCompactRefractiveSample(
            materialWi, randomValue, state, samplingFlags);
    if (result.bsdfSample.throughput.flags == PRIME_OPENPBR_FLAG_NONE) { return result; }
    result.bsdfSample.wo = primeCompactTransmissionMaterialFrame(
            result.bsdfSample.wo, state);
    if (primeOpenPbrIsTransmissive(result.bsdfSample.throughput.flags)) {
        result.bsdfSample.throughput.value *= state.transmissionTint;
    }
    if (primeOpenPbrIsNonDelta(result.bsdfSample.throughput.flags)) {
        result.bsdfSample.throughput.value *=
                primeOpenPbrIsTransmissive(result.bsdfSample.throughput.flags)
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
        PrimeOpenPbrVecPair rt = primeCompactTransmissionThinWallRt(
                dot(wi, halfVector), state);
        float reflectionProbability = primeOpenPbrSpectrumToWeight(rt.first);
        float transmissionProbability = primeOpenPbrSpectrumToWeight(rt.second);
        if (!primeOpenPbrIsReflective(samplingFlags)) { reflectionProbability = 0.0; }
        if (!primeOpenPbrIsTransmissive(samplingFlags)) { transmissionProbability = 0.0; }
        float probabilitySum = reflectionProbability + transmissionProbability;
        if (probabilitySum == 0.0) { return 0.0; }
        float microfacetPdf = primeOpenPbrReflectivePdf(
                wi, wo, state.transmissionMicrofacet,
                samplingFlags, PRIME_OPENPBR_FLAG_ALL);
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

public bool primeCompactTransmissionSupports(PrimeOpenPbrMaterial material) {
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
        PrimeOpenPbrMaterial material,
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
    state.specularMicrofacet.alpha = primeOpenPbrSpecularNdfRoughnesses(
            material.specular.roughness,
            material.specular.roughnessAnisotropy);
    state.specularFresnel.ior = material.specular.ior * inverseOutsideIor;
    state.specularFresnel.energyIor = primeOpenPbrIorAdjustment(
            state.specularFresnel.ior, material.weight.specular);
    state.specularFresnel.color = material.specular.color;
    state.transmissionTint = material.transmission.depth == 0.0
            ? material.transmission.color : float3(1.0);
    state.transmissionVolume = primeOpenPbrVolumeFromTransmission(material.transmission);
    state.transmissionMicrofacet.alpha = float2(0.0);
    state.transmissionMultipleScattering = float3(1.0, 1.0, 0.0);
    if (state.geometryThinWalled != 0u) {
        state.transmissionMicrofacet.alpha = clamp(
                state.specularMicrofacet.alpha
                * primeOpenPbrThinDielectricRoughnessScaler2(state.specularFresnel.ior),
                float2(0.0),
                float2(1.0));
    } else {
        float3 materialWi = primeCompactTransmissionMaterialFrame(wi, state);
        state.transmissionMultipleScattering = primeOpenPbrMicrofacetDielectricMsCompensation(
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
    state.transmissionMultipleScattering = primeOpenPbrMicrofacetDielectricMsCompensation(
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

public PrimeOpenPbrSampleResult primeCompactTransmissionSample(
        float3 wi,
        float3 randomValue,
        PrimeCompactTransmissionState state,
        uint samplingFlags,
        PrimeOpenPbrVolumeStack stack) {
    PrimeOpenPbrSampleResult result = primeCompactTransmissionSampleRaw(
            wi,
            randomValue,
            primeCompactTransmissionClosureState(state),
            samplingFlags,
            stack);
    if (state.geometryThinWalled == 0u
            && primeOpenPbrIsTransmissive(result.bsdfSample.throughput.flags)) {
        if (state.entering != 0u) {
            PrimeOpenPbrVolume volume = state.transmissionVolume;
            volume.ior = state.originalIor;
            primeOpenPbrStackPush(result.volumeStack, volume);
        } else if (result.volumeStack.count > 0u) {
            result.rayT = 0.0;
            primeOpenPbrStackPop(result.volumeStack);
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
    if (!primeOpenPbrMicrofacetEffectivelySmooth(state.specularMicrofacet)
            && state.specularFresnel.ior != 1.0) {
        if (!primeOpenPbrIsSpecular(samplingFlags)) { return float3(0.0); }
    } else if (!primeOpenPbrIsDelta(samplingFlags)) {
        return float3(0.0);
    }
    float helperR = state.geometryThinWalled != 0u
            ? primeOpenPbrMicrofacetDielectricMsCompensation(
                    transmissionGgxEnergy,
                    state.specularMicrofacet,
                    abs(wi.z),
                    state.specularFresnel.ior).z
            : state.transmissionMultipleScattering.z;
    float reflected = primeOpenPbrIsReflective(samplingFlags) ? helperR : 0.0;
    float transmitted = primeOpenPbrIsTransmissive(samplingFlags) ? 1.0 - helperR : 0.0;
    return reflected * state.specularFresnel.color
            + transmitted * state.transmissionTint;
}
