#ifndef PRIME_WAVEFRONT_MEDIUM_GLSL
#define PRIME_WAVEFRONT_MEDIUM_GLSL

uvec2 primePackWavefrontMedium(PrimeRcVolume medium) {
    // Queued transport currently performs absorption and dielectric boundary tracking, which
    // consume only extinction and IOR. Albedo/anisotropy belong to future volume scattering and
    // must get an ABI revision before they become queue-observable state.
    return uvec2(
            packHalf2x16(medium.extinction.xy),
            packHalf2x16(vec2(medium.extinction.z, medium.ior)));
}

PrimeRcVolume primeUnpackWavefrontMedium(uvec2 packedMedium) {
    vec2 extinction01 = unpackHalf2x16(packedMedium.x);
    vec2 extinction2Ior = unpackHalf2x16(packedMedium.y);
    PrimeRcVolume medium;
    medium.extinction = vec3(extinction01, extinction2Ior.x);
    medium.ior = extinction2Ior.y;
    medium.albedo = vec3(0.0);
    medium.anisotropy = 0.0;
    return medium;
}

#endif
