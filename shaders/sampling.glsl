#ifndef PRIME_SAMPLING_GLSL
#define PRIME_SAMPLING_GLSL

// Random dimensions are addressed explicitly. No result depends on invocation order, so the
// same path contract remains valid if paths later move between wavefront queues.
uint primeHash(uint value) {
    value ^= value >> 16;
    value *= 0x7feb352du;
    value ^= value >> 15;
    value *= 0x846ca68bu;
    return value ^ (value >> 16);
}

float primeRandom(inout PathState path) {
    uint seed = primeHash(path.pixel.x ^ primeHash(path.pixel.y));
    seed ^= primeHash(path.sampleIndex + 0x9e3779b9u);
    seed ^= primeHash(path.sampleEpoch + 0x85ebca6bu);
    seed ^= primeHash(path.sampleDimension++ + 0xc2b2ae35u);
    // Keep the result strictly below one at native f32 precision.
    return float(primeHash(seed) >> 8) * (1.0 / 16777216.0);
}

void primeBasis(vec3 normal, out vec3 tangent, out vec3 bitangent) {
    vec3 helper = abs(normal.z) < 0.999 ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0);
    tangent = normalize(cross(helper, normal));
    bitangent = cross(normal, tangent);
}

vec3 primeLocalToWorld(vec3 localDirection, vec3 normal) {
    vec3 tangent;
    vec3 bitangent;
    primeBasis(normal, tangent, bitangent);
    return normalize(tangent * localDirection.x + bitangent * localDirection.y
            + normal * localDirection.z);
}

#endif
