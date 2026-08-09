#ifndef PRIME_MEDIUM_BOUNDARY_GLSL
#define PRIME_MEDIUM_BOUNDARY_GLSL

// The low 24 bits retain the translated LabPBR specular texel. Boundary-only flags live above it.
const uint PRIME_ADJACENT_MEDIUM_VALID = 1u << 24u;
const uint PRIME_ADJACENT_MEDIUM_WATER = 1u << 25u;
const uint PRIME_ADJACENT_MEDIUM_COLORLESS = 1u << 26u;
const uint PRIME_ADJACENT_MEDIUM_LABPBR_SPECULAR = 1u << 27u;
const uint PRIME_ADJACENT_MEDIUM_AIR_GAP = 1u << 28u;

#endif
