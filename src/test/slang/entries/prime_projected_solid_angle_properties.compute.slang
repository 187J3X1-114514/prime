#include "core/prime_test_abi.slang"
import "math/basis.slang";
import "math/projected_solid_angle_domain.slang";
import "math/projected_solid_angle_sample.slang";

static const uint PRIME_PROJECTED_INPUT_WORDS = 7u;
static const uint PRIME_PROJECTED_WITNESS_WORDS = 11u;
static const float PRIME_PROJECTED_TEST_PI = 3.141592653589793;

struct PrimeProjectedTestResult {
    uint mask;
    float4 domain;
    float4 sample;
    float4 intersection;
};

void primeProjectedClassify(
        inout PrimeProjectedTestResult result,
        PrimeProjectedSolidAngleDomain domain) {
    result.mask |= primeTestClassify(domain.area);
    result.mask |= primeTestClassify(domain.innerEllipse0);
    [[unroll]]
    for (uint index = 0u; index < PRIME_PROJECTED_VERTEX_COUNT; ++index) {
        result.mask |= primeTestClassify(domain.vertices[index]);
        result.mask |= primeTestClassify(domain.ellipses[index]);
        result.mask |= primeTestClassify(domain.sectorAreas[index]);
    }
}

void primeCheckProjectedSolidAngle(
        inout PrimeProjectedTestResult result,
        uint expectedVertexCount,
        float3 corner,
        float3 firstEdge,
        float3 secondEdge,
        float3 receiverPosition,
        float3 receiverNormal,
        float2 randomValue) {
    PrimeProjectedSolidAngleDomain domain =
            primePrepareProjectedSolidAngleTriangle(
                    corner,
                    firstEdge,
                    secondEdge,
                    receiverPosition,
                    receiverNormal);
    PrimeProjectedSolidAngleDomain reversed =
            primePrepareProjectedSolidAngleTriangle(
                    corner,
                    secondEdge,
                    firstEdge,
                    receiverPosition,
                    receiverNormal);
    primeProjectedClassify(result, domain);
    primeProjectedClassify(result, reversed);
    bool flexibleGrazing = expectedVertexCount == 0xffffffffu;
    if ((!flexibleGrazing && (domain.vertexCount != expectedVertexCount
                    || reversed.vertexCount != expectedVertexCount))
            || (flexibleGrazing
                    && (domain.vertexCount != reversed.vertexCount
                            || (domain.vertexCount != 0u
                                    && domain.vertexCount != 3u)))) {
        result.mask |= PRIME_TEST_FAILURE_EVENT;
    }
    if (expectedVertexCount == 0u) {
        if (domain.area != 0.0 || reversed.area != 0.0
                || primeProjectedSolidAnglePdf(
                        domain.area, receiverNormal, receiverNormal) != 0.0) {
            result.mask |= PRIME_TEST_FAILURE_PDF;
        }
        result.domain = float4(domain.area, reversed.area, 0.0, 0.0);
        return;
    }
    bool reliable = primeProjectedSolidAngleReliable(domain.area);
    bool reversedReliable = primeProjectedSolidAngleReliable(reversed.area);
    if (reliable != reversedReliable) {
        result.mask |= PRIME_TEST_FAILURE_SYMMETRY;
    }
    if (!reliable || !reversedReliable) {
        if (primeProjectedSolidAnglePdf(
                domain.area, receiverNormal, receiverNormal) != 0.0) {
            result.mask |= PRIME_TEST_FAILURE_PDF;
        }
        result.domain = float4(domain.area, reversed.area, 0.0, 0.0);
        return;
    }
    if (!(domain.area > 0.0) || domain.area > PRIME_PROJECTED_TEST_PI
            || !primeTestClose(domain.area, reversed.area, 2.0e-6, 2.0e-4)) {
        result.mask |= PRIME_TEST_FAILURE_RANGE;
    }

    float3 localDirection = primeSampleProjectedSolidAngle(domain, randomValue);
    result.mask |= primeTestClassify(localDirection);
    if (!primeTestClose(dot(localDirection, localDirection), 1.0, 3.0e-5, 3.0e-5)) {
        result.mask |= PRIME_TEST_FAILURE_UNIT_LENGTH;
    }
    if (localDirection.z < -2.0e-6) {
        result.mask |= PRIME_TEST_FAILURE_HEMISPHERE;
    }
    float3 tangent;
    float3 bitangent;
    primeBasis(receiverNormal, tangent, bitangent);
    float3 direction = normalize(
            tangent * localDirection.x
                    + bitangent * localDirection.y
                    + receiverNormal * localDirection.z);
    float receiverCosine = dot(receiverNormal, direction);
    float pdf = primeProjectedSolidAnglePdf(
            domain.area, receiverNormal, direction);
    float reversedPdf = primeProjectedSolidAnglePdf(
            reversed.area, receiverNormal, direction);
    result.mask |= primeTestClassify(float4(direction, pdf));
    result.mask |= primeTestClassify(reversedPdf);
    if (receiverCosine < -2.0e-6
            || pdf < 0.0
            || !primeTestClose(pdf, max(receiverCosine, 0.0) / domain.area,
                    2.0e-6, 2.0e-5)
            || !primeTestClose(pdf, reversedPdf, 2.0e-6, 2.0e-4)) {
        result.mask |= PRIME_TEST_FAILURE_PDF;
    }

    float3 edgeCross = cross(firstEdge, secondEdge);
    float planeDenominator = dot(edgeCross, direction);
    float distance = dot(edgeCross, corner - receiverPosition)
            / planeDenominator;
    float3 relative = receiverPosition + distance * direction - corner;
    float inverseAreaSquared = 1.0 / dot(edgeCross, edgeCross);
    float2 barycentric = float2(
            dot(cross(relative, secondEdge), edgeCross) * inverseAreaSquared,
            dot(cross(firstEdge, relative), edgeCross) * inverseAreaSquared);
    result.mask |= primeTestClassify(float3(barycentric, distance));
    float barycentricTolerance = 2.0e-4;
    if (!(distance > 0.0)
            || any(barycentric < float2(-barycentricTolerance))
            || barycentric.x + barycentric.y > 1.0 + barycentricTolerance) {
        result.mask |= PRIME_TEST_FAILURE_RANGE;
    }
    float3 hitPosition = corner
            + firstEdge * barycentric.x
            + secondEdge * barycentric.y;
    float3 reconstructedDirection = normalize(hitPosition - receiverPosition);
    if (!primeTestClose(direction, reconstructedDirection, 3.0e-5, 3.0e-5)) {
        result.mask |= PRIME_TEST_FAILURE_ROUND_TRIP;
    }

    result.domain = float4(
            domain.area, reversed.area, pdf, reversedPdf);
    result.sample = float4(direction, receiverCosine);
    result.intersection = float4(barycentric, distance, localDirection.z);
}

[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID) {
    uint caseIndex;
    if (!primeTestBegin(dispatchThreadId, caseIndex)) return;
    uint inputOffset = primeTestInputOffset(
            caseIndex, PRIME_PROJECTED_INPUT_WORDS);
    uint4 control = primeTestInput[inputOffset];
    float3 corner = asfloat(primeTestInput[inputOffset + 1]).xyz;
    float3 firstEdge = asfloat(primeTestInput[inputOffset + 2]).xyz;
    float3 secondEdge = asfloat(primeTestInput[inputOffset + 3]).xyz;
    float3 receiverPosition = asfloat(primeTestInput[inputOffset + 4]).xyz;
    float3 receiverNormal = asfloat(primeTestInput[inputOffset + 5]).xyz;
    float2 randomValue = asfloat(primeTestInput[inputOffset + 6]).xy;

    PrimeProjectedTestResult result;
    result.mask = 0u;
    result.domain = float4(0.0);
    result.sample = float4(0.0);
    result.intersection = float4(0.0);
    primeCheckProjectedSolidAngle(
            result,
            control.y,
            corner,
            firstEdge,
            secondEdge,
            receiverPosition,
            receiverNormal,
            randomValue);

    uint outputWords = primeTestOutputWords();
    uint outputOffset = primeTestOutputOffset(caseIndex, outputWords);
    primeTestOutput[outputOffset] = uint4(
            result.mask, control.x, caseIndex, control.y);
    if (outputWords >= PRIME_PROJECTED_WITNESS_WORDS) {
        primeTestOutput[outputOffset + 1] = control;
        [[unroll]]
        for (uint index = 1u; index < PRIME_PROJECTED_INPUT_WORDS; ++index) {
            primeTestOutput[outputOffset + 1u + index] =
                    primeTestInput[inputOffset + index];
        }
        primeTestOutput[outputOffset + 8] = asuint(result.domain);
        primeTestOutput[outputOffset + 9] = asuint(result.sample);
        primeTestOutput[outputOffset + 10] = asuint(result.intersection);
    }
}
