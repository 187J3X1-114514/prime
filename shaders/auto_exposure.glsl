#ifndef PRIME_AUTO_EXPOSURE_GLSL
#define PRIME_AUTO_EXPOSURE_GLSL

const float PRIME_AUTO_EXPOSURE_MIN_LOG_LUMINANCE = -16.0;
const float PRIME_AUTO_EXPOSURE_MAX_LOG_LUMINANCE = 20.0;
const float PRIME_AUTO_EXPOSURE_LOG_LUMINANCE_RANGE =
        PRIME_AUTO_EXPOSURE_MAX_LOG_LUMINANCE - PRIME_AUTO_EXPOSURE_MIN_LOG_LUMINANCE;
const float PRIME_AUTO_EXPOSURE_KEY = 0.16;
const float PRIME_AUTO_EXPOSURE_BASELINE_EV = 0.5;
const float PRIME_AUTO_EXPOSURE_MIN_EV = -4.0;
const float PRIME_AUTO_EXPOSURE_MAX_EV = 4.0;
const float PRIME_AUTO_EXPOSURE_LN_10 = 2.302585092994046;
const float PRIME_AUTO_EXPOSURE_DARKEN_T90 = 0.5;
const float PRIME_AUTO_EXPOSURE_BRIGHTEN_T90 = 2.0;
const float PRIME_AUTO_EXPOSURE_REFERENCE_ALBEDO = 0.18;
const float PRIME_AUTO_EXPOSURE_MIN_ALBEDO = 0.05;
const float PRIME_AUTO_EXPOSURE_ALBEDO_BLEND = 0.75;
const uint PRIME_AUTO_EXPOSURE_MATERIAL_DIELECTRIC = 0u;
const uint PRIME_AUTO_EXPOSURE_MATERIAL_FOLIAGE = 3u;

float primeAutoExposureLuminance(vec3 radiance) {
    return dot(max(radiance, vec3(0.0)), vec3(0.2627, 0.6780, 0.0593));
}

float primeAutoExposureMaterialConfidence(
        uint materialClass,
        float primaryDistance) {
    bool diffuseSurface = materialClass == PRIME_AUTO_EXPOSURE_MATERIAL_DIELECTRIC
            || materialClass == PRIME_AUTO_EXPOSURE_MATERIAL_FOLIAGE;
    return diffuseSurface && primaryDistance >= 0.0 ? 1.0 : 0.0;
}

float primeAutoExposureAlbedoScale(vec3 albedo, float confidence) {
    float albedoLuminance =
            primeAutoExposureLuminance(clamp(albedo, vec3(0.0), vec3(1.0)));
    float fullScale = PRIME_AUTO_EXPOSURE_REFERENCE_ALBEDO
            / max(albedoLuminance, PRIME_AUTO_EXPOSURE_MIN_ALBEDO);
    float blendedScale = mix(
            1.0, fullScale, PRIME_AUTO_EXPOSURE_ALBEDO_BLEND);
    return mix(1.0, blendedScale, clamp(confidence, 0.0, 1.0));
}

float primeAutoExposureMeteredLuminance(
        vec3 radiance,
        vec3 albedo,
        float confidence) {
    return primeAutoExposureLuminance(radiance)
            * primeAutoExposureAlbedoScale(albedo, confidence);
}

float primeAutoExposureBinLogLuminance(uint bin) {
    return PRIME_AUTO_EXPOSURE_MIN_LOG_LUMINANCE
            + (float(bin) + 0.5)
                    * (PRIME_AUTO_EXPOSURE_LOG_LUMINANCE_RANGE / 256.0);
}

float primeAutoExposureTargetEv(float measuredLogLuminance) {
    return clamp(
            log2(PRIME_AUTO_EXPOSURE_KEY) + PRIME_AUTO_EXPOSURE_BASELINE_EV
                    - measuredLogLuminance,
            PRIME_AUTO_EXPOSURE_MIN_EV,
            PRIME_AUTO_EXPOSURE_MAX_EV);
}

float primeAutoExposureAdapt(
        float previousEv,
        float targetEv,
        float deltaSeconds) {
    float t90 = targetEv < previousEv
            ? PRIME_AUTO_EXPOSURE_DARKEN_T90
            : PRIME_AUTO_EXPOSURE_BRIGHTEN_T90;
    float blend = 1.0 - exp(
            -max(deltaSeconds, 0.0) * PRIME_AUTO_EXPOSURE_LN_10 / t90);
    return mix(previousEv, targetEv, clamp(blend, 0.0, 1.0));
}

#endif
