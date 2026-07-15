#ifndef PRIME_ROBOCUTE_BSDF_FRESNEL_GLSL
#define PRIME_ROBOCUTE_BSDF_FRESNEL_GLSL

#include "robocute_bsdf_common.glsl"

float primeRcDielectricEnergyFit(float eta) {
    return log((10893.0 * eta - 1438.2)
            / (-774.4 * eta * eta + 10212.0 * eta + 1.0));
}

PrimeRcFloatPair primeRcDielectricRsRp(float ior, float cosineTheta) {
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

PrimeRcFloatPair primeRcDielectricReflectance(float ior, float cosineTheta) {
    PrimeRcFloatPair result = primeRcDielectricRsRp(ior, cosineTheta);
    result.first *= result.first;
    result.second *= result.second;
    return result;
}

float primeRcDielectricUnpolarized(float ior, float cosineTheta) {
    PrimeRcFloatPair value = primeRcDielectricReflectance(ior, cosineTheta);
    return 0.5 * (value.first + value.second);
}

PrimeRcRefractResult primeRcDielectricRefract(float ior, vec3 wi, vec3 normal) {
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
    result.wo = inverseEta * wi
            - (inverseEta * cosineTheta + sqrt(max(t0, 0.0))) * normal;
    return result;
}

float primeRcDielectricEnergy(float ior) {
    if (ior > 1.0) {
        return primeRcDielectricEnergyFit(ior);
    }
    if (ior < 1.0) {
        return 1.0 - ior * ior * (1.0 - primeRcDielectricEnergyFit(1.0 / ior));
    }
    return 0.0;
}

vec3 primeRcSchlickF82Tint(
        vec3 f0, vec3 f82, vec3 f90, float weight, float cosineTheta) {
    const float cosineThetaMax = 1.0 / 7.0;
    const float cosineThetaFactor = 1.0
            / (cosineThetaMax * primeRcPow6(1.0 - cosineThetaMax));
    vec3 a = mix(f0, f90, primeRcPow5(1.0 - cosineThetaMax))
            * (vec3(1.0) - f82) * cosineThetaFactor;
    return clamp((mix(f0, f90, primeRcPow5(1.0 - cosineTheta))
            - a * cosineTheta * primeRcPow6(1.0 - cosineTheta)) * weight,
            vec3(0.0), vec3(1.0));
}

vec2 primeRcComplexMultiply(vec2 a, vec2 b) {
    return vec2(a.x * b.x - a.y * b.y, a.x * b.y + a.y * b.x);
}

vec2 primeRcComplexConjugate(vec2 value) { return vec2(value.x, -value.y); }
float primeRcComplexMagnitude2(vec2 value) { return dot(value, value); }
vec2 primeRcComplexInverse(vec2 value) {
    return primeRcComplexConjugate(value) / primeRcComplexMagnitude2(value);
}

vec3 primeRcThinFilmSensitivity(float opticalPathDifference, vec3 shift) {
    float phase = 2.0 * PRIME_RC_PI * opticalPathDifference;
    vec3 value = vec3(5.4856e-13, 4.4201e-13, 5.2481e-13);
    vec3 position = vec3(1.6810e+06, 1.7953e+06, 2.2084e+06);
    vec3 variance = vec3(4.3278e+09, 9.3046e+09, 6.6121e+09);
    vec3 xyz = value * sqrt(2.0 * PRIME_RC_PI * variance)
            * cos(position * phase + shift) * exp(-variance * phase * phase);
    xyz.x += 9.7470e-14 * sqrt(2.0 * PRIME_RC_PI * 4.5282e+09)
            * cos(2.2399e+06 * phase + shift.x)
            * exp(-4.5282e+09 * phase * phase);
    return xyz / 1.0685e-7;
}

vec3 primeRcXyzEToRec2020D65(vec3 xyz) {
    return vec3(
            dot(vec3(1.668660, -0.432006, -0.236654), xyz),
            dot(vec3(-0.690510, 1.673585, 0.016925), xyz),
            dot(vec3(0.018993, -0.043268, 1.024276), xyz));
}

PrimeRcVecPair primeRcThinFilmReflectance(
        float thickness,
        float filmIor,
        vec3 lambdaNm,
        uint spectrumed,
        float cosineTheta,
        PrimeRcVecPair baseReflectance,
        PrimeRcVecPair baseAmplitude,
        vec3 baseEffectiveIor) {
    if (thickness <= 0.0) {
        return baseReflectance;
    }
    float cosineThetaBase = sqrt(
            1.0 - (1.0 - cosineTheta * cosineTheta) / (filmIor * filmIor));
    float distanceMeters = thickness * 1.0e-6;
    float opticalPathDifference = 2.0 * filmIor
            * abs(cosineThetaBase) * distanceMeters;
    float cosineBrewster = inversesqrt(filmIor * filmIor + 1.0);
    vec2 phi21 = vec2(PRIME_RC_PI, abs(cosineTheta) < cosineBrewster
            ? 0.0 : PRIME_RC_PI);
    vec3 phi23s = mix(vec3(0.0), vec3(PRIME_RC_PI), lessThan(baseEffectiveIor, vec3(filmIor)));
    vec3 phi23p = phi23s;
    vec3 perpendicular = vec3(0.0);
    vec3 parallel = vec3(0.0);

    if (spectrumed != 0u) {
        PrimeRcFloatPair r12Pair = primeRcDielectricRsRp(filmIor, cosineTheta);
        vec2 r12 = vec2(r12Pair.first, r12Pair.second);
        vec2 t12 = 1.0 - r12;
        PrimeRcFloatPair r21Pair = primeRcDielectricRsRp(filmIor, -cosineThetaBase);
        vec2 r21 = vec2(r21Pair.first, r21Pair.second);
        vec2 t21 = 1.0 - r21;
        if (phi21.x > 0.5 * PRIME_RC_PI) { r21.x = -r21.x; }
        if (phi21.y > 0.5 * PRIME_RC_PI) { r21.y = -r21.y; }
        vec3 r23s = baseAmplitude.first;
        vec3 r23p = baseAmplitude.second;
        r23s = mix(r23s, -r23s, greaterThan(phi23s, vec3(0.5 * PRIME_RC_PI)));
        r23p = mix(r23p, -r23p, greaterThan(phi23p, vec3(0.5 * PRIME_RC_PI)));
        vec3 phase = 2.0 * PRIME_RC_PI
                * (opticalPathDifference * 1.0e+9) / lambdaNm;
        for (int channel = 0; channel < 3; channel++) {
            vec2 exponential = vec2(cos(phase[channel]), sin(phase[channel]));
            vec2 seriesS = primeRcComplexMultiply(
                    exponential,
                    primeRcComplexInverse(vec2(1.0, 0.0)
                            - r21.x * r23s[channel] * exponential));
            vec2 seriesP = primeRcComplexMultiply(
                    exponential,
                    primeRcComplexInverse(vec2(1.0, 0.0)
                            - r21.y * r23p[channel] * exponential));
            perpendicular[channel] = primeRcComplexMagnitude2(
                    vec2(r12.x, 0.0) + t12.x * r23s[channel] * t21.x * seriesS);
            parallel[channel] = primeRcComplexMagnitude2(
                    vec2(r12.y, 0.0) + t12.y * r23p[channel] * t21.y * seriesP);
        }
    } else {
        PrimeRcFloatPair interfaceReflectance =
                primeRcDielectricReflectance(filmIor, cosineTheta);
        vec2 r12 = vec2(interfaceReflectance.first, interfaceReflectance.second);
        vec2 t121 = 1.0 - r12;
        vec3 r123s = sqrt(r12.x * baseReflectance.first);
        vec3 r123p = sqrt(r12.y * baseReflectance.second);

        vec3 rs = (t121.x * t121.x * baseReflectance.first)
                / (vec3(1.0) - r12.x * baseReflectance.first);
        perpendicular += r12.x + rs;
        vec3 cm = rs - t121.x;
        for (int order = 1; order <= 2; order++) {
            cm *= r123s;
            vec3 sensitivity = 2.0 * primeRcThinFilmSensitivity(
                    float(order) * opticalPathDifference,
                    float(order) * (phi23s + vec3(phi21.x)));
            perpendicular += cm * sensitivity;
        }

        vec3 rp = (t121.y * t121.y * baseReflectance.second)
                / (vec3(1.0) - r12.y * baseReflectance.second);
        parallel += r12.y + rp;
        cm = rp - t121.y;
        for (int order = 1; order <= 2; order++) {
            cm *= r123p;
            vec3 sensitivity = 2.0 * primeRcThinFilmSensitivity(
                    float(order) * opticalPathDifference,
                    float(order) * (phi23p + vec3(phi21.y)));
            parallel += cm * sensitivity;
        }
        perpendicular = primeRcXyzEToRec2020D65(perpendicular);
        parallel = primeRcXyzEToRec2020D65(parallel);
    }
    PrimeRcVecPair result;
    result.first = clamp(perpendicular, vec3(0.0), vec3(1.0));
    result.second = clamp(parallel, vec3(0.0), vec3(1.0));
    return result;
}

PrimeRcVecPair primeRcDielectricThinFilmReflectance(
        float thickness,
        float filmIor,
        float baseIor,
        vec3 lambdaNm,
        uint spectrumed,
        float cosineTheta) {
    float cosineBase = thickness > 0.0
            ? sqrt(1.0 - (1.0 - cosineTheta * cosineTheta) / (filmIor * filmIor))
            : cosineTheta;
    PrimeRcFloatPair reflectanceScalar = primeRcDielectricReflectance(baseIor, cosineBase);
    PrimeRcFloatPair amplitudeScalar = primeRcDielectricRsRp(baseIor, cosineBase);
    PrimeRcVecPair reflectance;
    reflectance.first = vec3(reflectanceScalar.first);
    reflectance.second = vec3(reflectanceScalar.second);
    PrimeRcVecPair amplitude;
    amplitude.first = vec3(amplitudeScalar.first);
    amplitude.second = vec3(amplitudeScalar.second);
    return primeRcThinFilmReflectance(
            thickness, filmIor, lambdaNm, spectrumed, cosineTheta,
            reflectance, amplitude, vec3(baseIor));
}

PrimeRcVecPair primeRcConductorThinFilmReflectance(
        float thickness,
        float filmIor,
        vec3 f0,
        vec3 f82,
        vec3 f90,
        float weight,
        vec3 lambdaNm,
        uint spectrumed,
        float cosineTheta) {
    float cosineBase = thickness > 0.0
            ? sqrt(1.0 - (1.0 - cosineTheta * cosineTheta) / (filmIor * filmIor))
            : cosineTheta;
    vec3 value = primeRcSchlickF82Tint(f0, f82, f90, weight, cosineBase);
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

vec3 primeRcSpecularFresnelUnpolarized(
        PrimeRcSpecularFresnel fresnel,
        vec3 lambdaNm,
        uint spectrumed,
        float cosineTheta) {
    vec3 result = vec3(0.0);
    if (fresnel.thinFilmWeight < 1.0) {
        result = vec3(primeRcDielectricUnpolarized(fresnel.energyIor, cosineTheta));
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

PrimeRcVecPair primeRcSpecularFresnelRt(
        PrimeRcSpecularFresnel fresnel,
        vec3 lambdaNm,
        uint spectrumed,
        float cosineTheta) {
    bool tint = true;
    if (cosineTheta < 0.0) {
        float t0 = 1.0 - (1.0 - cosineTheta * cosineTheta) * fresnel.ior * fresnel.ior;
        if (t0 > 0.0) {
            cosineTheta = sqrt(t0);
        } else {
            PrimeRcVecPair totalInternalReflection;
            totalInternalReflection.first = vec3(1.0);
            totalInternalReflection.second = vec3(0.0);
            return totalInternalReflection;
        }
        tint = false;
    }
    vec3 reflection = vec3(0.0);
    if (fresnel.thinFilmWeight < 1.0) {
        reflection = vec3(primeRcDielectricUnpolarized(fresnel.energyIor, cosineTheta));
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
    result.second = vec3(1.0) - reflection;
    result.first = tint ? reflection * fresnel.color : reflection;
    return result;
}

PrimeRcRefractResult primeRcSpecularFresnelRefract(
        PrimeRcSpecularFresnel fresnel, vec3 wi, vec3 normal) {
    return primeRcDielectricRefract(fresnel.ior, wi, normal);
}

vec3 primeRcConductorFresnelUnpolarized(
        PrimeRcConductorFresnel fresnel,
        vec3 lambdaNm,
        uint spectrumed,
        float cosineTheta) {
    vec3 result = vec3(0.0);
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

#endif
