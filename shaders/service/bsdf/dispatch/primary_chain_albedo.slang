#language slang 2026
module "service/bsdf/dispatch/primary_chain_albedo.slang";

import "prime_abi_types.slang";
import "bsdf/compact/dielectric/contract.slang";
import "bsdf/compact/opaque/discrete.slang";
import "math/packed_geometry.slang";
import "model/material/defaults.slang";
import "service/bsdf/contract/albedo.slang";
import "service/bsdf/opaque/discrete.slang";
import "service/bsdf/dielectric/albedo.slang";
import "service/bsdf/dielectric/state.slang";
import "transport/surface/access.slang";
import "bsdf/compact/contract/volume.slang";

// Primary-chain only owns opaque and dielectric discrete transport. Foliage is a terminal
// solid-angle surface and remains on the existing independent guide-probe fallback.
public bool primePrimaryChainGuideAlbedos(
        SurfaceInteraction surface,
        float3 viewDirection,
        PrimeOpenPbrVolumeStack volumeStack,
        bool usesAirGap,
        out PrimeDenoiseAlbedos albedos) {
    albedos.diffuse = float3(0.0);
    albedos.specular = float3(0.0);
    if (primeMaterialIsFoliage(surface.materialControl)) {
        return false;
    }
    float3 outwardNormal = primeUnpackOctahedralNormal(surface.shadingNormal);
    outwardNormal = dot(outwardNormal, surface.geometricNormal) >= 0.0
            ? outwardNormal
            : -outwardNormal;
    if (primeMaterialIsTransmissive(surface.materialControl)) {
        PrimeCompactTransmissionState state = primeMinecraftBoundaryTransmissionState(
                surface.baseColor,
                primeSurfaceOpacity(surface),
                outwardNormal,
                surface.materialControl,
                surface.roughness,
                surface.opticalControl,
                viewDirection,
                surface.t,
                volumeStack,
                surface.adjacentBaseColor,
                surface.adjacentInterfaceControl,
                usesAirGap);
        albedos = primeDenoiseAlbedosFromState(
                state, viewDirection, PRIME_DENOISE_CLOSURE_TRANSMISSIVE);
        return true;
    }
    float3 shadingNormal = dot(surface.geometricNormal, viewDirection) >= 0.0
            ? outwardNormal
            : -outwardNormal;
    PrimeCompactOpaqueDiscreteState state = primeOpaqueDiscreteState(
            surface.baseColor,
            shadingNormal,
            surface.roughness,
            surface.opticalControl,
            surface.materialControl,
            surface.t);
    albedos = primeOpaqueDiscreteAlbedosFromState(state, viewDirection);
    return true;
}
