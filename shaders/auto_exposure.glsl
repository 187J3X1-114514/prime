#ifndef PRIME_AUTO_EXPOSURE_GLSL
#define PRIME_AUTO_EXPOSURE_GLSL

#include "prime_color_contract.glsl"
#include "color_space.glsl"
#include "oklab.glsl"

const float PRIME_AUTO_EXPOSURE_MIN_LOG_BRIGHTNESS = -16.0;
const float PRIME_AUTO_EXPOSURE_MAX_LOG_BRIGHTNESS = 20.0;
const float PRIME_AUTO_EXPOSURE_LOG_BRIGHTNESS_RANGE =
        PRIME_AUTO_EXPOSURE_MAX_LOG_BRIGHTNESS - PRIME_AUTO_EXPOSURE_MIN_LOG_BRIGHTNESS;
const float PRIME_AUTO_EXPOSURE_KEY = 0.16;
const float PRIME_AUTO_EXPOSURE_BASELINE_EV = 0.0;
const float PRIME_AUTO_EXPOSURE_MIN_EV = 0.0;
const float PRIME_AUTO_EXPOSURE_MAX_EV = 4.0;
const float PRIME_AUTO_EXPOSURE_LN_10 = 2.302585092994046;
const float PRIME_AUTO_EXPOSURE_DARKEN_T90 = 0.5;
const float PRIME_AUTO_EXPOSURE_BRIGHTEN_T90 = 2.0;
const uint PRIME_AUTO_EXPOSURE_TAIL_DENOMINATOR = 200u;
const float PRIME_AUTO_EXPOSURE_REFERENCE_ALBEDO = 0.18;
const float PRIME_AUTO_EXPOSURE_MIN_ALBEDO = 0.02;
const float PRIME_AUTO_EXPOSURE_ALBEDO_BLEND = 1.0;
const float PRIME_AUTO_EXPOSURE_SCENE_KEY_MIN_RANGE_EV = 2.0;
const uint PRIME_AUTO_EXPOSURE_MATERIAL_DIELECTRIC = 0u;
const uint PRIME_AUTO_EXPOSURE_MATERIAL_FOLIAGE = 3u;

float primeAutoExposureBrightness(vec3 radiance) {
    vec3 linearBt709 = max(
            primeLinearRec2020ToLinearBt709(max(radiance, vec3(0.0))),
            vec3(0.0));
    float lightness = primeLinearBt709ToOklab(linearBt709).x;
    return lightness * lightness * lightness;
}

float primeAutoExposureMaterialConfidence(
        uint materialClass,
        float primaryDistance) {
    bool diffuseSurface = materialClass == PRIME_AUTO_EXPOSURE_MATERIAL_DIELECTRIC
            || materialClass == PRIME_AUTO_EXPOSURE_MATERIAL_FOLIAGE;
    return diffuseSurface && primaryDistance >= 0.0 ? 1.0 : 0.0;
}

float primeAutoExposureAlbedoScale(vec3 albedo, float confidence) {
    float albedoBrightness =
            primeAutoExposureBrightness(clamp(albedo, vec3(0.0), vec3(1.0)));
    float fullScale = PRIME_AUTO_EXPOSURE_REFERENCE_ALBEDO
            / max(albedoBrightness, PRIME_AUTO_EXPOSURE_MIN_ALBEDO);
    float blendedScale = mix(
            1.0, fullScale, PRIME_AUTO_EXPOSURE_ALBEDO_BLEND);
    return mix(1.0, blendedScale, clamp(confidence, 0.0, 1.0));
}

float primeAutoExposureMeteredBrightness(
        vec3 radiance,
        vec3 albedo,
        float confidence) {
    return primeAutoExposureBrightness(radiance)
            * primeAutoExposureAlbedoScale(albedo, confidence);
}

float primeAutoExposureBinLogBrightness(uint bin) {
    return PRIME_AUTO_EXPOSURE_MIN_LOG_BRIGHTNESS
            + (float(bin) + 0.5)
                    * (PRIME_AUTO_EXPOSURE_LOG_BRIGHTNESS_RANGE / 256.0);
}

float primeAutoExposureSceneKeyBiasEv(
        float measuredLogBrightness,
        float minimumLogBrightness,
        float maximumLogBrightness) {
    float range = maximumLogBrightness - minimumLogBrightness;
    if (range <= 0.0) {
        return 0.0;
    }
    // Reinhard 2002 automatic key: alpha = key * 4^q, or 2q in EV.
    // A range floor removes its singular sensitivity on nearly uniform images.
    float q = (
            2.0 * measuredLogBrightness
                    - minimumLogBrightness
                    - maximumLogBrightness)
            / max(range, PRIME_AUTO_EXPOSURE_SCENE_KEY_MIN_RANGE_EV);
    return 2.0 * q;
}

float primeAutoExposureTargetEv(
        float measuredLogBrightness,
        float minimumLogBrightness,
        float maximumLogBrightness) {
    return clamp(
            log2(PRIME_AUTO_EXPOSURE_KEY) + PRIME_AUTO_EXPOSURE_BASELINE_EV
                    - measuredLogBrightness
                    + primeAutoExposureSceneKeyBiasEv(
                            measuredLogBrightness,
                            minimumLogBrightness,
                            maximumLogBrightness),
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
