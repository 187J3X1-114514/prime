import "core/image_access.slang";

// This pass runs at the raygen -> RR-prepare boundary. These snapshots are diagnostic-only and
// preserve resources that RR preparation aliases or mutates in place.
[[vk::binding(1, 0)]] [[vk::image_format("rg16f")]] readonly RWTexture2D<float4> rrCaptureTransportMetadata;
[[vk::binding(5, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> rrCaptureMaterial;
[[vk::binding(6, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> rrCaptureSpecularMaterial;
[[vk::binding(14, 0)]] [[vk::image_format("rgba16f")]] writeonly RWTexture2D<float4> rrCaptureRawMaterial;
[[vk::binding(15, 0)]] [[vk::image_format("rgba16f")]] writeonly RWTexture2D<float4> rrCaptureRawSpecularMaterial;
[[vk::binding(16, 0)]] [[vk::image_format("rgba16f")]] writeonly RWTexture2D<float4> rrCaptureRawTransportMetadata;

[numthreads(8, 8, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID) {
    int2 pixel = int2(dispatchThreadId.xy);
    int2 extent = primeWriteImageSize(rrCaptureRawMaterial);
    if (any(pixel >= extent)) return;

    primeWriteImageStore(
            rrCaptureRawMaterial,
            pixel,
            primeReadonlyImageLoad(rrCaptureMaterial, pixel));
    primeWriteImageStore(
            rrCaptureRawSpecularMaterial,
            pixel,
            primeReadonlyImageLoad(rrCaptureSpecularMaterial, pixel));
    float2 metadata = primeReadonlyImageLoad(
            rrCaptureTransportMetadata, pixel).xy;
    primeWriteImageStore(
            rrCaptureRawTransportMetadata,
            pixel,
            float4(metadata, 0.0, 0.0));
}
