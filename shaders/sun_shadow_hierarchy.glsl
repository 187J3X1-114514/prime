#ifndef PRIME_SUN_SHADOW_HIERARCHY_GLSL
#define PRIME_SUN_SHADOW_HIERARCHY_GLSL

// Adapted to Prime's world-locked 2D clipmap from DiligentFX's hierarchical
// interval classification (DiligentGraphics/DiligentFX@997bc7b).
const int PRIME_SUN_SHADOW_RESOLUTION = 512;
const int PRIME_SUN_SHADOW_HIERARCHY_LEVELS = 10;

int primeSunShadowHierarchyResolution(int level) {
    return PRIME_SUN_SHADOW_RESOLUTION >> level;
}

ivec2 primeSunShadowHierarchyOffset(int level) {
    if (level == 0) return ivec2(0, 0);
    if (level == 1) return ivec2(512, 0);
    if (level == 2) return ivec2(512, 256);
    if (level == 3) return ivec2(640, 256);
    if (level == 4) return ivec2(640, 320);
    if (level == 5) return ivec2(672, 320);
    if (level == 6) return ivec2(672, 336);
    if (level == 7) return ivec2(680, 336);
    if (level == 8) return ivec2(680, 340);
    return ivec2(682, 340);
}

// Exact visible length for one piecewise-constant leaf. The ray depth is affine in
// distance, so a leaf can contain at most one blocker crossing.
float primeSunShadowLeafVisibleLength(
        float rayDepthAtZero,
        float rayDepthRate,
        float startDistance,
        float endDistance,
        float blockerDepth,
        float bias) {
    float startDepth =
            rayDepthAtZero + rayDepthRate * startDistance + bias;
    float endDepth =
            rayDepthAtZero + rayDepthRate * endDistance + bias;
    if (startDepth >= blockerDepth && endDepth >= blockerDepth) {
        return endDistance - startDistance;
    }
    if (startDepth < blockerDepth && endDepth < blockerDepth) {
        return 0.0;
    }
    float crossing = (blockerDepth - bias - rayDepthAtZero) / rayDepthRate;
    return rayDepthRate > 0.0
            ? endDistance - clamp(crossing, startDistance, endDistance)
            : clamp(crossing, startDistance, endDistance) - startDistance;
}

#endif
