#ifndef PRIME_BSDF_FRESNEL_GLSL
#define PRIME_BSDF_FRESNEL_GLSL

#include "bsdf_common.glsl"

float primeIorFromF0(float f0) {
    float root = sqrt(clamp(f0, 0.0, 0.9999));
    return (1.0 + root) / max(1.0 - root, PRIME_BSDF_EPSILON);
}

float primeF0FromIor(float relativeIor) {
    float ratio = (relativeIor - 1.0) / (relativeIor + 1.0);
    return ratio * ratio;
}

// Exact unpolarized Fresnel reflectance for a non-absorbing boundary. The IORs describe the
// media on the incident and transmitted sides; the signed cosine permits the routine to swap
// them when the direction approaches from the back side.
float primeFresnelDielectric(float cosineIncident, float incidentIor, float transmittedIor) {
    float cosine = clamp(cosineIncident, -1.0, 1.0);
    float etaI = max(incidentIor, PRIME_BSDF_EPSILON);
    float etaT = max(transmittedIor, PRIME_BSDF_EPSILON);
    if (cosine < 0.0) {
        cosine = -cosine;
        float swapIor = etaI;
        etaI = etaT;
        etaT = swapIor;
    }
    float sineIncident = sqrt(max(0.0, 1.0 - cosine * cosine));
    float sineTransmitted = etaI * sineIncident / etaT;
    if (sineTransmitted >= 1.0) {
        return 1.0;
    }
    float cosineTransmitted = sqrt(max(0.0, 1.0 - sineTransmitted * sineTransmitted));
    float parallel = (etaT * cosine - etaI * cosineTransmitted)
            / max(etaT * cosine + etaI * cosineTransmitted, PRIME_BSDF_EPSILON);
    float perpendicular = (etaI * cosine - etaT * cosineTransmitted)
            / max(etaI * cosine + etaT * cosineTransmitted, PRIME_BSDF_EPSILON);
    return 0.5 * (parallel * parallel + perpendicular * perpendicular);
}

vec3 primeFresnelSchlick(vec3 f0, float cosine) {
    float complement = 1.0 - clamp(abs(cosine), 0.0, 1.0);
    float complement2 = complement * complement;
    float complement5 = complement2 * complement2 * complement;
    return f0 + (vec3(1.0) - f0) * complement5;
}

// Exact conductor Fresnel for complex relative IOR eta + i*k. eta and k are expected in the
// integrator's linear Rec.2020 basis; LabPBR predefined metals can populate them without changing
// this closure. Custom-metal mode only supplies F0 and therefore uses the Schlick closure above.
vec3 primeFresnelConductor(float cosineIncident, vec3 eta, vec3 k) {
    float cosine = clamp(abs(cosineIncident), 0.0, 1.0);
    float cosine2 = cosine * cosine;
    float sine2 = 1.0 - cosine2;
    vec3 eta2 = eta * eta;
    vec3 k2 = k * k;
    vec3 t0 = eta2 - k2 - vec3(sine2);
    vec3 a2PlusB2 = sqrt(max(t0 * t0 + 4.0 * eta2 * k2, vec3(0.0)));
    vec3 a = sqrt(max(0.5 * (a2PlusB2 + t0), vec3(0.0)));
    vec3 t1 = a2PlusB2 + vec3(cosine2);
    vec3 t2 = 2.0 * cosine * a;
    vec3 rs = (t1 - t2) / max(t1 + t2, vec3(PRIME_BSDF_EPSILON));
    vec3 t3 = cosine2 * a2PlusB2 + vec3(sine2 * sine2);
    vec3 t4 = t2 * sine2;
    vec3 rp = rs * (t3 - t4) / max(t3 + t4, vec3(PRIME_BSDF_EPSILON));
    return clamp(0.5 * (rs + rp), vec3(0.0), vec3(1.0));
}

#endif
