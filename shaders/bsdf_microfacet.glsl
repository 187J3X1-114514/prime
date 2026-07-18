#ifndef PRIME_BSDF_MICROFACET_GLSL
#define PRIME_BSDF_MICROFACET_GLSL

#include "bsdf_common.glsl"
#include "bsdf_fresnel.glsl"

const float PRIME_GGX_DELTA_ALPHA = 1.0e-4;

// LabPBR stores perceptual smoothness. This is the only sanctioned conversion to the GGX alpha
// parameter: alpha=(1-smoothness)^2. Downstream closures consume alpha directly and must never
// square it again.
float primeLabPbrSmoothnessToGgxAlpha(float perceptualSmoothness) {
    float perceptualRoughness = 1.0 - clamp(perceptualSmoothness, 0.0, 1.0);
    return perceptualRoughness * perceptualRoughness;
}

bool primeGgxIsDelta(float alpha) {
    return alpha <= PRIME_GGX_DELTA_ALPHA;
}

float primeGgxD(float alpha, vec3 microNormal) {
    if (microNormal.z <= 0.0) {
        return 0.0;
    }
    float a = max(alpha, PRIME_GGX_DELTA_ALPHA);
    float a2 = a * a;
    float cosine2 = microNormal.z * microNormal.z;
    float denominator = cosine2 * (a2 - 1.0) + 1.0;
    // This denominator reaches alpha^4 at the specular peak. PRIME_BSDF_EPSILON is suitable for
    // geometric divisions but is many orders of magnitude too large here: at perceptual
    // roughness 0.1 it already clips D by about 3x, and at 0.02 it erases nearly the entire peak.
    // alpha is bounded above, so the expression remains finite without an unrelated epsilon.
    return a2 / (PRIME_PI * denominator * denominator);
}

float primeGgxLambda(float alpha, vec3 direction) {
    float cosine2 = direction.z * direction.z;
    if (cosine2 <= PRIME_BSDF_EPSILON) {
        return 1.0e20;
    }
    float tangent2 = max(0.0, 1.0 - cosine2) / cosine2;
    return 0.5 * (sqrt(1.0 + alpha * alpha * tangent2) - 1.0);
}

float primeGgxG1(float alpha, vec3 direction) {
    return 1.0 / (1.0 + primeGgxLambda(alpha, direction));
}

float primeGgxG2(float alpha, vec3 first, vec3 second) {
    return 1.0 / (1.0 + primeGgxLambda(alpha, first) + primeGgxLambda(alpha, second));
}

// Heitz visible-normal sampling. Sampling the VNDF rather than the bare NDF prevents the grazing
// angle fireflies produced by a mismatched D*cos(theta) proposal.
vec3 primeSampleGgxVisibleNormal(vec3 viewDirection, float alpha, vec2 sampleValue) {
    float a = max(alpha, PRIME_GGX_DELTA_ALPHA);
    vec3 stretchedView = normalize(vec3(a * viewDirection.xy, viewDirection.z));
    float lensq = dot(stretchedView.xy, stretchedView.xy);
    vec3 tangent = lensq > 0.0
            ? vec3(-stretchedView.y, stretchedView.x, 0.0) * inversesqrt(lensq)
            : vec3(1.0, 0.0, 0.0);
    vec3 bitangent = cross(stretchedView, tangent);
    float radius = sqrt(sampleValue.x);
    float azimuth = 2.0 * PRIME_PI * sampleValue.y;
    float diskX = radius * cos(azimuth);
    float diskY = radius * sin(azimuth);
    float blend = 0.5 * (1.0 + stretchedView.z);
    diskY = mix(sqrt(max(0.0, 1.0 - diskX * diskX)), diskY, blend);
    vec3 visibleNormal = diskX * tangent + diskY * bitangent
            + sqrt(max(0.0, 1.0 - diskX * diskX - diskY * diskY)) * stretchedView;
    return normalize(vec3(a * visibleNormal.xy, max(visibleNormal.z, 0.0)));
}

float primeGgxVisibleNormalPdf(vec3 viewDirection, vec3 microNormal, float alpha) {
    if (viewDirection.z <= 0.0 || microNormal.z <= 0.0) {
        return 0.0;
    }
    return primeGgxD(alpha, microNormal) * primeGgxG1(alpha, viewDirection)
            * max(dot(viewDirection, microNormal), 0.0) / viewDirection.z;
}

BsdfEvaluation primeEvaluateGgxReflectionBase(
        float alpha,
        vec3 normal,
        vec3 viewDirection,
        vec3 scatterDirection,
        out vec3 microNormal) {
    BsdfEvaluation result = primeInvalidBsdfEvaluation();
    microNormal = normal;
    if (primeGgxIsDelta(alpha)) {
        return result;
    }
    vec3 localView = primeWorldToLocal(viewDirection, normal);
    vec3 localScatter = primeWorldToLocal(scatterDirection, normal);
    if (localView.z <= 0.0 || localScatter.z <= 0.0) {
        return result;
    }
    vec3 sumDirection = localView + localScatter;
    if (dot(sumDirection, sumDirection) <= PRIME_BSDF_EPSILON) {
        return result;
    }
    vec3 localMicroNormal = normalize(sumDirection);
    if (localMicroNormal.z <= 0.0 || dot(localView, localMicroNormal) <= 0.0) {
        return result;
    }
    float distribution = primeGgxD(alpha, localMicroNormal);
    float masking = primeGgxG2(alpha, localView, localScatter);
    float denominator = 4.0 * localView.z * localScatter.z;
    result.value = vec3(distribution * masking / max(denominator, PRIME_BSDF_EPSILON));
    float microNormalPdf = primeGgxVisibleNormalPdf(localView, localMicroNormal, alpha);
    result.pdf = microNormalPdf
            / max(4.0 * abs(dot(localView, localMicroNormal)), PRIME_BSDF_EPSILON);
    microNormal = primeLocalToWorld(localMicroNormal, normal);
    return result;
}

bool primeSampleGgxReflectionDirection(
        float alpha,
        vec3 normal,
        vec3 viewDirection,
        vec2 sampleValue,
        out vec3 microNormal,
        out vec3 scatterDirection) {
    vec3 localView = primeWorldToLocal(viewDirection, normal);
    if (localView.z <= 0.0 || primeGgxIsDelta(alpha)) {
        microNormal = normal;
        scatterDirection = vec3(0.0);
        return false;
    }
    vec3 localMicroNormal = primeSampleGgxVisibleNormal(localView, alpha, sampleValue);
    vec3 localScatter = reflect(-localView, localMicroNormal);
    if (localScatter.z <= 0.0) {
        microNormal = normal;
        scatterDirection = vec3(0.0);
        return false;
    }
    microNormal = primeLocalToWorld(localMicroNormal, normal);
    scatterDirection = primeLocalToWorld(localScatter, normal);
    return true;
}

BsdfEvaluation primeEvaluateGgxDielectricReflection(
        float relativeIor,
        float alpha,
        vec3 normal,
        vec3 viewDirection,
        vec3 scatterDirection) {
    vec3 microNormal;
    BsdfEvaluation result = primeEvaluateGgxReflectionBase(
            alpha, normal, viewDirection, scatterDirection, microNormal);
    if (result.pdf <= 0.0) {
        return result;
    }
    float fresnel = primeFresnelDielectric(
            dot(viewDirection, microNormal), 1.0, relativeIor);
    result.value *= fresnel;
    return result;
}

BsdfSample primeSampleGgxDielectricReflection(
        float relativeIor,
        float alpha,
        vec3 normal,
        vec3 viewDirection,
        vec2 sampleValue) {
    if (dot(normal, viewDirection) <= 0.0) {
        return primeInvalidBsdfSample();
    }
    if (primeGgxIsDelta(alpha)) {
        BsdfSample result;
        result.direction = reflect(-viewDirection, normal);
        result.weight = vec3(primeFresnelDielectric(
                dot(normal, viewDirection), 1.0, relativeIor));
        result.pdf = 1.0;
        result.relativeEta = 1.0;
        result.eventFlags = PRIME_BSDF_EVENT_REFLECTION | PRIME_BSDF_EVENT_DELTA;
        return result;
    }
    vec3 microNormal;
    vec3 scatterDirection;
    if (!primeSampleGgxReflectionDirection(
            alpha, normal, viewDirection, sampleValue, microNormal, scatterDirection)) {
        return primeInvalidBsdfSample();
    }
    BsdfEvaluation evaluation = primeEvaluateGgxDielectricReflection(
            relativeIor, alpha, normal, viewDirection, scatterDirection);
    BsdfSample result;
    result.direction = scatterDirection;
    result.pdf = evaluation.pdf;
    result.weight = evaluation.value
            * (max(dot(normal, scatterDirection), 0.0) / max(result.pdf, PRIME_BSDF_EPSILON));
    result.relativeEta = 1.0;
    result.eventFlags = PRIME_BSDF_EVENT_REFLECTION | PRIME_BSDF_EVENT_GLOSSY;
    return result;
}

BsdfEvaluation primeEvaluateGgxConductorF0(
        vec3 f0,
        float alpha,
        vec3 normal,
        vec3 viewDirection,
        vec3 scatterDirection) {
    vec3 microNormal;
    BsdfEvaluation result = primeEvaluateGgxReflectionBase(
            alpha, normal, viewDirection, scatterDirection, microNormal);
    if (result.pdf > 0.0) {
        result.value *= primeFresnelSchlick(clamp(f0, vec3(0.0), vec3(1.0)),
                dot(viewDirection, microNormal));
    }
    return result;
}

BsdfSample primeSampleGgxConductorF0(
        vec3 f0,
        float alpha,
        vec3 normal,
        vec3 viewDirection,
        vec2 sampleValue) {
    if (dot(normal, viewDirection) <= 0.0) {
        return primeInvalidBsdfSample();
    }
    if (primeGgxIsDelta(alpha)) {
        BsdfSample result;
        result.direction = reflect(-viewDirection, normal);
        result.weight = primeFresnelSchlick(
                clamp(f0, vec3(0.0), vec3(1.0)), dot(normal, viewDirection));
        result.pdf = 1.0;
        result.relativeEta = 1.0;
        result.eventFlags = PRIME_BSDF_EVENT_REFLECTION | PRIME_BSDF_EVENT_DELTA;
        return result;
    }
    vec3 microNormal;
    vec3 scatterDirection;
    if (!primeSampleGgxReflectionDirection(
            alpha, normal, viewDirection, sampleValue, microNormal, scatterDirection)) {
        return primeInvalidBsdfSample();
    }
    BsdfEvaluation evaluation = primeEvaluateGgxConductorF0(
            f0, alpha, normal, viewDirection, scatterDirection);
    BsdfSample result;
    result.direction = scatterDirection;
    result.pdf = evaluation.pdf;
    result.weight = evaluation.value
            * (max(dot(normal, scatterDirection), 0.0) / max(result.pdf, PRIME_BSDF_EPSILON));
    result.relativeEta = 1.0;
    result.eventFlags = PRIME_BSDF_EVENT_REFLECTION | PRIME_BSDF_EVENT_GLOSSY;
    return result;
}

BsdfEvaluation primeEvaluateGgxConductorComplex(
        vec3 eta,
        vec3 k,
        float alpha,
        vec3 normal,
        vec3 viewDirection,
        vec3 scatterDirection) {
    vec3 microNormal;
    BsdfEvaluation result = primeEvaluateGgxReflectionBase(
            alpha, normal, viewDirection, scatterDirection, microNormal);
    if (result.pdf > 0.0) {
        result.value *= primeFresnelConductor(
                dot(viewDirection, microNormal), max(eta, vec3(0.0)), max(k, vec3(0.0)));
    }
    return result;
}

BsdfSample primeSampleGgxConductorComplex(
        vec3 eta,
        vec3 k,
        float alpha,
        vec3 normal,
        vec3 viewDirection,
        vec2 sampleValue) {
    if (dot(normal, viewDirection) <= 0.0) {
        return primeInvalidBsdfSample();
    }
    if (primeGgxIsDelta(alpha)) {
        BsdfSample result;
        result.direction = reflect(-viewDirection, normal);
        result.weight = primeFresnelConductor(
                dot(normal, viewDirection), max(eta, vec3(0.0)), max(k, vec3(0.0)));
        result.pdf = 1.0;
        result.relativeEta = 1.0;
        result.eventFlags = PRIME_BSDF_EVENT_REFLECTION | PRIME_BSDF_EVENT_DELTA;
        return result;
    }
    vec3 microNormal;
    vec3 scatterDirection;
    if (!primeSampleGgxReflectionDirection(
            alpha, normal, viewDirection, sampleValue, microNormal, scatterDirection)) {
        return primeInvalidBsdfSample();
    }
    BsdfEvaluation evaluation = primeEvaluateGgxConductorComplex(
            eta, k, alpha, normal, viewDirection, scatterDirection);
    BsdfSample result;
    result.direction = scatterDirection;
    result.pdf = evaluation.pdf;
    result.weight = evaluation.value
            * (max(dot(normal, scatterDirection), 0.0) / max(result.pdf, PRIME_BSDF_EPSILON));
    result.relativeEta = 1.0;
    result.eventFlags = PRIME_BSDF_EVENT_REFLECTION | PRIME_BSDF_EVENT_GLOSSY;
    return result;
}

BsdfEvaluation primeEvaluateGgxDielectricInterface(
        float relativeIor,
        float alpha,
        vec3 normal,
        vec3 viewDirection,
        vec3 scatterDirection) {
    BsdfEvaluation result = primeInvalidBsdfEvaluation();
    if (primeGgxIsDelta(alpha) || relativeIor <= 0.0
            || abs(relativeIor - 1.0) <= PRIME_BSDF_EPSILON) {
        return result;
    }
    vec3 localView = primeWorldToLocal(viewDirection, normal);
    vec3 localScatter = primeWorldToLocal(scatterDirection, normal);
    if (localView.z <= 0.0 || abs(localScatter.z) <= PRIME_BSDF_EPSILON) {
        return result;
    }
    bool reflection = localScatter.z > 0.0;
    float etaPath = relativeIor;
    vec3 halfVector = reflection
            ? localView + localScatter
            : localScatter * etaPath + localView;
    if (dot(halfVector, halfVector) <= PRIME_BSDF_EPSILON) {
        return result;
    }
    halfVector = normalize(halfVector);
    if (halfVector.z < 0.0) {
        halfVector = -halfVector;
    }
    float dotView = dot(localView, halfVector);
    float dotScatter = dot(localScatter, halfVector);
    if (dotView * localView.z < 0.0 || dotScatter * localScatter.z < 0.0) {
        return result;
    }
    float fresnel = primeFresnelDielectric(dotView, 1.0, relativeIor);
    float distribution = primeGgxD(alpha, halfVector);
    float masking = primeGgxG2(alpha, localView, localScatter);
    float microNormalPdf = primeGgxVisibleNormalPdf(localView, halfVector, alpha);
    if (reflection) {
        result.value = vec3(fresnel * distribution * masking
                / max(abs(4.0 * localView.z * localScatter.z), PRIME_BSDF_EPSILON));
        result.pdf = microNormalPdf
                / max(4.0 * abs(dotView), PRIME_BSDF_EPSILON) * fresnel;
        return result;
    }
    float denominatorTerm = dotScatter + dotView / etaPath;
    float denominator2 = denominatorTerm * denominatorTerm;
    float transmittance = 1.0 - fresnel;
    float btdf = transmittance * distribution * masking
            * abs(dotScatter * dotView
            / max(abs(denominator2 * localScatter.z * localView.z), PRIME_BSDF_EPSILON));
    // Radiance transport is non-symmetric across a refractive boundary. The eta^-2 factor is
    // required by the change in differential solid angle and must not be removed as a "tuning".
    btdf /= etaPath * etaPath;
    result.value = vec3(btdf);
    float halfVectorJacobian = abs(dotScatter) / max(denominator2, PRIME_BSDF_EPSILON);
    result.pdf = microNormalPdf * halfVectorJacobian * transmittance;
    return result;
}

BsdfSample primeSampleSmoothDielectricInterface(
        float relativeIor,
        vec3 normal,
        vec3 viewDirection,
        float selector) {
    if (dot(normal, viewDirection) <= 0.0 || relativeIor <= 0.0) {
        return primeInvalidBsdfSample();
    }
    float fresnel = primeFresnelDielectric(
            dot(normal, viewDirection), 1.0, relativeIor);
    BsdfSample result;
    result.relativeEta = 1.0;
    if (selector < fresnel) {
        result.direction = reflect(-viewDirection, normal);
        result.weight = vec3(1.0);
        result.pdf = fresnel;
        result.eventFlags = PRIME_BSDF_EVENT_REFLECTION | PRIME_BSDF_EVENT_DELTA;
        return result;
    }
    vec3 refracted = refract(-viewDirection, normal, 1.0 / relativeIor);
    if (dot(refracted, refracted) <= PRIME_BSDF_EPSILON) {
        return primeInvalidBsdfSample();
    }
    result.direction = normalize(refracted);
    result.weight = vec3(1.0 / (relativeIor * relativeIor));
    result.pdf = 1.0 - fresnel;
    result.relativeEta = relativeIor;
    result.eventFlags = PRIME_BSDF_EVENT_TRANSMISSION | PRIME_BSDF_EVENT_DELTA;
    return result;
}

BsdfSample primeSampleGgxDielectricInterface(
        float relativeIor,
        float alpha,
        vec3 normal,
        vec3 viewDirection,
        vec3 sampleValue) {
    if (primeGgxIsDelta(alpha) || abs(relativeIor - 1.0) <= PRIME_BSDF_EPSILON) {
        return primeSampleSmoothDielectricInterface(
                relativeIor, normal, viewDirection, sampleValue.z);
    }
    vec3 localView = primeWorldToLocal(viewDirection, normal);
    if (localView.z <= 0.0 || relativeIor <= 0.0) {
        return primeInvalidBsdfSample();
    }
    vec3 halfVector = primeSampleGgxVisibleNormal(localView, alpha, sampleValue.xy);
    float fresnel = primeFresnelDielectric(dot(localView, halfVector), 1.0, relativeIor);
    vec3 localScatter;
    uint eventFlags;
    float etaEvent = 1.0;
    if (sampleValue.z < fresnel) {
        localScatter = reflect(-localView, halfVector);
        if (localScatter.z <= 0.0) {
            return primeInvalidBsdfSample();
        }
        eventFlags = PRIME_BSDF_EVENT_REFLECTION | PRIME_BSDF_EVENT_GLOSSY;
    } else {
        localScatter = refract(-localView, halfVector, 1.0 / relativeIor);
        if (dot(localScatter, localScatter) <= PRIME_BSDF_EPSILON || localScatter.z >= 0.0) {
            return primeInvalidBsdfSample();
        }
        etaEvent = relativeIor;
        eventFlags = PRIME_BSDF_EVENT_TRANSMISSION | PRIME_BSDF_EVENT_GLOSSY;
    }
    vec3 scatterDirection = primeLocalToWorld(normalize(localScatter), normal);
    BsdfEvaluation evaluation = primeEvaluateGgxDielectricInterface(
            relativeIor, alpha, normal, viewDirection, scatterDirection);
    if (evaluation.pdf <= 0.0) {
        return primeInvalidBsdfSample();
    }
    BsdfSample result;
    result.direction = scatterDirection;
    result.pdf = evaluation.pdf;
    result.weight = evaluation.value
            * (abs(dot(normal, scatterDirection)) / evaluation.pdf);
    result.relativeEta = etaEvent;
    result.eventFlags = eventFlags;
    return result;
}

// Smooth parallel-sided sheet including the infinite series of internal reflections. The ray
// remains in the same exterior medium, so unlike a single dielectric boundary transmission has
// neither angular refraction nor an eta^-2 radiance factor.
BsdfSample primeSampleThinDielectric(
        float relativeIor,
        vec3 transmissionTint,
        vec3 normal,
        vec3 viewDirection,
        float selector) {
    if (dot(normal, viewDirection) <= 0.0 || relativeIor <= 0.0) {
        return primeInvalidBsdfSample();
    }
    float reflectance = primeFresnelDielectric(
            dot(normal, viewDirection), 1.0, relativeIor);
    float transmittance = 1.0 - reflectance;
    if (reflectance < 1.0) {
        reflectance += transmittance * transmittance * reflectance
                / max(1.0 - reflectance * reflectance, PRIME_BSDF_EPSILON);
        transmittance = 1.0 - reflectance;
    }
    BsdfSample result;
    result.relativeEta = 1.0;
    if (selector < reflectance) {
        result.direction = reflect(-viewDirection, normal);
        result.weight = vec3(1.0);
        result.pdf = reflectance;
        result.eventFlags = PRIME_BSDF_EVENT_REFLECTION | PRIME_BSDF_EVENT_DELTA;
    } else {
        result.direction = -viewDirection;
        result.weight = max(transmissionTint, vec3(0.0));
        result.pdf = transmittance;
        result.eventFlags = PRIME_BSDF_EVENT_TRANSMISSION | PRIME_BSDF_EVENT_DELTA;
    }
    return result;
}

#endif
