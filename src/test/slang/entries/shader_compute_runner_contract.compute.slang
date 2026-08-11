


layout(set = 0, binding = 0, std430) readonly buffer PrimeRunnerInput {
    uint unused[];
} primeRunnerInput;

layout(set = 0, binding = 1, std430) writeonly buffer PrimeRunnerOutput {
    float4 values[];
} primeRunnerOutput;

layout(set = 0, binding = 2) uniform sampler2D primeRunnerSampled;
layout(set = 0, binding = 3, rgba32f) uniform image2D primeRunnerStorage;

layout(push_constant) uniform PrimeRunnerPush {
    float scale;
    float bias;
    float2 unused;
} primeRunnerPush;

[numthreads(1, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID) {
    int2 pixel = int2(gl_GlobalInvocationID.xy);
    int2 size = imageSize(primeRunnerStorage);
    if (any(greaterThanEqual(pixel, size))) return;
    float4 value = texelFetch(primeRunnerSampled, pixel, 0)
            * primeRunnerPush.scale
            + float4(primeRunnerPush.bias);
    imageStore(primeRunnerStorage, pixel, value);
    memoryBarrierImage();
    uint index = uint(pixel.y * size.x + pixel.x);
    primeRunnerOutput.values[index] = imageLoad(primeRunnerStorage, pixel);
}
