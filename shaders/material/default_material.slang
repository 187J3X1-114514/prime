#ifndef PRIME_DEFAULT_MATERIAL_SLANG
#define PRIME_DEFAULT_MATERIAL_SLANG

static const uint PRIME_MATERIAL_FAMILY_MASK = 3;
static const uint PRIME_MATERIAL_FAMILY_OPAQUE = 0;
static const uint PRIME_MATERIAL_FAMILY_DIELECTRIC = 1;
static const uint PRIME_MATERIAL_FAMILY_FOLIAGE = 2;
static const uint PRIME_MATERIAL_MEDIUM_SHIFT = 2;
static const uint PRIME_MATERIAL_MEDIUM_MASK = 3 << PRIME_MATERIAL_MEDIUM_SHIFT;
static const uint PRIME_MATERIAL_MEDIUM_NONE = 0;
static const uint PRIME_MATERIAL_MEDIUM_COLORLESS_GLASS = 1 << PRIME_MATERIAL_MEDIUM_SHIFT;
static const uint PRIME_MATERIAL_MEDIUM_STAINED_GLASS = 2 << PRIME_MATERIAL_MEDIUM_SHIFT;
static const uint PRIME_MATERIAL_MEDIUM_WATER = 3 << PRIME_MATERIAL_MEDIUM_SHIFT;
static const uint PRIME_MATERIAL_THIN_WALLED = 1 << 4;
static const uint PRIME_MATERIAL_ANIMATED = 1 << 5;
static const uint PRIME_MATERIAL_VISIBLE_EMISSION = 1 << 6;
static const uint PRIME_MATERIAL_DECORATIVE_INTERFACE = 1 << 7;

static const uint PRIME_OPTICAL_FRESNEL_MASK = 0xff;
static const uint PRIME_OPTICAL_SUBSURFACE_SHIFT = 8;
static const uint PRIME_OPTICAL_POROSITY_SHIFT = 16;
static const uint PRIME_FRESNEL_DEFAULT_DIELECTRIC = 0;
static const uint PRIME_FRESNEL_IRON = 231;
static const uint PRIME_FRESNEL_GOLD = 232;
static const uint PRIME_FRESNEL_ALUMINIUM = 233;
static const uint PRIME_FRESNEL_CHROME = 234;
static const uint PRIME_FRESNEL_COPPER = 235;
static const uint PRIME_FRESNEL_LEAD = 236;
static const uint PRIME_FRESNEL_PLATINUM = 237;
static const uint PRIME_FRESNEL_SILVER = 238;
static const uint PRIME_FRESNEL_CUSTOM_CONDUCTOR = 239;

static const float PRIME_DEFAULT_DIELECTRIC_F0 = 0.04;
static const float PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS = 0.9;
#ifndef PRIME_RUNTIME_DEFAULT_LINEAR_ROUGHNESS
#define PRIME_RUNTIME_DEFAULT_LINEAR_ROUGHNESS PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS
#endif

static const float PRIME_BUILTIN_ROUGHNESS[13] = {
    0.9, 0.82, 0.48, 0.96, 0.72, 0.98, 0.62,
    0.28, 0.90, 0.38, 0.30, 0.34, 0.78
};

float primeDefaultLinearRoughness()
{
    return clamp(PRIME_RUNTIME_DEFAULT_LINEAR_ROUGHNESS, 0.0, 1.0);
}

float primeBuiltinRoughness(uint materialClass)
{
    return materialClass < 13
            ? PRIME_BUILTIN_ROUGHNESS[materialClass]
            : primeDefaultLinearRoughness();
}

uint primeBuiltinFresnelCode(uint materialClass)
{
    return materialClass == 9
            ? PRIME_FRESNEL_IRON
            : materialClass == 10
                    ? PRIME_FRESNEL_GOLD
                    : materialClass == 11 ? PRIME_FRESNEL_COPPER : 0;
}

uint primeMaterialFamily(uint control) { return control & PRIME_MATERIAL_FAMILY_MASK; }
uint primeMaterialMedium(uint control) { return control & PRIME_MATERIAL_MEDIUM_MASK; }
bool primeMaterialIsTransmissive(uint control)
{
    return primeMaterialFamily(control) == PRIME_MATERIAL_FAMILY_DIELECTRIC;
}
bool primeMaterialIsFoliage(uint control)
{
    return primeMaterialFamily(control) == PRIME_MATERIAL_FAMILY_FOLIAGE;
}
bool primeMaterialIsColorlessGlass(uint control)
{
    return primeMaterialMedium(control) == PRIME_MATERIAL_MEDIUM_COLORLESS_GLASS;
}
bool primeMaterialIsWater(uint control)
{
    return primeMaterialMedium(control) == PRIME_MATERIAL_MEDIUM_WATER;
}
bool primeMaterialIsRoughGlass(uint control)
{
    return (control & PRIME_MATERIAL_DECORATIVE_INTERFACE) != 0;
}
bool primeFresnelIsConductor(uint fresnelCode)
{
    return fresnelCode >= PRIME_FRESNEL_IRON
            && fresnelCode <= PRIME_FRESNEL_CUSTOM_CONDUCTOR;
}
float primeDecodeDielectricF0(uint fresnelCode)
{
    return fresnelCode == PRIME_FRESNEL_DEFAULT_DIELECTRIC
            ? PRIME_DEFAULT_DIELECTRIC_F0
            : clamp(float(fresnelCode - 1) / 255.0, 0.02, 0.17);
}

struct PrimeFsrMasks
{
    float reactive;
    float transparencyAndComposition;
};

PrimeFsrMasks primeFsrMasks(uint control)
{
    PrimeFsrMasks masks;
    if (primeMaterialIsTransmissive(control)) {
        masks.reactive = 0.0;
        masks.transparencyAndComposition = 1.0;
        return masks;
    }
    masks.reactive = 0.0;
    masks.transparencyAndComposition =
            (control & PRIME_MATERIAL_ANIMATED) != 0 ? 0.75 : 0.0;
    return masks;
}

#endif
