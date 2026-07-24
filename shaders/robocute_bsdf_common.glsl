#ifndef PRIME_ROBOCUTE_BSDF_COMMON_GLSL
#define PRIME_ROBOCUTE_BSDF_COMMON_GLSL

// Direct GLSL port of RoboCute's Apache-2.0 BSDF data model. This namespace deliberately uses
// its own throughput convention: value is f(wi, wo) * abs(wo.z), exactly as in the source library,
// and a sampled path multiplier is value / pdf. Do not interchange it with Prime's legacy
// BsdfEvaluation without an explicit convention adapter.

const float PRIME_RC_PI = 3.14159265358979323846;
const float PRIME_RC_INV_PI = 0.31830988618379067154;
const float PRIME_RC_DENOM_TOLERANCE = 1.0e-10;

const uint PRIME_RC_FLAG_NONE = 0u;
const uint PRIME_RC_FLAG_DIFFUSE_REFLECTION = 1u << 0u;
const uint PRIME_RC_FLAG_DIFFUSE_TRANSMISSION = 1u << 1u;
const uint PRIME_RC_FLAG_SPECULAR_REFLECTION = 1u << 2u;
const uint PRIME_RC_FLAG_SPECULAR_TRANSMISSION = 1u << 3u;
const uint PRIME_RC_FLAG_DELTA_REFLECTION = 1u << 4u;
const uint PRIME_RC_FLAG_DELTA_TRANSMISSION = 1u << 5u;
const uint PRIME_RC_FLAG_DIFFUSE =
        PRIME_RC_FLAG_DIFFUSE_REFLECTION | PRIME_RC_FLAG_DIFFUSE_TRANSMISSION;
const uint PRIME_RC_FLAG_SPECULAR =
        PRIME_RC_FLAG_SPECULAR_REFLECTION | PRIME_RC_FLAG_SPECULAR_TRANSMISSION;
const uint PRIME_RC_FLAG_DELTA =
        PRIME_RC_FLAG_DELTA_REFLECTION | PRIME_RC_FLAG_DELTA_TRANSMISSION;
const uint PRIME_RC_FLAG_REFLECTION = PRIME_RC_FLAG_DIFFUSE_REFLECTION
        | PRIME_RC_FLAG_SPECULAR_REFLECTION | PRIME_RC_FLAG_DELTA_REFLECTION;
const uint PRIME_RC_FLAG_TRANSMISSION = PRIME_RC_FLAG_DIFFUSE_TRANSMISSION
        | PRIME_RC_FLAG_SPECULAR_TRANSMISSION | PRIME_RC_FLAG_DELTA_TRANSMISSION;
const uint PRIME_RC_FLAG_ALL = PRIME_RC_FLAG_REFLECTION | PRIME_RC_FLAG_TRANSMISSION;

const uint PRIME_RC_DETAIL_DEFAULT = 0u;
const uint PRIME_RC_DETAIL_INDIRECT_SPECULAR = 1u;
const uint PRIME_RC_DETAIL_INDIRECT_DIFFUSE = 2u;
const uint PRIME_RC_DIFFRACTION_SINUSOIDAL = 0u;
const uint PRIME_RC_DIFFRACTION_RECTANGULAR = 1u;
const uint PRIME_RC_DIFFRACTION_LINEAR = 2u;
const uint PRIME_RC_MAX_DIFFRACTION_LOBES = 7u;
const uint PRIME_RC_MAX_VOLUME_STACK_SIZE = 2u;

struct PrimeRcOnb {
    vec3 tangent;
    vec3 bitangent;
    vec3 normal;
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
    vec3 color;
    float roughness;
    float roughnessAnisotropy;
    float ior;
};

struct PrimeRcEmission { vec3 luminance; };
struct PrimeRcBase { vec3 color; };

struct PrimeRcSubsurface {
    vec3 color;
    vec3 radius;
    float scatterAnisotropy;
};

struct PrimeRcTransmission {
    vec3 color;
    float depth;
    vec3 scatter;
    float scatterAnisotropy;
    float dispersionScale;
    float dispersionAbbeNumber;
};

struct PrimeRcCoat {
    vec3 color;
    float roughness;
    float roughnessAnisotropy;
    float ior;
    float darkening;
    float roughening;
    PrimeRcOnb onb;
};

struct PrimeRcFuzz {
    vec3 color;
    float roughness;
};

struct PrimeRcThinFilm {
    float thickness;
    float ior;
};

struct PrimeRcDiffraction {
    vec3 color;
    float thickness;
    vec2 invPitch;
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
    vec3 value;
    uint flags;
};

struct PrimeRcSample {
    PrimeRcThroughput throughput;
    float pdf;
    vec3 wo;
};

struct PrimeRcEval {
    PrimeRcThroughput throughput;
    float pdf;
};

struct PrimeRcFloatPair { float first; float second; };
struct PrimeRcVecPair { vec3 first; vec3 second; };

struct PrimeRcRefractResult {
    vec3 wo;
    uint valid;
};

struct PrimeRcMicrofacet { vec2 alpha; };

struct PrimeRcSpecularFresnel {
    float ior;
    float energyIor;
    vec3 color;
    float thinFilmWeight;
    float thinFilmThickness;
    float thinFilmIor;
};

struct PrimeRcConductorFresnel {
    vec3 f0;
    // Preserves RoboCute's source field name. This is the relative Schlick tint at its 1/7
    // cosine anchor, not absolute F(82 degrees); adapters must solve it from a target reflectance.
    vec3 f82;
    vec3 f90;
    float weight;
    vec3 energyF0;
    float thinFilmWeight;
    float thinFilmThickness;
    float thinFilmIor;
};

struct PrimeRcVolume {
    vec3 extinction;
    vec3 albedo;
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
    vec3 coatTransIn;
    float subSampleWeight;
    float coatSampleWeight;
};

struct PrimeRcDiffractionState {
    float directionalAlbedo;
    vec3 h;
    vec2 angleCs;
};

struct PrimeRcState {
    PrimeRcMaterial material;
    uint detail;
    uint spectrumed;
    uint geometryThinWalled;
    uint selectedWavelength;
    vec3 wavelengthsNm;
    float rayT;
    float invOutIor;
    float originalIor;
    uint heroWavelengthIndex;
    uint samplingFlags;
    vec3 randomValue;
    PrimeRcMicrofacet specularMicrofacet;
    PrimeRcSpecularFresnel specularFresnel;
    vec3 specularMultipleScattering;
    PrimeRcConductorFresnel conductorFresnel;
    PrimeRcOnb coatLocalOnb;
    float conductorEss;
    PrimeRcMicrofacet transmissionMicrofacet;
    vec3 transmissionTint;
    PrimeRcVolume transmissionVolume;
    vec3 transmissionMultipleScattering;
    PrimeRcMicrofacet coatMicrofacet;
    float coatFresnelIor;
    vec3 coatTint;
    float coatDarkening;
    float coatBaseRoughness;
    vec3 coatMultipleScattering;
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
bool primeRcIsNonDiffuse(uint flags) {
    return (flags & (PRIME_RC_FLAG_DELTA | PRIME_RC_FLAG_SPECULAR)) != 0u;
}
bool primeRcIsSpecular(uint flags) { return (flags & PRIME_RC_FLAG_SPECULAR) != 0u; }
bool primeRcIsDelta(uint flags) { return (flags & PRIME_RC_FLAG_DELTA) != 0u; }
bool primeRcIsNonDelta(uint flags) {
    return (flags & (PRIME_RC_FLAG_DIFFUSE | PRIME_RC_FLAG_SPECULAR)) != 0u;
}

PrimeRcThroughput primeRcZeroThroughput() {
    PrimeRcThroughput result;
    result.value = vec3(0.0);
    result.flags = PRIME_RC_FLAG_NONE;
    return result;
}

PrimeRcSample primeRcZeroSample() {
    PrimeRcSample result;
    result.throughput = primeRcZeroThroughput();
    result.pdf = 0.0;
    result.wo = vec3(0.0);
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

PrimeRcThroughput primeRcThroughputScale(PrimeRcThroughput value, vec3 scale) {
    value.value *= scale;
    if (all(equal(scale, vec3(0.0)))) {
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
vec2 primeRcSquare(vec2 value) { return value * value; }
vec3 primeRcSquare(vec3 value) { return value * value; }
float primeRcPow4(float value) { float x = value * value; return x * x; }
float primeRcPow5(float value) { return primeRcPow4(value) * value; }
float primeRcPow6(float value) { float x = value * value * value; return x * x; }
float primeRcReduceSum(vec2 value) { return value.x + value.y; }
float primeRcReduceSum(vec3 value) { return value.x + value.y + value.z; }
float primeRcReduceMax(vec2 value) { return max(value.x, value.y); }
float primeRcReduceMax(vec3 value) { return max(value.x, max(value.y, value.z)); }
float primeRcReduceMin(vec3 value) { return min(value.x, min(value.y, value.z)); }
float primeRcSpectrumToWeight(vec3 value) { return primeRcReduceSum(value) / 3.0; }

PrimeRcOnb primeRcOnbFromNormal(vec3 normal) {
    float signZ = normal.z >= 0.0 ? 1.0 : -1.0;
    float a = -1.0 / (signZ + normal.z);
    float b = normal.x * normal.y * a;
    PrimeRcOnb result;
    result.tangent = vec3(
            1.0 + signZ * normal.x * normal.x * a,
            signZ * b,
            -signZ * normal.x);
    result.bitangent = vec3(b, signZ + normal.y * normal.y * a, -normal.y);
    result.normal = normal;
    return result;
}

vec3 primeRcOnbToWorld(PrimeRcOnb onb, vec3 value) {
    return value.x * onb.tangent + value.y * onb.bitangent + value.z * onb.normal;
}

vec3 primeRcOnbToLocal(PrimeRcOnb onb, vec3 value) {
    return vec3(dot(onb.tangent, value), dot(onb.bitangent, value), dot(onb.normal, value));
}

PrimeRcOnb primeRcOnbRotate(PrimeRcOnb onb, float angle) {
    vec2 cs = vec2(cos(angle), sin(angle));
    PrimeRcOnb result;
    result.tangent = onb.tangent * cs.x - onb.bitangent * cs.y;
    result.bitangent = onb.tangent * cs.y + onb.bitangent * cs.x;
    result.normal = onb.normal;
    return result;
}

vec3 primeRcUniformSampleHemisphere(vec2 sampleValue) {
    float sine = sqrt(1.0 - sampleValue.x * sampleValue.x);
    float phi = 2.0 * PRIME_RC_PI * sampleValue.y;
    return vec3(sine * cos(phi), sine * sin(phi), sampleValue.x);
}

vec3 primeRcCosineSampleHemisphere(vec2 sampleValue) {
    float radius = sqrt(sampleValue.x);
    float phi = 2.0 * PRIME_RC_PI * sampleValue.y;
    return vec3(radius * cos(phi), radius * sin(phi), sqrt(1.0 - sampleValue.x));
}

vec3 primeRcUniformSampleSphere(vec2 sampleValue) {
    float phi = 2.0 * PRIME_RC_PI * sampleValue.x;
    float cosine = sampleValue.y * 2.0 - 1.0;
    float sine = sqrt(1.0 - cosine * cosine);
    return vec3(sine * cos(phi), sine * sin(phi), cosine);
}

float primeRcF0ToIor(float f0) {
    float root = sqrt(clamp(f0, 0.0, 0.999));
    return (1.0 + root) / (1.0 - root);
}

vec3 primeRcF0ToIor(vec3 f0) {
    vec3 root = sqrt(clamp(f0, vec3(0.0), vec3(0.999)));
    return (vec3(1.0) + root) / (vec3(1.0) - root);
}

float primeRcIorToF0(float ior) {
    return primeRcSquare((ior - 1.0) / (ior + 1.0));
}

vec2 primeRcSpecularNdfRoughnesses(float roughness, float anisotropy) {
    vec2 alpha;
    if (anisotropy == 0.0) {
        return vec2(primeRcSquare(roughness));
    }
    alpha.x = primeRcSquare(roughness)
            * sqrt(2.0 / (1.0 + primeRcSquare(1.0 - anisotropy)));
    alpha.y = (1.0 - anisotropy) * alpha.x;
    if (primeRcReduceMax(alpha) < 1.0e-4) {
        return vec2(0.0);
    }
    return max(alpha, vec2(1.0e-4));
}

float primeRcRoughnessOverlap(float roughness, float coatRoughness, float weight) {
    if (weight == 0.0) {
        return roughness;
    }
    float coated = pow(min(1.0,
            primeRcPow4(roughness) + 2.0 * primeRcPow4(coatRoughness)), 0.25);
    return mix(roughness, coated, weight);
}

float primeRcIorAdjustment(float ior, float coatIor, float coatWeight) {
    if (coatWeight == 0.0) {
        return ior;
    }
    float boundary = coatIor > ior ? coatIor / ior : ior / coatIor;
    return mix(ior, boundary, coatWeight);
}

float primeRcIorAdjustment(float ior, float specularWeight) {
    float e = sign(ior - 1.0)
            * sqrt(clamp(specularWeight * primeRcIorToF0(ior), 0.0, 0.999));
    return (1.0 + e) / (1.0 - e);
}

vec3 primeRcCoatViewDependentAbsorption(
        vec3 color, float mu, float ior, float modifier) {
    float muT = sqrt(max(1.0e-4, 1.0 - (1.0 - primeRcSquare(mu)) / primeRcSquare(ior)));
    return pow(color, vec3(1.0 / muT + modifier));
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
        vec3 muT = -log(max(vec3(1.0e-3), transmission.color)) / transmission.depth;
        vec3 muS = transmission.scatter / transmission.depth;
        vec3 muA = muT - muS;
        muA -= vec3(min(0.0, primeRcReduceMin(muA)));
        result.extinction = muA + muS;
        result.albedo = vec3(
                result.extinction.x != 0.0 ? muS.x / result.extinction.x : 0.0,
                result.extinction.y != 0.0 ? muS.y / result.extinction.y : 0.0,
                result.extinction.z != 0.0 ? muS.z / result.extinction.z : 0.0);
        result.anisotropy = clamp(transmission.scatterAnisotropy, -0.99, 0.99);
    } else {
        result.extinction = vec3(0.0);
        result.albedo = vec3(0.0);
        result.anisotropy = 0.0;
    }
    result.ior = 1.0;
    return result;
}

PrimeRcVolume primeRcVolumeFromSubsurface(PrimeRcSubsurface subsurface) {
    PrimeRcVolume result;
    float g = clamp(subsurface.scatterAnisotropy, -0.95, 0.95);
    vec3 a = subsurface.color;
    vec3 s = 4.09712 + 4.20863 * a
            - sqrt(9.59217 + 41.6808 * a + 17.7126 * primeRcSquare(a));
    vec3 s2 = primeRcSquare(s);
    result.extinction = 1.0 / max(vec3(3.0e-4), subsurface.radius);
    result.albedo = (1.0 - s2) / max(1.0 - g * s2, vec3(1.0e-6));
    result.anisotropy = g;
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
