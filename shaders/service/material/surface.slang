#language slang 2026
module "service/material/surface.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "math/color_space.slang";
import "math/packed_geometry.slang";
import "policy/runtime/config.slang";
import "model/material/defaults.slang";
import "model/material/labpbr.slang";
import "model/material/translation.slang";
import "model/material/transparent.slang";

float4 primeSampleSceneTexture(uint index, float2 uv, float lod)
{
    return primeSceneTextures[NonUniformResourceIndex(index)].SampleLevel(uv, lod);
}

public struct PrimeSceneTextureDimensions {
    public int2 extent;
    public int mipLevels;
};

public PrimeSceneTextureDimensions primeSceneTextureDimensions(uint index) {
    uint width;
    uint height;
    uint levels;
    primeSceneTextures[NonUniformResourceIndex(index)].GetDimensions(
            0, width, height, levels);
    PrimeSceneTextureDimensions result;
    result.extent = int2(width, height);
    result.mipLevels = int(levels);
    return result;
}

public int2 primeSceneTextureExtent(uint index) {
    return primeSceneTextureDimensions(index).extent;
}

float4 primeLoadSceneTexture(uint index, int2 texel)
{
    return primeSceneTextures[NonUniformResourceIndex(index)].Load(int3(texel, 0));
}
// Minimal Minecraft adapter. The integrator consumes only this result, so future material
// models can replace atlas/tint decoding without changing path scheduling or traversal.
public struct PrimeMaterialSample {
    public float3 baseColor;
    public float opacity;
    public float3 shadingNormal;
    public float roughness;
    public uint materialControl;
    public uint opticalControl;
};

static const uint PRIME_EMITTER_FLAG_TWO_SIDED = 1u;
static const uint PRIME_EMITTER_FLAG_LABPBR_EMISSION = 2u;
public static const uint PRIME_CONSTANT_UV_DENSITY = 0x80000000u;
static const uint PRIME_CONSTANT_UV_OWN_TINT = 1u;
static const uint PRIME_CONSTANT_UV_BAKED_MATERIAL = 2u;
public static const uint PRIME_RECIPE_ALPHA_CUTOUT = 1u;
static const uint PRIME_RECIPE_ANIMATED = 1u << 1u;
static const uint PRIME_RECIPE_SCATTERING_SHIFT = 2u;
static const uint PRIME_RECIPE_SCATTERING_MASK = 3u << PRIME_RECIPE_SCATTERING_SHIFT;
static const uint PRIME_RECIPE_OPAQUE = 0u;
static const uint PRIME_RECIPE_DIELECTRIC_SOLID = 1u << PRIME_RECIPE_SCATTERING_SHIFT;
static const uint PRIME_RECIPE_DIELECTRIC_THIN = 2u << PRIME_RECIPE_SCATTERING_SHIFT;
static const uint PRIME_RECIPE_FOLIAGE_THIN = 3u << PRIME_RECIPE_SCATTERING_SHIFT;
public static const uint PRIME_RECIPE_WATER = 1u << 4u;
static const uint PRIME_RECIPE_TANGENT_NEGATIVE = 1u << 7u;
static const uint PRIME_RECIPE_FRONT_FACE_ONLY = 1u << 8u;
static const uint PRIME_RECIPE_RASTER_COMPOSITE = 1u << 9u;
static const uint PRIME_RECIPE_BUILTIN_SHIFT = 11u;
public static const uint PRIME_RECIPE_BUILTIN_MASK = 15u << PRIME_RECIPE_BUILTIN_SHIFT;
public static const uint PRIME_RECIPE_MATERIAL_MASK = 0xffu | PRIME_RECIPE_BUILTIN_MASK;

public uint primePrimitiveControl(PrimitiveRecord primitive) {
    return (primitive.tint >> 24u)
            | ((primitive.flagsEmitter & 7u) << 8u)
            | ((primitive.flagsEmitter >> 16u) & PRIME_RECIPE_BUILTIN_MASK);
}

public bool primeRecipeIsTransmissive(uint control) {
    uint scattering = control & PRIME_RECIPE_SCATTERING_MASK;
    return scattering == PRIME_RECIPE_DIELECTRIC_SOLID
            || scattering == PRIME_RECIPE_DIELECTRIC_THIN;
}

public bool primeRecipeIsThinWalled(uint control) {
    uint scattering = control & PRIME_RECIPE_SCATTERING_MASK;
    return scattering == PRIME_RECIPE_DIELECTRIC_THIN
            || scattering == PRIME_RECIPE_FOLIAGE_THIN;
}

bool primePrimitiveIsFrontFaceOnly(PrimitiveRecord primitive) {
    return (primitive.flagsEmitter & 1u) != 0u;
}

public bool primeUsesRasterComposite(PrimitiveRecord primitive) {
    return (primitive.flagsEmitter & 2u) != 0u;
}

uint primeRasterCompositeTint(PrimitiveRecord primitive) {
    return (primitive.flagsEmitter >> 3u) & 0x00ffffffu;
}

public static const uint PRIME_DYNAMIC_TEXTURE_FLAG = 0x80000000u;
static const uint PRIME_VISIBLE_EMISSION_FLAG = 1u << 10u;
static const uint PRIME_DYNAMIC_RED_ALPHA_FLAG = 1u << 9u;
static const uint PRIME_DYNAMIC_TEXTURE_INDEX_MASK = 0x3fu;

public uint primePrimitiveTextureIndex(PrimitiveRecord primitive) {
    return (primitive.flagsEmitter & PRIME_DYNAMIC_TEXTURE_FLAG) != 0u
            ? ((primitive.flagsEmitter >> 3u) & PRIME_DYNAMIC_TEXTURE_INDEX_MASK)
            : 0u;
}

bool primeUsesDynamicRedAlpha(PrimitiveRecord primitive) {
    return (primitive.flagsEmitter
            & (PRIME_DYNAMIC_TEXTURE_FLAG | PRIME_DYNAMIC_RED_ALPHA_FLAG))
            == (PRIME_DYNAMIC_TEXTURE_FLAG | PRIME_DYNAMIC_RED_ALPHA_FLAG);
}

float2 primeRasterCompositeUvOffset(PrimitiveRecord primitive) {
    int x = int(primitive.uvDensity << 16u) >> 16;
    int y = int(primitive.uvDensity) >> 16;
    int2 atlasExtent = primeSceneTextureExtent(0);
    return float2(x, y) / float2(max(atlasExtent, int2(1)));
}

float4 primeSamplePrimitiveTexture(
        PrimitiveRecord primitive, float2 uv, float textureLodValue) {
    uint textureIndex = min(
            primePrimitiveTextureIndex(primitive), PRIME_SCENE_TEXTURE_COUNT - 1u);
    return primeSampleSceneTexture(textureIndex, uv, textureLodValue);
}

public bool primeUsesBakedMaterial(PrimitiveRecord primitive) {
    return primitive.uvDensity == PRIME_CONSTANT_UV_DENSITY
            && (primitive.uv2 & PRIME_CONSTANT_UV_BAKED_MATERIAL) != 0u;
}

public float3 primeAtlasBaseColor(
        uint packedTint, float2 uv, float textureLodValue, out float opacity) {
    float4 textureSample = primeSampleSceneTexture(0, uv, textureLodValue);
    float4 tint = primeUnpackTint(packedTint);
    opacity = textureSample.a;
    float3 linearSrgbAlbedo = primeDecodeSrgb(textureSample.rgb) * primeDecodeSrgb(tint.rgb);
    return primeLinearSrgbToLinearRec2020(linearSrgbAlbedo);
}

public float2 primePrimitiveMaterialReferenceUv(PrimitiveRecord primitive) {
    if (primitive.uvDensity == PRIME_CONSTANT_UV_DENSITY) {
        return float2(asfloat(primitive.uv0), asfloat(primitive.uv1));
    }
    float2 first = primeUnpackUv(primitive.uv0);
    float2 second = primeUnpackUv(primitive.uv1);
    float2 third = primeUnpackUv(primitive.uv2);
    return 0.5 * (min(first, min(second, third)) + max(first, max(second, third)));
}

public PrimeMaterialSample primeEvaluateMaterial(
        PrimitiveRecord primitive, float2 uv, float textureLodValue, uint instanceTint) {
    PrimeMaterialSample result;
    bool bakedMaterial = primeUsesBakedMaterial(primitive);
    // Minecraft stores both atlas texels and tint RGB as display-encoded sRGB in UNORM values.
    // Decode both before multiplication, then cross the single material boundary into the
    // integrator's linear Rec.2020 working space. Alpha is coverage and is never color-decoded.
    if (bakedMaterial) {
        float3 linearSrgbAlbedo = primeDecodeSrgb(primeUnpackTint(primitive.tint).rgb);
        bool ownsTint = (primitive.uv2 & PRIME_CONSTANT_UV_OWN_TINT) != 0u;
        if (!ownsTint && (instanceTint & 0x80000000u) != 0u) {
            linearSrgbAlbedo *= primeDecodeSrgb(primeUnpackTint(instanceTint).rgb);
        }
        result.baseColor = primeLinearSrgbToLinearRec2020(linearSrgbAlbedo);
        result.opacity = 1.0;
    } else if (primeUsesRasterComposite(primitive)) {
        float4 baseSample =
                primeSamplePrimitiveTexture(primitive, uv, textureLodValue);
        float2 overlayUv = uv + primeRasterCompositeUvOffset(primitive);
        float4 overlaySample = primeSamplePrimitiveTexture(
                primitive, overlayUv, textureLodValue);
        bool overlayCovered =
                overlaySample.a >= PRIME_CUTOUT_ALPHA_THRESHOLD;
        float4 textureSample = overlayCovered ? overlaySample : baseSample;
        uint packedTint = overlayCovered
                ? primeRasterCompositeTint(primitive)
                : primitive.tint;
        result.opacity = 1.0;
        result.baseColor = primeLinearSrgbToLinearRec2020(
                primeDecodeSrgb(textureSample.rgb)
                        * primeDecodeSrgb(primeUnpackTint(packedTint).rgb));
        uv = overlayCovered ? overlayUv : uv;
    } else {
        float4 textureSample =
                primeSamplePrimitiveTexture(primitive, uv, textureLodValue);
        float4 tint = primeUnpackTint(primitive.tint);
        if (primeUsesDynamicRedAlpha(primitive)) {
            result.opacity = textureSample.r;
            result.baseColor = primeLinearSrgbToLinearRec2020(
                    primeDecodeSrgb(tint.rgb));
        } else {
            result.opacity = textureSample.a;
            result.baseColor = primeLinearSrgbToLinearRec2020(
                    primeDecodeSrgb(textureSample.rgb)
                            * primeDecodeSrgb(tint.rgb));
        }
    }
    uint recipeControl = primePrimitiveControl(primitive);
    uint recipeScattering = recipeControl & PRIME_RECIPE_SCATTERING_MASK;
    bool recipeTransmissive = recipeScattering == PRIME_RECIPE_DIELECTRIC_SOLID
            || recipeScattering == PRIME_RECIPE_DIELECTRIC_THIN;
    uint materialControl = recipeScattering == PRIME_RECIPE_FOLIAGE_THIN
            ? PRIME_MATERIAL_FAMILY_FOLIAGE | PRIME_MATERIAL_THIN_WALLED
            : recipeTransmissive
                    ? PRIME_MATERIAL_FAMILY_DIELECTRIC
                            | (recipeScattering == PRIME_RECIPE_DIELECTRIC_THIN
                                    ? PRIME_MATERIAL_THIN_WALLED
                                    : 0u)
                    : PRIME_MATERIAL_FAMILY_OPAQUE;
    materialControl |= (recipeControl & PRIME_RECIPE_ANIMATED) != 0u
            ? PRIME_MATERIAL_ANIMATED
            : 0u;
    uint builtinClass = recipeControl >> PRIME_RECIPE_BUILTIN_SHIFT & 15u;
    result.roughness = primeUsesVanillaPbrPresets() && builtinClass != 0u
            ? primeBuiltinRoughness(
                    builtinClass,
                    primeConfiguredDefaultLinearRoughness())
            : primeConfiguredDefaultLinearRoughness();
    result.opticalControl = primeUsesVanillaPbrPresets()
            ? primeBuiltinFresnelCode(builtinClass)
            : 0u;
    if (bakedMaterial && (recipeControl & PRIME_RECIPE_OPTICAL_TEXTURE) != 0u) {
        PrimeCanonicalOptics optics = primeAdaptLabPbrSpecular(
                unpackUnorm4x8ToFloat(primitive.uv1));
        result.roughness = optics.roughness;
        result.opticalControl = optics.opticalControl;
    } else if (!bakedMaterial
            && (recipeControl & PRIME_RECIPE_OPTICAL_TEXTURE) != 0u) {
        PrimeCanonicalOptics optics = primeAdaptLabPbrSpecular(
                primeLabPbrSpecularAtlas.SampleLevel(uv, textureLodValue));
        result.roughness = optics.roughness;
        result.opticalControl = optics.opticalControl;
    }
    if (recipeTransmissive
            && (recipeControl & PRIME_RECIPE_WATER) == 0u
            && !bakedMaterial
            && !primeUsesRasterComposite(primitive)) {
        float referenceOpacity;
        float3 referenceBaseColor = primeAtlasBaseColor(
                primitive.tint,
                primePrimitiveMaterialReferenceUv(primitive),
                0.0,
                referenceOpacity);
        bool stained = primeGlassReferenceIsStained(referenceOpacity);
        bool rough = primeGlassTexelIsRough(
                result.opacity, primeUsesSeamlessGlass());
        if (stained) {
            // Both boundaries of a closed medium must agree on its color even when they hit
            // different decorative texels. Shadow rays use this same stable material reference.
            result.baseColor = referenceBaseColor;
            materialControl |= PRIME_MATERIAL_MEDIUM_STAINED_GLASS;
        } else {
            materialControl |= PRIME_MATERIAL_MEDIUM_COLORLESS_GLASS;
        }
        if (rough) {
            materialControl |= PRIME_MATERIAL_DECORATIVE_INTERFACE;
        } else {
            result.roughness = 0.0;
        }
        if (!stained && rough) {
            // Vanilla clear-glass borders are opaque blue-grey paint, not a second dielectric
            // volume. Keep the primitive in the transmissive BLAS but shade this texel as rough
            // diffuse so the transparent body remains a closed colorless medium.
            materialControl = (materialControl & ~PRIME_MATERIAL_FAMILY_MASK)
                    | PRIME_MATERIAL_FAMILY_OPAQUE;
        }
    } else if ((recipeControl & PRIME_RECIPE_WATER) != 0u) {
        materialControl |= PRIME_MATERIAL_MEDIUM_WATER;
    }
    if ((primitive.flagsEmitter
            & (PRIME_DYNAMIC_TEXTURE_FLAG | PRIME_VISIBLE_EMISSION_FLAG))
            == (PRIME_DYNAMIC_TEXTURE_FLAG | PRIME_VISIBLE_EMISSION_FLAG)) {
        materialControl |= PRIME_MATERIAL_VISIBLE_EMISSION;
    }
    uint subsurfaceCode = result.opticalControl
            >> PRIME_OPTICAL_SUBSURFACE_SHIFT & 0xffu;
    if ((recipeControl & PRIME_RECIPE_ALPHA_CUTOUT) != 0u
            && subsurfaceCode != 0u) {
        materialControl |= PRIME_MATERIAL_THIN_WALLED;
    }
    result.materialControl = materialControl;
    float3 geometricNormal = primeUnpackOctahedralNormal(primitive.normal);
    // Normal-map filtering and the temporal normal contract remain intentionally disconnected.
    result.shadingNormal = geometricNormal;
    return result;
}

public PrimeMaterialSample primeEvaluateMaterialReference(
        uint recipeControl, uint packedTint, float2 uv) {
    PrimitiveRecord primitive;
    primitive.uv0 = asuint(uv.x);
    primitive.uv1 = asuint(uv.y);
    primitive.uv2 = 0u;
    primitive.tint = (packedTint & 0x00ffffffu)
            | ((recipeControl & 0xffu) << 24u);
    primitive.normal = 0u;
    primitive.flagsEmitter = ((recipeControl >> 8u) & 7u)
            | ((recipeControl & PRIME_RECIPE_BUILTIN_MASK) << 16u);
    primitive.uvDensity = PRIME_CONSTANT_UV_DENSITY;
    primitive.tangent = 0u;
    return primeEvaluateMaterial(primitive, uv, 0.0, 0u);
}

public float3 primeEvaluateEmitterRadiance(
        uint atlasTint,
        uint emitterFlags,
        float fallbackRadiance,
        float2 uv,
        float textureLodValue) {
    float opacity;
    float3 color = primeAtlasBaseColor(atlasTint, uv, textureLodValue, opacity);
    bool cutout = (emitterFlags & PRIME_EMITTER_FLAG_TWO_SIDED) != 0u;
    if (cutout && opacity < PRIME_CUTOUT_ALPHA_THRESHOLD) {
        return float3(0.0);
    }
    // Minecraft's 0..15 block-light value is an ordinal influence radius, not radiometry. Prime's
    // documented fallback maps its squared normalized level to the ABI source calibration, so a
    // white level-15 texel has radiance PRIME_LEVEL_15_BLOCK_INTENSITY. The
    // path geometry term supplies the actual inverse-square falloff; never add it here as well.
    // This global radiometric scale is uniform over every emitter. It therefore changes neither
    // light-tree selection probabilities nor their PDFs and does not require rebuilding the tree.
    float authoredEmission = 0.0;
    if ((emitterFlags & PRIME_EMITTER_FLAG_LABPBR_EMISSION) != 0u) {
        authoredEmission = primeDecodeLabPbrEmission(
                primeLabPbrSpecularAtlas.SampleLevel(uv, textureLodValue).a);
    }
    // LabPBR defines only a normalized emissiveness, not absolute radiometry. Prime maps 100%
    // to the same calibrated radiance as a white vanilla level-15 source. Once authored, this
    // channel replaces Minecraft's block-light value; alpha 0 can intentionally turn it off.
    float radianceScale = (emitterFlags & PRIME_EMITTER_FLAG_LABPBR_EMISSION) != 0u
            ? authoredEmission * PRIME_LEVEL_15_BLOCK_INTENSITY
            : max(fallbackRadiance, 0.0);
    return color * radianceScale * primeBlockLightRadianceMultiplier();
}

public float3 primeEvaluateEmitterRadiance(
        uint atlasTint,
        uint emitterFlags,
        float fallbackRadiance,
        float2 uv) {
    // A sampled light point has no screen-space ray footprint. The distribution was constructed
    // from mip 0, so evaluating that same radiometric function preserves its PDF contract.
    return primeEvaluateEmitterRadiance(
            atlasTint, emitterFlags, fallbackRadiance, uv, 0.0);
}

public float primeEvaluateOpacity(PrimitiveRecord primitive, float2 uv) {
    uint textureIndex = min(
            primePrimitiveTextureIndex(primitive), PRIME_SCENE_TEXTURE_COUNT - 1u);
    int2 extent = primeSceneTextureExtent(textureIndex);
    int2 texel = clamp(
            int2(floor(uv * float2(extent))),
            int2(0),
            max(extent - 1, int2(0)));
    // OMMs are baked from mip-0 alpha. Exact texel fetches keep unknown any-hit decisions and
    // known OMM states on the same coverage function, independent of ray-cone LOD and filtering.
    return primeLoadSceneTexture(textureIndex, texel).a;
}
