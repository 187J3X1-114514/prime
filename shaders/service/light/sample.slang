#language slang 2026
module "service/light/sample.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "model/atmosphere/sky.slang";
import "service/atmosphere/trace.slang";
import "math/packed_geometry.slang";
import "policy/runtime/config.slang";
import "math/sampling.slang";
import "math/transport.slang";
import "service/material/surface.slang";
import "math/light_tree.slang";
import "service/atmosphere/starmap.slang";
import "service/light/emission.slang";

static const float PRIME_LIGHT_PI = 3.141592653589793;

public struct LightSample {
    public float3 direction;
    public float distance;
    public float3 radiance;
    public float pdf;
    public uint isDelta;
};

// Area-light geometry selection is deliberately separate from radiance resolution. Most sampled
// lights are occluded in dense terrain; retaining only stable indices and UVs across traceRayEXT
// avoids both an atlas read and live emitter state until the visibility test succeeds.
public struct AreaLightSample {
    public LightSample light;
    public uint sectionIndex;
    public uint emitterIndex;
    public float2 uv;
};

public struct LightEvaluation {
    public float3 radiance;
    public float pdf;
};

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

static const uint PRIME_LIGHT_LEAF_FLAG = 0x80000000u;
static const uint PRIME_LIGHT_INDEX_MASK = 0x7fffffffu;
static const uint PRIME_LIGHT_TREE_MAX_DEPTH = 27u;
static const uint PRIME_LIGHT_PATH_DEPTH_SHIFT = 27u;
static const uint PRIME_LIGHT_PATH_TRAIL_MASK = 0x07ffffffu;
static const uint PRIME_LIGHT_LEAF_MAX_ENTRIES = 8u;
static const uint PRIME_LIGHT_CELL_SUBDIVISION = 16u;
static const uint PRIME_LIGHT_CELL_COUNT = 256u;
struct LightTreePick {
    uint index;
    float pdf;
    float remapped;
    uint valid;
};

LightTreePick primeInvalidLightTreePick() {
    LightTreePick result;
    result.index = 0u;
    result.pdf = 0.0;
    result.remapped = 0.0;
    result.valid = 0u;
    return result;
}

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

LightNode* primeWorldLightNodes(uint64_t worldLightAddress) {
    return (LightNode*)worldLightAddress;
}

void primeUnpackLightNode(
        LightNode* nodes,
        uint index,
        out float3 centroid,
        out float power,
        out uint emissionDirection,
        out uint childOrLeaf) {
    LightNode node = nodes[index];
    centroid = node.centroidPower.xyz;
    power = node.centroidPower.w;
    emissionDirection = node.directionChildReserved.x;
    childOrLeaf = node.directionChildReserved.y;
}

float2 primeLoadLightNodeMetrics(
        LightNode* nodes,
        uint index,
        float3 point,
        float3 receiverNormal) {
    float3 centroid;
    float power;
    uint emissionDirection;
    uint childOrLeaf;
    primeUnpackLightNode(
            nodes,
            index,
            centroid,
            power,
            emissionDirection,
            childOrLeaf);
    return primeLightNodeMetrics(
            centroid,
            power,
            point,
            receiverNormal,
            emissionDirection);
}

// The root proxy estimates incident irradiance from the same power, direction and receiver-normal
// quantities used by tree traversal. It deliberately excludes visibility; that belongs to the
// optional second-stage hierarchy hint and must not add another ray here.
public float primeAreaLightFamilyImportance(
        float3 surfacePosition, uint packedReceiverNormal) {
    SectionRecord* sections = (SectionRecord*)primePush.sectionTableAddress;
    uint64_t worldLightAddress = sections[0].worldLightAddress;
    if (worldLightAddress == uint64_t(0)) {
        return 0.0;
    }
    if (sections[0].worldLightLeafCount == 0u) {
        return 0.0;
    }
    float2 metrics = primeLoadLightNodeMetrics(
            primeWorldLightNodes(worldLightAddress),
            0u,
            surfacePosition,
            primeUnpackLightReceiverNormal(packedReceiverNormal));
    // Emitter power is area*pi*radiance. Its directional point approximation therefore converts
    // back to receiver irradiance with 1/(pi*r^2). The tiny denominator guard only covers an exact
    // aggregate-centroid coincidence; the probability clamp bounds its effect.
    return metrics.y * primeBlockLightRadianceMultiplier()
            / (PRIME_LIGHT_PI * max(metrics.x, 1.0e-12));
}

float2 primeEmitterMetrics(
        LightEmitter emitter,
        float power,
        float3 point,
        float3 receiverNormal) {
    float3 first = emitter.cornerArea.xyz;
    float3 second = first + emitter.edgeOneScale.xyz;
    float3 third = first + emitter.edgeTwoPower.xyz;
    float3 boundsMin = min(first, min(second, third));
    float3 boundsMax = max(first, max(second, third));
    float3 closest = clamp(point, boundsMin, boundsMax);
    float3 delta = point - closest;
    float boundsDistanceSquared = dot(delta, delta);
    float inverseDistance = boundsDistanceSquared > 0.0
            ? rsqrt(boundsDistanceSquared)
            : 0.0;
    float receiverBound = primeLightReceiverCosineBoundWithInverse(
            boundsMin,
            boundsMax,
            point,
            receiverNormal,
            boundsDistanceSquared,
            inverseDistance);
    float emitterBound = primeLightEmissionAxisCosineBound(
            boundsMin,
            boundsMax,
            point,
            emitter.normalPadding.xyz,
            boundsDistanceSquared,
            inverseDistance);
    if ((emitter.metadata.z & PRIME_EMITTER_TWO_SIDED) != 0u) {
        emitterBound = 0.5 * max(
                emitterBound,
                primeLightEmissionAxisCosineBound(
                        boundsMin,
                        boundsMax,
                        point,
                        -emitter.normalPadding.xyz,
                        boundsDistanceSquared,
                        inverseDistance));
    }
    float angularImportance = clamp(receiverBound * emitterBound, 0.0, 1.0);
    float weightedPower = max(power, 0.0)
            * (PRIME_LIGHT_RECEIVER_COSINE_FLOOR
                    + (1.0 - PRIME_LIGHT_RECEIVER_COSINE_FLOOR)
                            * angularImportance);
    float targetDistanceSquared = boundsDistanceSquared;
    if (targetDistanceSquared == 0.0) {
        float3 centroidDelta = point - (first + second + third) / 3.0;
        targetDistanceSquared = dot(centroidDelta, centroidDelta);
    }
    if (targetDistanceSquared == 0.0) {
        // A valid emissive triangle has nonzero edge extent. It supplies a deterministic target
        // scale for the exact self-centroid case without altering visibility or ray endpoints.
        float3 edgeOne = second - first;
        float3 edgeTwo = third - first;
        targetDistanceSquared = dot(edgeOne, edgeOne) + dot(edgeTwo, edgeTwo);
    }
    return float2(targetDistanceSquared, weightedPower);
}

float2 primeLoadEmitterMetrics(
        LightEmitter* emitters,
        LightLeafEntry entry,
        float3 point,
        float3 receiverNormal) {
    return primeEmitterMetrics(
            emitters[entry.index],
            entry.power,
            point,
            receiverNormal);
}

LightTreePick primePickLightTree(
        LightNode* nodes,
        LightLeaf* leaves,
        LightLeafEntry* entries,
        uint root,
        float3 point,
        float3 receiverNormal,
        float seed) {
    uint nodeIndex = root;
    float lowerBound = 0.0;
    float pdf = 1.0;
    [loop]
    for (uint depth = 0u; depth <= PRIME_LIGHT_TREE_MAX_DEPTH; ++depth) {
        uint childOrLeaf = nodes[nodeIndex].directionChildReserved.y;
        if ((childOrLeaf & PRIME_LIGHT_LEAF_FLAG) != 0u) {
            uint leafIndex = childOrLeaf & PRIME_LIGHT_INDEX_MASK;
            LightLeaf leaf = leaves[leafIndex];
            float totalPower = nodes[nodeIndex].centroidPower.w;
            if (!(totalPower > 0.0) || leaf.entryCount == 0u
                    || leaf.entryCount > PRIME_LIGHT_LEAF_MAX_ENTRIES) {
                return primeInvalidLightTreePick();
            }
            float localSeed = clamp(
                    (seed - lowerBound) / pdf,
                    0.0,
                    asfloat(0x3f7fffffu));
            float entryLower = 0.0;
            [[unroll]]
            for (uint offset = 0u; offset < PRIME_LIGHT_LEAF_MAX_ENTRIES; ++offset) {
                if (offset >= leaf.entryCount) break;
                LightLeafEntry entry = entries[leaf.firstEntry + offset];
                float entryProbability = entry.power / totalPower;
                float entryUpper = entryLower + entryProbability;
                bool selected = localSeed < entryUpper || offset + 1u == leaf.entryCount;
                if (selected) {
                    if (!(entryProbability > 0.0)) {
                        return primeInvalidLightTreePick();
                    }
                    LightTreePick result;
                    result.index = entry.index;
                    result.pdf = pdf * entryProbability;
                    result.remapped = clamp(
                            (localSeed - entryLower) / entryProbability,
                            0.0,
                            asfloat(0x3f7fffffu));
                    result.valid = 1u;
                    return result;
                }
                entryLower = entryUpper;
            }
            return primeInvalidLightTreePick();
        }
        // CPU linearization guarantees that these mandatory sibling records are consecutive.
        uint leftIndex = childOrLeaf;
        uint rightIndex = leftIndex + 1u;
        float2 leftMetrics = primeLoadLightNodeMetrics(
                nodes, leftIndex, point, receiverNormal);
        float2 rightMetrics = primeLoadLightNodeMetrics(
                nodes, rightIndex, point, receiverNormal);
        float leftProbability = primeLightBranchProbability(leftMetrics, rightMetrics);
        if (!(leftProbability >= 0.0)) {
            return primeInvalidLightTreePick();
        }
        float rightProbability = 1.0 - leftProbability;
        // Keep the original sample in its cumulative interval. This is the same inverse-CDF
        // traversal as repeatedly remapping value to [0, 1), but replaces one division per tree
        // level with a multiply-add. pdf is also the current interval width.
        float split = lowerBound + pdf * leftProbability;
        bool selectLeft = seed < split || rightProbability <= 0.0;
        if (selectLeft && leftProbability <= 0.0) {
            return primeInvalidLightTreePick();
        }
        pdf *= selectLeft ? leftProbability : rightProbability;
        lowerBound = selectLeft ? lowerBound : split;
        nodeIndex = selectLeft ? leftIndex : rightIndex;
    }
    return primeInvalidLightTreePick();
}

float primeLightTreeSelectionPdf(
        LightNode* nodes,
        LightLeaf* leaves,
        LightLeafEntry* entries,
        uint root,
        uint packedPath,
        uint expectedIndex,
        float3 point,
        float3 receiverNormal) {
    if (packedPath == PRIME_NO_LIGHT_INDEX) {
        return 0.0;
    }
    uint depth = packedPath >> PRIME_LIGHT_PATH_DEPTH_SHIFT;
    uint trail = packedPath & PRIME_LIGHT_PATH_TRAIL_MASK;
    if (depth > PRIME_LIGHT_TREE_MAX_DEPTH
            || (depth < PRIME_LIGHT_TREE_MAX_DEPTH && (trail >> depth) != 0u)) {
        return 0.0;
    }
    uint nodeIndex = root;
    float pdf = 1.0;
    [loop]
    for (uint level = 0u; level < PRIME_LIGHT_TREE_MAX_DEPTH; ++level) {
        if (level >= depth) break;
        uint childOrLeaf = nodes[nodeIndex].directionChildReserved.y;
        if ((childOrLeaf & PRIME_LIGHT_LEAF_FLAG) != 0u) {
            return 0.0;
        }
        uint leftIndex = childOrLeaf;
        float2 leftMetrics = primeLoadLightNodeMetrics(
                nodes, leftIndex, point, receiverNormal);
        float2 rightMetrics = primeLoadLightNodeMetrics(
                nodes, leftIndex + 1u, point, receiverNormal);
        float leftProbability = primeLightBranchProbability(leftMetrics, rightMetrics);
        if (!(leftProbability >= 0.0)) {
            return 0.0;
        }
        bool right = ((trail >> level) & 1u) != 0u;
        float branchProbability = right ? 1.0 - leftProbability : leftProbability;
        if (!(branchProbability > 0.0)) return 0.0;
        pdf *= branchProbability;
        nodeIndex = leftIndex + (right ? 1u : 0u);
    }
    uint childOrLeaf = nodes[nodeIndex].directionChildReserved.y;
    if ((childOrLeaf & PRIME_LIGHT_LEAF_FLAG) == 0u) return 0.0;
    LightLeaf leaf = leaves[childOrLeaf & PRIME_LIGHT_INDEX_MASK];
    float totalPower = nodes[nodeIndex].centroidPower.w;
    if (!(totalPower > 0.0) || leaf.entryCount > PRIME_LIGHT_LEAF_MAX_ENTRIES) {
        return 0.0;
    }
    [[unroll]]
    for (uint offset = 0u; offset < PRIME_LIGHT_LEAF_MAX_ENTRIES; ++offset) {
        if (offset >= leaf.entryCount) break;
        LightLeafEntry entry = entries[leaf.firstEntry + offset];
        if (entry.index == expectedIndex) {
            return entry.power > 0.0 ? pdf * entry.power / totalPower : 0.0;
        }
    }
    return 0.0;
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

uint primeLightCellIndex(float2 parentBarycentric) {
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

float primeAreaSolidAnglePdf(
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

public AreaLightSample primeSampleAreaLight(
        float3 surfacePosition,
        uint packedReceiverNormal,
        float2 treeSample,
        float2 positionSample) {
    float3 receiverNormal = primeUnpackLightReceiverNormal(packedReceiverNormal);
    SectionRecord* sections = (SectionRecord*)primePush.sectionTableAddress;
    uint64_t worldNodeAddress = sections[0].worldLightAddress;
    uint64_t worldLeafAddress = sections[0].worldLightLeafAddress;
    uint worldLeafCount = sections[0].worldLightLeafCount;
    if (worldNodeAddress == uint64_t(0)
            || worldLeafAddress == uint64_t(0)
            || worldLeafCount == 0u) {
        return primeInvalidAreaLightSample();
    }
    LightNode* worldNodes = primeWorldLightNodes(worldNodeAddress);
    LightLeaf* worldLeaves = (LightLeaf*)worldLeafAddress;
    LightLeafEntry* worldEntries = (LightLeafEntry*)(
            worldLeafAddress + uint64_t(worldLeafCount) * uint64_t(PRIME_LIGHT_LEAF_SIZE));
    LightTreePick worldPick = primePickLightTree(
            worldNodes,
            worldLeaves,
            worldEntries,
            0u,
            surfacePosition,
            receiverNormal,
            treeSample.x);
    if (worldPick.valid == 0u) {
        return primeInvalidAreaLightSample();
    }
    uint64_t sectionLightAddress = sections[worldPick.index].lightAddress;
    if (sectionLightAddress == uint64_t(0)) {
        return primeInvalidAreaLightSample();
    }
    SectionLightHeader* sectionBuffer = (SectionLightHeader*)sectionLightAddress;
    uint64_t sectionNodeAddress = sectionBuffer[0].nodeAddress;
    uint64_t sectionLeafAddress = sectionBuffer[0].leafAddress;
    uint64_t sectionEntryAddress = sectionBuffer[0].entryAddress;
    uint sectionRoot = sectionBuffer[0].root;
    uint emitterCount = sectionBuffer[0].emitterCount;
    LightNode* sectionNodes = (LightNode*)sectionNodeAddress;
    LightLeaf* sectionLeaves = (LightLeaf*)sectionLeafAddress;
    LightLeafEntry* sectionEntries = (LightLeafEntry*)sectionEntryAddress;
    float3 sectionTranslation = sections[worldPick.index].translation;
    float3 localSurfacePosition = surfacePosition - sectionTranslation;
    LightTreePick sectionPick = primePickLightTree(
            sectionNodes,
            sectionLeaves,
            sectionEntries,
            sectionRoot,
            localSurfacePosition,
            receiverNormal,
            worldPick.remapped);
    if (sectionPick.valid == 0u || sectionPick.index >= emitterCount) {
        return primeInvalidAreaLightSample();
    }
    return primeSampleAreaLightEmitter(
            surfacePosition,
            worldPick.index,
            sectionPick.index,
            worldPick.pdf * sectionPick.pdf,
            treeSample.y,
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
            emitters[areaSample.emitterIndex].uvsTint.w,
            emitters[areaSample.emitterIndex].metadata.z,
            emitters[areaSample.emitterIndex].edgeOneScale.w,
            areaSample.uv);
}

public LightEvaluation primeEvaluateAreaLight(
        SurfaceInteraction surface,
        float3 rayOrigin,
        float3 rayDirection,
        uint packedReceiverNormal,
        bool evaluatePdf) {
    LightEvaluation result;
    result.radiance = float3(0.0);
    result.pdf = 0.0;
    if (!evaluatePdf) {
        result.radiance = primeEvaluateHitEmitterRadiance(
                surface, rayDirection);
        return result;
    }
    uint emitterIndex = primeSurfaceEmitterIndex(surface);
    if (emitterIndex == PRIME_NO_LIGHT_INDEX) {
        return result;
    }
    SectionRecord* sections = (SectionRecord*)primePush.sectionTableAddress;
    uint64_t sectionLightAddress = sections[surface.sectionIndex].lightAddress;
    if (sectionLightAddress == uint64_t(0)) {
        return result;
    }
    SectionLightHeader* sectionBuffer = (SectionLightHeader*)sectionLightAddress;
    if (emitterIndex >= sectionBuffer[0].emitterCount) {
        return result;
    }
    LightEmitter* emitters = (LightEmitter*)sectionBuffer[0].emitterAddress;
    float4 emitterCornerArea = emitters[emitterIndex].cornerArea;
    float lightCosine = primeEmitterCosine(
            emitters[emitterIndex].normalPadding.xyz,
            emitters[emitterIndex].metadata.z,
            rayDirection);
    if (!(lightCosine > 0.0) || !(emitterCornerArea.w > 0.0)) {
        return result;
    }

    float3 sectionTranslation = sections[surface.sectionIndex].translation;
    float3 localPosition = surface.position - sectionTranslation;
    float3 relative = localPosition - emitterCornerArea.xyz;
    float3 firstEdge = emitters[emitterIndex].edgeOneScale.xyz;
    float3 secondEdge = emitters[emitterIndex].edgeTwoPower.xyz;
    float3 edgeCross = cross(firstEdge, secondEdge);
    float denominator = dot(edgeCross, edgeCross);
    float2 parentBarycentric = float2(
            dot(cross(relative, secondEdge), edgeCross) / denominator,
            dot(cross(firstEdge, relative), edgeCross) / denominator);
    result.radiance = primeEvaluateEmitterRadiance(
            emitters[emitterIndex].uvsTint.w,
            emitters[emitterIndex].metadata.z,
            emitters[emitterIndex].edgeOneScale.w,
            primeEmitterUv(
                    emitters[emitterIndex].uvsTint.xyz,
                    parentBarycentric),
            surface.textureLod);
    float3 receiverNormal = primeUnpackLightReceiverNormal(packedReceiverNormal);

    uint64_t worldNodeAddress = sections[surface.sectionIndex].worldLightAddress;
    uint64_t worldLeafAddress =
            sections[surface.sectionIndex].worldLightLeafAddress;
    uint worldLeafCount = sections[surface.sectionIndex].worldLightLeafCount;
    uint worldLightPath = sections[surface.sectionIndex].worldLightPath;
    if (worldNodeAddress == uint64_t(0)
            || worldLeafAddress == uint64_t(0)
            || worldLeafCount == 0u
            || worldLightPath == PRIME_NO_LIGHT_INDEX) {
        return result;
    }
    LightCell* cells = (LightCell*)sectionBuffer[0].cellAddress;
    uint cellIndex = primeLightCellIndex(parentBarycentric);
    uint emitterCellBase = emitters[emitterIndex].metadata.x;
    float cellProbabilityMass = cells[emitterCellBase + cellIndex].probabilityMass;
    float cellArea = emitterCornerArea.w / float(PRIME_LIGHT_CELL_COUNT);
    if (!(cellProbabilityMass > 0.0) || !(cellArea > 0.0)) {
        return result;
    }
    float worldPdf;
    {
        LightNode* worldNodes = primeWorldLightNodes(worldNodeAddress);
        LightLeaf* worldLeaves = (LightLeaf*)worldLeafAddress;
        LightLeafEntry* worldEntries = (LightLeafEntry*)(
                worldLeafAddress
                        + uint64_t(worldLeafCount) * uint64_t(PRIME_LIGHT_LEAF_SIZE));
        worldPdf = primeLightTreeSelectionPdf(
                worldNodes,
                worldLeaves,
                worldEntries,
                0u,
                worldLightPath,
                surface.sectionIndex,
                rayOrigin,
                receiverNormal);
    }
    float sectionPdf;
    {
        LightNode* sectionNodes = (LightNode*)sectionBuffer[0].nodeAddress;
        LightLeaf* sectionLeaves = (LightLeaf*)sectionBuffer[0].leafAddress;
        LightLeafEntry* sectionEntries =
                (LightLeafEntry*)sectionBuffer[0].entryAddress;
        sectionPdf = primeLightTreeSelectionPdf(
                sectionNodes,
                sectionLeaves,
                sectionEntries,
                sectionBuffer[0].root,
                emitters[emitterIndex].metadata.y,
                emitterIndex,
                rayOrigin - sectionTranslation,
                receiverNormal);
    }
    if (!(worldPdf > 0.0) || !(sectionPdf > 0.0)) {
        return result;
    }
    float areaPdf = worldPdf * sectionPdf * cellProbabilityMass / cellArea;
    result.pdf = primeAreaSolidAnglePdf(
            rayOrigin,
            surface.position,
            lightCosine,
            areaPdf);
    return result;
}
