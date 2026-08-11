#ifndef PRIME_MEDIUM_BOUNDARY_SLANG
#define PRIME_MEDIUM_BOUNDARY_SLANG

static const uint PRIME_ADJACENT_MATERIAL_MASK = 0xff;
static const uint PRIME_ADJACENT_FRESNEL_SHIFT = 8;
static const uint PRIME_ADJACENT_FRESNEL_MASK = 0xff << PRIME_ADJACENT_FRESNEL_SHIFT;
static const uint PRIME_ADJACENT_INTERFACE_VALID = 1 << 16;
static const uint PRIME_ADJACENT_MICRO_GAP_ELIGIBLE = 1 << 17;

uint primeAdjacentMaterialControl(uint control)
{
    return control & PRIME_ADJACENT_MATERIAL_MASK;
}

uint primeAdjacentFresnelCode(uint control)
{
    return (control >> PRIME_ADJACENT_FRESNEL_SHIFT) & 0xff;
}

#endif
