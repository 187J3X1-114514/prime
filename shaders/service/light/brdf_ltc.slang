#language slang 2026
module "service/light/brdf_ltc.slang";

import "math/basis.slang";
import "math/light_receiver.slang";
import "math/projected_solid_angle_domain.slang";
import "service/light/brdf_contract.slang";

static const float PRIME_AREA_LTC_PI = 3.141592653589793;

public struct PrimeGgxLtc {
    public float a;
    public float b;
    public float c;
    public float d;
    public float e;
    public float albedo;
    public float determinant;
    public float3 basisX;
    public float3 basisY;
    public float3 basisZ;
};

public PrimeGgxLtc primeInvalidGgxLtc() {
    PrimeGgxLtc result;
    result.a = 0.0;
    result.b = 0.0;
    result.c = 0.0;
    result.d = 0.0;
    result.e = 0.0;
    result.albedo = 0.0;
    result.determinant = 0.0;
    result.basisX = float3(0.0);
    result.basisY = float3(0.0);
    result.basisZ = float3(0.0);
    return result;
}

public bool primeAreaLightBrdfActive(uint packedBrdf) {
    return (packedBrdf & PRIME_LIGHT_RECEIVER_NORMAL_ACTIVE) != 0u;
}

public uint primeAreaLightBrdfF0Index(uint packedBrdf) {
    return min(
            (packedBrdf >> PRIME_AREA_BRDF_F0_SHIFT) & PRIME_AREA_BRDF_F0_MASK,
            50u);
}

public float primeAreaLightBrdfDiffuseLuminance(uint packedBrdf) {
    return float((packedBrdf >> PRIME_AREA_BRDF_DIFFUSE_SHIFT)
            & PRIME_AREA_BRDF_DIFFUSE_MASK) / 15.0;
}

public float3 primeGgxLtcCoordinate(
        uint packedReceiverNormal, uint packedBrdf) {
    float3 normal = primeUnpackLightReceiverNormal(packedReceiverNormal);
    float3 viewDirection = primeUnpackLightReceiverNormal(packedBrdf & 0xffffu);
    float inclination = acos(clamp(dot(normal, viewDirection), 0.0, 1.0));
    uint roughnessIndex = (packedBrdf >> PRIME_AREA_BRDF_ROUGHNESS_SHIFT)
            & PRIME_AREA_BRDF_ROUGHNESS_MASK;
    return float3(
            (float(roughnessIndex) + 0.5) / 64.0,
            (inclination * (63.0 / (0.5 * PRIME_AREA_LTC_PI)) + 0.5) / 64.0,
            (float(primeAreaLightBrdfF0Index(packedBrdf)) + 0.5) / 51.0);
}

public PrimeGgxLtc primeMakeGgxLtc(
        uint packedReceiverNormal,
        uint packedBrdf,
        float4 matrix,
        float2 amplitude) {
    PrimeGgxLtc result = primeInvalidGgxLtc();
    result.basisZ = primeUnpackLightReceiverNormal(packedReceiverNormal);
    float3 viewDirection = primeUnpackLightReceiverNormal(packedBrdf & 0xffffu);
    float viewCosine = clamp(dot(result.basisZ, viewDirection), 0.0, 1.0);
    float3 tangentView = viewDirection - result.basisZ * viewCosine;
    float tangentLengthSquared = dot(tangentView, tangentView);
    if (tangentLengthSquared > 1.0e-8) {
        result.basisX = tangentView * rsqrt(tangentLengthSquared);
        result.basisY = cross(result.basisZ, result.basisX);
    } else {
        primeBasis(result.basisZ, result.basisX, result.basisY);
    }
    result.a = matrix.x;
    result.b = matrix.y;
    result.c = matrix.z;
    result.d = matrix.w;
    result.e = amplitude.x;
    result.albedo = amplitude.y;
    result.determinant = result.c * (result.a * result.e + result.b * result.d);
    return result;
}

public float3 primeGgxLtcWorldToShading(
        PrimeGgxLtc ltc, float3 direction) {
    return float3(
            dot(direction, ltc.basisX),
            dot(direction, ltc.basisY),
            dot(direction, ltc.basisZ));
}

public float3 primeGgxLtcShadingToWorld(
        PrimeGgxLtc ltc, float3 direction) {
    return ltc.basisX * direction.x
            + ltc.basisY * direction.y
            + ltc.basisZ * direction.z;
}

public float3 primeGgxLtcShadingToCosine(
        PrimeGgxLtc ltc, float3 direction) {
    return float3(
            ltc.a * direction.x + ltc.d * direction.z,
            ltc.c * direction.y,
            -ltc.b * direction.x + ltc.e * direction.z);
}

public float3 primeGgxLtcCosineToShading(
        PrimeGgxLtc ltc, float3 direction) {
    float determinant2 = ltc.a * ltc.e + ltc.b * ltc.d;
    return float3(
            (ltc.e * direction.x - ltc.d * direction.z) / determinant2,
            direction.y / ltc.c,
            (ltc.b * direction.x + ltc.a * direction.z) / determinant2);
}

public PrimeProjectedSolidAngleDomain primePrepareGgxLtcTriangle(
        PrimeGgxLtc ltc,
        float3 corner,
        float3 edgeOne,
        float3 edgeTwo,
        float3 receiverPosition) {
    float3 first = primeGgxLtcShadingToCosine(
            ltc, primeGgxLtcWorldToShading(ltc, corner - receiverPosition));
    float3 second = primeGgxLtcShadingToCosine(
            ltc, primeGgxLtcWorldToShading(ltc, corner + edgeOne - receiverPosition));
    float3 third = primeGgxLtcShadingToCosine(
            ltc, primeGgxLtcWorldToShading(ltc, corner + edgeTwo - receiverPosition));
    return primePrepareProjectedSolidAngleTriangleLocal(first, second, third);
}

public float primeGgxLtcPdf(
        PrimeGgxLtc ltc,
        float projectedArea,
        float3 worldDirection) {
    if (!primeProjectedSolidAngleReliable(projectedArea)
            || !(ltc.determinant > 0.0)) {
        return 0.0;
    }
    float3 cosineDirection = primeGgxLtcShadingToCosine(
            ltc, primeGgxLtcWorldToShading(ltc, worldDirection));
    float lengthSquared = dot(cosineDirection, cosineDirection);
    return lengthSquared > 0.0
            ? max(cosineDirection.z, 0.0) * ltc.determinant
                    / (lengthSquared * lengthSquared * projectedArea)
            : 0.0;
}

public float primeAreaDiffuseRatio(
        uint packedBrdf,
        float specularAlbedo,
        float diffuseArea,
        float specularArea) {
    if (!primeProjectedSolidAngleReliable(specularArea)) {
        return 1.0;
    }
    // These weights select between proposals only. The exact compact OpenPBR response and the
    // complete mixture PDF remain unchanged, so the coarse luminance encoding cannot bias paths.
    float diffuseWeight = max(
            primeAreaLightBrdfDiffuseLuminance(packedBrdf), 0.01)
            * diffuseArea;
    float specularWeight = max(specularAlbedo, 0.0) * specularArea;
    float weightSum = diffuseWeight + specularWeight;
    return weightSum > 0.0 ? diffuseWeight / weightSum : 1.0;
}
