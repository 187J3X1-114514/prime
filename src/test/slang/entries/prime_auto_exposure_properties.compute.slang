#include "core/prime_test_abi.slang"
import "core/auto_exposure.slang";
import "core/color_space.slang";
import "post/display_transform.slang";

static const uint PRIME_AUTO_EXPOSURE_INPUT_WORDS = 2;
static const uint PRIME_AUTO_EXPOSURE_WITNESS_WORDS = 4;

[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    uint caseIndex;
    if (!primeTestBegin(dispatchThreadId, caseIndex)) return;
    uint inputOffset =
            primeTestInputOffset(caseIndex, PRIME_AUTO_EXPOSURE_INPUT_WORDS);
    uint4 control = primeTestInput[inputOffset];
    float4 values = asfloat(primeTestInput[inputOffset + 1]);
    uint kind = control.x;
    uint mask = 0;
    float2 actual = float2(0.0);
    float2 expected = float2(0.0);

    if (kind == 0) {
        actual.x = primeAutoExposureTargetEv(
                values.x, values.y, values.z, values.w);
        float range = values.z - values.y;
        float keyBiasEv = range > 0.0
                ? 2.0 * (2.0 * values.x - values.y - values.z) / max(range, 2.0)
                : 0.0;
        expected.x = values.w * clamp(
                log2(0.16) - values.x + keyBiasEv,
                -16.0,
                16.0);
        if (actual.x < -16.0 || actual.x > 16.0) {
            mask |= PRIME_TEST_FAILURE_RANGE;
        }
        if (!primeTestClose(actual.x, expected.x, 1.0e-6, 1.0e-6)) {
            mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
    } else if (kind == 1) {
        float t90 = values.y < values.x ? 0.5 : 2.0;
        actual.x = primeAutoExposureAdapt(values.x, values.y, t90);
        expected.x = values.y + (values.x - values.y) * 0.1;
        if (!primeTestClose(actual.x, expected.x, 2.0e-6, 2.0e-6)) {
            mask |= PRIME_TEST_FAILURE_STATE;
        }
    } else if (kind == 2) {
        actual.x = primeAutoExposureAlbedoScale(float3(values.x), values.y);
        float fullScale = 0.18 / max(clamp(values.x, 0.0, 1.0), 0.02);
        expected.x = lerp(1.0, fullScale, clamp(values.y, 0.0, 1.0));
        if (actual.x < 0.179999 || actual.x > 9.00001) {
            mask |= PRIME_TEST_FAILURE_RANGE;
        }
        if (!primeTestClose(actual.x, expected.x, 2.0e-6, 2.0e-6)) {
            mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
    } else if (kind == 3) {
        uint materialClass = uint(clamp(round(values.x), 0.0, 3.0));
        actual.x = primeAutoExposureMaterialConfidence(materialClass, values.y);
        bool diffuseSurface = materialClass == 0 || materialClass == 3;
        expected.x = diffuseSurface && values.y >= 0.0 ? 1.0 : 0.0;
        if (!primeTestClose(actual.x, expected.x, 1.0e-6, 1.0e-6)) {
            mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
    } else if (kind == 4) {
        actual.x = primeAutoExposureSceneKeyBiasEv(values.x, values.y, values.z);
        float range = values.z - values.y;
        expected.x = range > 0.0
                ? 2.0 * (2.0 * values.x - values.y - values.z) / max(range, 2.0)
                : 0.0;
        if (actual.x < -2.00001 || actual.x > 2.00001) {
            mask |= PRIME_TEST_FAILURE_RANGE;
        }
        if (!primeTestClose(actual.x, expected.x, 1.0e-6, 1.0e-6)) {
            mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
    } else if (kind == 5) {
        float exposure = exp2(values.w);
        actual.x = primeAutoExposureBrightness(values.xyz);
        actual.y = primeAutoExposureBrightness(values.xyz * exposure);
        expected.x = actual.x * exposure;
        expected.y = dot(
                max(values.xyz, float3(0.0)),
                float3(0.2627, 0.6780, 0.0593));
        if (actual.x < 0.0 || actual.y < 0.0) {
            mask |= PRIME_TEST_FAILURE_RANGE;
        }
        if (!primeTestClose(actual.x, expected.y, 2.0e-6, 2.0e-6)
                || !primeTestClose(actual.y, expected.x, 2.0e-5, 2.0e-6)) {
            mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
    } else if (kind == 6) {
        PrimeAgxHsvParameters parameters = primeDefaultAgxHsvParameters();
        float normalized = clamp(
                (values.x - parameters.minimumLog2)
                        * parameters.inverseDynamicRange,
                0.0,
                1.0);
        actual.x = primeAgxCurveComponent(normalized, parameters);
        actual.y = max(
                max(
                        abs(parameters.outputPivot - 0.46135613),
                        abs(parameters.pivotSlope - 2.4606366)),
                max(
                        max(
                                abs(parameters.toePower - 1.55),
                                abs(parameters.shoulderPower - 5.2)),
                        abs(parameters.gamutCompression - 0.05)));
        if (values.x <= parameters.minimumLog2) {
            expected.x = 0.0;
        } else if (values.x >= parameters.minimumLog2
                + 1.0 / parameters.inverseDynamicRange) {
            expected.x = 1.0;
        } else if (primeTestClose(
                normalized, parameters.inputPivot, 1.0e-6, 1.0e-6)) {
            expected.x = parameters.outputPivot;
        } else {
            expected.x = actual.x;
        }
        expected.y = 0.0;
        if (!primeTestClose(actual.x, expected.x, 3.0e-5, 3.0e-5)
                || !primeTestClose(actual.y, expected.y, 1.0e-7, 1.0e-7)
                || !primeTestClose(
                        parameters.toeCoefficient, 11.219474, 1.0e-5, 1.0e-6)
                || !primeTestClose(
                        parameters.shoulderCoefficient, 2568.7498, 2.0e-2, 1.0e-6)) {
            mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
        if (actual.x < -1.0e-5 || actual.x > 1.00001) {
            mask |= PRIME_TEST_FAILURE_RANGE;
        }
    } else if (kind == 7) {
        PrimeAgxHsvParameters parameters = primeDefaultAgxHsvParameters();
        float3 rgb = max(values.xyz, float3(0.0));
        float3 hsv = primeAgxRgbToHsv(rgb);
        float3 roundTrip = primeAgxHsvToRgb(hsv);
        float3 error = abs(roundTrip - rgb);
        float3 mapped = primeAgxHsvTonemap(rgb, parameters);
        actual = float2(
                max(error.x, max(error.y, error.z)),
                max(mapped.x, max(mapped.y, mapped.z)));
        expected = float2(0.0, actual.y);
        if (!primeTestClose(actual.x, 0.0, 4.0e-6, 4.0e-6)) {
            mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
        mask |= primeTestClassify(mapped);
    } else if (kind == 8) {
        float3 hdrRec2020 = max(values.xyz, float3(0.0));
        float3 expectedAgxHsv = clamp(
                primeAgxHsvTonemap(
                        max(
                                primeLinearRec2020ToLinearBt709(hdrRec2020),
                                float3(0.0)),
                        primeDefaultAgxHsvParameters()),
                float3(0.0),
                float3(1.0));
        float3 output = primeDisplayTransformToSrgb(hdrRec2020, 1.0);
        float3 error = abs(output - expectedAgxHsv);
        actual = float2(
                max(error.x, max(error.y, error.z)),
                max(output.x, max(output.y, output.z)));
        expected = float2(0.0, actual.y);
        if (!primeTestClose(actual.x, 0.0, 1.0e-7, 1.0e-7)) {
            mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
        if (any(output < float3(0.0)) || any(output > float3(1.0))) {
            mask |= PRIME_TEST_FAILURE_RANGE;
        }
        mask |= primeTestClassify(output);
    } else {
        mask |= PRIME_TEST_FAILURE_EVENT;
    }
    mask |= primeTestClassify(actual);

    uint outputWords = primeTestOutputWords();
    uint outputOffset = primeTestOutputOffset(caseIndex, outputWords);
    primeTestOutput[outputOffset] = uint4(mask, kind, caseIndex, 0);
    if (outputWords >= PRIME_AUTO_EXPOSURE_WITNESS_WORDS) {
        primeTestOutput[outputOffset + 1] = control;
        primeTestOutput[outputOffset + 2] = asuint(values);
        primeTestOutput[outputOffset + 3] = asuint(float4(actual, expected));
    }
}
