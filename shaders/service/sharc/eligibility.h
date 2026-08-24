#ifndef PRIME_SHARC_ELIGIBILITY_H
#define PRIME_SHARC_ELIGIBILITY_H

bool primeSharcCacheEligible(
        PathState path,
        SurfaceInteraction surface) {
    if (path.previousSharcEvent == PRIME_SHARC_EVENT_DISCRETE) {
        return false;
    }

    float voxelSize = primeSharcVoxelSize(
            surface.position, surface.geometricNormal);
    const float voxelDiagonalScale = 1.7320508075688772;
    if (surface.t <= voxelSize * voxelDiagonalScale) {
        return false;
    }
    if (path.previousSharcEvent == PRIME_SHARC_EVENT_GLOSSY) {
        float roughness = min(path.previousSharcRoughness, 0.99);
        float alpha = roughness * roughness;
        float alphaSquared = alpha * alpha;
        float footprintRadius = surface.t * sqrt(
                0.5 * alphaSquared / max(1.0 - alphaSquared, 1.0e-6));
        if (footprintRadius <= voxelSize) {
            return false;
        }
    }
    return true;
}

#endif
