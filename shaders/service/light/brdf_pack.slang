#language slang 2026
module "service/light/brdf_pack.slang";

import "prime_abi_types.slang";
import "math/light_receiver.slang";
import "model/material/translation.slang";
import "service/light/brdf_contract.slang";

public uint primePackAreaLightBrdf(
        SurfaceInteraction surface,
        float3 viewDirection,
        uint packedReceiverNormal) {
    if (packedReceiverNormal == 0u) {
        return 0u;
    }
    PrimeOpticalMaterial material = primeDecodeOpticalMaterial(
            surface.roughness,
            surface.opticalControl,
            surface.materialControl);
    bool metal = primeOpticalMaterialIsMetal(material);
    float3 f0 = metal
            ? primeConductorFresnel(surface.baseColor, material.fresnelCode).f0
            : float3(material.dielectricF0);
    const float3 luminanceWeights = float3(0.2627, 0.6780, 0.0593);
    float f0Luminance = clamp(dot(f0, luminanceWeights), 0.0, 1.0);
    float diffuseLuminance = metal
            ? 0.0
            : clamp(dot(max(surface.baseColor, float3(0.0)), luminanceWeights), 0.0, 1.0);
    uint roughness = uint(round(
            clamp(material.perceptualRoughness, 0.0, 1.0) * 63.0));
    uint f0Index = uint(round(f0Luminance * 50.0));
    uint diffuse = uint(round(diffuseLuminance * 15.0));
    return primePackLightReceiverNormal(viewDirection)
            | (roughness << PRIME_AREA_BRDF_ROUGHNESS_SHIFT)
            | (f0Index << PRIME_AREA_BRDF_F0_SHIFT)
            | (diffuse << PRIME_AREA_BRDF_DIFFUSE_SHIFT);
}
