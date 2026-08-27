#language slang 2026
module "service/light/brdf_common.slang";

import "prime_abi_bindings.slang";
import "math/projected_solid_angle_domain.slang";
import "service/light/brdf_ltc.slang";

// Peters' 64x64 GGX LTC: 51 exact-F0 reference slices.
// THIRD_PARTY_LICENSES/POLYGONAL-LIGHT-LTC-NOTICE.txt.
[[vk::binding(PRIME_GGX_LTC_MATRIX_BINDING, 0)]]
Sampler3D<float4> primeGgxLtcMatrixTexture;
[[vk::binding(PRIME_GGX_LTC_AMPLITUDE_BINDING, 0)]]
Sampler3D<float4> primeGgxLtcAmplitudeTexture;

public struct PrimeAreaProposal {
    public float4 fit;
    // LTC e, diffuse ratio, diffuse area, specular area.
    public float4 data;
};

public PrimeGgxLtc primeGgxLtc(
        PrimeAreaProposal p, uint normalCode, uint brdfCode) {
    return primeMakeGgxLtc(normalCode, brdfCode, p.fit,
            float2(p.data.x, 0.0));
}

public PrimeAreaProposal primeAreaProposal(
        float3 corner, float3 firstEdge, float3 secondEdge,
        float3 receiver, float3 normal, uint normalCode, uint brdfCode) {
    PrimeAreaProposal p;
    p.fit = float4(0.0);
    p.data = float4(0.0, 1.0, 0.0, 0.0);
    if (!primeAreaLightBrdfActive(normalCode)) return p;
    p.data.z = primePrepareProjectedSolidAngleTriangle(
            corner, firstEdge, secondEdge, receiver, normal).area;
    if (!primeProjectedSolidAngleReliable(p.data.z)
            || !primeAreaLightBrdfActive(brdfCode)) return p;
    float3 uvw = primeGgxLtcCoordinate(normalCode, brdfCode);
    p.fit = primeGgxLtcMatrixTexture.SampleLevel(uvw, 0.0);
    float2 amp = primeGgxLtcAmplitudeTexture.SampleLevel(uvw, 0.0).rg;
    PrimeGgxLtc ltc = primeMakeGgxLtc(normalCode, brdfCode, p.fit, amp);
    if (ltc.determinant > 0.0) {
        p.data.w = primePrepareGgxLtcTriangle(
                ltc, corner, firstEdge, secondEdge, receiver).area;
    }
    p.data.xy = float2(amp.x,
            primeAreaDiffuseRatio(brdfCode, amp.y, p.data.z, p.data.w));
    return p;
}
