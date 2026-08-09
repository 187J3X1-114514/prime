#ifndef PRIME_MATERIAL_TRANSLATION_GLSL
#define PRIME_MATERIAL_TRANSLATION_GLSL

#include "default_material.glsl"
#include "color_space.glsl"

// Source-neutral optical parameters decoded from Prime's compact canonical control word.
struct PrimeOpticalMaterial {
    float perceptualRoughness;
    float dielectricF0;
    float subsurfaceWeight;
    uint fresnelCode;
    uint thinWalled;
};

const float PRIME_COMMON_DIELECTRIC_F0_MINIMUM = 0.02;
const float PRIME_COMMON_DIELECTRIC_F0_MAXIMUM = 0.17;

bool primeIsStandardConductorCode(uint fresnelCode) {
    return fresnelCode >= PRIME_FRESNEL_IRON && fresnelCode <= PRIME_FRESNEL_SILVER;
}

bool primeIsCustomConductorCode(uint fresnelCode) {
    return fresnelCode == PRIME_FRESNEL_CUSTOM_CONDUCTOR;
}

bool primeIsSupportedConductorCode(uint fresnelCode) {
    return primeIsStandardConductorCode(fresnelCode)
            || primeIsCustomConductorCode(fresnelCode);
}

PrimeOpticalMaterial primeDecodeOpticalMaterial(
        float roughness,
        uint opticalControl,
        uint materialControl) {
    uint fresnelCode = opticalControl & PRIME_OPTICAL_FRESNEL_MASK;
    uint subsurfaceCode = opticalControl >> PRIME_OPTICAL_SUBSURFACE_SHIFT & 0xffu;
    bool transmissive = primeMaterialIsTransmissive(materialControl);
    PrimeOpticalMaterial result;
    result.perceptualRoughness = clamp(roughness, 0.0, 1.0);
    result.fresnelCode = !transmissive
                    && !primeMaterialIsColorlessGlass(materialControl)
                    && primeIsSupportedConductorCode(fresnelCode)
            ? fresnelCode
            : PRIME_FRESNEL_DEFAULT_DIELECTRIC;
    result.dielectricF0 = fresnelCode <= 230u
            ? primeDecodeDielectricF0(fresnelCode)
            : PRIME_DEFAULT_DIELECTRIC_F0;
    result.subsurfaceWeight = float(min(subsurfaceCode, 190u)) / 190.0;
    result.thinWalled = (materialControl & PRIME_MATERIAL_THIN_WALLED) != 0u
            ? 1u
            : 0u;
    return result;
}

bool primeOpticalMaterialIsMetal(PrimeOpticalMaterial material) {
    return primeFresnelIsConductor(material.fresnelCode);
}

bool primeAirGapCompatible(
        bool enabled,
        uint currentControl,
        uint currentFresnelCode,
        uint adjacentControl,
        uint adjacentFresnelCode) {
    return enabled
            && primeMaterialIsTransmissive(currentControl)
            && primeMaterialIsTransmissive(adjacentControl)
            && !primeMaterialIsWater(currentControl)
            && !primeMaterialIsWater(adjacentControl)
            && (currentControl
                    & (PRIME_MATERIAL_THIN_WALLED
                            | PRIME_MATERIAL_DECORATIVE_INTERFACE)) == 0u
            && (adjacentControl
                    & (PRIME_MATERIAL_THIN_WALLED
                            | PRIME_MATERIAL_DECORATIVE_INTERFACE)) == 0u
            && currentFresnelCode == adjacentFresnelCode;
}

// LabPBR defines these optical constants in linear sRGB. Translation resolves the two endpoints
// consumed by RoboCute's fitted conductor Fresnel before crossing into Prime's Rec.2020 space.
vec3 primeConductorReflectance(float cosineIncident, vec3 eta, vec3 k) {
    float cosine = abs(cosineIncident);
    float cosine2 = cosine * cosine;
    float sine2 = 1.0 - cosine2;
    vec3 eta2 = eta * eta;
    vec3 k2 = k * k;
    vec3 t0 = eta2 - k2 - vec3(sine2);
    vec3 a2PlusB2 = sqrt(t0 * t0 + 4.0 * eta2 * k2);
    // Rationalization preserves the small nonnegative root when t0 is negative.
    vec3 a2 = vec3(
            t0.x >= 0.0
                    ? 0.5 * (a2PlusB2.x + t0.x)
                    : 2.0 * eta2.x * k2.x / (a2PlusB2.x - t0.x),
            t0.y >= 0.0
                    ? 0.5 * (a2PlusB2.y + t0.y)
                    : 2.0 * eta2.y * k2.y / (a2PlusB2.y - t0.y),
            t0.z >= 0.0
                    ? 0.5 * (a2PlusB2.z + t0.z)
                    : 2.0 * eta2.z * k2.z / (a2PlusB2.z - t0.z));
    vec3 a = sqrt(a2);
    vec3 t1 = a2PlusB2 + vec3(cosine2);
    vec3 t2 = 2.0 * cosine * a;
    vec3 rs = (t1 - t2) / (t1 + t2);
    vec3 t3 = cosine2 * a2PlusB2 + vec3(sine2 * sine2);
    vec3 t4 = t2 * sine2;
    vec3 rp = rs * (t3 - t4) / (t3 + t4);
    // The exact result is a unit reflectance. At the integer LabPBR domain boundary, the final
    // subtraction can undershoot zero by one f32 rounding step, so enforce the physical adapter
    // contract before the value enters the fitted Fresnel endpoints.
    return clamp(0.5 * (rs + rp), vec3(0.0), vec3(1.0));
}

bool primeConductorOpticalConstants(uint fresnelCode, out vec3 eta, out vec3 k) {
    if (fresnelCode == PRIME_FRESNEL_IRON) {
        eta = vec3(2.9114, 2.9497, 2.5845);
        k = vec3(3.0893, 2.9318, 2.7670);
    } else if (fresnelCode == PRIME_FRESNEL_GOLD) {
        eta = vec3(0.18299, 0.42108, 1.3734);
        k = vec3(3.4242, 2.3459, 1.7704);
    } else if (fresnelCode == PRIME_FRESNEL_ALUMINIUM) {
        eta = vec3(1.3456, 0.96521, 0.61722);
        k = vec3(7.4746, 6.3995, 5.3031);
    } else if (fresnelCode == PRIME_FRESNEL_CHROME) {
        eta = vec3(3.1071, 3.1812, 2.3230);
        k = vec3(3.3314, 3.3291, 3.1350);
    } else if (fresnelCode == PRIME_FRESNEL_COPPER) {
        eta = vec3(0.27105, 0.67693, 1.3164);
        k = vec3(3.6092, 2.6248, 2.2921);
    } else if (fresnelCode == PRIME_FRESNEL_LEAD) {
        eta = vec3(1.9100, 1.8300, 1.4400);
        k = vec3(3.5100, 3.4000, 3.1800);
    } else if (fresnelCode == PRIME_FRESNEL_PLATINUM) {
        eta = vec3(2.3757, 2.0847, 1.8453);
        k = vec3(4.2655, 3.7153, 3.1365);
    } else if (fresnelCode == PRIME_FRESNEL_SILVER) {
        eta = vec3(0.15943, 0.14512, 0.13547);
        k = vec3(3.9291, 3.1900, 2.3808);
    } else {
        eta = vec3(0.0);
        k = vec3(0.0);
        return false;
    }
    return true;
}

struct PrimeConductorFresnel {
    vec3 f0;
    vec3 f82Tint;
};

PrimeConductorFresnel primeConductorFresnel(vec3 baseColor, uint fresnelCode) {
    PrimeConductorFresnel result;
    vec3 sourceTint = clamp(primeLinearRec2020ToLinearBt709(baseColor), 0.0, 1.0);
    vec3 eta;
    vec3 k;
    const float f82AnchorCosine = 1.0 / 7.0;
    float schlick82Weight = pow(1.0 - f82AnchorCosine, 5.0);
    if (primeConductorOpticalConstants(fresnelCode, eta, k)) {
        vec3 sourceF0 = primeConductorReflectance(1.0, eta, k) * sourceTint;
        vec3 sourceF82 = primeConductorReflectance(
                f82AnchorCosine, eta, k) * sourceTint;
        result.f0 = clamp(primeLinearSrgbToLinearRec2020(sourceF0), 0.0, 1.0);
        vec3 targetF82 = clamp(
                primeLinearSrgbToLinearRec2020(sourceF82), 0.0, 1.0);
        vec3 untintedSchlickF82 = mix(result.f0, vec3(1.0), schlick82Weight);
        // RoboCute's f82 stores a relative tint at cos(theta)=1/7, not absolute reflectance.
        result.f82Tint = clamp(targetF82 / untintedSchlickF82, 0.0, 1.0);
    } else if (primeIsCustomConductorCode(fresnelCode)) {
        result.f0 = clamp(baseColor, 0.0, 1.0);
        result.f82Tint = vec3(1.0);
    } else {
        result.f0 = vec3(PRIME_DEFAULT_DIELECTRIC_F0);
        result.f82Tint = vec3(1.0);
    }
    return result;
}

#endif
