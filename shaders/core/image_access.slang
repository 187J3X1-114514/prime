#ifndef PRIME_IMAGE_ACCESS_SLANG
#define PRIME_IMAGE_ACCESS_SLANG

int2 primeReadonlyImageSize(readonly RWTexture2D<float4> source) {
    uint width;
    uint height;
    source.GetDimensions(width, height);
    return int2(width, height);
}

int2 primeReadonlyImageSize(readonly RWTexture2D<float2> source) {
    uint width;
    uint height;
    source.GetDimensions(width, height);
    return int2(width, height);
}

int3 primeReadonlyImageSize(readonly RWTexture3D<float4> source) {
    uint width;
    uint height;
    uint depth;
    source.GetDimensions(width, height, depth);
    return int3(width, height, depth);
}

int2 primeWriteImageSize(writeonly RWTexture2D<float4> source) {
    uint width;
    uint height;
    source.GetDimensions(width, height);
    return int2(width, height);
}

int3 primeWriteImageSize(writeonly RWTexture3D<float4> source) {
    uint width;
    uint height;
    uint depth;
    source.GetDimensions(width, height, depth);
    return int3(width, height, depth);
}

float4 primeReadonlyImageLoad(
        readonly RWTexture2D<float4> source, int2 coordinate) {
    return source[coordinate];
}

float2 primeReadonlyImageLoad(
        readonly RWTexture2D<float2> source, int2 coordinate) {
    return source[coordinate];
}

void primeWriteImageStore(
        writeonly RWTexture2D<float4> target, int2 coordinate, float4 value) {
    target[coordinate] = value;
}

void primeWriteImageStore(
        writeonly RWTexture3D<float4> target, int3 coordinate, float4 value) {
    target[coordinate] = value;
}

int2 primeImageSize(RWTexture2D<float4> source) {
    uint width;
    uint height;
    source.GetDimensions(width, height);
    return int2(width, height);
}

int2 primeImageSize(RWTexture2D<float2> source) {
    uint width;
    uint height;
    source.GetDimensions(width, height);
    return int2(width, height);
}

int2 primeImageSize(RWTexture2D<float> source) {
    uint width;
    uint height;
    source.GetDimensions(width, height);
    return int2(width, height);
}

int3 primeImageSize(RWTexture3D<float4> source) {
    uint width;
    uint height;
    uint depth;
    source.GetDimensions(width, height, depth);
    return int3(width, height, depth);
}

float4 primeImageLoad(RWTexture2D<float4> source, int2 coordinate) {
    return source[coordinate];
}

float2 primeImageLoad(RWTexture2D<float2> source, int2 coordinate) {
    return source[coordinate];
}

float primeImageLoad(RWTexture2D<float> source, int2 coordinate) {
    return source[coordinate];
}

float4 primeImageLoad(RWTexture3D<float4> source, int3 coordinate) {
    return source[coordinate];
}

void primeImageStore(
        RWTexture2D<float4> target, int2 coordinate, float4 value) {
    target[coordinate] = value;
}

void primeImageStore(
        RWTexture2D<float2> target, int2 coordinate, float2 value) {
    target[coordinate] = value;
}

void primeImageStore(
        RWTexture2D<float> target, int2 coordinate, float value) {
    target[coordinate] = value;
}

void primeImageStore(
        RWTexture3D<float4> target, int3 coordinate, float4 value) {
    target[coordinate] = value;
}

#endif
