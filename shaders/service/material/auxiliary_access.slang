#language slang 2026
module "service/material/auxiliary_access.slang";

import "service/material/access.slang";

// Reviewed bindings in shaders/abi.json. Only full material evaluation imports the auxiliary
// arrays, keeping opacity-only hit programs isolated from normal and optical resources.
[[vk::binding(19, 0)]]
public Sampler2D<float4> primeMaterialNormalPages[16];
[[vk::binding(49, 0)]]
public Sampler2D<float4> primeMaterialOpticalPages[16];

public float4 primeSampleMaterialNormal(uint textureId, float2 uv, float lod) {
    uint pages = primeTextureRecordWord(textureId, 16u);
    uint page = pages & 0xffu;
    uint width;
    uint height;
    uint levels;
    primeMaterialNormalPages[NonUniformResourceIndex(page)].GetDimensions(
            0, width, height, levels);
    float2 translated = primeTranslatedUv(
            primeTextureRecordWord(textureId, 8u),
            primeTextureFrameExtent(textureId),
            int2(width, height),
            uv);
    return primeMaterialNormalPages[NonUniformResourceIndex(page)].SampleLevel(
            translated, min(lod, float((pages >> 16u) & 0xffu)));
}

public float4 primeSampleMaterialOptical(uint textureId, float2 uv, float lod) {
    uint pages = primeTextureRecordWord(textureId, 16u);
    uint page = (pages >> 8u) & 0xffu;
    uint width;
    uint height;
    uint levels;
    primeMaterialOpticalPages[NonUniformResourceIndex(page)].GetDimensions(
            0, width, height, levels);
    float2 translated = primeTranslatedUv(
            primeTextureRecordWord(textureId, 12u),
            primeTextureFrameExtent(textureId),
            int2(width, height),
            uv);
    return primeMaterialOpticalPages[NonUniformResourceIndex(page)].SampleLevel(
            translated, min(lod, float((pages >> 24u) & 0xffu)));
}
