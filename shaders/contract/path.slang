#language slang 2026
module "contract/path.slang";

// Stable values shared by hit stages, transport policies and queued path storage.
public static const uint PRIME_HIT_NONE = 0u;
public static const uint PRIME_HIT_SURFACE = 1u;
public static const uint PRIME_NO_PRIMITIVE_INDEX = 0xffffffffu;
public static const uint PRIME_PATH_PREVIOUS_DISCRETE = 1u;
public static const uint PRIME_PATH_PREVIOUS_NO_SUN_NEE = 2u;
