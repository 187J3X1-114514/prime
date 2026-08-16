#language slang 2026
module "realtime/wavefront_medium.slang";

import "bsdf/compact/openpbr_common.slang";

public uint4 primePackWavefrontMedium(PrimeOpenPbrVolume medium) {
    // Queued transport currently performs absorption and dielectric boundary tracking, which
    // consume only extinction and IOR. Albedo/anisotropy belong to future volume scattering and
    // must get an ABI revision before they become queue-observable state.
    return asuint(float4(medium.extinction, medium.ior));
}
public PrimeOpenPbrVolume primeUnpackWavefrontMedium(uint4 packedMedium) {
    float4 transport = asfloat(packedMedium);
    PrimeOpenPbrVolume medium;
    medium.extinction = transport.xyz;
    // IOR selects TIR topology and extinction controls exponential attenuation. Neither may be
    // rounded between dispatches; the 16-byte medium lane holds all four f32 values exactly.
    medium.ior = transport.w;
    medium.albedo = float3(0.0);
    medium.anisotropy = 0.0;
    return medium;
}
