#language slang 2026
module "service/light/brdf_common.slang";

import "prime_abi_bindings.slang";
import "service/light/brdf_ltc.slang";

// Peters' 64 x 64 GGX LTC fits, stored as 51 exact F0 slices. The matrix and amplitude
// encodings match the reference loader; see THIRD_PARTY_LICENSES/POLYGONAL-LIGHT-LTC-NOTICE.txt.
[[vk::binding(PRIME_GGX_LTC_MATRIX_BINDING, 0)]]
Sampler3D<float4> primeGgxLtcMatrixTexture;
[[vk::binding(PRIME_GGX_LTC_AMPLITUDE_BINDING, 0)]]
Sampler3D<float4> primeGgxLtcAmplitudeTexture;

public PrimeGgxLtc primeGgxLtc(
        uint packedReceiverNormal, uint packedBrdf) {
    float3 coordinate = primeGgxLtcCoordinate(
            packedReceiverNormal, packedBrdf);
    return primeMakeGgxLtc(
            packedReceiverNormal,
            packedBrdf,
            primeGgxLtcMatrixTexture.SampleLevel(coordinate, 0.0),
            primeGgxLtcAmplitudeTexture.SampleLevel(coordinate, 0.0).rg);
}
