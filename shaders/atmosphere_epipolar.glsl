#ifndef PRIME_ATMOSPHERE_EPIPOLAR_GLSL
#define PRIME_ATMOSPHERE_EPIPOLAR_GLSL

const float ATM_EPIPOLAR_LIMIT = 16384.0;
const float ATM_EPIPOLAR_EPSILON = 1.0e-6;

struct AtmEpipolarSegment {
    vec2 entry;
    vec2 exit;
    bool valid;
};

struct AtmEpipolarRange {
    float start;
    float span;
    vec2 axis;
    float extent;
    bool parallel;
};

vec2 atmEpipolarProjectDirection(
        mat4 inverseViewProjection,
        vec3 worldDirection) {
    vec4 clip = inverse(inverseViewProjection) * vec4(worldDirection, 0.0);
    vec2 projected;
    if (abs(clip.w) > ATM_EPIPOLAR_EPSILON) {
        projected = clip.xy / clip.w;
    } else {
        float lengthSquared = dot(clip.xy, clip.xy);
        projected = lengthSquared > ATM_EPIPOLAR_EPSILON
                ? clip.xy * inversesqrt(lengthSquared) * ATM_EPIPOLAR_LIMIT
                : vec2(0.0);
    }
    if (any(isnan(projected)) || any(isinf(projected))) {
        return vec2(0.0);
    }
    return clamp(
            projected,
            vec2(-ATM_EPIPOLAR_LIMIT),
            vec2(ATM_EPIPOLAR_LIMIT));
}

void atmEpipolarSortPair(inout float first, inout float second) {
    if (first > second) {
        float temporary = first;
        first = second;
        second = temporary;
    }
}

AtmEpipolarRange atmEpipolarVisibleRange(vec2 epipoleNdc) {
    const float TWO_PI = 6.28318530717958647692;
    AtmEpipolarRange result;
    result.axis = vec2(0.0);
    result.extent = 0.0;
    result.parallel = false;
    if (all(lessThanEqual(abs(epipoleNdc), vec2(1.0)))) {
        result.start = -3.14159265358979323846;
        result.span = TWO_PI;
        return result;
    }
    // Beyond this distance the finite epipole differs from parallel lines by
    // less than a pixel, while subtracting two f32 angles becomes less stable.
    if (max(abs(epipoleNdc.x), abs(epipoleNdc.y)) > 2048.0) {
        result.start = 0.0;
        result.span = 1.0;
        result.axis = normalize(-epipoleNdc);
        vec2 perpendicular = vec2(-result.axis.y, result.axis.x);
        result.extent = abs(perpendicular.x) + abs(perpendicular.y);
        result.parallel = true;
        return result;
    }

    float angle0 = mod(
            atan(-1.0 - epipoleNdc.y, -1.0 - epipoleNdc.x)
                    + TWO_PI,
            TWO_PI);
    float angle1 = mod(
            atan(-1.0 - epipoleNdc.y, 1.0 - epipoleNdc.x)
                    + TWO_PI,
            TWO_PI);
    float angle2 = mod(
            atan(1.0 - epipoleNdc.y, 1.0 - epipoleNdc.x)
                    + TWO_PI,
            TWO_PI);
    float angle3 = mod(
            atan(1.0 - epipoleNdc.y, -1.0 - epipoleNdc.x)
                    + TWO_PI,
            TWO_PI);
    atmEpipolarSortPair(angle0, angle1);
    atmEpipolarSortPair(angle2, angle3);
    atmEpipolarSortPair(angle0, angle2);
    atmEpipolarSortPair(angle1, angle3);
    atmEpipolarSortPair(angle1, angle2);

    vec4 gaps = vec4(
            angle1 - angle0,
            angle2 - angle1,
            angle3 - angle2,
            angle0 + TWO_PI - angle3);
    float largestGap = gaps.x;
    result.start = angle1;
    if (gaps.y > largestGap) {
        largestGap = gaps.y;
        result.start = angle2;
    }
    if (gaps.z > largestGap) {
        largestGap = gaps.z;
        result.start = angle3;
    }
    if (gaps.w > largestGap) {
        largestGap = gaps.w;
        result.start = angle0;
    }
    result.span = max(TWO_PI - largestGap, ATM_EPIPOLAR_EPSILON);
    return result;
}

float atmEpipolarRangeCoordinate(
        AtmEpipolarRange range,
        vec2 epipoleNdc,
        vec2 screenNdc) {
    if (range.parallel) {
        vec2 perpendicular = vec2(-range.axis.y, range.axis.x);
        return clamp(
                dot(screenNdc, perpendicular)
                        / max(2.0 * range.extent, ATM_EPIPOLAR_EPSILON)
                        + 0.5,
                0.0,
                1.0);
    }
    const float TWO_PI = 6.28318530717958647692;
    vec2 direction = screenNdc - epipoleNdc;
    float angle = atan(direction.y, direction.x);
    float offset = mod(angle - range.start + TWO_PI, TWO_PI);
    if (offset <= range.span) {
        return offset / range.span;
    }
    // A corner can round to the opposite side of the circular seam. Snap only
    // to the nearer interval endpoint instead of stretching that error across
    // the complete angular domain.
    float distanceToStart = TWO_PI - offset;
    float distanceToEnd = offset - range.span;
    return distanceToStart < distanceToEnd ? 0.0 : 1.0;
}

AtmEpipolarSegment atmEpipolarRaySegment(vec2 origin, vec2 direction) {
    float entryDistance = 0.0;
    float exitDistance = ATM_EPIPOLAR_LIMIT * 4.0;
    bool valid = dot(direction, direction)
            > ATM_EPIPOLAR_EPSILON * ATM_EPIPOLAR_EPSILON;

    if (abs(direction.x) <= ATM_EPIPOLAR_EPSILON) {
        valid = valid && abs(origin.x) <= 1.0;
    } else {
        vec2 distances = (vec2(-1.0, 1.0) - origin.x) / direction.x;
        entryDistance = max(entryDistance, min(distances.x, distances.y));
        exitDistance = min(exitDistance, max(distances.x, distances.y));
    }
    if (abs(direction.y) <= ATM_EPIPOLAR_EPSILON) {
        valid = valid && abs(origin.y) <= 1.0;
    } else {
        vec2 distances = (vec2(-1.0, 1.0) - origin.y) / direction.y;
        entryDistance = max(entryDistance, min(distances.x, distances.y));
        exitDistance = min(exitDistance, max(distances.x, distances.y));
    }
    valid = valid
            && exitDistance >= entryDistance
            && exitDistance >= 0.0;

    AtmEpipolarSegment result;
    result.entry = origin + direction * max(entryDistance, 0.0);
    result.exit = origin + direction * exitDistance;
    result.valid = valid;
    return result;
}

AtmEpipolarSegment atmEpipolarSliceSegment(
        vec2 epipoleNdc,
        AtmEpipolarRange range,
        int slice,
        int sliceCount) {
    float sliceCoordinate =
            (float(slice) + 0.5) / float(sliceCount);
    if (range.parallel) {
        vec2 perpendicular = vec2(-range.axis.y, range.axis.x);
        float offset = mix(
                -range.extent,
                range.extent,
                sliceCoordinate);
        vec2 origin = perpendicular * offset - range.axis * 4.0;
        return atmEpipolarRaySegment(origin, range.axis);
    }
    float angle = range.start + range.span * sliceCoordinate;
    return atmEpipolarRaySegment(
            epipoleNdc,
            vec2(cos(angle), sin(angle)));
}

vec2 atmEpipolarSliceNdc(
        vec2 epipoleNdc,
        ivec2 coordinate,
        ivec2 dimensions) {
    AtmEpipolarRange range = atmEpipolarVisibleRange(epipoleNdc);
    AtmEpipolarSegment segment = atmEpipolarSliceSegment(
            epipoleNdc,
            range,
            coordinate.y,
            dimensions.y);
    float radialCoordinate =
            (float(coordinate.x) + 0.5) / float(dimensions.x);
    return segment.valid
            ? mix(segment.entry, segment.exit, radialCoordinate)
            : vec2(2.0);
}

float atmEpipolarRadialCoordinate(
        vec2 screenNdc,
        AtmEpipolarSegment segment) {
    vec2 extent = segment.exit - segment.entry;
    float lengthSquared = dot(extent, extent);
    return lengthSquared > ATM_EPIPOLAR_EPSILON
            ? clamp(
                    dot(screenNdc - segment.entry, extent) / lengthSquared,
                    0.0,
                    1.0)
            : 0.0;
}

#define ATM_DECLARE_SAMPLE_EPIPOLAR(functionName, source) \
vec4 functionName##Slice(vec2 screenNdc, float normalizedDepth, vec2 epipoleNdc, AtmEpipolarRange range, int slice) { \
    ivec3 dimensions = imageSize(source); \
    AtmEpipolarSegment segment = atmEpipolarSliceSegment( \
            epipoleNdc, range, slice, dimensions.y); \
    float radial = atmEpipolarRadialCoordinate(screenNdc, segment); \
    vec2 coordinate = clamp(vec2(radial, normalizedDepth), vec2(0.0), vec2(1.0)) \
            * vec2(dimensions.x, dimensions.z) - vec2(0.5); \
    ivec2 lower = ivec2(floor(coordinate)); \
    vec2 fraction = fract(coordinate); \
    ivec2 maximum = dimensions.xz - ivec2(1); \
    ivec2 p00 = clamp(lower, ivec2(0), maximum); \
    ivec2 p10 = clamp(lower + ivec2(1, 0), ivec2(0), maximum); \
    ivec2 p01 = clamp(lower + ivec2(0, 1), ivec2(0), maximum); \
    ivec2 p11 = clamp(lower + ivec2(1), ivec2(0), maximum); \
    vec4 depth0 = mix( \
            imageLoad(source, ivec3(p00.x, slice, p00.y)), \
            imageLoad(source, ivec3(p10.x, slice, p10.y)), \
            fraction.x); \
    vec4 depth1 = mix( \
            imageLoad(source, ivec3(p01.x, slice, p01.y)), \
            imageLoad(source, ivec3(p11.x, slice, p11.y)), \
            fraction.x); \
    return mix(depth0, depth1, fraction.y); \
} \
vec4 functionName##WithRange( \
        vec2 screenUv, \
        float normalizedDepth, \
        vec2 epipoleNdc, \
        AtmEpipolarRange range) { \
    ivec3 dimensions = imageSize(source); \
    vec2 screenNdc = screenUv * 2.0 - 1.0; \
    const float twoPi = 6.28318530717958647692; \
    float sliceCoordinate = atmEpipolarRangeCoordinate( \
            range, epipoleNdc, screenNdc) \
            * float(dimensions.y) - 0.5; \
    int lowerSliceUnwrapped = int(floor(sliceCoordinate)); \
    float sliceFraction = fract(sliceCoordinate); \
    bool wraps = !range.parallel \
            && range.span >= twoPi - ATM_EPIPOLAR_EPSILON; \
    int lowerSlice = wraps \
            ? (lowerSliceUnwrapped % dimensions.y + dimensions.y) % dimensions.y \
            : clamp(lowerSliceUnwrapped, 0, dimensions.y - 1); \
    int upperSlice = wraps \
            ? (lowerSlice + 1) % dimensions.y \
            : clamp(lowerSliceUnwrapped + 1, 0, dimensions.y - 1); \
    vec4 lower = functionName##Slice( \
            screenNdc, normalizedDepth, epipoleNdc, range, lowerSlice); \
    vec4 upper = functionName##Slice( \
            screenNdc, normalizedDepth, epipoleNdc, range, upperSlice); \
    return mix(lower, upper, sliceFraction); \
} \
vec4 functionName(vec2 screenUv, float normalizedDepth, vec2 epipoleNdc) { \
    return functionName##WithRange( \
            screenUv, \
            normalizedDepth, \
            epipoleNdc, \
            atmEpipolarVisibleRange(epipoleNdc)); \
}

#endif
