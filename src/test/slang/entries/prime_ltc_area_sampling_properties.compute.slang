#include "core/prime_test_abi.slang"
import "math/projected_solid_angle_domain.slang";
import "math/projected_solid_angle_sample.slang";
import "math/light_receiver.slang";
import "service/light/brdf_contract.slang";
import "service/light/brdf_ltc.slang";

static const uint PRIME_LTC_INPUT_WORDS = 9u;
static const uint PRIME_LTC_WITNESS_WORDS = 12u;

struct PrimeLtcTestResult {
    uint mask;
    float4 domain;
    float4 sample;
};

void primeClassifyLtc(
        inout PrimeLtcTestResult result, PrimeGgxLtc ltc) {
    result.mask |= primeTestClassify(float4(ltc.a, ltc.b, ltc.c, ltc.d));
    result.mask |= primeTestClassify(float4(
            ltc.e, ltc.albedo, ltc.determinant, 0.0));
    result.mask |= primeTestClassify(ltc.basisX);
    result.mask |= primeTestClassify(ltc.basisY);
    result.mask |= primeTestClassify(ltc.basisZ);
}

void primeCheckLtcAreaSampling(
        inout PrimeLtcTestResult result,
        uint diffuseCode,
        float3 corner,
        float3 firstEdge,
        float3 secondEdge,
        float3 receiver,
        float3 normal,
        float3 viewDirection,
        float4 matrix,
        float2 amplitude,
        float2 randomValue) {
    uint packedNormal = primePackLightReceiverNormal(normal);
    uint packedBrdf = primePackLightReceiverNormal(viewDirection)
            | ((diffuseCode & PRIME_AREA_BRDF_DIFFUSE_MASK)
                    << PRIME_AREA_BRDF_DIFFUSE_SHIFT);
    PrimeGgxLtc ltc = primeMakeGgxLtc(
            packedNormal, packedBrdf, matrix, amplitude);
    primeClassifyLtc(result, ltc);
    if (!(ltc.determinant > 0.0)
            || !primeTestClose(dot(ltc.basisX, ltc.basisX), 1.0, 2.0e-5, 2.0e-5)
            || !primeTestClose(dot(ltc.basisY, ltc.basisY), 1.0, 2.0e-5, 2.0e-5)
            || !primeTestClose(dot(ltc.basisZ, ltc.basisZ), 1.0, 2.0e-5, 2.0e-5)
            || abs(dot(ltc.basisX, ltc.basisY)) > 3.0e-5
            || abs(dot(ltc.basisX, ltc.basisZ)) > 3.0e-5
            || abs(dot(ltc.basisY, ltc.basisZ)) > 3.0e-5) {
        result.mask |= PRIME_TEST_FAILURE_ORTHOGONAL;
    }

    PrimeProjectedSolidAngleDomain domain = primePrepareGgxLtcTriangle(
            ltc, corner, firstEdge, secondEdge, receiver);
    PrimeProjectedSolidAngleDomain reversed = primePrepareGgxLtcTriangle(
            ltc, corner, secondEdge, firstEdge, receiver);
    result.mask |= primeTestClassify(float2(domain.area, reversed.area));
    bool reliable = primeProjectedSolidAngleReliable(domain.area);
    bool reversedReliable = primeProjectedSolidAngleReliable(reversed.area);
    if (reliable != reversedReliable) {
        result.mask |= PRIME_TEST_FAILURE_SYMMETRY;
    }
    if (!reliable || !reversedReliable) {
        if (primeGgxLtcPdf(ltc, domain.area, ltc.basisZ) != 0.0
                || primeGgxLtcPdf(ltc, reversed.area, ltc.basisZ) != 0.0) {
            result.mask |= PRIME_TEST_FAILURE_PDF;
        }
        result.domain = float4(domain.area, reversed.area, 0.0, 0.0);
        return;
    }
    if (!primeTestClose(domain.area, reversed.area, 3.0e-6, 3.0e-4)) {
        result.mask |= PRIME_TEST_FAILURE_SYMMETRY;
    }

    float3 cosineDirection = primeSampleProjectedSolidAngle(
            domain, randomValue);
    float3 shadingDirection = normalize(
            primeGgxLtcCosineToShading(ltc, cosineDirection));
    float3 worldDirection = normalize(
            primeGgxLtcShadingToWorld(ltc, shadingDirection));
    float3 roundTrip = normalize(primeGgxLtcShadingToCosine(
            ltc, primeGgxLtcWorldToShading(ltc, worldDirection)));
    result.mask |= primeTestClassify(float4(worldDirection, 0.0));
    result.mask |= primeTestClassify(roundTrip);
    if (!primeTestClose(dot(worldDirection, worldDirection), 1.0, 3.0e-5, 3.0e-5)
            // The normalized UNORM fit contains near-singular mirror transforms. Below this
            // determinant, the hit and PDF checks remain meaningful but an f32 inverse round
            // trip is necessarily ill-conditioned.
            || (ltc.determinant >= 1.0e-3
                    && !primeTestClose(
                            cosineDirection, roundTrip, 6.0e-5, 6.0e-5))) {
        result.mask |= PRIME_TEST_FAILURE_ROUND_TRIP;
    }

    float pdf = primeGgxLtcPdf(ltc, domain.area, worldDirection);
    float reversedPdf = primeGgxLtcPdf(ltc, reversed.area, worldDirection);
    float3 transformed = primeGgxLtcShadingToCosine(
            ltc, primeGgxLtcWorldToShading(ltc, worldDirection));
    float lengthSquared = dot(transformed, transformed);
    float expectedPdf = max(transformed.z, 0.0) * ltc.determinant
            / (lengthSquared * lengthSquared * domain.area);
    result.mask |= primeTestClassify(float3(pdf, reversedPdf, expectedPdf));
    if (!(pdf > 0.0)
            || !primeTestClose(pdf, expectedPdf, 3.0e-6, 5.0e-5)
            || !primeTestClose(pdf, reversedPdf, 3.0e-6, 4.0e-4)) {
        result.mask |= PRIME_TEST_FAILURE_PDF;
    }

    float3 edgeCross = cross(firstEdge, secondEdge);
    float distance = dot(edgeCross, corner - receiver)
            / dot(edgeCross, worldDirection);
    float3 relative = receiver + distance * worldDirection - corner;
    float inverseAreaSquared = 1.0 / dot(edgeCross, edgeCross);
    float2 barycentric = float2(
            dot(cross(relative, secondEdge), edgeCross) * inverseAreaSquared,
            dot(cross(firstEdge, relative), edgeCross) * inverseAreaSquared);
    result.mask |= primeTestClassify(float3(barycentric, distance));
    if (!(distance > 0.0)
            || any(barycentric < float2(-3.0e-4))
            || barycentric.x + barycentric.y > 1.0 + 3.0e-4) {
        result.mask |= PRIME_TEST_FAILURE_RANGE;
    }

    PrimeProjectedSolidAngleDomain diffuseDomain =
            primePrepareProjectedSolidAngleTriangle(
                    corner,
                    firstEdge,
                    secondEdge,
                    receiver,
                    primeUnpackLightReceiverNormal(packedNormal));
    float diffuseRatio = primeAreaDiffuseRatio(
            packedBrdf,
            ltc.albedo,
            diffuseDomain.area,
            domain.area);
    if (diffuseRatio < 0.0 || diffuseRatio > 1.0) {
        result.mask |= PRIME_TEST_FAILURE_VALUE;
    }
    result.domain = float4(domain.area, reversed.area, pdf, reversedPdf);
    float3 roundTripError = abs(cosineDirection - roundTrip);
    result.sample = float4(
            barycentric,
            distance,
            max(max(roundTripError.x, roundTripError.y), roundTripError.z));
}

[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID) {
    uint caseIndex;
    if (!primeTestBegin(dispatchThreadId, caseIndex)) return;
    uint inputOffset = primeTestInputOffset(caseIndex, PRIME_LTC_INPUT_WORDS);
    uint4 control = primeTestInput[inputOffset];
    float3 corner = asfloat(primeTestInput[inputOffset + 1]).xyz;
    float3 firstEdge = asfloat(primeTestInput[inputOffset + 2]).xyz;
    float3 secondEdge = asfloat(primeTestInput[inputOffset + 3]).xyz;
    float3 receiver = asfloat(primeTestInput[inputOffset + 4]).xyz;
    float3 normal = asfloat(primeTestInput[inputOffset + 5]).xyz;
    float3 viewDirection = asfloat(primeTestInput[inputOffset + 6]).xyz;
    float4 matrix = asfloat(primeTestInput[inputOffset + 7]);
    float4 amplitudeAndRandom = asfloat(primeTestInput[inputOffset + 8]);

    PrimeLtcTestResult result;
    result.mask = 0u;
    result.domain = float4(0.0);
    result.sample = float4(0.0);
    primeCheckLtcAreaSampling(
            result,
            control.y,
            corner,
            firstEdge,
            secondEdge,
            receiver,
            normal,
            viewDirection,
            matrix,
            amplitudeAndRandom.xy,
            amplitudeAndRandom.zw);

    uint outputWords = primeTestOutputWords();
    uint outputOffset = primeTestOutputOffset(caseIndex, outputWords);
    primeTestOutput[outputOffset] = uint4(
            result.mask, control.x, caseIndex, control.y);
    if (outputWords >= PRIME_LTC_WITNESS_WORDS) {
        primeTestOutput[outputOffset + 1] = control;
        [[unroll]]
        for (uint index = 1u; index < PRIME_LTC_INPUT_WORDS; ++index) {
            primeTestOutput[outputOffset + 1u + index] =
                    primeTestInput[inputOffset + index];
        }
        primeTestOutput[outputOffset + 10] = asuint(result.domain);
        primeTestOutput[outputOffset + 11] = asuint(result.sample);
    }
}
