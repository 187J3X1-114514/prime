#ifndef PRIME_NRD_SH_GLSL
#define PRIME_NRD_SH_GLSL

// Application-side half of NRD 4.17.4's REBLUR SH contract. Keep these operations synchronized
// with NRD/Shaders/NRD.hlsli when the bundled native version changes.
const float PRIME_NRD_PI = 3.14159265358979323846;
const float PRIME_NRD_EPS = 1.0e-6;
const float PRIME_NRD_REJITTER_VIEW_Z_THRESHOLD = 0.01;
const float PRIME_NRD_REJITTER_AMPLITUDE = 2.0;

struct PrimeNrdSg {
    float c0;
    vec2 chroma;
    float normalizedHitDistance;
    vec3 c1;
};

struct PrimeNrdResolvedSg {
    vec3 diffuse;
    vec3 specular;
};

PrimeNrdSg primeNrdUnpackSg(vec4 sh0, vec3 sh1) {
    PrimeNrdSg sg;
    sg.c0 = primeNrdIsFinite(sh0.x) ? max(sh0.x, 0.0) : 0.0;
    sg.chroma = primeNrdIsFinite(sh0.yz) ? sh0.yz : vec2(0.0);
    sg.normalizedHitDistance = primeNrdSanitizeUnit(sh0.w, 0.0);
    sg.c1 = primeNrdIsFinite(sh1) ? sh1 : vec3(0.0);
    return sg;
}

vec3 primeNrdSgExtractColor(PrimeNrdSg sg) {
    return primeNrdYCoCgToLinear(vec3(sg.c0, sg.chroma));
}

vec3 primeNrdSgExtractDirection(PrimeNrdSg sg) {
    return sg.c1 / max(length(sg.c1), PRIME_NRD_EPS);
}

vec3 primeNrdYCoCgToLinearCorrected(float y, float y0, vec2 chroma) {
    y = max(y, 0.0);
    chroma *= (y + PRIME_NRD_EPS) / (y0 + PRIME_NRD_EPS);
    return primeNrdYCoCgToLinear(vec3(y, chroma));
}

float primeNrdPow5(float value) {
    return pow(clamp(1.0 - value, 0.0, 1.0), 5.0);
}

float primeNrdDistributionTerm(float roughness, float noH) {
    float m = roughness * roughness;
    float m2 = m * m;
    float t = (noH * m2 - noH) * noH + 1.0;
    float a = m / t;
    return a * a / PRIME_NRD_PI;
}

float primeNrdGeometryTerm(float roughness, float noL, float noV) {
    float m = roughness * roughness;
    float m2 = m * m;
    float a = noV * sqrt((noL - m2 * noL) * noL + m2);
    float b = noL * sqrt((noV - m2 * noV) * noV + m2);
    return 0.5 / (a + b);
}

float primeNrdDiffuseTerm(float roughness, float noL, float noV, float voH) {
    float f = 2.0 * voH * voH * roughness - 0.5;
    float fdV = f * primeNrdPow5(noV) + 1.0;
    float fdL = f * primeNrdPow5(noL) + 1.0;
    return fdV * fdL / PRIME_NRD_PI;
}

float primeNrdSgInnerProduct(
        float firstC0,
        vec3 firstDirection,
        float firstSharpness,
        float secondC0,
        vec3 secondDirection,
        float secondSharpness) {
    vec3 direction = firstSharpness * firstDirection
            + secondSharpness * secondDirection;
    float directionLength = length(direction);
    float value = exp(directionLength - firstSharpness - secondSharpness);
    value *= 1.0 - exp(-2.0 * directionLength);
    value /= max(directionLength, PRIME_NRD_EPS);
    return 2.0 * PRIME_NRD_PI * value * firstC0 * secondC0;
}

vec3 primeNrdResolveSgDiffuse(PrimeNrdSg sg, vec3 normal, vec3 view, float roughness) {
    vec3 lightDirection = primeNrdSgExtractDirection(sg);
    float noL = clamp(dot(normal, lightDirection), 0.0, 1.0);
    float y = primeNrdSgInnerProduct(
            1.0,
            normal,
            2.0,
            sg.c0 * 2.0,
            lightDirection,
            2.0);
    vec3 halfDirection = normalize(lightDirection + view);
    float noV = abs(dot(normal, view));
    float voH = abs(dot(view, halfDirection));
    y *= primeNrdDiffuseTerm(roughness, noL, noV, voH);
    y *= mix(1.0, mix(1.5, 0.6, roughness), primeNrdPow5(noV));
    y = max(y, sg.c0 / PRIME_NRD_PI);
    vec3 resolved = primeNrdYCoCgToLinearCorrected(y, sg.c0, sg.chroma);
    return primeNrdIsFinite(resolved) ? resolved : primeNrdSgExtractColor(sg);
}

vec3 primeNrdResolveSgSpecular(PrimeNrdSg sg, vec3 normal, vec3 view, float roughness) {
    roughness = max(roughness, 0.05);
    float m = roughness * roughness;
    float m2 = m * m;
    vec3 lightDirection = primeNrdSgExtractDirection(sg);
    float noL = clamp(dot(normal, lightDirection), 0.0, 1.0);
    vec3 halfDirection = normalize(lightDirection + view);
    float noV = abs(dot(normal, view));
    float voH = abs(dot(view, halfDirection));
    noV = mix(0.02, 1.0, noV);
    float lightSharpness = 2.0 / m2;
    float ndfSharpness = 0.5 / max(m2 * voH, 1.0e-8);
    float y = primeNrdSgInnerProduct(
            1.0,
            lightDirection,
            ndfSharpness,
            sg.c0 * lightSharpness,
            lightDirection,
            lightSharpness);
    y *= primeNrdGeometryTerm(roughness, noL, noV) * noL;
    y *= mix(mix(0.1, 0.4, m2), 0.8, noV);
    y = max(y, sg.c0 / PRIME_NRD_PI);
    vec3 resolved = primeNrdYCoCgToLinearCorrected(y, sg.c0, sg.chroma);
    return primeNrdIsFinite(resolved) ? resolved : primeNrdSgExtractColor(sg);
}

vec2 primeNrdComputeBrdfs(
        vec3 diffuseLight,
        vec3 specularLight,
        vec3 normal,
        vec3 view,
        float roughness) {
    float noV = abs(dot(normal, view));
    vec3 diffuseHalf = normalize(diffuseLight + view);
    float diffuseNoL = clamp(dot(normal, diffuseLight), 0.0, 1.0);
    float diffuseVoH = abs(dot(view, diffuseHalf));
    float diffuse = primeNrdDiffuseTerm(
            roughness, diffuseNoL, noV, diffuseVoH) * diffuseNoL;

    vec3 specularHalf = normalize(specularLight + view);
    float specularNoL = clamp(dot(normal, specularLight), 0.0, 1.0);
    float specularNoH = clamp(dot(normal, specularHalf), 0.0, 1.0);
    float specular = primeNrdDistributionTerm(roughness, specularNoH)
            * primeNrdGeometryTerm(roughness, specularNoL, noV)
            * specularNoL;
    return vec2(diffuse, specular);
}

vec2 primeNrdSgReJitter(
        PrimeNrdSg diffuseSg,
        PrimeNrdSg specularSg,
        vec3 view,
        float roughness,
        float viewZ,
        vec4 neighborViewZ,
        vec3 normal,
        vec3 eastNormal,
        vec3 westNormal,
        vec3 northNormal,
        vec3 southNormal) {
    vec3 diffuseLight = primeNrdSgExtractDirection(diffuseSg);
    vec3 specularLight = normalize(mix(view, primeNrdSgExtractDirection(specularSg), roughness));
    vec2 center = primeNrdComputeBrdfs(
            diffuseLight, specularLight, normal, view, roughness);
    vec2 average = primeNrdComputeBrdfs(
            diffuseLight, specularLight, eastNormal, view, roughness);
    average += primeNrdComputeBrdfs(
            diffuseLight, specularLight, northNormal, view, roughness);
    average += primeNrdComputeBrdfs(
            diffuseLight, specularLight, westNormal, view, roughness);
    average += primeNrdComputeBrdfs(
            diffuseLight, specularLight, southNormal, view, roughness);
    average *= 0.25;
    vec2 scale = (center + PRIME_NRD_EPS) / (average + PRIME_NRD_EPS);
    scale = clamp(
            scale,
            vec2(1.0 / PRIME_NRD_REJITTER_AMPLITUDE),
            vec2(PRIME_NRD_REJITTER_AMPLITUDE));

    float noV = abs(dot(normal, view));
    float threshold = PRIME_NRD_REJITTER_VIEW_Z_THRESHOLD * abs(viewZ)
            / (noV * 0.95 + 0.05);
    bvec4 withinSurface = lessThanEqual(abs(neighborViewZ - viewZ), vec4(threshold));
    bool symmetrical = all(withinSurface);
    return symmetrical && primeNrdIsFinite(scale) ? scale : vec2(1.0);
}

PrimeNrdResolvedSg primeNrdResolveFilteredSg(
        PrimeNrdSg diffuseSg,
        PrimeNrdSg specularSg,
        vec3 view,
        float roughness,
        float viewZ,
        vec4 neighborViewZ,
        vec3 normal,
        vec3 eastNormal,
        vec3 westNormal,
        vec3 northNormal,
        vec3 southNormal) {
    bool hasDiffuseDirection =
            dot(diffuseSg.c1, diffuseSg.c1) > PRIME_NRD_EPS * PRIME_NRD_EPS;
    bool hasSpecularDirection =
            dot(specularSg.c1, specularSg.c1) > PRIME_NRD_EPS * PRIME_NRD_EPS;
    PrimeNrdResolvedSg resolved;
    resolved.diffuse = !hasDiffuseDirection
            ? primeNrdSgExtractColor(diffuseSg)
            : primeNrdResolveSgDiffuse(diffuseSg, normal, view, roughness);
    resolved.specular = !hasSpecularDirection
            ? primeNrdSgExtractColor(specularSg)
            : primeNrdResolveSgSpecular(specularSg, normal, view, roughness);
    if (!hasDiffuseDirection && !hasSpecularDirection) {
        return resolved;
    }

    vec2 scale = primeNrdSgReJitter(
            diffuseSg,
            specularSg,
            view,
            roughness,
            viewZ,
            neighborViewZ,
            normal,
            eastNormal,
            westNormal,
            northNormal,
            southNormal);
    if (!hasDiffuseDirection) scale.x = 1.0;
    if (!hasSpecularDirection) scale.y = 1.0;
    resolved.diffuse *= scale.x;
    resolved.specular *= scale.y;
    return resolved;
}

#endif
