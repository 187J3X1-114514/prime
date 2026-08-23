#language slang 2026
module "contract/path.slang";

// Stable values shared by hit stages, transport policies and queued path storage.
public static const uint PRIME_HIT_NONE = 0u;
public static const uint PRIME_HIT_SURFACE = 1u;
public static const uint PRIME_NO_PRIMITIVE_INDEX = 0xffffffffu;
public static const uint PRIME_PATH_PREVIOUS_DISCRETE = 1u;
public static const uint PRIME_PATH_PREVIOUS_NO_SUN_NEE = 2u;
public static const uint PRIME_SUN_FAMILY_PROBABILITY_STEPS = 15u;
public static const uint PRIME_SUN_FAMILY_MINIMUM_MIXED_CODE = 2u;
public static const uint PRIME_SUN_FAMILY_MAXIMUM_MIXED_CODE = 13u;
public static const uint PRIME_WAVEFRONT_SURFACE_SUN_PROBABILITY_SHIFT = 8u;
public static const uint PRIME_WAVEFRONT_SURFACE_SUN_PROBABILITY_MASK = 0x1f00u;
