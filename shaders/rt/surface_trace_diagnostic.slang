#ifndef PRIME_SURFACE_TRACE_DIAGNOSTIC_SLANG
#define PRIME_SURFACE_TRACE_DIAGNOSTIC_SLANG

// Miss-only diagnostic bits occupy the payload hit-kind lanes above PRIME_HIT_KIND_MASK.
// Closest-hit replaces the whole field, so successful traversal keeps the production ABI intact.
public static const uint PRIME_TRACE_IGNORED_EXACT_SOURCE = 1u << 8u;
public static const uint PRIME_TRACE_IGNORED_SOURCE_PRIMITIVE = 1u << 9u;
public static const uint PRIME_TRACE_IGNORED_CUTOUT = 1u << 10u;
public static const uint PRIME_TRACE_DIAGNOSTIC_MASK =
        PRIME_TRACE_IGNORED_EXACT_SOURCE
        | PRIME_TRACE_IGNORED_SOURCE_PRIMITIVE
        | PRIME_TRACE_IGNORED_CUTOUT;

#endif
