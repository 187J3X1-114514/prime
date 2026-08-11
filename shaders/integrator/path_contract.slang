#language slang 2026
module "integrator/path_contract.slang";

// Stable semantic values shared by hit shaders, integrator stages, and queued path storage.
public static const uint PRIME_HIT_NONE = 0u;
public static const uint PRIME_HIT_SURFACE = 1u;
public static const uint PRIME_PATH_PREVIOUS_DISCRETE = 1u;
