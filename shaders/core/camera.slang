#language slang 2026
module "core/camera.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "core/render_config.slang";
import "core/sampling.slang";
import "integrator/path_contract.slang";

public float2 primeSobolCameraSample(uint2 launchIndex) {
    PrimeSampleBase base;
    base.pixel = launchIndex;
    base.sampleIndex = primeSampleIndex();
    base.sampleEpoch = primeSampleEpoch();
    base.vertexIndex = 0u;
    base.pathIndex = 0u;
    return primeSobolSample2D(
            primePrepareSampleBase(base),
            PRIME_SAMPLE_EFFECT_CAMERA,
            PRIME_SAMPLE_DIMENSION_PRIMARY);
}

public float2 primeOfflineCameraSample(uint2 launchIndex) {
    return primeSobolCameraSample(launchIndex);
}

public float2 primeRealtimeCameraSample(uint2 launchIndex) {
    uint jitterPhase =
            (primePush.path.z >> 16u) & PRIME_PATH_JITTER_PHASE_MASK;
    if (jitterPhase == 0u) {
        return primeSobolCameraSample(launchIndex);
    }
    float halton2 = 0.0;
    float halton3 = 0.0;
    float fraction2 = 1.0;
    float fraction3 = 1.0;
    uint index2 = jitterPhase;
    uint index3 = jitterPhase;
    while (index2 > 0u) {
        fraction2 *= 0.5;
        halton2 += fraction2 * float(index2 % 2u);
        index2 /= 2u;
    }
    while (index3 > 0u) {
        fraction3 /= 3.0;
        halton3 += fraction3 * float(index3 % 3u);
        index3 /= 3u;
    }
    return float2(halton2, halton3);
}

public float3 primeCameraRayDirection(uint2 pixel, float2 cameraSample) {
    float2 uv = (float2(pixel) + cameraSample) / float2(primePush.outputExtent);
    float2 ndc = float2(uv.x * 2.0 - 1.0, uv.y * 2.0 - 1.0);
    float4 nearPoint = primePush.inverseViewProjection
            * float4(ndc, 1.0, 1.0);
    float4 farPoint = primePush.inverseViewProjection
            * float4(ndc, 0.0, 1.0);
    return normalize(farPoint.xyz / farPoint.w - nearPoint.xyz / nearPoint.w);
}

public PathState primeCameraPath(uint2 pixel, uint pathIndex, float2 cameraSample) {
    PathState path;
    path.physicalOrigin = primePush.cameraPosition;
    path.bounce = 0u;
    path.sourcePrimitive = uint2(PRIME_NO_PRIMITIVE_INDEX);
    path.sourceReserved = 0u;
    path.sampleDimension = pathIndex;
    path.rayDirection = primeCameraRayDirection(pixel, cameraSample);
    path.flags = PRIME_PATH_PREVIOUS_DISCRETE;
    path.throughput = float3(1.0);
    path.previousBsdfPdf = 0.0;
    path.etaScale = 1.0;
    path.previousLightNormal = 0u;
    path.pixel = pixel;
    path.sampleIndex = primeSampleIndex();
    path.sampleEpoch = primeSampleEpoch();
    path.previousSharcRoughness = 0.0;
    path.previousSharcEvent = 0u;
    return path;
}
