#language slang 2026
module "service/light/sample.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "contract/light_sample.slang";
import "model/atmosphere/sky.slang";
import "service/atmosphere/trace.slang";
import "math/packed_geometry.slang";
import "policy/runtime/config.slang";
import "math/sampling.slang";
import "math/transport.slang";
import "service/material/surface.slang";
import "service/atmosphere/starmap.slang";
import "service/light/emission.slang";

static const float PRIME_LIGHT_PI = 3.141592653589793;

// All radiance values in this adapter are linear Rec.2020 D65. pdf is the complete f32 sampling
// density, including light-selection probability when a future registry introduces one. Reverse
// PDF queries must reuse that exact quantized value. The environment is evaluated only when a BSDF
// path escapes; the sun and area-light adapters are sampled explicitly and perform no selection.
// The submitted sun direction and all path directions are unit vectors at this boundary.
// Renormalizing them per vertex would only repeat a square root and three divisions.

public float primePowerHeuristic(float firstPdf, float secondPdf) {
    return primePowerHeuristicValue(firstPdf, secondPdf);
}
public float primePowerHeuristicOverPdf(float sampledPdf, float otherPdf) {
    float result = primePowerHeuristicOverPdfValue(sampledPdf, otherPdf);
    return result;
}

public float3 primeEnvironmentRadiance(IntegratorRecord integrator, float3 direction) {
    // Atmosphere LUT construction is linear in its extraterrestrial source. Reusing the calibrated
    // base LUT and applying the same scale as direct sun is exact for this single-source model.
    float sunScale = max(integrator.sunDirectionIntensity.w, 0.0)
            / ATM_SPACE_SUN_INTENSITY;
    return primeAtmosphereSky(direction, integrator.sunDirectionIntensity.xyz) * sunScale;
}

float primeSunCosAngularRadius() {
    return cos(ATM_SUN_ANGULAR_RADIUS_RADIANS);
}

float primeSunSolidAngle() {
    // 4*pi*sin(radius/2)^2 is algebraically identical to 2*pi*(1-cos(radius))
    // but avoids subtracting two nearly equal f32 values for the real solar radius.
    float sineHalfRadius = sin(0.5 * ATM_SUN_ANGULAR_RADIUS_RADIANS);
    return 4.0 * PRIME_LIGHT_PI * sineHalfRadius * sineHalfRadius;
}

public float primeSunPdf() {
    return 1.0 / primeSunSolidAngle();
}

bool primeSunContainsDirection(IntegratorRecord integrator, float3 direction) {
    return dot(direction, integrator.sunDirectionIntensity.xyz)
            >= primeSunCosAngularRadius();
}

float3 primeSunRadiance(
        IntegratorRecord integrator,
        float3 surfacePosition,
        float3 direction) {
    return float3(max(integrator.sunDirectionIntensity.w, 0.0) / primeSunSolidAngle())
            * primeAtmosphereSunTransmittance(surfacePosition, direction);
}

public float3 primeResolveSampledSunRadiance(
        IntegratorRecord integrator,
        float3 surfacePosition,
        LightSample light) {
    return primeSunRadiance(integrator, surfacePosition, light.direction);
}

public LightEvaluation primeEvaluateSun(
        IntegratorRecord integrator,
        float3 surfacePosition,
        float3 direction) {
    LightEvaluation result;
    bool containsDirection = primeSunContainsDirection(integrator, direction);
    result.radiance = containsDirection
            ? primeSunRadiance(integrator, surfacePosition, direction)
            : float3(0.0);
    result.pdf = containsDirection ? primeSunPdf() : 0.0;
    return result;
}

public LightSample primeSampleSun(
        IntegratorRecord integrator,
        float3 surfacePosition,
        float2 sampleValue) {
    float cosine = lerp(primeSunCosAngularRadius(), 1.0, sampleValue.x);
    float sine = sqrt(1.0 - cosine * cosine);
    float azimuth = 2.0 * PRIME_LIGHT_PI * sampleValue.y;
    float3 localDirection = float3(sine * cos(azimuth), sine * sin(azimuth), cosine);
    LightSample result;
    result.direction = primeLocalToWorld(
            localDirection,
            integrator.sunDirectionIntensity.xyz);
    result.distance = 1000000.0;
    // Visibility must be known before evaluating the atmosphere transmittance.
    result.radiance = float3(0.0);
    result.pdf = primeSunPdf();
    result.isDelta = 0u;
    return result;
}

public static const uint PRIME_LIGHT_CELL_SUBDIVISION = 16u;
public static const uint PRIME_LIGHT_CELL_COUNT = 256u;

AreaLightSample primeInvalidAreaLightSample() {
    AreaLightSample result;
    result.light.direction = float3(0.0, 1.0, 0.0);
    result.light.distance = 0.0;
    result.light.radiance = float3(0.0);
    result.light.pdf = 0.0;
    result.light.isDelta = 0u;
    result.sectionIndex = PRIME_NO_LIGHT_INDEX;
    result.emitterIndex = PRIME_NO_LIGHT_INDEX;
    result.uv = float2(0.0);
    return result;
}

void primeLightCellVertices(uint geometry, out float2 first, out float2 second, out float2 third) {
    uint column = geometry & 0xfu;
    uint row = (geometry >> 4u) & 0xfu;
    bool upper = (geometry & 0x100u) != 0u;
    float inverseSubdivision = 1.0 / float(PRIME_LIGHT_CELL_SUBDIVISION);
    float x = float(column) * inverseSubdivision;
    float y = float(row) * inverseSubdivision;
    if (upper) {
        first = float2(x + inverseSubdivision, y);
        second = float2(x + inverseSubdivision, y + inverseSubdivision);
        third = float2(x, y + inverseSubdivision);
    } else {
        first = float2(x, y);
        second = float2(x + inverseSubdivision, y);
        third = float2(x, y + inverseSubdivision);
    }
}

public uint primeLightCellIndex(float2 parentBarycentric) {
    float2 bounded = max(parentBarycentric, float2(0.0));
    float sum = bounded.x + bounded.y;
    if (sum > 1.0) {
        bounded /= sum;
    }
    float2 scaled = min(
            bounded * float(PRIME_LIGHT_CELL_SUBDIVISION),
            float2(float(PRIME_LIGHT_CELL_SUBDIVISION) - 1.0e-5));
    uint2 coordinate = uint2(floor(scaled));
    if (coordinate.x + coordinate.y >= PRIME_LIGHT_CELL_SUBDIVISION) {
        if (coordinate.x > 0u) {
            coordinate.x--;
        } else {
            coordinate.y--;
        }
    }
    float2 local = scaled - float2(coordinate);
    bool upper = local.x + local.y > 1.0
            && coordinate.x + coordinate.y < PRIME_LIGHT_CELL_SUBDIVISION - 1u;
    uint rowBase = coordinate.y * (2u * PRIME_LIGHT_CELL_SUBDIVISION - coordinate.y);
    return rowBase + 2u * coordinate.x + (upper ? 1u : 0u);
}

public float primeAreaSolidAnglePdf(
        float3 origin,
        float3 lightPosition,
        float lightCosine,
        float areaPdf) {
    float3 delta = lightPosition - origin;
    float distanceSquared = dot(delta, delta);
    return primeAreaSolidAnglePdfValue(areaPdf, distanceSquared, lightCosine);
}

AreaLightSample primeSampleAreaLightEmitter(
        float3 surfacePosition,
        uint sectionIndex,
        uint emitterIndex,
        float emitterSelectionPdf,
        float cellSample,
        float2 positionSample) {
    SectionRecord* sections = (SectionRecord*)primePush.sectionTableAddress;
    uint64_t sectionLightAddress = sections[sectionIndex].lightAddress;
    if (sectionLightAddress == uint64_t(0)) {
        return primeInvalidAreaLightSample();
    }
    SectionLightHeader* sectionBuffer = (SectionLightHeader*)sectionLightAddress;
    if (emitterIndex >= sectionBuffer[0].emitterCount
            || !(emitterSelectionPdf > 0.0)) {
        return primeInvalidAreaLightSample();
    }
    LightEmitter* emitters = (LightEmitter*)sectionBuffer[0].emitterAddress;
    LightCell* cells = (LightCell*)sectionBuffer[0].cellAddress;
    uint emitterCellBase = emitters[emitterIndex].metadata.x;
    float aliasValue = cellSample * float(PRIME_LIGHT_CELL_COUNT);
    // Sobol conversion is strictly below one, so aliasValue is strictly below CELL_COUNT.
    uint column = uint(aliasValue);
    LightCell aliasCell = cells[emitterCellBase + column];
    uint cellIndex = aliasValue - float(column) < aliasCell.aliasProbability
            ? column
            : aliasCell.aliasGeometry & 0xffu;
    LightCell selectedCell = cells[emitterCellBase + cellIndex];
    float4 emitterCornerArea = emitters[emitterIndex].cornerArea;
    if (!(selectedCell.probabilityMass > 0.0) || !(emitterCornerArea.w > 0.0)) {
        return primeInvalidAreaLightSample();
    }

    float2 cellFirst;
    float2 cellSecond;
    float2 cellThird;
    primeLightCellVertices(selectedCell.aliasGeometry >> 8u, cellFirst, cellSecond, cellThird);
    float squareRoot = sqrt(positionSample.x);
    float3 cellBarycentric = float3(
            1.0 - squareRoot,
            squareRoot * (1.0 - positionSample.y),
            squareRoot * positionSample.y);
    float2 parentBarycentric = cellFirst * cellBarycentric.x
            + cellSecond * cellBarycentric.y
            + cellThird * cellBarycentric.z;
    float3 localLightPosition;
    {
        float3 edgeOne = emitters[emitterIndex].edgeOneScale.xyz;
        float3 edgeTwo = emitters[emitterIndex].edgeTwoPower.xyz;
        localLightPosition = emitterCornerArea.xyz
                + edgeOne * parentBarycentric.x
                + edgeTwo * parentBarycentric.y;
    }
    float3 lightPosition = localLightPosition + sections[sectionIndex].translation;
    float3 toLight = lightPosition - surfacePosition;
    float distanceSquared = dot(toLight, toLight);
    if (!(distanceSquared > 0.0)) {
        return primeInvalidAreaLightSample();
    }
    float distance = sqrt(distanceSquared);
    float3 direction = toLight / distance;
    float lightCosine = primeEmitterCosine(
            emitters[emitterIndex].normalPadding.xyz,
            emitters[emitterIndex].metadata.z,
            direction);
    if (!(lightCosine > 0.0)) {
        return primeInvalidAreaLightSample();
    }
    float cellArea = emitterCornerArea.w / float(PRIME_LIGHT_CELL_COUNT);
    float areaPdf = emitterSelectionPdf
            * selectedCell.probabilityMass / cellArea;
    float pdf = primeAreaSolidAnglePdf(
            surfacePosition,
            lightPosition,
            lightCosine,
            areaPdf);
    if (!(pdf > 0.0)) {
        return primeInvalidAreaLightSample();
    }

    AreaLightSample result;
    result.light.direction = direction;
    result.light.distance = distance;
    result.light.radiance = float3(0.0);
    result.light.pdf = pdf;
    result.light.isDelta = 0u;
    result.sectionIndex = sectionIndex;
    result.emitterIndex = emitterIndex;
    result.uv = primeEmitterUv(
            emitters[emitterIndex].uvsTint.xyz,
            parentBarycentric);
    return result;
}

public AreaLightSample primeSampleAreaLightSelection(
        float3 surfacePosition,
        AreaLightSelection selection,
        float cellSample,
        float2 positionSample) {
    if (selection.sectionIndex == PRIME_NO_LIGHT_INDEX
            || selection.emitterIndex == PRIME_NO_LIGHT_INDEX
            || !(selection.pdf > 0.0)) {
        return primeInvalidAreaLightSample();
    }
    return primeSampleAreaLightEmitter(
            surfacePosition,
            selection.sectionIndex,
            selection.emitterIndex,
            selection.pdf,
            cellSample,
            positionSample);
}

public float3 primeResolveSampledAreaLightRadiance(AreaLightSample areaSample) {
    if (areaSample.sectionIndex == PRIME_NO_LIGHT_INDEX
            || areaSample.emitterIndex == PRIME_NO_LIGHT_INDEX) {
        return float3(0.0);
    }
    SectionRecord* sections = (SectionRecord*)primePush.sectionTableAddress;
    uint64_t sectionLightAddress = sections[areaSample.sectionIndex].lightAddress;
    if (sectionLightAddress == uint64_t(0)) {
        return float3(0.0);
    }
    SectionLightHeader* sectionBuffer = (SectionLightHeader*)sectionLightAddress;
    if (areaSample.emitterIndex >= sectionBuffer[0].emitterCount) {
        return float3(0.0);
    }
    LightEmitter* emitters = (LightEmitter*)sectionBuffer[0].emitterAddress;
    return primeEvaluateEmitterRadiance(
            emitters[areaSample.emitterIndex].metadata.w,
            emitters[areaSample.emitterIndex].uvsTint.w,
            emitters[areaSample.emitterIndex].metadata.z,
            emitters[areaSample.emitterIndex].edgeOneScale.w,
            areaSample.uv);
}
