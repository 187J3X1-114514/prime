#language slang 2026
module "service/light/brdf_contract.slang";

public static const uint PRIME_AREA_BRDF_ROUGHNESS_SHIFT = 16u;
public static const uint PRIME_AREA_BRDF_ROUGHNESS_MASK = 0x3fu;
public static const uint PRIME_AREA_BRDF_F0_SHIFT = 22u;
public static const uint PRIME_AREA_BRDF_F0_MASK = 0x3fu;
public static const uint PRIME_AREA_BRDF_DIFFUSE_SHIFT = 28u;
public static const uint PRIME_AREA_BRDF_DIFFUSE_MASK = 0xfu;
