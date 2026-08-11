#ifndef PRIME_ROBOCUTE_COMMON_SLANG
#define PRIME_ROBOCUTE_COMMON_SLANG

// Mechanical Slang port of the production-reachable declaration closure from
// robocute_bsdf_common.glsl at RoboCute commit 0d982c77b3fd26c2c5a3c0852be3bd05e5860bd8.

static const float PRIME_RC_PI = 3.14159265358979323846;

static const float PRIME_RC_INV_PI = 0.31830988618379067154;

static const float PRIME_RC_DENOM_TOLERANCE = 1.0e-10;

static const uint PRIME_RC_FLAG_NONE = 0u;

static const uint PRIME_RC_FLAG_DIFFUSE_REFLECTION = 1u << 0u;

static const uint PRIME_RC_FLAG_DIFFUSE_TRANSMISSION = 1u << 1u;

static const uint PRIME_RC_FLAG_SPECULAR_REFLECTION = 1u << 2u;

static const uint PRIME_RC_FLAG_SPECULAR_TRANSMISSION = 1u << 3u;

static const uint PRIME_RC_FLAG_DELTA_REFLECTION = 1u << 4u;

static const uint PRIME_RC_FLAG_DELTA_TRANSMISSION = 1u << 5u;

static const uint PRIME_RC_FLAG_DIFFUSE =
        PRIME_RC_FLAG_DIFFUSE_REFLECTION | PRIME_RC_FLAG_DIFFUSE_TRANSMISSION;

static const uint PRIME_RC_FLAG_SPECULAR =
        PRIME_RC_FLAG_SPECULAR_REFLECTION | PRIME_RC_FLAG_SPECULAR_TRANSMISSION;

static const uint PRIME_RC_FLAG_DELTA =
        PRIME_RC_FLAG_DELTA_REFLECTION | PRIME_RC_FLAG_DELTA_TRANSMISSION;

static const uint PRIME_RC_FLAG_REFLECTION = PRIME_RC_FLAG_DIFFUSE_REFLECTION
        | PRIME_RC_FLAG_SPECULAR_REFLECTION | PRIME_RC_FLAG_DELTA_REFLECTION;

static const uint PRIME_RC_FLAG_TRANSMISSION = PRIME_RC_FLAG_DIFFUSE_TRANSMISSION
        | PRIME_RC_FLAG_SPECULAR_TRANSMISSION | PRIME_RC_FLAG_DELTA_TRANSMISSION;

static const uint PRIME_RC_FLAG_ALL = PRIME_RC_FLAG_REFLECTION | PRIME_RC_FLAG_TRANSMISSION;

static const uint PRIME_RC_DETAIL_DEFAULT = 0u;

static const uint PRIME_RC_DETAIL_INDIRECT_DIFFUSE = 2u;

static const uint PRIME_RC_DIFFRACTION_RECTANGULAR = 1u;

static const uint PRIME_RC_MAX_VOLUME_STACK_SIZE = 2u;

struct PrimeRcOnb {
    float3 tangent;
    float3 bitangent;
    float3 normal;
};

struct PrimeRcWeight {
    float base;
    float diffuseRoughness;
    float specular;
    float metalness;
    float subsurface;
    float transmission;
    float coat;
    float fuzz;
    float thinFilm;
    float diffraction;
};

struct PrimeRcGeometry {
    uint thinWalled;
    float thickness;
    PrimeRcOnb onb;
};

struct PrimeRcSpecular {
    float3 color;
    float roughness;
    float roughnessAnisotropy;
    float ior;
};

struct PrimeRcEmission { float3 luminance; };

struct PrimeRcBase { float3 color; };

struct PrimeRcSubsurface {
    float3 color;
    float3 radius;
    float scatterAnisotropy;
};

struct PrimeRcTransmission {
    float3 color;
    float depth;
    float3 scatter;
    float scatterAnisotropy;
    float dispersionScale;
    float dispersionAbbeNumber;
};

struct PrimeRcCoat {
    float3 color;
    float roughness;
    float roughnessAnisotropy;
    float ior;
    float darkening;
    float roughening;
    PrimeRcOnb onb;
};

struct PrimeRcFuzz {
    float3 color;
    float roughness;
};

struct PrimeRcThinFilm {
    float thickness;
    float ior;
};

struct PrimeRcDiffraction {
    float3 color;
    float thickness;
    float2 invPitch;
    float angle;
    uint lobeCount;
    uint kind;
};

struct PrimeRcMaterial {
    PrimeRcWeight weight;
    PrimeRcGeometry geometry;
    PrimeRcSpecular specular;
    PrimeRcEmission emission;
    PrimeRcBase base;
    PrimeRcSubsurface subsurface;
    PrimeRcTransmission transmission;
    PrimeRcCoat coat;
    PrimeRcFuzz fuzz;
    PrimeRcThinFilm thinFilm;
    PrimeRcDiffraction diffraction;
};

struct PrimeRcThroughput {
    float3 value;
    uint flags;
};

struct PrimeRcSample {
    PrimeRcThroughput throughput;
    float pdf;
    float eta;
    float3 wo;
};

struct PrimeRcEval {
    PrimeRcThroughput throughput;
    float pdf;
};

struct PrimeRcFloatPair { float first; float second; };

struct PrimeRcVecPair { float3 first; float3 second; };

struct PrimeRcRefractResult {
    float3 wo;
    float relativeIor;
    uint valid;
};

struct PrimeRcMicrofacet { float2 alpha; };

struct PrimeRcSpecularFresnel {
    float ior;
    float energyIor;
    float3 color;
    float thinFilmWeight;
    float thinFilmThickness;
    float thinFilmIor;
};

struct PrimeRcConductorFresnel {
    float3 f0;
    // Preserves RoboCute's source field name. This is the relative Schlick tint at its 1/7
    // cosine anchor, not absolute F(82 degrees); adapters must solve it from a target reflectance.
    float3 f82;
    float3 f90;
    float weight;
    float3 energyF0;
    float thinFilmWeight;
    float thinFilmThickness;
    float thinFilmIor;
};

struct PrimeRcVolume {
    float3 extinction;
    float3 albedo;
    float anisotropy;
    float ior;
};

struct PrimeRcVolumeStack {
    PrimeRcVolume values[2];
    uint count;
};

struct PrimeRcSampleResult {
    PrimeRcSample bsdfSample;
    PrimeRcVolumeStack volumeStack;
    float rayT;
};

struct PrimeRcMixState {
    float firstSampleWeight;
    float secondSampleWeight;
};

struct PrimeRcLayerState {
    float3 coatTransIn;
    float subSampleWeight;
    float coatSampleWeight;
};

struct PrimeRcDiffractionState {
    float directionalAlbedo;
    float3 h;
    float2 angleCs;
};

struct PrimeRcState {
    PrimeRcMaterial material;
    uint detail;
    uint spectrumed;
    uint geometryThinWalled;
    uint entering;
    uint selectedWavelength;
    float3 wavelengthsNm;
    float rayT;
    float invOutIor;
    float originalIor;
    uint heroWavelengthIndex;
    uint samplingFlags;
    float3 randomValue;
    PrimeRcMicrofacet specularMicrofacet;
    PrimeRcSpecularFresnel specularFresnel;
    float3 specularMultipleScattering;
    PrimeRcConductorFresnel conductorFresnel;
    PrimeRcOnb coatLocalOnb;
    float conductorEss;
    PrimeRcMicrofacet transmissionMicrofacet;
    float3 transmissionTint;
    PrimeRcVolume transmissionVolume;
    float3 transmissionMultipleScattering;
    PrimeRcMicrofacet coatMicrofacet;
    float coatFresnelIor;
    float3 coatTint;
    float coatDarkening;
    float coatBaseRoughness;
    float3 coatMultipleScattering;
    PrimeRcDiffractionState diffractionState;
    PrimeRcLayerState basicGlossy;
    PrimeRcMixState basicMetal;
    PrimeRcLayerState subsurfaceGlossy;
    PrimeRcMixState mixedDiffuse;
    PrimeRcLayerState glossyDiffuse;
    PrimeRcMixState dielectricBase;
    PrimeRcMixState baseSubstrate;
    PrimeRcLayerState diffractionBase;
    PrimeRcLayerState coatedBase;
    PrimeRcLayerState surface;
};

bool primeRcIsReflective(uint flags) { return (flags & PRIME_RC_FLAG_REFLECTION) != 0u; }

bool primeRcIsTransmissive(uint flags) { return (flags & PRIME_RC_FLAG_TRANSMISSION) != 0u; }

bool primeRcIsDiffuse(uint flags) { return (flags & PRIME_RC_FLAG_DIFFUSE) != 0u; }

bool primeRcIsSpecular(uint flags) { return (flags & PRIME_RC_FLAG_SPECULAR) != 0u; }

bool primeRcIsDelta(uint flags) { return (flags & PRIME_RC_FLAG_DELTA) != 0u; }

bool primeRcIsNonDelta(uint flags) {
    return (flags & (PRIME_RC_FLAG_DIFFUSE | PRIME_RC_FLAG_SPECULAR)) != 0u;
}

PrimeRcThroughput primeRcZeroThroughput() {
    PrimeRcThroughput result;
    result.value = float3(0.0);
    result.flags = PRIME_RC_FLAG_NONE;
    return result;
}

PrimeRcSample primeRcZeroSample() {
    PrimeRcSample result;
    result.throughput = primeRcZeroThroughput();
    result.pdf = 0.0;
    result.eta = 1.0;
    result.wo = float3(0.0);
    return result;
}

PrimeRcSampleResult primeRcZeroSampleResult(PrimeRcState state, PrimeRcVolumeStack stack) {
    PrimeRcSampleResult result;
    result.bsdfSample = primeRcZeroSample();
    result.volumeStack = stack;
    result.rayT = state.rayT;
    return result;
}

PrimeRcThroughput primeRcThroughputAdd(PrimeRcThroughput a, PrimeRcThroughput b) {
    PrimeRcThroughput result;
    result.value = a.value + b.value;
    result.flags = a.flags | b.flags;
    return result;
}

PrimeRcThroughput primeRcThroughputScale(PrimeRcThroughput value, float3 scale) {
    value.value *= scale;
    if (all(scale == float3(0.0))) {
        value.flags = PRIME_RC_FLAG_NONE;
    }
    return value;
}

PrimeRcThroughput primeRcThroughputScale(PrimeRcThroughput value, float scale) {
    value.value *= scale;
    if (scale == 0.0) {
        value.flags = PRIME_RC_FLAG_NONE;
    }
    return value;
}

float primeRcSquare(float value) { return value * value; }

float2 primeRcSquare(float2 value) { return value * value; }

float3 primeRcSquare(float3 value) { return value * value; }

float primeRcPow4(float value) { float x = value * value; return x * x; }

float primeRcPow5(float value) { return primeRcPow4(value) * value; }

float primeRcPow6(float value) { float x = value * value * value; return x * x; }

float primeRcReduceSum(float2 value) { return value.x + value.y; }

float primeRcReduceSum(float3 value) { return value.x + value.y + value.z; }

float primeRcReduceMax(float2 value) { return max(value.x, value.y); }

float primeRcReduceMax(float3 value) { return max(value.x, max(value.y, value.z)); }

float primeRcReduceMin(float3 value) { return min(value.x, min(value.y, value.z)); }

float primeRcSpectrumToWeight(float3 value) { return primeRcReduceSum(value) / 3.0; }

PrimeRcOnb primeRcOnbFromNormal(float3 normal) {
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

float3 primeRcOnbToWorld(PrimeRcOnb onb, float3 value) {
    return value.x * onb.tangent + value.y * onb.bitangent + value.z * onb.normal;
}

float3 primeRcOnbToLocal(PrimeRcOnb onb, float3 value) {
    return float3(dot(onb.tangent, value), dot(onb.bitangent, value), dot(onb.normal, value));
}

float3 primeRcUniformSampleHemisphere(float2 sampleValue) {
    float sine = sqrt(1.0 - sampleValue.x * sampleValue.x);
    float phi = 2.0 * PRIME_RC_PI * sampleValue.y;
    return float3(sine * cos(phi), sine * sin(phi), sampleValue.x);
}

float3 primeRcCosineSampleHemisphere(float2 sampleValue) {
    float radius = sqrt(sampleValue.x);
    float phi = 2.0 * PRIME_RC_PI * sampleValue.y;
    return float3(radius * cos(phi), radius * sin(phi), sqrt(1.0 - sampleValue.x));
}

float primeRcF0ToIor(float f0) {
    float root = sqrt(clamp(f0, 0.0, 0.999));
    return (1.0 + root) / (1.0 - root);
}

float3 primeRcF0ToIor(float3 f0) {
    float3 root = sqrt(clamp(f0, float3(0.0), float3(0.999)));
    return (float3(1.0) + root) / (float3(1.0) - root);
}

float primeRcIorToF0(float ior) {
    return primeRcSquare((ior - 1.0) / (ior + 1.0));
}

float2 primeRcSpecularNdfRoughnesses(float roughness, float anisotropy) {
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

float primeRcRoughnessOverlap(float roughness, float coatRoughness, float weight) {
    if (weight == 0.0) {
        return roughness;
    }
    float coated = pow(min(1.0,
            primeRcPow4(roughness) + 2.0 * primeRcPow4(coatRoughness)), 0.25);
    return lerp(roughness, coated, weight);
}

float primeRcIorAdjustment(float ior, float coatIor, float coatWeight) {
    if (coatWeight == 0.0) {
        return ior;
    }
    float boundary = coatIor > ior ? coatIor / ior : ior / coatIor;
    return lerp(ior, boundary, coatWeight);
}

float primeRcIorAdjustment(float ior, float specularWeight) {
    float e = sign(ior - 1.0)
            * sqrt(clamp(specularWeight * primeRcIorToF0(ior), 0.0, 0.999));
    return (1.0 + e) / (1.0 - e);
}

float primeRcThinDielectricRoughnessScaler2(float ior) {
    float eta = ior >= 1.0 ? ior : 1.0 / ior;
    return 3.7 * (eta - 1.0) * primeRcSquare(eta - 0.5) / (eta * eta * eta);
}

float primeRcDispersionIor(float nd, float vd, float scale, float lambda) {
    const float lambdaC = 656.3;
    const float lambdaD = 587.6;
    const float lambdaF = 486.1;
    const float lambdaFC2 = 1.0
            / (1.0 / (lambdaF * lambdaF) - 1.0 / (lambdaC * lambdaC));
    float b = (nd - 1.0) * lambdaFC2 / max(0.1, vd) * scale;
    float a = nd - b / primeRcSquare(lambdaD);
    return a + b / primeRcSquare(lambda);
}

PrimeRcVolume primeRcVolumeFromTransmission(PrimeRcTransmission transmission) {
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

bool primeRcStackPush(inout PrimeRcVolumeStack stack, PrimeRcVolume volume) {
    if (stack.count >= PRIME_RC_MAX_VOLUME_STACK_SIZE) {
        return false;
    }
    stack.values[stack.count] = volume;
    stack.count++;
    return true;
}

bool primeRcStackPop(inout PrimeRcVolumeStack stack) {
    if (stack.count == 0u) {
        return false;
    }
    stack.count--;
    return true;
}

#endif
