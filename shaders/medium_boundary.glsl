#ifndef PRIME_MEDIUM_BOUNDARY_GLSL
#define PRIME_MEDIUM_BOUNDARY_GLSL

// Adjacent boundaries carry only source-neutral interface facts.
const uint PRIME_ADJACENT_MATERIAL_MASK = 0xffu;
const uint PRIME_ADJACENT_FRESNEL_SHIFT = 8u;
const uint PRIME_ADJACENT_FRESNEL_MASK = 0xffu << PRIME_ADJACENT_FRESNEL_SHIFT;
const uint PRIME_ADJACENT_INTERFACE_VALID = 1u << 16u;
const uint PRIME_ADJACENT_MICRO_GAP_ELIGIBLE = 1u << 17u;

uint primeAdjacentMaterialControl(uint control) {
    return control & PRIME_ADJACENT_MATERIAL_MASK;
}

uint primeAdjacentFresnelCode(uint control) {
    return control >> PRIME_ADJACENT_FRESNEL_SHIFT & 0xffu;
}

#endif
