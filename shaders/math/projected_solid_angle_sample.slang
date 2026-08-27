#language slang 2026
module "math/projected_solid_angle_sample.slang";

import "math/projected_solid_angle_domain.slang";

struct PrimeProjectedQuadratic {
    float xx;
    float xy;
    float yx;
    float yy;
};

float primeProjectedMix(float first, float second, float amount) {
    return fma(amount, second, fma(-amount, first, first));
}

float primeProjectedDirectionFactorReciprocalSquared(
        float2 ellipse, float2 direction) {
    float ellipseDotDirection = dot(ellipse, direction);
    return fma(
            ellipseDotDirection,
            ellipseDotDirection,
            dot(direction, direction));
}

float primeProjectedDirectionFactor(float2 ellipse, float2 direction) {
    return rsqrt(primeProjectedDirectionFactorReciprocalSquared(ellipse, direction));
}

float primeProjectedNormalizedDirectionFactor(
        float2 ellipse, float2 normalizedDirection) {
    float ellipseDotDirection = dot(ellipse, normalizedDirection);
    return rsqrt(fma(ellipseDotDirection, ellipseDotDirection, 1.0));
}

float2 primeProjectedNormalizeApproxAndFlip(float2 value, float2 semicircle) {
    float scaling = abs(value.x) + abs(value.y);
    scaling = asfloat(asuint(scaling) ^ 0x7f800000u);
    scaling = dot(value, semicircle) >= 0.0 ? scaling : -scaling;
    return scaling * value;
}

PrimeProjectedQuadratic primeProjectedOuterProduct(float2 lhs, float2 rhs) {
    PrimeProjectedQuadratic result;
    result.xx = lhs.x * rhs.x;
    result.xy = lhs.x * rhs.y;
    result.yx = lhs.y * rhs.x;
    result.yy = lhs.y * rhs.y;
    return result;
}

PrimeProjectedQuadratic primeProjectedSubtract(
        PrimeProjectedQuadratic lhs, PrimeProjectedQuadratic rhs) {
    lhs.xx -= rhs.xx;
    lhs.xy -= rhs.xy;
    lhs.yx -= rhs.yx;
    lhs.yy -= rhs.yy;
    return lhs;
}

float2 primeProjectedSolveQuadratic(PrimeProjectedQuadratic quadratic) {
    float coefficient = 0.5 * (quadratic.xy + quadratic.yx);
    float root = sqrt(max(
            0.0, coefficient * coefficient - quadratic.xx * quadratic.yy));
    float scaledRoot = abs(coefficient) + root;
    return coefficient >= 0.0
            ? float2(scaledRoot, -quadratic.xx)
            : float2(quadratic.yy, scaledRoot);
}

float2 primeSampleProjectedSector(
        float2 randomValue,
        float targetArea,
        float2 innerEllipse,
        float2 outerEllipse,
        float2 firstDirection,
        float2 secondDirection) {
    float2 directions[3];
    directions[0] = normalize(firstDirection);
    directions[2] = normalize(secondDirection);
    directions[1] = directions[0] + directions[2];

    float factors[2][3];
    factors[0][0] = primeProjectedNormalizedDirectionFactor(
            innerEllipse, directions[0]);
    factors[0][1] = primeProjectedDirectionFactor(innerEllipse, directions[1]);
    factors[0][2] = primeProjectedNormalizedDirectionFactor(
            innerEllipse, directions[2]);
    factors[1][0] = primeProjectedNormalizedDirectionFactor(
            outerEllipse, directions[0]);
    factors[1][1] = primeProjectedDirectionFactor(outerEllipse, directions[1]);
    factors[1][2] = primeProjectedNormalizedDirectionFactor(
            outerEllipse, directions[2]);

    float2 sectorAreas = float2(
            factors[1][0] * factors[1][1] - factors[0][0] * factors[0][1],
            factors[1][1] * factors[1][2] - factors[0][1] * factors[0][2]);
    float targetQuadrilateralArea = primeProjectedMix(
            -sectorAreas.x, sectorAreas.y, randomValue.x);
    bool firstHalf = targetQuadrilateralArea <= 0.0;
    directions[2] = firstHalf ? directions[0] : directions[2];
    factors[0][2] = firstHalf ? factors[0][0] : factors[0][2];
    factors[1][2] = firstHalf ? factors[1][0] : factors[1][2];
    targetQuadrilateralArea += firstHalf ? sectorAreas.x : -sectorAreas.y;
    targetQuadrilateralArea *= abs(
            directions[1].x * directions[2].y
                    - directions[1].y * directions[2].x);

    float2 normals[2];
    normals[0] = primeProjectedEllipseTransform(
            innerEllipse,
            directions[1] * factors[0][1]
                    + directions[2] * factors[0][2]);
    normals[1] = primeProjectedEllipseTransform(
            outerEllipse,
            directions[1] * factors[1][1]
                    + directions[2] * factors[1][2]);
    float2 offsets = float2(
            dot(normals[0], directions[1]) * factors[0][1],
            dot(normals[1], directions[1]) * factors[1][1]);

    float2 firstOuter = (offsets.y * factors[1][2])
            * primeProjectedRotate90(directions[2]);
    float2 secondOuter = (offsets.x * factors[0][2])
            * primeProjectedRotate90(directions[2])
            + targetQuadrilateralArea * normals[0];
    PrimeProjectedQuadratic quadratic = primeProjectedSubtract(
            primeProjectedOuterProduct(firstOuter, normals[0]),
            primeProjectedOuterProduct(secondOuter, normals[1]));
    float2 currentDirection = primeProjectedSolveQuadratic(quadratic);

    float innerReciprocalRootDeterminant =
            primeProjectedEllipseReciprocalRootDeterminant(innerEllipse);
    float outerReciprocalRootDeterminant =
            primeProjectedEllipseReciprocalRootDeterminant(outerEllipse);
    bool refine = abs(randomValue.x - 0.5) <= 0.5 - 1.0e-5;
    [[unroll]]
    for (uint iteration = 0u; iteration < 2u; ++iteration) {
        if (!refine) break;
        currentDirection = primeProjectedNormalizeApproxAndFlip(
                currentDirection, directions[1]);
        float2 innerDirection = primeProjectedEllipseTransform(
                innerEllipse, currentDirection);
        float2 outerDirection = primeProjectedEllipseTransform(
                outerEllipse, currentDirection);
        float directionDeterminant = max(
                0.0,
                dot(currentDirection, primeProjectedRotate90(directions[0])));
        float error = targetArea - primeProjectedAreaFromTangents(
                innerReciprocalRootDeterminant,
                directionDeterminant
                        / (innerReciprocalRootDeterminant
                                * dot(directions[0], innerDirection)),
                outerReciprocalRootDeterminant,
                directionDeterminant
                        / (outerReciprocalRootDeterminant
                                * dot(directions[0], outerDirection)));
        quadratic = primeProjectedSubtract(
                primeProjectedOuterProduct(
                        innerDirection - outerDirection,
                        primeProjectedRotate90(currentDirection)),
                primeProjectedOuterProduct(
                        (2.0 * error) * innerDirection,
                        outerDirection));
        currentDirection = primeProjectedSolveQuadratic(quadratic);
    }

    currentDirection = dot(currentDirection, directions[1]) >= 0.0
            ? currentDirection
            : -currentDirection;
    float innerFactor = 1.0
            / primeProjectedDirectionFactorReciprocalSquared(
                    innerEllipse, currentDirection);
    float outerFactor = 1.0
            / primeProjectedDirectionFactorReciprocalSquared(
                    outerEllipse, currentDirection);
    currentDirection *= sqrt(
            primeProjectedMix(innerFactor, outerFactor, randomValue.y));
    return currentDirection;
}

public float3 primeSampleProjectedSolidAngle(
        PrimeProjectedSolidAngleDomain domain, float2 randomValue) {
    float targetArea = randomValue.x * domain.area;
    float3 sampledDirection = float3(0.0);
    float2 outerEllipse = float2(0.0);
    float2 firstDirection = float2(0.0);
    if (primeProjectedCentral(domain)) {
        [[unroll]]
        for (uint index = 0u; index < PRIME_PROJECTED_VERTEX_COUNT; ++index) {
            if (index > 0u) targetArea -= domain.sectorAreas[index - 1u];
            outerEllipse = domain.ellipses[index];
            firstDirection = domain.vertices[index];
            if ((index >= 2u && index + 1u == domain.vertexCount)
                    || targetArea < domain.sectorAreas[index]) {
                break;
            }
        }
        float rootDeterminant = sqrt(
                primeProjectedEllipseDeterminant(outerEllipse));
        float angle = 2.0 * targetArea * rootDeterminant;
        sampledDirection.xy = (cos(angle) * rootDeterminant) * firstDirection
                + sin(angle) * primeProjectedRotate90(
                        primeProjectedEllipseTransform(
                                outerEllipse, firstDirection));
        sampledDirection.xy *= sqrt(
                randomValue.y
                        / primeProjectedDirectionFactorReciprocalSquared(
                                outerEllipse, sampledDirection.xy));
    } else {
        float sectorArea = 0.0;
        float2 innerEllipse = domain.innerEllipse0;
        float2 secondDirection = float2(0.0);
        [[unroll]]
        for (uint index = 0u; index < PRIME_PROJECTED_VERTEX_COUNT - 1u; ++index) {
            float2 vertexEllipse = domain.ellipses[index];
            if (index == 0u) {
                outerEllipse = vertexEllipse;
            } else {
                targetArea -= domain.sectorAreas[index - 1u];
                bool inner = primeProjectedInnerEllipse(vertexEllipse);
                innerEllipse = inner ? vertexEllipse : innerEllipse;
                outerEllipse = inner ? outerEllipse : vertexEllipse;
            }
            firstDirection = domain.vertices[index];
            secondDirection = domain.vertices[index + 1u];
            sectorArea = domain.sectorAreas[index];
            if ((index >= 1u && index + 2u == domain.vertexCount)
                    || targetArea < sectorArea) {
                break;
            }
        }
        randomValue.x = targetArea / sectorArea;
        sampledDirection.xy = primeSampleProjectedSector(
                randomValue,
                targetArea,
                innerEllipse,
                outerEllipse,
                firstDirection,
                secondDirection);
    }
    sampledDirection.z = sqrt(max(
            0.0,
            fma(-sampledDirection.x, sampledDirection.x,
                    fma(-sampledDirection.y, sampledDirection.y, 1.0))));
    return sampledDirection;
}
