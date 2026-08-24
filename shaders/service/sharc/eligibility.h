#ifndef PRIME_SHARC_ELIGIBILITY_H
#define PRIME_SHARC_ELIGIBILITY_H

bool primeSharcCacheFootprintEligible(
        bool discrete,
        bool glossy,
        float roughness,
        float segmentLength,
        float voxelSize) {
    if (discrete) {
        return false;
    }
    // Diffuse transport is already low-frequency. Rejecting it by the sampled segment footprint
    // creates a systematic energy hole at corners without protecting directional detail.
    if (!glossy) {
        return true;
    }

    const float voxelDiagonalScale = 1.7320508075688772;
    if (segmentLength <= voxelSize * voxelDiagonalScale) {
        return false;
    }
    roughness = min(roughness, 0.99);
    float alpha = roughness * roughness;
    float alphaSquared = alpha * alpha;
    float footprintRadius = segmentLength * sqrt(
            0.5 * alphaSquared / max(1.0 - alphaSquared, 1.0e-6));
    return footprintRadius > voxelSize;
}

#ifndef PRIME_SHARC_ELIGIBILITY_CORE_ONLY
bool primeSharcCacheEligible(
        PathState path,
        SurfaceInteraction surface) {
    float voxelSize = primeSharcVoxelSize(
            surface.position, surface.geometricNormal);
    return primeSharcCacheFootprintEligible(
            path.previousSharcEvent == PRIME_SHARC_EVENT_DISCRETE,
            path.previousSharcEvent == PRIME_SHARC_EVENT_GLOSSY,
            path.previousSharcRoughness,
            surface.t,
            voxelSize);
}
#endif

#endif
