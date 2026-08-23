#language slang 2026
module "transport/direct/selection.slang";

import "prime_abi_types.slang";
import "model/atmosphere/sky.slang";
import "service/atmosphere/trace.slang";
import "math/light_tree.slang";
import "service/light/sample.slang";
import "model/material/defaults.slang";
import "transport/surface/access.slang";
import "policy/transport/light_family.slang";

public uint primeAreaLightReceiverNormal(
        SurfaceInteraction surface, float3 viewDirection) {
    if (primeMaterialIsTransmissive(surface.materialControl)
            || primeMaterialIsFoliage(surface.materialControl)) {
        return 0u;
    }
    return primePackLightReceiverNormal(
            primeSurfaceShadingNormal(surface, viewDirection));
}

public float3 primeSunDiskUpperDirection(float3 sunDirection) {
    float3 tangent = float3(0.0, 1.0, 0.0)
            - sunDirection * sunDirection.y;
    float tangentLengthSquared = dot(tangent, tangent);
    tangent = tangentLengthSquared > 0.0
            ? tangent * rsqrt(tangentLengthSquared)
            : float3(1.0, 0.0, 0.0);
    return sunDirection * cos(ATM_SUN_ANGULAR_RADIUS_RADIANS)
            + tangent * sin(ATM_SUN_ANGULAR_RADIUS_RADIANS);
}

public float2 primeSecondaryLightFamilyImportance(
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        float3 viewDirection) {
    float3 sunDirection = integrator.sunDirectionIntensity.xyz;
    float3 upperDirection = primeSunDiskUpperDirection(sunDirection);
    uint packedReceiverNormal = primeAreaLightReceiverNormal(
            surface, viewDirection);
    float sunImportance = 0.0;
    if (primeAtmosphereDistantDirectionVisible(upperDirection)
            && integrator.sunDirectionIntensity.w > 0.0) {
        float3 representativeDirection =
                primeAtmosphereDistantDirectionVisible(sunDirection)
                ? sunDirection
                : upperDirection;
        float3 transmittance = primeAtmosphereSunTransmittance(
                surface.position, representativeDirection);
        float3 receiverNormal = primeUnpackLightReceiverNormal(
                packedReceiverNormal);
        float receiverCosine = primeLightReceiverCosineEstimate(
                sunDirection, receiverNormal);
        float angularImportance = PRIME_LIGHT_RECEIVER_COSINE_FLOOR
                + (1.0 - PRIME_LIGHT_RECEIVER_COSINE_FLOOR) * receiverCosine;
        sunImportance = integrator.sunDirectionIntensity.w
                * max(transmittance.x, max(transmittance.y, transmittance.z))
                * angularImportance;
    }
    float areaImportance = primeAreaLightFamilyImportance(
            surface.position, packedReceiverNormal);
    return float2(sunImportance, areaImportance);
}

public float primeSecondarySunFamilyProbability(
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        float3 viewDirection) {
    float2 importance = primeSecondaryLightFamilyImportance(
            integrator, surface, viewDirection);
    return primeQuantizedSunFamilyProbability(
            importance.x, importance.y);
}
