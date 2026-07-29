#ifndef PRIME_AUTO_EXPOSURE_GLSL
#define PRIME_AUTO_EXPOSURE_GLSL

const float PRIME_AUTO_EXPOSURE_MIN_LOG_LUMINANCE = -16.0;
const float PRIME_AUTO_EXPOSURE_MAX_LOG_LUMINANCE = 20.0;
const float PRIME_AUTO_EXPOSURE_LOG_LUMINANCE_RANGE =
        PRIME_AUTO_EXPOSURE_MAX_LOG_LUMINANCE - PRIME_AUTO_EXPOSURE_MIN_LOG_LUMINANCE;
const float PRIME_AUTO_EXPOSURE_KEY = 0.16;
const float PRIME_AUTO_EXPOSURE_MIN_EV = 0.0;
const float PRIME_AUTO_EXPOSURE_MAX_EV = 4.0;
const float PRIME_AUTO_EXPOSURE_LN_10 = 2.302585092994046;
const float PRIME_AUTO_EXPOSURE_DARKEN_T90 = 0.5;
const float PRIME_AUTO_EXPOSURE_BRIGHTEN_T90 = 2.0;

float primeAutoExposureBinLogLuminance(uint bin) {
    return PRIME_AUTO_EXPOSURE_MIN_LOG_LUMINANCE
            + (float(bin) + 0.5)
                    * (PRIME_AUTO_EXPOSURE_LOG_LUMINANCE_RANGE / 256.0);
}

float primeAutoExposureTargetEv(float measuredLogLuminance) {
    return clamp(
            log2(PRIME_AUTO_EXPOSURE_KEY) - measuredLogLuminance,
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
