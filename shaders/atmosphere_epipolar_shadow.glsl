#ifndef PRIME_ATMOSPHERE_EPIPOLAR_SHADOW_GLSL
#define PRIME_ATMOSPHERE_EPIPOLAR_SHADOW_GLSL

// Adapted from Intel's Outdoor Light Scattering Sample.
// Copyright 2017 Intel Corporation
// Licensed under the Apache License, Version 2.0. See
// THIRD_PARTY_LICENSES/APACHE-2.0.txt and OUTDOOR-LIGHT-SCATTERING-NOTICE.txt.
//
// Prime modification: the reference's raster-shadow line and 1D min/max texture
// are replaced by an exact leaf-cell profile over world-space RT clipmaps.
// Missing coverage and profile overflow stay shadowed to prevent cave light leaks.

const int SUN_SHADOW_PROFILE_NEAR_CAPACITY = 512;
const int SUN_SHADOW_PROFILE_FAR_CAPACITY = 256;
const int SUN_SHADOW_PROFILE_TOTAL_CAPACITY =
        SUN_SHADOW_PROFILE_NEAR_CAPACITY
                + (SUN_SHADOW_CASCADE_COUNT - 1)
                        * SUN_SHADOW_PROFILE_FAR_CAPACITY;
const float SUN_SHADOW_PROFILE_EPSILON = 1.0e-4;

shared vec3 sunShadowProfileBasisU;
shared vec3 sunShadowProfileBasisV;
shared vec3 sunShadowProfileDirectionToSun;
shared vec2 sunShadowProfileCamera;
shared vec2 sunShadowProfileDirection;
shared float sunShadowProfileDirectionMax;
shared float sunShadowProfileCameraDepth;
shared int sunShadowProfileCounts[SUN_SHADOW_CASCADE_COUNT];
shared vec2 sunShadowProfileEntries[SUN_SHADOW_PROFILE_TOTAL_CAPACITY];

int sunShadowProfileBase(int cascade) {
    return cascade == 0
            ? 0
            : SUN_SHADOW_PROFILE_NEAR_CAPACITY
                    + (cascade - 1) * SUN_SHADOW_PROFILE_FAR_CAPACITY;
}

int sunShadowProfileCapacity(int cascade) {
    return cascade == 0
            ? SUN_SHADOW_PROFILE_NEAR_CAPACITY
            : SUN_SHADOW_PROFILE_FAR_CAPACITY;
}

float sunShadowProfileCascadeEnd(int cascade) {
    float radius = SUN_SHADOW_CASCADE_RADIUS_TEXELS
            * sunShadowTexelSize(cascade);
    return min(
            radius / max(
                    sunShadowProfileDirectionMax,
                    SUN_SHADOW_PROFILE_EPSILON),
            SUN_SHADOW_MAX_DISTANCE_METERS);
}

void sunShadowBuildProfileCascade(int cascade) {
    if (atmospherePush.shadowCameraEnabled.w < 0.5) {
        sunShadowProfileCounts[cascade] = 0;
        return;
    }

    float startScalar = cascade == 0
            ? 0.0
            : sunShadowProfileCascadeEnd(cascade - 1);
    float endScalar = sunShadowProfileCascadeEnd(cascade);
    if (endScalar <= startScalar + SUN_SHADOW_PROFILE_EPSILON) {
        sunShadowProfileCounts[cascade] = 0;
        return;
    }

    float texelSize = sunShadowTexelSize(cascade);
    vec2 gridRate = sunShadowProfileDirection / texelSize;
    // Only the toroidal storage coordinate matters. Keeping DDA coordinates
    // within one clipmap period prevents the directional boundary offset from
    // disappearing into the ULP of a large world-space grid coordinate.
    vec2 absoluteGridOrigin = sunShadowProfileCamera / texelSize;
    vec2 gridPoint = mod(
            absoluteGridOrigin + gridRate * startScalar,
            float(PRIME_SUN_SHADOW_RESOLUTION));
    ivec2 cell = ivec2(floor(
            gridPoint + sign(gridRate) * SUN_SHADOW_PROFILE_EPSILON));
    ivec2 cellStep = ivec2(sign(gridRate));
    vec2 nextCrossing = vec2(1.0e20);
    vec2 crossingStep = vec2(1.0e20);
    if (gridRate.x > 0.0) {
        nextCrossing.x =
                (float(cell.x + 1) - gridPoint.x) / gridRate.x;
        crossingStep.x = 1.0 / gridRate.x;
    } else if (gridRate.x < 0.0) {
        nextCrossing.x =
                (float(cell.x) - gridPoint.x) / gridRate.x;
        crossingStep.x = -1.0 / gridRate.x;
    }
    if (gridRate.y > 0.0) {
        nextCrossing.y =
                (float(cell.y + 1) - gridPoint.y) / gridRate.y;
        crossingStep.y = 1.0 / gridRate.y;
    } else if (gridRate.y < 0.0) {
        nextCrossing.y =
                (float(cell.y) - gridPoint.y) / gridRate.y;
        crossingStep.y = -1.0 / gridRate.y;
    }
    float profileLength = endScalar - startScalar;
    float cursor = 0.0;
    int count = 0;
    int base = sunShadowProfileBase(cascade);
    int capacity = sunShadowProfileCapacity(cascade);

    for (; count < capacity; ++count) {
        float spanEnd = min(
                profileLength,
                min(nextCrossing.x, nextCrossing.y));
        if (spanEnd <= cursor + SUN_SHADOW_PROFILE_EPSILON) {
            sunShadowProfileCounts[cascade] = -1;
            return;
        }

        vec2 resolvedShadow =
                sunShadowHierarchyRange(cascade, 0, cell);
        sunShadowProfileEntries[base + count] =
                vec2(startScalar + spanEnd, resolvedShadow.x);
        cursor = spanEnd;
        if (cursor + SUN_SHADOW_PROFILE_EPSILON >= profileLength) {
            sunShadowProfileCounts[cascade] = count + 1;
            return;
        }

        bool crossesX = nextCrossing.x
                <= spanEnd + SUN_SHADOW_PROFILE_EPSILON;
        bool crossesY = nextCrossing.y
                <= spanEnd + SUN_SHADOW_PROFILE_EPSILON;
        if (!crossesX && !crossesY) {
            sunShadowProfileCounts[cascade] = -1;
            return;
        }
        if (crossesX) {
            cell.x += cellStep.x;
            nextCrossing.x += crossingStep.x;
        }
        if (crossesY) {
            cell.y += cellStep.y;
            nextCrossing.y += crossingStep.y;
        }
    }

    // A truncated profile is unknown, never lit.
    sunShadowProfileCounts[cascade] = -1;
}

int sunShadowProfileFind(int cascade, float scalar) {
    int count = sunShadowProfileCounts[cascade];
    int base = sunShadowProfileBase(cascade);
    int low = 0;
    int high = count;
    while (low < high) {
        int middle = (low + high) >> 1;
        if (sunShadowProfileEntries[base + middle].x
                <= scalar + SUN_SHADOW_PROFILE_EPSILON) {
            low = middle + 1;
        } else {
            high = middle;
        }
    }
    return low;
}

float sunShadowProfileCascadeLitLength(
        int cascade,
        float startDistance,
        float endDistance,
        float scalarRate,
        float rayDepthRate) {
    int count = sunShadowProfileCounts[cascade];
    if (count <= 0 || endDistance <= startDistance) {
        return 0.0;
    }

    int base = sunShadowProfileBase(cascade);
    if (scalarRate <= SUN_SHADOW_PROFILE_EPSILON) {
        return primeSunShadowLeafVisibleLength(
                sunShadowProfileCameraDepth,
                rayDepthRate,
                startDistance,
                endDistance,
                sunShadowProfileEntries[base].y,
                SUN_SHADOW_DEPTH_BIAS_METERS);
    }

    float startScalar = scalarRate * startDistance;
    int index = sunShadowProfileFind(cascade, startScalar);
    float cursor = startDistance;
    float litLength = 0.0;
    int capacity = sunShadowProfileCapacity(cascade);
    for (int traversal = 0;
            traversal < capacity
                    && index < count
                    && cursor + SUN_SHADOW_TRAVERSAL_EPSILON < endDistance;
            ++traversal, ++index) {
        vec2 entry = sunShadowProfileEntries[base + index];
        float spanEnd = min(entry.x / scalarRate, endDistance);
        if (spanEnd <= cursor + SUN_SHADOW_TRAVERSAL_EPSILON) {
            continue;
        }
        litLength += primeSunShadowLeafVisibleLength(
                sunShadowProfileCameraDepth,
                rayDepthRate,
                cursor,
                spanEnd,
                entry.y,
                SUN_SHADOW_DEPTH_BIAS_METERS);
        cursor = spanEnd;
    }
    // Any tail not represented by the profile is intentionally shadowed.
    return litLength;
}

float localSunVisibilityInterval(
        vec3 rayDirection,
        float startDistance,
        float endDistance) {
    if (atmospherePush.shadowCameraEnabled.w < 0.5
            || endDistance <= startDistance) {
        return 1.0;
    }
    float tracedEnd = min(endDistance, SUN_SHADOW_MAX_DISTANCE_METERS);
    if (tracedEnd <= startDistance) {
        return 0.0;
    }

    vec2 projectedRay = vec2(
            dot(rayDirection, sunShadowProfileBasisU),
            dot(rayDirection, sunShadowProfileBasisV));
    float scalarRate = dot(projectedRay, sunShadowProfileDirection);
    float perpendicularRate = abs(
            projectedRay.x * sunShadowProfileDirection.y
                    - projectedRay.y * sunShadowProfileDirection.x);
    if (scalarRate < -SUN_SHADOW_PROFILE_EPSILON
            || perpendicularRate > 2.0e-4) {
        return 0.0;
    }
    scalarRate = max(scalarRate, 0.0);
    float rayDepthRate =
            dot(rayDirection, sunShadowProfileDirectionToSun);

    float cursor = startDistance;
    float litLength = 0.0;
    for (int cascade = 0;
            cascade < SUN_SHADOW_CASCADE_COUNT && cursor < tracedEnd;
            ++cascade) {
        float cascadeEnd = scalarRate > SUN_SHADOW_PROFILE_EPSILON
                ? min(
                        tracedEnd,
                        sunShadowProfileCascadeEnd(cascade) / scalarRate)
                : tracedEnd;
        if (cascadeEnd > cursor) {
            litLength += sunShadowProfileCascadeLitLength(
                    cascade,
                    cursor,
                    cascadeEnd,
                    scalarRate,
                    rayDepthRate);
            cursor = cascadeEnd;
        }
    }
    return clamp(
            litLength / (endDistance - startDistance),
            0.0,
            1.0);
}

#endif
