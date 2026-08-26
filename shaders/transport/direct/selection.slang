#language slang 2026
module "transport/direct/selection.slang";

import "prime_abi_types.slang";
import "math/light_receiver.slang";
import "model/material/defaults.slang";
import "transport/surface/access.slang";

public uint primeAreaLightReceiverNormal(
        SurfaceInteraction surface, float3 viewDirection) {
    if (primeMaterialIsTransmissive(surface.materialControl)
            || primeMaterialIsFoliage(surface.materialControl)) {
        return 0u;
    }
    return primePackLightReceiverNormal(
            primeSurfaceShadingNormal(surface, viewDirection));
}
