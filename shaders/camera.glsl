#ifndef PRIME_CAMERA_GLSL
#define PRIME_CAMERA_GLSL

// This is the single camera-sampling contract shared by the opaque and transparent passes.
// FSR 3.1's base-2/base-3 Halton sample is screen-constant for a frame; using another jitter or
// pixel-centre convention in the transparent pass would detach glass edges from opaque depth.
vec2 primeCameraSample() {
#if defined(PRIME_SCREENSHOT_MODE)
    // Screenshot mode is an actual Monte Carlo accumulator, so its primary sample belongs to
    // the same Owen-scrambled Sobol contract as every later path decision. It deliberately does
    // not reuse FSR's short, repeating Halton phase: FSR is absent and a finite temporal jitter
    // cycle would stop improving pixel integration after only a few frames.
    PrimeSampleBase base;
    base.pixel = gl_LaunchIDEXT.xy;
    base.sampleIndex = primePush.path.x;
    base.sampleEpoch = primePush.path.y;
    base.vertexIndex = 0u;
    base.pathIndex = 0u;
    return primeSobolSample2D(
            base, PRIME_SAMPLE_EFFECT_CAMERA, PRIME_SAMPLE_DIMENSION_PRIMARY);
#else
    uint jitterPhase = max(
            (primePush.path.z >> 16u) & PRIME_PATH_JITTER_PHASE_MASK,
            1u);
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
    return vec2(halton2, halton3);
#endif
}

vec3 primeCameraRayDirection(uvec2 pixel, vec2 cameraSample) {
    vec2 uv = (vec2(pixel) + cameraSample) / vec2(primePush.outputExtent);
    vec2 ndc = vec2(uv.x * 2.0 - 1.0, uv.y * 2.0 - 1.0);
    // Minecraft's Vulkan projection uses reversed Z: clip z=1 is near and z=0 is far.
    vec4 nearPoint = primePush.inverseViewProjection * vec4(ndc, 1.0, 1.0);
    vec4 farPoint = primePush.inverseViewProjection * vec4(ndc, 0.0, 1.0);
    return normalize(farPoint.xyz / farPoint.w - nearPoint.xyz / nearPoint.w);
}

PathState primeCameraPath(uvec2 pixel, uint pathIndex, vec2 cameraSample) {
    PathState path;
    path.physicalOrigin = primePush.cameraPosition;
    path.bounce = 0u;
    path.traceOrigin = primePush.cameraPosition;
    path.sampleDimension = pathIndex;
    path.rayDirection = primeCameraRayDirection(pixel, cameraSample);
    path.flags = PRIME_PATH_PREVIOUS_DELTA;
    path.throughput = vec3(1.0);
    path.previousBsdfPdf = 0.0;
    path.radiance = vec3(0.0);
    path.pixel = pixel;
    path.sampleIndex = primePush.path.x;
    path.sampleEpoch = primePush.path.y;
    return path;
}

#endif
