#language slang 2026
module "integrator/path_contract.slang";

// Stable semantic values shared by hit shaders, integrator stages, and queued path storage.
public static const uint PRIME_HIT_NONE = 0u;
public static const uint PRIME_HIT_SURFACE = 1u;
public static const uint PRIME_PATH_PREVIOUS_DISCRETE = 1u;
// The previous realtime vertex sampled one member of the Sun/Area family mixture. Reverse MIS
// must use the same family probability as the forward one-sample estimator.
public static const uint PRIME_PATH_PREVIOUS_MIXED_LIGHT_NEE = 4u;
public static const float PRIME_SECONDARY_LIGHT_FAMILY_PROBABILITY = 0.5;
