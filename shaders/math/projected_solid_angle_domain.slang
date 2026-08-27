#language slang 2026
module "math/projected_solid_angle_domain.slang";

import "math/basis.slang";

// Christoph Peters, "BRDF Importance Sampling for Polygonal Lights", 2021.
// The triangle-only specialization is derived from polygon_sampling.glsl under its BSD-3-Clause
// option; see THIRD_PARTY_LICENSES/PROJECTED-SOLID-ANGLE-SAMPLING-NOTICE.txt. A receiver-clipped
// triangle has at most four spherical boundary vertices.
public static const uint PRIME_PROJECTED_VERTEX_COUNT = 4u;
static const float PRIME_PROJECTED_PI = 3.141592653589793;
// Below 128 f32 epsilons, subtractive sector-area construction no longer carries enough
// absolute precision for a reliable two-step inverse. Callers deterministically retain their
// original area proposal for these numerically degenerate domains.
public static const float PRIME_PROJECTED_MIN_AREA = 1.52587890625e-5;

public struct PrimeProjectedSolidAngleDomain {
    public uint vertexCount;
    public float2 vertices[PRIME_PROJECTED_VERTEX_COUNT];
    public float2 ellipses[PRIME_PROJECTED_VERTEX_COUNT];
    public float2 innerEllipse0;
    public float sectorAreas[PRIME_PROJECTED_VERTEX_COUNT];
    public float area;
};

public PrimeProjectedSolidAngleDomain primeInvalidProjectedSolidAngleDomain() {
    PrimeProjectedSolidAngleDomain result;
    result.vertexCount = 0u;
    result.innerEllipse0 = float2(0.0);
    result.area = 0.0;
    [[unroll]]
    for (uint index = 0u; index < PRIME_PROJECTED_VERTEX_COUNT; ++index) {
        result.vertices[index] = float2(0.0);
        result.ellipses[index] = float2(0.0);
        result.sectorAreas[index] = 0.0;
    }
    return result;
}

float primeProjectedKahan(float a, float b, float c, float d) {
    float cd = c * d;
    float error = fma(c, d, -cd);
    float result = fma(a, b, -cd);
    return result - error;
}

float3 primeProjectedCross(float3 lhs, float3 rhs) {
    return float3(
            primeProjectedKahan(lhs.y, rhs.z, lhs.z, rhs.y),
            primeProjectedKahan(lhs.z, rhs.x, lhs.x, rhs.z),
            primeProjectedKahan(lhs.x, rhs.y, lhs.y, rhs.x));
}

float primeProjectedPositiveAtan(float tangent) {
    return atan(tangent) + (tangent < 0.0 ? PRIME_PROJECTED_PI : 0.0);
}

public float2 primeProjectedRotate90(float2 value) {
    return float2(-value.y, value.x);
}

public bool primeProjectedInnerEllipse(float2 ellipse) {
    return (asuint(ellipse.x) & 0x80000000u) != 0u;
}

public bool primeProjectedCentral(PrimeProjectedSolidAngleDomain domain) {
    return domain.innerEllipse0.x > 0.0;
}

float2 primeProjectedEllipseFromEdge(float3 first, float3 second) {
    float3 normal = primeProjectedCross(first, second);
    float scaling = 1.0 / normal.z;
    scaling = primeProjectedInnerEllipse(normal.xy) ? -scaling : scaling;
    float2 ellipse = normal.xy * scaling;
    ellipse.x = normal.z != 0.0 ? ellipse.x : asfloat(0x7f800000u);
    return ellipse;
}

public float2 primeProjectedEllipseTransform(float2 ellipse, float2 point) {
    return fma(float2(dot(ellipse, point)), ellipse, point);
}

public float primeProjectedEllipseDeterminant(float2 ellipse) {
    return fma(ellipse.x, ellipse.x, fma(ellipse.y, ellipse.y, 1.0));
}

public float primeProjectedEllipseReciprocalRootDeterminant(float2 ellipse) {
    return rsqrt(primeProjectedEllipseDeterminant(ellipse));
}

public float primeProjectedAreaFromTangents(
        float innerReciprocalRootDeterminant,
        float innerTangent,
        float outerReciprocalRootDeterminant,
        float outerTangent) {
    float innerArea = innerReciprocalRootDeterminant
            * primeProjectedPositiveAtan(innerTangent);
    float result = fma(
            outerReciprocalRootDeterminant,
            primeProjectedPositiveAtan(outerTangent),
            -innerArea);
    return result > 0.0 ? 0.5 * result : 0.0;
}

float primeProjectedAreaBetweenEllipses(
        float2 innerEllipse,
        float innerReciprocalRootDeterminant,
        float2 outerEllipse,
        float outerReciprocalRootDeterminant,
        float2 firstDirection,
        float2 secondDirection) {
    float directionDeterminant = max(
            0.0, dot(secondDirection, primeProjectedRotate90(firstDirection)));
    float innerDot = innerReciprocalRootDeterminant * dot(
            firstDirection,
            primeProjectedEllipseTransform(innerEllipse, secondDirection));
    float outerDot = outerReciprocalRootDeterminant * dot(
            firstDirection,
            primeProjectedEllipseTransform(outerEllipse, secondDirection));
    return primeProjectedAreaFromTangents(
            innerReciprocalRootDeterminant,
            directionDeterminant / innerDot,
            outerReciprocalRootDeterminant,
            directionDeterminant / outerDot);
}

float primeProjectedEllipseSectorArea(
        float2 ellipse, float2 firstDirection, float2 secondDirection) {
    float reciprocalRootDeterminant =
            primeProjectedEllipseReciprocalRootDeterminant(ellipse);
    float directionDeterminant = max(
            0.0, dot(secondDirection, primeProjectedRotate90(firstDirection)));
    float ellipseDot = reciprocalRootDeterminant * dot(
            firstDirection,
            primeProjectedEllipseTransform(ellipse, secondDirection));
    float area = 0.5 * reciprocalRootDeterminant
            * primeProjectedPositiveAtan(directionDeterminant / ellipseDot);
    return reciprocalRootDeterminant > 0.0 ? area : 0.0;
}

void primeProjectedCompareSwap(
        inout PrimeProjectedSolidAngleDomain domain, uint lhs, uint rhs) {
    float2 lhsCopy = domain.vertices[lhs];
    float normalZ = primeProjectedKahan(
            lhsCopy.x,
            -domain.vertices[rhs].y,
            lhsCopy.y,
            -domain.vertices[rhs].x);
    bool swap = normalZ == 0.0
            ? isinf(domain.ellipses[rhs].x)
            : normalZ > 0.0;
    domain.vertices[lhs] = swap ? domain.vertices[rhs] : lhsCopy;
    domain.vertices[rhs] = swap ? lhsCopy : domain.vertices[rhs];
    lhsCopy = domain.ellipses[lhs];
    domain.ellipses[lhs] = swap ? domain.ellipses[rhs] : lhsCopy;
    domain.ellipses[rhs] = swap ? lhsCopy : domain.ellipses[rhs];
}

void primeProjectedSort(inout PrimeProjectedSolidAngleDomain domain) {
    if (domain.vertexCount == 3u) {
        primeProjectedCompareSwap(domain, 1u, 2u);
    } else {
        primeProjectedCompareSwap(domain, 1u, 3u);
    }
    primeProjectedCompareSwap(domain, 0u, 2u);
    if (domain.vertexCount == 4u) {
        primeProjectedCompareSwap(domain, 2u, 3u);
    }
    primeProjectedCompareSwap(domain, 0u, 1u);
}

float3 primeProjectedHorizonIntersection(float3 lhs, float3 rhs) {
    float factor = lhs.z / (lhs.z - rhs.z);
    return float3(
            fma(float2(factor), rhs.xy, fma(float2(-factor), lhs.xy, lhs.xy)),
            0.0);
}

uint primeClipProjectedTriangle(inout float3 vertices[PRIME_PROJECTED_VERTEX_COUNT]) {
    bool firstAbove = vertices[0].z > 0.0;
    bool secondAbove = vertices[1].z > 0.0;
    bool thirdAbove = vertices[2].z > 0.0;
    if (!firstAbove && !secondAbove && !thirdAbove) {
        return 0u;
    }
    if (firstAbove && secondAbove && thirdAbove) {
        vertices[3] = vertices[0];
        return 3u;
    }
    if (firstAbove && !secondAbove && !thirdAbove) {
        vertices[1] = primeProjectedHorizonIntersection(vertices[0], vertices[1]);
        vertices[2] = primeProjectedHorizonIntersection(vertices[2], vertices[0]);
        vertices[3] = vertices[0];
        return 3u;
    }
    if (!firstAbove && secondAbove && !thirdAbove) {
        vertices[0] = primeProjectedHorizonIntersection(vertices[0], vertices[1]);
        vertices[2] = primeProjectedHorizonIntersection(vertices[1], vertices[2]);
        vertices[3] = vertices[0];
        return 3u;
    }
    if (!firstAbove && !secondAbove && thirdAbove) {
        vertices[0] = primeProjectedHorizonIntersection(vertices[2], vertices[0]);
        vertices[1] = primeProjectedHorizonIntersection(vertices[1], vertices[2]);
        vertices[3] = vertices[0];
        return 3u;
    }
    if (firstAbove && secondAbove) {
        vertices[3] = primeProjectedHorizonIntersection(vertices[2], vertices[0]);
        vertices[2] = primeProjectedHorizonIntersection(vertices[1], vertices[2]);
        return 4u;
    }
    if (secondAbove && thirdAbove) {
        vertices[3] = primeProjectedHorizonIntersection(vertices[2], vertices[0]);
        vertices[0] = primeProjectedHorizonIntersection(vertices[0], vertices[1]);
        return 4u;
    }
    vertices[3] = vertices[2];
    vertices[2] = primeProjectedHorizonIntersection(vertices[1], vertices[2]);
    vertices[1] = primeProjectedHorizonIntersection(vertices[0], vertices[1]);
    return 4u;
}

PrimeProjectedSolidAngleDomain primePrepareProjectedSolidAngleDomain(
        uint vertexCount,
        float3 vertices[PRIME_PROJECTED_VERTEX_COUNT]) {
    PrimeProjectedSolidAngleDomain domain = primeInvalidProjectedSolidAngleDomain();
    domain.vertexCount = vertexCount;
    domain.innerEllipse0 = float2(1.0, 0.0);
    domain.vertices[0] = vertices[0].xy;
    domain.ellipses[0] = primeProjectedEllipseFromEdge(vertices[0], vertices[1]);
    float2 previousEllipse = domain.ellipses[0];
    [[unroll]]
    for (uint index = 1u; index < PRIME_PROJECTED_VERTEX_COUNT; ++index) {
        domain.vertices[index] = vertices[index].xy;
        if (index > 2u && index == domain.vertexCount) break;
        float2 ellipse = primeProjectedEllipseFromEdge(
                vertices[index], vertices[(index + 1u) & 3u]);
        bool inner = primeProjectedInnerEllipse(ellipse);
        domain.ellipses[index] = inner ? previousEllipse : ellipse;
        domain.innerEllipse0 = primeProjectedInnerEllipse(previousEllipse) && !inner
                ? previousEllipse
                : domain.innerEllipse0;
        previousEllipse = ellipse;
    }

    float2 firstEllipse = domain.ellipses[0];
    bool firstInner = primeProjectedInnerEllipse(firstEllipse);
    domain.ellipses[0] = firstInner ? previousEllipse : firstEllipse;
    domain.innerEllipse0 = primeProjectedInnerEllipse(previousEllipse) && !firstInner
            ? previousEllipse
            : domain.innerEllipse0;

    if (primeProjectedCentral(domain)) {
        [[unroll]]
        for (uint index = 0u; index < PRIME_PROJECTED_VERTEX_COUNT; ++index) {
            if (index > 2u && index == domain.vertexCount) break;
            domain.sectorAreas[index] = primeProjectedEllipseSectorArea(
                    domain.ellipses[index],
                    domain.vertices[index],
                    domain.vertices[(index + 1u) & 3u]);
            domain.area += domain.sectorAreas[index];
        }
    } else {
        primeProjectedSort(domain);
        float2 innerEllipse = domain.innerEllipse0;
        float innerReciprocalRootDeterminant =
                primeProjectedEllipseReciprocalRootDeterminant(innerEllipse);
        float2 outerEllipse = float2(0.0);
        float outerReciprocalRootDeterminant = 0.0;
        [[unroll]]
        for (uint index = 0u; index < PRIME_PROJECTED_VERTEX_COUNT - 1u; ++index) {
            if (index > 1u && index + 1u == domain.vertexCount) break;
            float2 vertexEllipse = domain.ellipses[index];
            bool vertexInner = primeProjectedInnerEllipse(vertexEllipse);
            float vertexReciprocalRootDeterminant =
                    primeProjectedEllipseReciprocalRootDeterminant(vertexEllipse);
            if (index == 0u) {
                outerEllipse = vertexEllipse;
                outerReciprocalRootDeterminant = vertexReciprocalRootDeterminant;
            } else {
                innerEllipse = vertexInner ? vertexEllipse : innerEllipse;
                innerReciprocalRootDeterminant = vertexInner
                        ? vertexReciprocalRootDeterminant
                        : innerReciprocalRootDeterminant;
                outerEllipse = vertexInner ? outerEllipse : vertexEllipse;
                outerReciprocalRootDeterminant = vertexInner
                        ? outerReciprocalRootDeterminant
                        : vertexReciprocalRootDeterminant;
            }
            domain.sectorAreas[index] = primeProjectedAreaBetweenEllipses(
                    innerEllipse,
                    innerReciprocalRootDeterminant,
                    outerEllipse,
                    outerReciprocalRootDeterminant,
                    domain.vertices[index],
                    domain.vertices[index + 1u]);
            domain.area += domain.sectorAreas[index];
        }
    }
    return domain;
}

public PrimeProjectedSolidAngleDomain primePrepareProjectedSolidAngleTriangleBasis(
        float3 corner,
        float3 edgeOne,
        float3 edgeTwo,
        float3 receiverPosition,
        float3 receiverNormal,
        float3 tangent,
        float3 bitangent) {
    if (!(dot(receiverNormal, receiverNormal) > 0.0)) {
        return primeInvalidProjectedSolidAngleDomain();
    }
    float3 first = corner - receiverPosition;
    float3 second = corner + edgeOne - receiverPosition;
    float3 third = corner + edgeTwo - receiverPosition;
    float side = dot(cross(edgeOne, edgeTwo), receiverPosition - corner);
    // The projected sampler requires clockwise winding as seen from the receiver.
    if (side > 0.0) {
        float3 swap = second;
        second = third;
        third = swap;
    }

    float3 vertices[PRIME_PROJECTED_VERTEX_COUNT];
    vertices[0] = float3(dot(first, tangent), dot(first, bitangent), dot(first, receiverNormal));
    vertices[1] = float3(dot(second, tangent), dot(second, bitangent), dot(second, receiverNormal));
    vertices[2] = float3(dot(third, tangent), dot(third, bitangent), dot(third, receiverNormal));
    vertices[3] = vertices[0];
    uint vertexCount = primeClipProjectedTriangle(vertices);
    if (vertexCount < 3u) {
        return primeInvalidProjectedSolidAngleDomain();
    }
    PrimeProjectedSolidAngleDomain domain =
            primePrepareProjectedSolidAngleDomain(vertexCount, vertices);
    if (!(domain.area > 0.0) || isnan(domain.area) || isinf(domain.area)) {
        return primeInvalidProjectedSolidAngleDomain();
    }
    return domain;
}

public PrimeProjectedSolidAngleDomain primePrepareProjectedSolidAngleTriangle(
        float3 corner,
        float3 edgeOne,
        float3 edgeTwo,
        float3 receiverPosition,
        float3 receiverNormal) {
    float3 tangent;
    float3 bitangent;
    primeBasis(receiverNormal, tangent, bitangent);
    return primePrepareProjectedSolidAngleTriangleBasis(
            corner,
            edgeOne,
            edgeTwo,
            receiverPosition,
            receiverNormal,
            tangent,
            bitangent);
}

public bool primeProjectedSolidAngleReliable(
        PrimeProjectedSolidAngleDomain domain) {
    return domain.area >= PRIME_PROJECTED_MIN_AREA;
}

public float primeProjectedSolidAnglePdf(
        PrimeProjectedSolidAngleDomain domain,
        float3 receiverNormal,
        float3 direction) {
    float receiverCosine = max(dot(receiverNormal, direction), 0.0);
    return primeProjectedSolidAngleReliable(domain)
            ? receiverCosine / domain.area
            : 0.0;
}
