import "core/atmosphere.slang";
import "core/image_access.slang";
import "core/atmosphere_epipolar.slang";
import "core/atmosphere_phase.slang";
import "core/sun_shadow_hierarchy.slang";
import "prime_abi_types.slang";

[[vk::binding(0, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> transmittanceLow;
[[vk::binding(1, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> transmittanceHigh;
[[vk::binding(2, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> multiScatteringLow;
[[vk::binding(3, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> multiScatteringHigh;
[[vk::binding(5, 0)]] [[vk::image_format("rgba16f")]] writeonly RWTexture3D<float4> aerialRadiance;
[[vk::binding(18, 0)]] [[vk::image_format("rg32f")]] readonly RWTexture2D<float2> sunShadowHierarchy0;
[[vk::binding(19, 0)]] [[vk::image_format("rg32f")]] readonly RWTexture2D<float2> sunShadowHierarchy1;
[[vk::binding(20, 0)]] [[vk::image_format("rg32f")]] readonly RWTexture2D<float2> sunShadowHierarchy2;
[[vk::binding(21, 0)]] [[vk::image_format("rg32f")]] readonly RWTexture2D<float2> sunShadowHierarchy3;
[[vk::binding(22, 0)]] [[vk::image_format("rg32f")]] readonly RWTexture2D<float2> sunShadowHierarchy4;


struct AtmospherePushConstants {
    float4x4 inverseViewProjection;
    float eyeRadiusKm;
    float maximumAerialDistanceKm;
    float2 epipoleNdc;
    float4 sunDirectionIntensity;
    float4 shadowDirectionBank;
    float4 shadowCameraEnabled;
};
[[vk::push_constant]] ConstantBuffer<AtmospherePushConstants> atmospherePush;

static const int SUN_SHADOW_CASCADE_COUNT = 5;
static const float SUN_SHADOW_MAX_DISTANCE_METERS = 2048.0;
static const float SUN_SHADOW_DEPTH_BIAS_METERS = 0.02;
static const float SUN_SHADOW_CASCADE_RADIUS_TEXELS = 254.0;
static const float SUN_SHADOW_TRAVERSAL_EPSILON = 1.0e-5;

float2 sunShadowHierarchyRange(
        int cascade, int level, int2 worldNode) {
    int resolution = primeSunShadowHierarchyResolution(level);
    int2 coordinate = primeSunShadowHierarchyOffset(level)
            + (worldNode & (resolution - 1));
    if (cascade == 0) return primeReadonlyImageLoad(sunShadowHierarchy0, coordinate).rg;
    if (cascade == 1) return primeReadonlyImageLoad(sunShadowHierarchy1, coordinate).rg;
    if (cascade == 2) return primeReadonlyImageLoad(sunShadowHierarchy2, coordinate).rg;
    if (cascade == 3) return primeReadonlyImageLoad(sunShadowHierarchy3, coordinate).rg;
    return primeReadonlyImageLoad(sunShadowHierarchy4, coordinate).rg;
}

void sunShadowBasis(float3 directionToSun, out float3 basisU, out float3 basisV) {
    // Permuted Frisvad basis: its unavoidable seam is at world down, outside the visible solar
    // hemisphere, while astronomical sunrise and sunset may legitimately cross ±X.
    if (directionToSun.y < -0.9999999) {
        basisU = float3(1.0, 0.0, 0.0);
    } else {
        float reciprocal = 1.0 / (1.0 + directionToSun.y);
        basisU = normalize(float3(
                1.0
                        - directionToSun.x
                                * directionToSun.x
                                * reciprocal,
                -directionToSun.x,
                -directionToSun.x
                        * directionToSun.z
                        * reciprocal));
    }
    basisV = cross(directionToSun, basisU);
}

float sunShadowTexelSize(int cascade) {
    return cascade == 0 ? 0.5
            : (cascade == 1 ? 1.0
            : (cascade == 2 ? 2.0
            : (cascade == 3 ? 4.0 : 8.0)));
}

#include "core/atmosphere_epipolar_shadow.slang"

float4 aerialTransmittanceToSun(int group, float sunMu, float normalizedAltitude) {
    float2 uv = atmTransmittanceUv(sunMu, normalizedAltitude);
    return group == 0
            ? atmSample2D(transmittanceLow, uv)
            : atmSample2D(transmittanceHigh, uv);
}

float4 aerialMultiScattering(int group, float sunMu, float normalizedAltitude) {
    float2 dimensions = float2(primeReadonlyImageSize(multiScatteringLow));
    float2 uv = atmMultiUv(sunMu, normalizedAltitude, dimensions);
    return group == 0
            ? atmSample2D(multiScatteringLow, uv)
            : atmSample2D(multiScatteringHigh, uv);
}

struct AerialPhase {
    float molecular;
    float4 aerosolBaseLow;
    float4 aerosolBackgroundLow;
    float4 aerosolBaseHigh;
    float4 aerosolBackgroundHigh;
};

AerialPhase aerialPhase(float phaseCosine) {
    AerialPhase result;
    result.molecular = atmRayleighPhase(phaseCosine);
    result.aerosolBaseLow = float4(0.0);
    result.aerosolBackgroundLow = float4(0.0);
    result.aerosolBaseHigh = float4(0.0);
    result.aerosolBackgroundHigh = float4(0.0);
    for (int species = 0;
            species < ATM_PHASE_SPECIES_COUNT;
            ++species) {
        float4 weightedLow = atmAerosolScatteringCrossSection(0, species)
                * atmAerosolPhaseValue(0, species, phaseCosine);
        float4 weightedHigh = atmAerosolScatteringCrossSection(1, species)
                * atmAerosolPhaseValue(1, species, phaseCosine);
        float baseDensity = atmAerosolBaseDensity(species);
        float backgroundDensity =
                atmAerosolBackgroundDensity(species);
        result.aerosolBaseLow += weightedLow * baseDensity;
        result.aerosolBackgroundLow += weightedLow * backgroundDensity;
        result.aerosolBaseHigh += weightedHigh * baseDensity;
        result.aerosolBackgroundHigh +=
                weightedHigh * backgroundDensity;
    }
    return result;
}

float4 aerialAerosolPhaseScattering(
        int group,
        float altitudeKm,
        AerialPhase phase) {
    float altitude = max(altitudeKm, 0.0);
    float falloff = exp(-altitude / 8.0);
    float backgroundBlend =
            smoothstep(1.0, 2.0, altitude);
    float4 base = group == 0
            ? phase.aerosolBaseLow
            : phase.aerosolBaseHigh;
    float4 background = group == 0
            ? phase.aerosolBackgroundLow
            : phase.aerosolBackgroundHigh;
    return falloff * lerp(base, background, backgroundBlend);
}

void aerialStepGroup(
        int group,
        float altitude,
        float sunMu,
        bool sunOccluded,
        float terrainSunVisibility,
        float stepLength,
        AerialPhase phase,
        out float4 radiance,
        out float4 transmittance) {
    float normalizedAltitude = altitude / ATM_THICKNESS_KM;
    AtmCoefficients coefficients = atmCoefficients(group, altitude);
    float4 direct = sunOccluded ? float4(0.0)
            : aerialTransmittanceToSun(group, sunMu, normalizedAltitude)
            * (coefficients.molecularScattering
                    * phase.molecular
            + aerialAerosolPhaseScattering(group, altitude, phase));
    float4 multiple = aerialMultiScattering(group, sunMu, normalizedAltitude)
            * (coefficients.aerosolScattering + coefficients.molecularScattering);
    float4 source = atmSolarSpectrum(group)
            * (direct + multiple)
            * terrainSunVisibility;
    transmittance = exp(-stepLength * coefficients.extinction);
    float4 safeExtinction = max(coefficients.extinction, float4(1.0e-7));
    radiance = (source - source * transmittance) / safeExtinction;
}

struct AerialSegment {
    float4 radianceLow;
    float4 transmittanceLow;
    float4 radianceHigh;
    float4 transmittanceHigh;
};

AerialSegment identityAerialSegment() {
    AerialSegment result;
    result.radianceLow = float4(0.0);
    result.transmittanceLow = float4(1.0);
    result.radianceHigh = float4(0.0);
    result.transmittanceHigh = float4(1.0);
    return result;
}

AerialSegment integrateAerialSegment(
        float3 origin,
        float3 direction,
        float segmentStart,
        float segmentEnd,
        AerialPhase phase) {
    AerialSegment result = identityAerialSegment();
    if (segmentEnd <= segmentStart) {
        return result;
    }

    float3 sunDirection = atmospherePush.sunDirectionIntensity.xyz;
    float stepLength = (segmentEnd - segmentStart)
            / float(PRIME_ATMOSPHERE_AERIAL_SEGMENT_SAMPLES);
    for (int substep = 0;
            substep < PRIME_ATMOSPHERE_AERIAL_SEGMENT_SAMPLES;
            ++substep) {
        float substepStartFraction = float(substep)
                / float(PRIME_ATMOSPHERE_AERIAL_SEGMENT_SAMPLES);
        float substepEndFraction = float(substep + 1)
                / float(PRIME_ATMOSPHERE_AERIAL_SEGMENT_SAMPLES);
        float substepStart =
                lerp(segmentStart, segmentEnd, substepStartFraction);
        float substepEnd =
                lerp(segmentStart, segmentEnd, substepEndFraction);
        float distanceKm = 0.5 * (substepStart + substepEnd);
        float3 point = origin + direction * distanceKm;
        float radius = length(point);
        float3 up = point / max(radius, 1.0e-6);
        float altitude = max(radius - ATM_BOTTOM_RADIUS_KM, 0.0);
        float sunMu = dot(up, sunDirection);
        bool sunOccluded = atmRaySegment(
                point - up * ATM_PLANET_RADIUS_OFFSET_KM,
                sunDirection).hitsGround;
        float terrainSunVisibility = localSunVisibilityInterval(
                direction,
                substepStart / ATM_WORLD_UNIT_SCALE_KM,
                substepEnd / ATM_WORLD_UNIT_SCALE_KM);

        float4 radianceLow;
        float4 transmittanceLowValue;
        float4 radianceHigh;
        float4 transmittanceHighValue;
        aerialStepGroup(
                0,
                altitude,
                sunMu,
                sunOccluded,
                terrainSunVisibility,
                stepLength,
                phase,
                radianceLow,
                transmittanceLowValue);
        aerialStepGroup(
                1,
                altitude,
                sunMu,
                sunOccluded,
                terrainSunVisibility,
                stepLength,
                phase,
                radianceHigh,
                transmittanceHighValue);
        result.radianceLow += result.transmittanceLow * radianceLow;
        result.transmittanceLow *= transmittanceLowValue;
        result.radianceHigh += result.transmittanceHigh * radianceHigh;
        result.transmittanceHigh *= transmittanceHighValue;
    }
    return result;
}

float3 aerialDirection(int2 coordinate, int2 dimensions) {
    float2 ndc = atmEpipolarSliceNdc(
            atmospherePush.epipoleNdc,
            coordinate,
            dimensions);
    float4 nearPoint = atmospherePush.inverseViewProjection * float4(ndc, 1.0, 1.0);
    float4 farPoint = atmospherePush.inverseViewProjection * float4(ndc, 0.0, 1.0);
    return normalize(farPoint.xyz / farPoint.w - nearPoint.xyz / nearPoint.w);
}

[numthreads(PRIME_ATMOSPHERE_AERIAL_EPIPOLAR_SAMPLES, 1, 1)]
void main(uint3 groupId : SV_GroupID, uint3 groupThreadId : SV_GroupThreadID) {
    int3 dimensions = primeWriteImageSize(aerialRadiance);
    int radial = int(groupThreadId.x);
    int slice = int(groupId.y);
    int2 coordinate = int2(radial, slice);

    // One workgroup owns one epipolar slice. Its lanes integrate all radial
    // screen rays while five lanes build the groupshared RT blocker profiles.
    if (radial == 0) {
        sunShadowProfileDirectionToSun = normalize(
                atmospherePush.shadowDirectionBank.xyz);
        sunShadowBasis(
                sunShadowProfileDirectionToSun,
                sunShadowProfileBasisU,
                sunShadowProfileBasisV);
        float3 cameraPosition =
                atmospherePush.shadowCameraEnabled.xyz;
        sunShadowProfileCamera = float2(
                dot(cameraPosition, sunShadowProfileBasisU),
                dot(cameraPosition, sunShadowProfileBasisV));
        sunShadowProfileCameraDepth = dot(
                cameraPosition,
                sunShadowProfileDirectionToSun);

        float3 edgeDirection = aerialDirection(
                int2(dimensions.x - 1, slice),
                dimensions.xy);
        float2 projectedEdge = float2(
                dot(edgeDirection, sunShadowProfileBasisU),
                dot(edgeDirection, sunShadowProfileBasisV));
        float projectedLength = length(projectedEdge);
        sunShadowProfileDirection = projectedLength > 1.0e-6
                ? projectedEdge / projectedLength
                : float2(1.0, 0.0);
        sunShadowProfileDirectionMax = max(
                abs(sunShadowProfileDirection.x),
                abs(sunShadowProfileDirection.y));

    }
    GroupMemoryBarrierWithGroupSync();
    if (radial < SUN_SHADOW_CASCADE_COUNT) {
        sunShadowBuildProfileCascade(radial);
    }
    GroupMemoryBarrierWithGroupSync();

    float3 direction = aerialDirection(coordinate, dimensions.xy);
    float phaseCosine = dot(
            -direction,
            atmospherePush.sunDirectionIntensity.xyz);
    AerialPhase phase = aerialPhase(phaseCosine);
    float3 origin = atmMoveToTop(
            float3(0.0, atmospherePush.eyeRadiusKm, 0.0),
            direction);
    float maximumDistance = max(
            atmRaySegment(origin, direction).maximumDistanceKm,
            0.0);
    float inverseDepth = 1.0 / float(dimensions.z);
    AerialSegment accumulated = identityAerialSegment();
    for (int depth = 0; depth < dimensions.z; ++depth) {
        float normalizedStart =
                max(float(depth) - 0.5, 0.0) * inverseDepth;
        float normalizedEnd =
                (float(depth) + 0.5) * inverseDepth;
        float segmentStart = min(
                atmospherePush.maximumAerialDistanceKm
                        * normalizedStart * normalizedStart,
                maximumDistance);
        float segmentEnd = min(
                atmospherePush.maximumAerialDistanceKm
                        * normalizedEnd * normalizedEnd,
                maximumDistance);
        AerialSegment segment = integrateAerialSegment(
                origin,
                direction,
                segmentStart,
                segmentEnd,
                phase);
        accumulated.radianceLow +=
                accumulated.transmittanceLow * segment.radianceLow;
        accumulated.transmittanceLow *= segment.transmittanceLow;
        accumulated.radianceHigh +=
                accumulated.transmittanceHigh * segment.radianceHigh;
        accumulated.transmittanceHigh *= segment.transmittanceHigh;

        float3 rec2020 = max(atmLinearRec2020FromSpectral(
                accumulated.radianceLow,
                accumulated.radianceHigh), float3(0.0));
        primeWriteImageStore(
                aerialRadiance,
                int3(coordinate, depth),
                float4(rec2020, 1.0));
    }
}
