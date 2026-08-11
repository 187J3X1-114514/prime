#ifndef PRIME_WAVEFRONT_MEDIUM_SLANG
#define PRIME_WAVEFRONT_MEDIUM_SLANG

uint2 primePackWavefrontMedium(PrimeRcVolume medium) {
    // Queued transport currently performs absorption and dielectric boundary tracking, which
    // consume only extinction and IOR. Albedo/anisotropy belong to future volume scattering and
    // must get an ABI revision before they become queue-observable state.
    return uint2(
            packHalf2x16(medium.extinction.xy),
            packHalf2x16(float2(medium.extinction.z, medium.ior)));
}

PrimeRcVolume primeUnpackWavefrontMedium(uint2 packedMedium) {
    float2 extinction01 = unpackHalf2x16ToFloat(packedMedium.x);
    float2 extinction2Ior = unpackHalf2x16ToFloat(packedMedium.y);
    PrimeRcVolume medium;
    medium.extinction = float3(extinction01, extinction2Ior.x);
    medium.ior = extinction2Ior.y;
    medium.albedo = float3(0.0);
    medium.anisotropy = 0.0;
    return medium;
}

#endif
