#language slang 2026
module "bsdf/core/robocute_common.slang";

// Mechanical Slang port of the production-reachable declaration closure from
// robocute_bsdf_common.glsl at RoboCute commit 0d982c77b3fd26c2c5a3c0852be3bd05e5860bd8.

public static const float PRIME_RC_PI = 3.14159265358979323846;

public static const float PRIME_RC_INV_PI = 0.31830988618379067154;

public static const float PRIME_RC_DENOM_TOLERANCE = 1.0e-10;

public static const uint PRIME_RC_FLAG_NONE = 0u;

public static const uint PRIME_RC_FLAG_DIFFUSE_REFLECTION = 1u << 0u;

public static const uint PRIME_RC_FLAG_DIFFUSE_TRANSMISSION = 1u << 1u;

public static const uint PRIME_RC_FLAG_SPECULAR_REFLECTION = 1u << 2u;

public static const uint PRIME_RC_FLAG_SPECULAR_TRANSMISSION = 1u << 3u;

public static const uint PRIME_RC_FLAG_DELTA_REFLECTION = 1u << 4u;

public static const uint PRIME_RC_FLAG_DELTA_TRANSMISSION = 1u << 5u;

public static const uint PRIME_RC_FLAG_DIFFUSE =
        PRIME_RC_FLAG_DIFFUSE_REFLECTION | PRIME_RC_FLAG_DIFFUSE_TRANSMISSION;

public static const uint PRIME_RC_FLAG_SPECULAR =
        PRIME_RC_FLAG_SPECULAR_REFLECTION | PRIME_RC_FLAG_SPECULAR_TRANSMISSION;

public static const uint PRIME_RC_FLAG_DELTA =
        PRIME_RC_FLAG_DELTA_REFLECTION | PRIME_RC_FLAG_DELTA_TRANSMISSION;

public static const uint PRIME_RC_FLAG_REFLECTION = PRIME_RC_FLAG_DIFFUSE_REFLECTION
        | PRIME_RC_FLAG_SPECULAR_REFLECTION | PRIME_RC_FLAG_DELTA_REFLECTION;

public static const uint PRIME_RC_FLAG_TRANSMISSION = PRIME_RC_FLAG_DIFFUSE_TRANSMISSION
        | PRIME_RC_FLAG_SPECULAR_TRANSMISSION | PRIME_RC_FLAG_DELTA_TRANSMISSION;

public static const uint PRIME_RC_FLAG_ALL = PRIME_RC_FLAG_REFLECTION | PRIME_RC_FLAG_TRANSMISSION;

public static const uint PRIME_RC_DETAIL_DEFAULT = 0u;

public static const uint PRIME_RC_DETAIL_INDIRECT_DIFFUSE = 2u;

public static const uint PRIME_RC_DIFFRACTION_RECTANGULAR = 1u;

public static const uint PRIME_RC_MAX_VOLUME_STACK_SIZE = 2u;

public struct PrimeRcOnb {
    public float3 tangent;
    public float3 bitangent;
    public float3 normal;
};

public struct PrimeRcWeight {
    public float base;
    public float diffuseRoughness;
    public float specular;
    public float metalness;
    public float subsurface;
    public float transmission;
    public float coat;
    public float fuzz;
    public float thinFilm;
    public float diffraction;
};

public struct PrimeRcGeometry {
    public uint thinWalled;
    public float thickness;
    public PrimeRcOnb onb;
};

public struct PrimeRcSpecular {
    public float3 color;
    public float roughness;
    public float roughnessAnisotropy;
    public float ior;
};

public struct PrimeRcEmission { public float3 luminance; };

public struct PrimeRcBase { public float3 color; };

public struct PrimeRcSubsurface {
    public float3 color;
    public float3 radius;
    public float scatterAnisotropy;
};

public struct PrimeRcTransmission {
    public float3 color;
    public float depth;
    public float3 scatter;
    public float scatterAnisotropy;
    public float dispersionScale;
    public float dispersionAbbeNumber;
};

public struct PrimeRcCoat {
    public float3 color;
    public float roughness;
    public float roughnessAnisotropy;
    public float ior;
    public float darkening;
    public float roughening;
    public PrimeRcOnb onb;
};

public struct PrimeRcFuzz {
    public float3 color;
    public float roughness;
};

public struct PrimeRcThinFilm {
    public float thickness;
    public float ior;
};

public struct PrimeRcDiffraction {
    public float3 color;
    public float thickness;
    public float2 invPitch;
    public float angle;
    public uint lobeCount;
    public uint kind;
};

public struct PrimeRcMaterial {
    public PrimeRcWeight weight;
    public PrimeRcGeometry geometry;
    public PrimeRcSpecular specular;
    public PrimeRcEmission emission;
    public PrimeRcBase base;
    public PrimeRcSubsurface subsurface;
    public PrimeRcTransmission transmission;
    public PrimeRcCoat coat;
    public PrimeRcFuzz fuzz;
    public PrimeRcThinFilm thinFilm;
    public PrimeRcDiffraction diffraction;
};

public struct PrimeRcThroughput {
    public float3 value;
    public uint flags;
};

public struct PrimeRcSample {
    public PrimeRcThroughput throughput;
    public float pdf;
    public float eta;
    public float3 wo;
};

public struct PrimeRcEval {
    public PrimeRcThroughput throughput;
    public float pdf;
};

public struct PrimeRcFloatPair { public float first; public float second; };

public struct PrimeRcVecPair { public float3 first; public float3 second; };

public struct PrimeRcRefractResult {
    public float3 wo;
    public float relativeIor;
    public uint valid;
};

public struct PrimeRcMicrofacet { public float2 alpha; };

public struct PrimeRcSpecularFresnel {
    public float ior;
    public float energyIor;
    public float3 color;
    public float thinFilmWeight;
    public float thinFilmThickness;
    public float thinFilmIor;
};

public struct PrimeRcConductorFresnel {
    public float3 f0;
    // Preserves RoboCute's source field name. This is the relative Schlick tint at its 1/7
    // cosine anchor, not absolute F(82 degrees); adapters must solve it from a target reflectance.
    public float3 f82;
    public float3 f90;
    public float weight;
    public float3 energyF0;
    public float thinFilmWeight;
    public float thinFilmThickness;
    public float thinFilmIor;
};

public struct PrimeRcVolume {
    public float3 extinction;
    public float3 albedo;
    public float anisotropy;
    public float ior;
};

public struct PrimeRcVolumeStack {
    public PrimeRcVolume values[2];
    public uint count;
};

public struct PrimeRcSampleResult {
    public PrimeRcSample bsdfSample;
    public PrimeRcVolumeStack volumeStack;
    public float rayT;
};

public struct PrimeRcMixState {
    public float firstSampleWeight;
    public float secondSampleWeight;
};

public struct PrimeRcLayerState {
    public float3 coatTransIn;
    public float subSampleWeight;
    public float coatSampleWeight;
};

public struct PrimeRcDiffractionState {
    public float directionalAlbedo;
    public float3 h;
    public float2 angleCs;
};

public struct PrimeRcState {
    public PrimeRcMaterial material;
    public uint detail;
    public uint spectrumed;
    public uint geometryThinWalled;
    public uint entering;
    public uint selectedWavelength;
    public float3 wavelengthsNm;
    public float rayT;
    public float invOutIor;
    public float originalIor;
    public uint heroWavelengthIndex;
    public uint samplingFlags;
    public float3 randomValue;
    public PrimeRcMicrofacet specularMicrofacet;
    public PrimeRcSpecularFresnel specularFresnel;
    public float3 specularMultipleScattering;
    public PrimeRcConductorFresnel conductorFresnel;
    public PrimeRcOnb coatLocalOnb;
    public float conductorEss;
    public PrimeRcMicrofacet transmissionMicrofacet;
    public float3 transmissionTint;
    public PrimeRcVolume transmissionVolume;
    public float3 transmissionMultipleScattering;
    public PrimeRcMicrofacet coatMicrofacet;
    public float coatFresnelIor;
    public float3 coatTint;
    public float coatDarkening;
    public float coatBaseRoughness;
    public float3 coatMultipleScattering;
    public PrimeRcDiffractionState diffractionState;
    public PrimeRcLayerState basicGlossy;
    public PrimeRcMixState basicMetal;
    public PrimeRcLayerState subsurfaceGlossy;
    public PrimeRcMixState mixedDiffuse;
    public PrimeRcLayerState glossyDiffuse;
    public PrimeRcMixState dielectricBase;
    public PrimeRcMixState baseSubstrate;
    public PrimeRcLayerState diffractionBase;
    public PrimeRcLayerState coatedBase;
    public PrimeRcLayerState surface;
};

public bool primeRcIsReflective(uint flags) { return (flags & PRIME_RC_FLAG_REFLECTION) != 0u; }

public bool primeRcIsTransmissive(uint flags) { return (flags & PRIME_RC_FLAG_TRANSMISSION) != 0u; }

public bool primeRcIsDiffuse(uint flags) { return (flags & PRIME_RC_FLAG_DIFFUSE) != 0u; }

public bool primeRcIsSpecular(uint flags) { return (flags & PRIME_RC_FLAG_SPECULAR) != 0u; }

public bool primeRcIsDelta(uint flags) { return (flags & PRIME_RC_FLAG_DELTA) != 0u; }

public bool primeRcIsNonDelta(uint flags) {
    return (flags & (PRIME_RC_FLAG_DIFFUSE | PRIME_RC_FLAG_SPECULAR)) != 0u;
}

public PrimeRcThroughput primeRcZeroThroughput() {
    PrimeRcThroughput result;
    result.value = float3(0.0);
    result.flags = PRIME_RC_FLAG_NONE;
    return result;
}

public PrimeRcSample primeRcZeroSample() {
    PrimeRcSample result;
    result.throughput = primeRcZeroThroughput();
    result.pdf = 0.0;
    result.eta = 1.0;
    result.wo = float3(0.0);
    return result;
}

public PrimeRcSampleResult primeRcZeroSampleResult(PrimeRcState state, PrimeRcVolumeStack stack) {
    PrimeRcSampleResult result;
    result.bsdfSample = primeRcZeroSample();
    result.volumeStack = stack;
    result.rayT = state.rayT;
    return result;
}

public PrimeRcThroughput primeRcThroughputAdd(PrimeRcThroughput a, PrimeRcThroughput b) {
    PrimeRcThroughput result;
    result.value = a.value + b.value;
    result.flags = a.flags | b.flags;
    return result;
}

public PrimeRcThroughput primeRcThroughputScale(PrimeRcThroughput value, float3 scale) {
    value.value *= scale;
    if (all(scale == float3(0.0))) {
        value.flags = PRIME_RC_FLAG_NONE;
    }
    return value;
}

public PrimeRcThroughput primeRcThroughputScale(PrimeRcThroughput value, float scale) {
    value.value *= scale;
    if (scale == 0.0) {
        value.flags = PRIME_RC_FLAG_NONE;
    }
    return value;
}

public float primeRcSquare(float value) { return value * value; }

public float2 primeRcSquare(float2 value) { return value * value; }

public float3 primeRcSquare(float3 value) { return value * value; }

public float primeRcPow4(float value) { float x = value * value; return x * x; }

public float primeRcPow5(float value) { return primeRcPow4(value) * value; }

public float primeRcPow6(float value) { float x = value * value * value; return x * x; }

public float primeRcReduceSum(float2 value) { return value.x + value.y; }

public float primeRcReduceSum(float3 value) { return value.x + value.y + value.z; }

public float primeRcReduceMax(float2 value) { return max(value.x, value.y); }

public float primeRcReduceMax(float3 value) { return max(value.x, max(value.y, value.z)); }

public float primeRcReduceMin(float3 value) { return min(value.x, min(value.y, value.z)); }

public float primeRcSpectrumToWeight(float3 value) { return primeRcReduceSum(value) / 3.0; }

public PrimeRcOnb primeRcOnbFromNormal(float3 normal) {
    float signZ = normal.z >= 0.0 ? 1.0 : -1.0;
    float a = -1.0 / (signZ + normal.z);
    float b = normal.x * normal.y * a;
    PrimeRcOnb result;
    result.tangent = float3(
            1.0 + signZ * normal.x * normal.x * a,
            signZ * b,
            -signZ * normal.x);
    result.bitangent = float3(b, signZ + normal.y * normal.y * a, -normal.y);
    result.normal = normal;
    return result;
}

public float3 primeRcOnbToWorld(PrimeRcOnb onb, float3 value) {
    return value.x * onb.tangent + value.y * onb.bitangent + value.z * onb.normal;
}

public float3 primeRcOnbToLocal(PrimeRcOnb onb, float3 value) {
    return float3(dot(onb.tangent, value), dot(onb.bitangent, value), dot(onb.normal, value));
}

public float3 primeRcUniformSampleHemisphere(float2 sampleValue) {
    float sine = sqrt(1.0 - sampleValue.x * sampleValue.x);
    float phi = 2.0 * PRIME_RC_PI * sampleValue.y;
    return float3(sine * cos(phi), sine * sin(phi), sampleValue.x);
}

public float3 primeRcCosineSampleHemisphere(float2 sampleValue) {
    float radius = sqrt(sampleValue.x);
    float phi = 2.0 * PRIME_RC_PI * sampleValue.y;
    return float3(radius * cos(phi), radius * sin(phi), sqrt(1.0 - sampleValue.x));
}

public float primeRcF0ToIor(float f0) {
    float root = sqrt(clamp(f0, 0.0, 0.999));
    return (1.0 + root) / (1.0 - root);
}

public float3 primeRcF0ToIor(float3 f0) {
    float3 root = sqrt(clamp(f0, float3(0.0), float3(0.999)));
    return (float3(1.0) + root) / (float3(1.0) - root);
}

public float primeRcIorToF0(float ior) {
    return primeRcSquare((ior - 1.0) / (ior + 1.0));
}

public float2 primeRcSpecularNdfRoughnesses(float roughness, float anisotropy) {
    float2 alpha;
    if (anisotropy == 0.0) {
        return float2(primeRcSquare(roughness));
    }
    alpha.x = primeRcSquare(roughness)
            * sqrt(2.0 / (1.0 + primeRcSquare(1.0 - anisotropy)));
    alpha.y = (1.0 - anisotropy) * alpha.x;
    if (primeRcReduceMax(alpha) < 1.0e-4) {
        return float2(0.0);
    }
    return max(alpha, float2(1.0e-4));
}

public float primeRcRoughnessOverlap(float roughness, float coatRoughness, float weight) {
    if (weight == 0.0) {
        return roughness;
    }
    float coated = pow(min(1.0,
            primeRcPow4(roughness) + 2.0 * primeRcPow4(coatRoughness)), 0.25);
    return lerp(roughness, coated, weight);
}

public float primeRcIorAdjustment(float ior, float coatIor, float coatWeight) {
    if (coatWeight == 0.0) {
        return ior;
    }
    float boundary = coatIor > ior ? coatIor / ior : ior / coatIor;
    return lerp(ior, boundary, coatWeight);
}

public float primeRcIorAdjustment(float ior, float specularWeight) {
    float e = sign(ior - 1.0)
            * sqrt(clamp(specularWeight * primeRcIorToF0(ior), 0.0, 0.999));
    return (1.0 + e) / (1.0 - e);
}

public float primeRcThinDielectricRoughnessScaler2(float ior) {
    float eta = ior >= 1.0 ? ior : 1.0 / ior;
    return 3.7 * (eta - 1.0) * primeRcSquare(eta - 0.5) / (eta * eta * eta);
}

public float primeRcDispersionIor(float nd, float vd, float scale, float lambda) {
    const float lambdaC = 656.3;
    const float lambdaD = 587.6;
    const float lambdaF = 486.1;
    const float lambdaFC2 = 1.0
            / (1.0 / (lambdaF * lambdaF) - 1.0 / (lambdaC * lambdaC));
    float b = (nd - 1.0) * lambdaFC2 / max(0.1, vd) * scale;
    float a = nd - b / primeRcSquare(lambdaD);
    return a + b / primeRcSquare(lambda);
}

public PrimeRcVolume primeRcVolumeFromTransmission(PrimeRcTransmission transmission) {
    PrimeRcVolume result;
    if (transmission.depth > 0.0) {
        float3 muT = -log(max(float3(1.0e-3), transmission.color)) / transmission.depth;
        float3 muS = transmission.scatter / transmission.depth;
        float3 muA = muT - muS;
        muA -= float3(min(0.0, primeRcReduceMin(muA)));
        result.extinction = muA + muS;
        result.albedo = float3(
                result.extinction.x != 0.0 ? muS.x / result.extinction.x : 0.0,
                result.extinction.y != 0.0 ? muS.y / result.extinction.y : 0.0,
                result.extinction.z != 0.0 ? muS.z / result.extinction.z : 0.0);
        result.anisotropy = clamp(transmission.scatterAnisotropy, -0.99, 0.99);
    } else {
        result.extinction = float3(0.0);
        result.albedo = float3(0.0);
        result.anisotropy = 0.0;
    }
    result.ior = 1.0;
    return result;
}

public bool primeRcStackPush(inout PrimeRcVolumeStack stack, PrimeRcVolume volume) {
    if (stack.count >= PRIME_RC_MAX_VOLUME_STACK_SIZE) {
        return false;
    }
    stack.values[stack.count] = volume;
    stack.count++;
    return true;
}

public bool primeRcStackPop(inout PrimeRcVolumeStack stack) {
    if (stack.count == 0u) {
        return false;
    }
    stack.count--;
    return true;
}
