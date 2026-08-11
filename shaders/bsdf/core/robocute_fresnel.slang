#language slang 2026
module "bsdf/core/robocute_fresnel.slang";

import "bsdf/core/robocute_common.slang";

// Mechanical Slang port of the production-reachable declaration closure from
// robocute_bsdf_fresnel.glsl at RoboCute commit 0d982c77b3fd26c2c5a3c0852be3bd05e5860bd8.

public PrimeRcFloatPair primeRcDielectricRsRp(float ior, float cosineTheta) {
    float eta = ior;
    if (cosineTheta < 0.0) {
        cosineTheta = -cosineTheta;
        eta = 1.0 / eta;
    }
    float t0 = cosineTheta * cosineTheta + eta * eta - 1.0;
    t0 = t0 > 0.0 ? sqrt(t0) / eta : 0.0;
    float t1 = eta * t0;
    float t2 = eta * cosineTheta;
    PrimeRcFloatPair result;
    result.first = (cosineTheta - t1) / (cosineTheta + t1);
    result.second = (t0 - t2) / (t0 + t2);
    return result;
}

public PrimeRcFloatPair primeRcDielectricReflectance(float ior, float cosineTheta) {
    PrimeRcFloatPair result = primeRcDielectricRsRp(ior, cosineTheta);
    result.first *= result.first;
    result.second *= result.second;
    return result;
}

public float primeRcDielectricUnpolarized(float ior, float cosineTheta) {
    PrimeRcFloatPair value = primeRcDielectricReflectance(ior, cosineTheta);
    return 0.5 * (value.first + value.second);
}

public PrimeRcRefractResult primeRcDielectricRefract(float ior, float3 wi, float3 normal) {
    float cosineTheta = dot(normal, wi);
    float inverseEta;
    if (cosineTheta < 0.0) {
        inverseEta = 1.0 / ior;
    } else {
        cosineTheta = -cosineTheta;
        normal = -normal;
        inverseEta = ior;
    }
    float t0 = 1.0 - (1.0 - cosineTheta * cosineTheta) * inverseEta * inverseEta;
    PrimeRcRefractResult result;
    result.valid = t0 > 0.0 ? 1u : 0u;
    result.relativeIor = 1.0 / inverseEta;
    result.wo = inverseEta * wi
            - (inverseEta * cosineTheta + sqrt(max(t0, 0.0))) * normal;
    return result;
}

float3 primeRcSchlickF82Tint(
        float3 f0, float3 f82, float3 f90, float weight, float cosineTheta) {
    const float cosineThetaMax = 1.0 / 7.0;
    const float cosineThetaFactor = 1.0
            / (cosineThetaMax * primeRcPow6(1.0 - cosineThetaMax));
    float3 a = lerp(f0, f90, primeRcPow5(1.0 - cosineThetaMax))
            * (float3(1.0) - f82) * cosineThetaFactor;
    return clamp((lerp(f0, f90, primeRcPow5(1.0 - cosineTheta))
            - a * cosineTheta * primeRcPow6(1.0 - cosineTheta)) * weight,
            float3(0.0), float3(1.0));
}

float2 primeRcComplexMultiply(float2 a, float2 b) {
    return float2(a.x * b.x - a.y * b.y, a.x * b.y + a.y * b.x);
}

float2 primeRcComplexConjugate(float2 value) { return float2(value.x, -value.y); }

float primeRcComplexMagnitude2(float2 value) { return dot(value, value); }

float2 primeRcComplexInverse(float2 value) {
    return primeRcComplexConjugate(value) / primeRcComplexMagnitude2(value);
}

float3 primeRcThinFilmSensitivity(float opticalPathDifference, float3 shift) {
    float phase = 2.0 * PRIME_RC_PI * opticalPathDifference;
    float3 value = float3(5.4856e-13, 4.4201e-13, 5.2481e-13);
    float3 position = float3(1.6810e+06, 1.7953e+06, 2.2084e+06);
    float3 variance = float3(4.3278e+09, 9.3046e+09, 6.6121e+09);
    float3 xyz = value * sqrt(2.0 * PRIME_RC_PI * variance)
            * cos(position * phase + shift) * exp(-variance * phase * phase);
    xyz.x += 9.7470e-14 * sqrt(2.0 * PRIME_RC_PI * 4.5282e+09)
            * cos(2.2399e+06 * phase + shift.x)
            * exp(-4.5282e+09 * phase * phase);
    return xyz / 1.0685e-7;
}

float3 primeRcXyzEToRec2020D65(float3 xyz) {
    return float3(
            dot(float3(1.668660, -0.432006, -0.236654), xyz),
            dot(float3(-0.690510, 1.673585, 0.016925), xyz),
            dot(float3(0.018993, -0.043268, 1.024276), xyz));
}

PrimeRcVecPair primeRcThinFilmReflectance(
        float thickness,
        float filmIor,
        float3 lambdaNm,
        uint spectrumed,
        float cosineTheta,
        PrimeRcVecPair baseReflectance,
        PrimeRcVecPair baseAmplitude,
        float3 baseEffectiveIor) {
    if (thickness <= 0.0) {
        return baseReflectance;
    }
    float cosineThetaBase = sqrt(
            1.0 - (1.0 - cosineTheta * cosineTheta) / (filmIor * filmIor));
    float distanceMeters = thickness * 1.0e-6;
    float opticalPathDifference = 2.0 * filmIor
            * abs(cosineThetaBase) * distanceMeters;
    float cosineBrewster = rsqrt(filmIor * filmIor + 1.0);
    float2 phi21 = float2(PRIME_RC_PI, abs(cosineTheta) < cosineBrewster
            ? 0.0 : PRIME_RC_PI);
    float3 phi23s = select(baseEffectiveIor < float3(filmIor), float3(PRIME_RC_PI), float3(0.0));
    float3 phi23p = phi23s;
    float3 perpendicular = float3(0.0);
    float3 parallel = float3(0.0);

    if (spectrumed != 0u) {
        PrimeRcFloatPair r12Pair = primeRcDielectricRsRp(filmIor, cosineTheta);
        float2 r12 = float2(r12Pair.first, r12Pair.second);
        float2 t12 = 1.0 - r12;
        PrimeRcFloatPair r21Pair = primeRcDielectricRsRp(filmIor, -cosineThetaBase);
        float2 r21 = float2(r21Pair.first, r21Pair.second);
        float2 t21 = 1.0 - r21;
        if (phi21.x > 0.5 * PRIME_RC_PI) { r21.x = -r21.x; }
        if (phi21.y > 0.5 * PRIME_RC_PI) { r21.y = -r21.y; }
        float3 r23s = baseAmplitude.first;
        float3 r23p = baseAmplitude.second;
        r23s = select(phi23s > float3(0.5 * PRIME_RC_PI), -r23s, r23s);
        r23p = select(phi23p > float3(0.5 * PRIME_RC_PI), -r23p, r23p);
        float3 phase = 2.0 * PRIME_RC_PI
                * (opticalPathDifference * 1.0e+9) / lambdaNm;
        for (int channel = 0; channel < 3; channel++) {
            float2 exponential = float2(cos(phase[channel]), sin(phase[channel]));
            float2 seriesS = primeRcComplexMultiply(
                    exponential,
                    primeRcComplexInverse(float2(1.0, 0.0)
                            - r21.x * r23s[channel] * exponential));
            float2 seriesP = primeRcComplexMultiply(
                    exponential,
                    primeRcComplexInverse(float2(1.0, 0.0)
                            - r21.y * r23p[channel] * exponential));
            perpendicular[channel] = primeRcComplexMagnitude2(
                    float2(r12.x, 0.0) + t12.x * r23s[channel] * t21.x * seriesS);
            parallel[channel] = primeRcComplexMagnitude2(
                    float2(r12.y, 0.0) + t12.y * r23p[channel] * t21.y * seriesP);
        }
    } else {
        PrimeRcFloatPair interfaceReflectance =
                primeRcDielectricReflectance(filmIor, cosineTheta);
        float2 r12 = float2(interfaceReflectance.first, interfaceReflectance.second);
        float2 t121 = 1.0 - r12;
        float3 r123s = sqrt(r12.x * baseReflectance.first);
        float3 r123p = sqrt(r12.y * baseReflectance.second);

        float3 rs = (t121.x * t121.x * baseReflectance.first)
                / (float3(1.0) - r12.x * baseReflectance.first);
        perpendicular += r12.x + rs;
        float3 cm = rs - t121.x;
        for (int order = 1; order <= 2; order++) {
            cm *= r123s;
            float3 sensitivity = 2.0 * primeRcThinFilmSensitivity(
                    float(order) * opticalPathDifference,
                    float(order) * (phi23s + float3(phi21.x)));
            perpendicular += cm * sensitivity;
        }

        float3 rp = (t121.y * t121.y * baseReflectance.second)
                / (float3(1.0) - r12.y * baseReflectance.second);
        parallel += r12.y + rp;
        cm = rp - t121.y;
        for (int order = 1; order <= 2; order++) {
            cm *= r123p;
            float3 sensitivity = 2.0 * primeRcThinFilmSensitivity(
                    float(order) * opticalPathDifference,
                    float(order) * (phi23p + float3(phi21.y)));
            parallel += cm * sensitivity;
        }
        perpendicular = primeRcXyzEToRec2020D65(perpendicular);
        parallel = primeRcXyzEToRec2020D65(parallel);
    }
    PrimeRcVecPair result;
    result.first = clamp(perpendicular, float3(0.0), float3(1.0));
    result.second = clamp(parallel, float3(0.0), float3(1.0));
    return result;
}

PrimeRcVecPair primeRcDielectricThinFilmReflectance(
        float thickness,
        float filmIor,
        float baseIor,
        float3 lambdaNm,
        uint spectrumed,
        float cosineTheta) {
    float cosineBase = thickness > 0.0
            ? sqrt(1.0 - (1.0 - cosineTheta * cosineTheta) / (filmIor * filmIor))
            : cosineTheta;
    PrimeRcFloatPair reflectanceScalar = primeRcDielectricReflectance(baseIor, cosineBase);
    PrimeRcFloatPair amplitudeScalar = primeRcDielectricRsRp(baseIor, cosineBase);
    PrimeRcVecPair reflectance;
    reflectance.first = float3(reflectanceScalar.first);
    reflectance.second = float3(reflectanceScalar.second);
    PrimeRcVecPair amplitude;
    amplitude.first = float3(amplitudeScalar.first);
    amplitude.second = float3(amplitudeScalar.second);
    return primeRcThinFilmReflectance(
            thickness, filmIor, lambdaNm, spectrumed, cosineTheta,
            reflectance, amplitude, float3(baseIor));
}

PrimeRcVecPair primeRcConductorThinFilmReflectance(
        float thickness,
        float filmIor,
        float3 f0,
        float3 f82,
        float3 f90,
        float weight,
        float3 lambdaNm,
        uint spectrumed,
        float cosineTheta) {
    float cosineBase = thickness > 0.0
            ? sqrt(1.0 - (1.0 - cosineTheta * cosineTheta) / (filmIor * filmIor))
            : cosineTheta;
    float3 value = primeRcSchlickF82Tint(f0, f82, f90, weight, cosineBase);
    PrimeRcVecPair reflectance;
    reflectance.first = value;
    reflectance.second = value;
    PrimeRcVecPair amplitude;
    amplitude.first = sqrt(value);
    amplitude.second = amplitude.first;
    return primeRcThinFilmReflectance(
            thickness, filmIor, lambdaNm, spectrumed, cosineTheta,
            reflectance, amplitude, primeRcF0ToIor(f0));
}

public float3 primeRcSpecularFresnelUnpolarized(
        PrimeRcSpecularFresnel fresnel,
        float3 lambdaNm,
        uint spectrumed,
        float cosineTheta) {
    float3 result = float3(0.0);
    if (fresnel.thinFilmWeight < 1.0) {
        result = float3(primeRcDielectricUnpolarized(fresnel.energyIor, cosineTheta));
    }
    if (fresnel.thinFilmWeight > 0.0) {
        PrimeRcVecPair film = primeRcDielectricThinFilmReflectance(
                fresnel.thinFilmThickness,
                fresnel.thinFilmIor,
                fresnel.energyIor,
                lambdaNm,
                spectrumed,
                cosineTheta);
        result *= 1.0 - fresnel.thinFilmWeight;
        result += fresnel.thinFilmWeight * 0.5 * (film.first + film.second);
    }
    return result * fresnel.color;
}

public PrimeRcVecPair primeRcSpecularFresnelRt(
        PrimeRcSpecularFresnel fresnel,
        float3 lambdaNm,
        uint spectrumed,
        float cosineTheta) {
    bool tint = true;
    if (cosineTheta < 0.0) {
        float t0 = 1.0 - (1.0 - cosineTheta * cosineTheta) * fresnel.ior * fresnel.ior;
        if (t0 > 0.0) {
            cosineTheta = sqrt(t0);
        } else {
            PrimeRcVecPair totalInternalReflection;
            totalInternalReflection.first = float3(1.0);
            totalInternalReflection.second = float3(0.0);
            return totalInternalReflection;
        }
        tint = false;
    }
    float3 reflection = float3(0.0);
    if (fresnel.thinFilmWeight < 1.0) {
        reflection = float3(primeRcDielectricUnpolarized(fresnel.energyIor, cosineTheta));
    }
    if (fresnel.thinFilmWeight > 0.0) {
        PrimeRcVecPair film = primeRcDielectricThinFilmReflectance(
                fresnel.thinFilmThickness,
                fresnel.thinFilmIor,
                fresnel.energyIor,
                lambdaNm,
                spectrumed,
                cosineTheta);
        reflection *= 1.0 - fresnel.thinFilmWeight;
        reflection += fresnel.thinFilmWeight * 0.5 * (film.first + film.second);
    }
    PrimeRcVecPair result;
    result.second = float3(1.0) - reflection;
    result.first = tint ? reflection * fresnel.color : reflection;
    return result;
}

public PrimeRcRefractResult primeRcSpecularFresnelRefract(
        PrimeRcSpecularFresnel fresnel, float3 wi, float3 normal) {
    return primeRcDielectricRefract(fresnel.ior, wi, normal);
}

public float3 primeRcConductorFresnelUnpolarized(
        PrimeRcConductorFresnel fresnel,
        float3 lambdaNm,
        uint spectrumed,
        float cosineTheta) {
    float3 result = float3(0.0);
    if (fresnel.thinFilmWeight < 1.0) {
        result = primeRcSchlickF82Tint(
                fresnel.f0, fresnel.f82, fresnel.f90, fresnel.weight, cosineTheta);
    }
    if (fresnel.thinFilmWeight > 0.0) {
        PrimeRcVecPair film = primeRcConductorThinFilmReflectance(
                fresnel.thinFilmThickness,
                fresnel.thinFilmIor,
                fresnel.f0,
                fresnel.f82,
                fresnel.f90,
                fresnel.weight,
                lambdaNm,
                spectrumed,
                cosineTheta);
        result *= 1.0 - fresnel.thinFilmWeight;
        result += fresnel.thinFilmWeight * 0.5 * (film.first + film.second);
    }
    return result;
}
