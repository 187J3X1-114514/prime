#ifndef PRIME_LIGHTWEIGHT_SAMPLING_GLSL
#define PRIME_LIGHTWEIGHT_SAMPLING_GLSL

#if defined(PRIME_LIGHTWEIGHT_ABI)
uint primeLightweightMaximumScatters() {
    return clamp(
            primeMaximumBounces(),
            1u,
            PRIME_LIGHTWEIGHT_MAXIMUM_SCATTERS);
}
#endif

#endif
