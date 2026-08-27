#language slang 2026
module "service/light/area.slang";

import "contract/light_sample.slang";
import "service/light/area_sample.slang";
import "service/light/tree_select.slang";

public AreaLightSample primeSampleAreaLight(
        float3 surfacePosition,
        uint packedReceiverNormal,
        uint packedReceiverBrdf,
        float2 treeSample,
        float2 positionSample) {
    AreaLightSelection selection = primeSelectAreaLight(
            surfacePosition, packedReceiverNormal, treeSample.x);
    return primeSampleAreaLightSelection(
            surfacePosition,
            packedReceiverNormal,
            packedReceiverBrdf,
            selection,
            treeSample.y,
            positionSample);
}
