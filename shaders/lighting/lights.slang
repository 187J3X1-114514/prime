#language slang 2026
module "lighting/lights.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "core/atmosphere.slang";
import "core/atmosphere_rt.slang";
import "core/numerical_diagnostics.slang";
import "core/packed_geometry.slang";
import "core/render_config.slang";
import "core/sampling.slang";
import "core/transport_math.slang";
import "material/material.slang";
import "core/light_tree_math.slang";
import "core/starmap.slang";

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

public float primePowerHeuristic(
        inout PrimeNumericalDiagnostics diagnostics,
        float firstPdf,
        float secondPdf) {
    primeRecordNonnegative(diagnostics, firstPdf);
    primeRecordNonnegative(diagnostics, secondPdf);
    return primePowerHeuristicValue(firstPdf, secondPdf);
}

public float primePowerHeuristicOverPdf(
        inout PrimeNumericalDiagnostics diagnostics,
        float sampledPdf,
        float otherPdf) {
    primeRecordNonnegative(diagnostics, sampledPdf);
    primeRecordNonnegative(diagnostics, otherPdf);
    float result = primePowerHeuristicOverPdfValue(sampledPdf, otherPdf);
    primeRecordNonnegative(diagnostics, result);
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

public static const uint PRIME_NO_LIGHT_INDEX = 0xffffffffu;
static const uint PRIME_LIGHT_LEAF_FLAG = 0x80000000u;
static const uint PRIME_LIGHT_INDEX_MASK = 0x7fffffffu;
static const uint PRIME_LIGHT_TREE_MAX_DEPTH = 27u;
static const uint PRIME_LIGHT_PATH_DEPTH_SHIFT = 27u;
static const uint PRIME_LIGHT_PATH_TRAIL_MASK = 0x07ffffffu;
static const uint PRIME_LIGHT_LEAF_MAX_ENTRIES = 8u;
static const uint PRIME_LIGHT_CELL_SUBDIVISION = 16u;
static const uint PRIME_LIGHT_CELL_COUNT = 256u;
static const uint PRIME_AREA_RIS_CANDIDATE_COUNT = 4u;
static const uint PRIME_EMITTER_TWO_SIDED = 1u;

struct LightTreePick {
    uint index;
    float pdf;
    float remapped;
    float leafSoftening;
    uint valid;
};

struct PrimeLightTreePick4 {
    uint4 indices;
    float4 pdfs;
    float4 remapped;
    uint validMask;
};

struct PrimeLightTreeTask4 {
    uint nodeIndex;
    uint firstCandidate;
    uint candidateCount;
    uint depth;
    float lowerBound;
    float pdf;
};

LightTreePick primeInvalidLightTreePick() {
    LightTreePick result;
    result.index = 0u;
    result.pdf = 0.0;
    result.remapped = 0.0;
    result.leafSoftening = 0.0;
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

void primeUnpackLightNode(
        LightNode* nodes,
        uint index,
        out float3 centroid,
        out float power,
        out float softening,
        out uint emissionDirection,
        out uint childOrLeaf) {
    LightNode node = nodes[index];
    float2 minXY = unpackHalf2x16ToFloat(node.packedBoundsDirection.x);
    float2 minZMaxX = unpackHalf2x16ToFloat(node.packedBoundsDirection.y);
    float2 maxYZ = unpackHalf2x16ToFloat(node.packedBoundsDirection.z);
    float3 boundsMin = float3(minXY, minZMaxX.x);
    float3 boundsMax = float3(minZMaxX.y, maxYZ);
    uint packedCentroid = node.childReserved.y;
    float3 normalizedCentroid = float3(
            float(packedCentroid & 0x3ffu),
            float((packedCentroid >> 10u) & 0x3ffu),
            float((packedCentroid >> 20u) & 0x3ffu)) / 1023.0;
    centroid = lerp(boundsMin, boundsMax, normalizedCentroid);
    power = node.powerSoftening.x;
    softening = node.powerSoftening.y;
    emissionDirection = node.packedBoundsDirection.w;
    childOrLeaf = node.childReserved.x;
}

float2 primeLoadLightNodeMetrics(
        LightNode* nodes,
        uint index,
        float3 point,
        float3 receiverNormal) {
    float3 centroid;
    float power;
    float softening;
    uint emissionDirection;
    uint childOrLeaf;
    primeUnpackLightNode(
            nodes,
            index,
            centroid,
            power,
            softening,
            emissionDirection,
            childOrLeaf);
    return primeLightNodeMetrics(
            centroid,
            power,
            softening,
            point,
            receiverNormal,
            emissionDirection);
}

float2 primeLoadLightNodeMetrics(
        LightNode* nodes,
        uint index,
        float3 point,
        float3 receiverNormal,
        bool usesSoftening) {
    if (usesSoftening) {
        return primeLoadLightNodeMetrics(nodes, index, point, receiverNormal);
    }
    float3 centroid;
    float power;
    float softening;
    uint emissionDirection;
    uint childOrLeaf;
    primeUnpackLightNode(
            nodes,
            index,
            centroid,
            power,
            softening,
            emissionDirection,
            childOrLeaf);
    return primeLightNodeMetrics(
            centroid,
            power,
            0.0,
            point,
            receiverNormal,
            emissionDirection);
}

interface IPrimeLightLeafMetrics {
    float2 load(
            LightLeafEntry entry,
            float3 point,
            float3 receiverNormal,
            float leafSoftening,
            bool usesSoftening);
}

struct PrimeWorldLightLeafMetrics : IPrimeLightLeafMetrics {
    SectionRecord* sections;

    float2 load(
            LightLeafEntry entry,
            float3 point,
            float3 receiverNormal,
            float leafSoftening,
            bool usesSoftening) {
        SectionRecord section = sections[entry.index];
        if (section.lightAddress == uint64_t(0)) {
            return float2(0.0);
        }
        SectionLightHeader* header = (SectionLightHeader*)section.lightAddress;
        if (header[0].nodeAddress == uint64_t(0)) {
            return float2(0.0);
        }
        return primeLoadLightNodeMetrics(
                (LightNode*)header[0].nodeAddress,
                header[0].root,
                point - section.translation,
                receiverNormal,
                usesSoftening);
    }
}

struct PrimeSectionLightLeafMetrics : IPrimeLightLeafMetrics {
    LightEmitter* emitters;

    float2 load(
            LightLeafEntry entry,
            float3 point,
            float3 receiverNormal,
            float leafSoftening,
            bool usesSoftening) {
        LightEmitter emitter = emitters[entry.index];
        float3 first = emitter.cornerArea.xyz;
        float3 second = first + emitter.edgeOneScale.xyz;
        float3 third = first + emitter.edgeTwoPower.xyz;
        float3 boundsMin = min(first, min(second, third));
        float3 boundsMax = max(first, max(second, third));
        float3 closest = clamp(point, boundsMin, boundsMax);
        float3 delta = point - closest;
        float distanceSquared = dot(delta, delta);
        float inverseDistance = distanceSquared > 0.0 ? rsqrt(distanceSquared) : 0.0;
        float receiverBound = primeLightReceiverCosineBoundWithInverse(
                boundsMin,
                boundsMax,
                point,
                receiverNormal,
                distanceSquared,
                inverseDistance);
        float emitterBound = primeLightEmissionAxisCosineBound(
                boundsMin,
                boundsMax,
                point,
                emitter.normalPadding.xyz,
                distanceSquared,
                inverseDistance);
        if ((emitter.metadata.z & PRIME_EMITTER_TWO_SIDED) != 0u) {
            emitterBound = 0.5 * max(
                    emitterBound,
                    primeLightEmissionAxisCosineBound(
                            boundsMin,
                            boundsMax,
                            point,
                            -emitter.normalPadding.xyz,
                            distanceSquared,
                            inverseDistance));
        }
        float angularImportance = clamp(receiverBound * emitterBound, 0.0, 1.0);
        float weightedPower = max(entry.power, 0.0)
                * (PRIME_LIGHT_RECEIVER_COSINE_FLOOR
                        + (1.0 - PRIME_LIGHT_RECEIVER_COSINE_FLOOR)
                                * angularImportance);
        return float2(
                distanceSquared + (usesSoftening ? max(leafSoftening, 0.0) : 0.0),
                weightedPower);
    }
}

PrimeLightTreePick4 primePickLightTreeRis4(
        LightNode* nodes,
        LightLeaf* leaves,
        LightLeafEntry* entries,
        uint root,
        float3 point,
        float3 receiverNormal,
        float4 sortedSeeds) {
    PrimeLightTreePick4 result;
    result.indices = uint4(0u);
    result.pdfs = float4(0.0);
    result.remapped = float4(0.0);
    result.validMask = 0u;

    PrimeLightTreeTask4 tasks[4];
    tasks[0].nodeIndex = root;
    tasks[0].firstCandidate = 0u;
    tasks[0].candidateCount = 4u;
    tasks[0].depth = 0u;
    tasks[0].lowerBound = 0.0;
    tasks[0].pdf = 1.0;
    uint taskCount = 1u;
    [loop]
    for (uint iteration = 0u;
            iteration < 4u * (PRIME_LIGHT_TREE_MAX_DEPTH + 1u);
            ++iteration) {
        if (taskCount == 0u) break;
        PrimeLightTreeTask4 task = tasks[--taskCount];
        if (task.depth > PRIME_LIGHT_TREE_MAX_DEPTH) {
            result.validMask = 0u;
            return result;
        }
        uint childOrLeaf = nodes[task.nodeIndex].childReserved.x;
        if ((childOrLeaf & PRIME_LIGHT_LEAF_FLAG) != 0u) {
            LightLeaf leaf = leaves[childOrLeaf & PRIME_LIGHT_INDEX_MASK];
            float totalPower = nodes[task.nodeIndex].powerSoftening.x;
            if (!(totalPower > 0.0)
                    || leaf.entryCount == 0u
                    || leaf.entryCount > PRIME_LIGHT_LEAF_MAX_ENTRIES) {
                result.validMask = 0u;
                return result;
            }
            [[unroll]]
            for (uint localCandidate = 0u; localCandidate < 4u; ++localCandidate) {
                if (localCandidate >= task.candidateCount) break;
                uint candidateIndex = task.firstCandidate + localCandidate;
                float localSeed = clamp(
                        (sortedSeeds[candidateIndex] - task.lowerBound) / task.pdf,
                        0.0,
                        asfloat(0x3f7fffffu));
                float entryLower = 0.0;
                bool selected = false;
                [[unroll]]
                for (uint offset = 0u; offset < PRIME_LIGHT_LEAF_MAX_ENTRIES; ++offset) {
                    if (offset >= leaf.entryCount) break;
                    LightLeafEntry entry = entries[leaf.firstEntry + offset];
                    float entryProbability = entry.power / totalPower;
                    float entryUpper = entryLower + entryProbability;
                    if (localSeed < entryUpper || offset + 1u == leaf.entryCount) {
                        if (!(entryProbability > 0.0)) {
                            result.validMask = 0u;
                            return result;
                        }
                        result.indices[candidateIndex] = entry.index;
                        result.pdfs[candidateIndex] = task.pdf * entryProbability;
                        result.remapped[candidateIndex] = clamp(
                                (localSeed - entryLower) / entryProbability,
                                0.0,
                                asfloat(0x3f7fffffu));
                        result.validMask |= 1u << candidateIndex;
                        selected = true;
                        break;
                    }
                    entryLower = entryUpper;
                }
                if (!selected) {
                    result.validMask = 0u;
                    return result;
                }
            }
            continue;
        }
        if (task.depth == PRIME_LIGHT_TREE_MAX_DEPTH) {
            result.validMask = 0u;
            return result;
        }
        uint leftIndex = childOrLeaf;
        float2 leftMetrics = primeLoadLightNodeMetrics(
                nodes, leftIndex, point, receiverNormal);
        float2 rightMetrics = primeLoadLightNodeMetrics(
                nodes, leftIndex + 1u, point, receiverNormal);
        float leftProbability = primeLightBranchProbability(leftMetrics, rightMetrics);
        if (!(leftProbability >= 0.0)) {
            result.validMask = 0u;
            return result;
        }
        float rightProbability = 1.0 - leftProbability;
        float split = task.lowerBound + task.pdf * leftProbability;
        uint firstRight = task.firstCandidate;
        [[unroll]]
        for (uint localCandidate = 0u; localCandidate < 4u; ++localCandidate) {
            if (localCandidate >= task.candidateCount) break;
            uint candidateIndex = task.firstCandidate + localCandidate;
            if (sortedSeeds[candidateIndex] >= split) break;
            ++firstRight;
        }
        uint leftCount = firstRight - task.firstCandidate;
        uint rightCount = task.candidateCount - leftCount;
        if ((leftCount > 0u && !(leftProbability > 0.0))
                || (rightCount > 0u && !(rightProbability > 0.0))) {
            result.validMask = 0u;
            return result;
        }
        if (rightCount > 0u) {
            if (taskCount >= 4u) {
                result.validMask = 0u;
                return result;
            }
            tasks[taskCount].nodeIndex = leftIndex + 1u;
            tasks[taskCount].firstCandidate = firstRight;
            tasks[taskCount].candidateCount = rightCount;
            tasks[taskCount].depth = task.depth + 1u;
            tasks[taskCount].lowerBound = split;
            tasks[taskCount].pdf = task.pdf * rightProbability;
            ++taskCount;
        }
        if (leftCount > 0u) {
            if (taskCount >= 4u) {
                result.validMask = 0u;
                return result;
            }
            tasks[taskCount].nodeIndex = leftIndex;
            tasks[taskCount].firstCandidate = task.firstCandidate;
            tasks[taskCount].candidateCount = leftCount;
            tasks[taskCount].depth = task.depth + 1u;
            tasks[taskCount].lowerBound = task.lowerBound;
            tasks[taskCount].pdf = task.pdf * leftProbability;
            ++taskCount;
        }
    }
    if (result.validMask != 0xfu) {
        result.validMask = 0u;
    }
    return result;
}

LightTreePick primePickLightTree<LeafMetrics>(
        LightNode* nodes,
        LightLeaf* leaves,
        LightLeafEntry* entries,
        LeafMetrics leafMetrics,
        uint root,
        float3 point,
        float3 receiverNormal,
        float seed)
    where LeafMetrics : IPrimeLightLeafMetrics {
    uint nodeIndex = root;
    float lowerBound = 0.0;
    float pdf = 1.0;
    bool usesSoftening = primeLightTreeUsesSoftening();
    bool usesReceiverLeaves = primeLightTreeUsesReceiverLeaves();
    [loop]
    for (uint depth = 0u; depth <= PRIME_LIGHT_TREE_MAX_DEPTH; ++depth) {
        uint childOrLeaf = nodes[nodeIndex].childReserved.x;
        if ((childOrLeaf & PRIME_LIGHT_LEAF_FLAG) != 0u) {
            uint leafIndex = childOrLeaf & PRIME_LIGHT_INDEX_MASK;
            LightLeaf leaf = leaves[leafIndex];
            float totalPower = nodes[nodeIndex].powerSoftening.x;
            if (!(totalPower > 0.0) || leaf.entryCount == 0u
                    || leaf.entryCount > PRIME_LIGHT_LEAF_MAX_ENTRIES) {
                return primeInvalidLightTreePick();
            }
            float localSeed = clamp(
                    (seed - lowerBound) / pdf,
                    0.0,
                    asfloat(0x3f7fffffu));
            if (usesReceiverLeaves) {
                float leafSoftening = nodes[nodeIndex].powerSoftening.y;
                float maximumPower = 0.0;
                float minimumPositiveDistance = asfloat(0x7f7fffffu);
                bool hasZeroDistance = false;
                [[unroll]]
                for (uint offset = 0u; offset < PRIME_LIGHT_LEAF_MAX_ENTRIES; ++offset) {
                    if (offset >= leaf.entryCount) break;
                    LightLeafEntry entry = entries[leaf.firstEntry + offset];
                    float2 value = leafMetrics.load(
                            entry,
                            point,
                            receiverNormal,
                            leafSoftening,
                            usesSoftening);
                    value = max(value, float2(0.0));
                    maximumPower = max(maximumPower, value.y);
                    hasZeroDistance = hasZeroDistance || value.x == 0.0;
                    if (value.x > 0.0) {
                        minimumPositiveDistance = min(minimumPositiveDistance, value.x);
                    }
                }
                if (!(maximumPower > 0.0)) {
                    return primeInvalidLightTreePick();
                }
                float totalScore = 0.0;
                [[unroll]]
                for (uint offset = 0u; offset < PRIME_LIGHT_LEAF_MAX_ENTRIES; ++offset) {
                    if (offset >= leaf.entryCount) break;
                    LightLeafEntry entry = entries[leaf.firstEntry + offset];
                    float2 value = max(
                            leafMetrics.load(
                                    entry,
                                    point,
                                    receiverNormal,
                                    leafSoftening,
                                    usesSoftening),
                            float2(0.0));
                    float score = primeLightLeafScore(
                            value,
                            maximumPower,
                            minimumPositiveDistance,
                            hasZeroDistance);
                    totalScore += score;
                }
                if (!(totalScore > 0.0)) {
                    return primeInvalidLightTreePick();
                }
                float entryLower = 0.0;
                [[unroll]]
                for (uint offset = 0u; offset < PRIME_LIGHT_LEAF_MAX_ENTRIES; ++offset) {
                    if (offset >= leaf.entryCount) break;
                    LightLeafEntry entry = entries[leaf.firstEntry + offset];
                    float2 value = max(
                            leafMetrics.load(
                                    entry,
                                    point,
                                    receiverNormal,
                                    leafSoftening,
                                    usesSoftening),
                            float2(0.0));
                    float entryProbability = primeLightLeafScore(
                            value,
                            maximumPower,
                            minimumPositiveDistance,
                            hasZeroDistance) / totalScore;
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
                        result.leafSoftening = max(
                                nodes[nodeIndex].powerSoftening.y, 0.0);
                        result.valid = 1u;
                        return result;
                    }
                    entryLower = entryUpper;
                }
                return primeInvalidLightTreePick();
            }
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
                    result.leafSoftening = max(
                            nodes[nodeIndex].powerSoftening.y, 0.0);
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
                nodes, leftIndex, point, receiverNormal, usesSoftening);
        float2 rightMetrics = primeLoadLightNodeMetrics(
                nodes, rightIndex, point, receiverNormal, usesSoftening);
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

float primeLightTreeSelectionPdf<LeafMetrics>(
        LightNode* nodes,
        LightLeaf* leaves,
        LightLeafEntry* entries,
        LeafMetrics leafMetrics,
        uint root,
        uint packedPath,
        uint expectedIndex,
        float3 point,
        float3 receiverNormal)
    where LeafMetrics : IPrimeLightLeafMetrics {
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
    bool usesSoftening = primeLightTreeUsesSoftening();
    bool usesReceiverLeaves = primeLightTreeUsesReceiverLeaves();
    [loop]
    for (uint level = 0u; level < PRIME_LIGHT_TREE_MAX_DEPTH; ++level) {
        if (level >= depth) break;
        uint childOrLeaf = nodes[nodeIndex].childReserved.x;
        if ((childOrLeaf & PRIME_LIGHT_LEAF_FLAG) != 0u) {
            return 0.0;
        }
        uint leftIndex = childOrLeaf;
        float2 leftMetrics = primeLoadLightNodeMetrics(
                nodes, leftIndex, point, receiverNormal, usesSoftening);
        float2 rightMetrics = primeLoadLightNodeMetrics(
                nodes, leftIndex + 1u, point, receiverNormal, usesSoftening);
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
    uint childOrLeaf = nodes[nodeIndex].childReserved.x;
    if ((childOrLeaf & PRIME_LIGHT_LEAF_FLAG) == 0u) return 0.0;
    LightLeaf leaf = leaves[childOrLeaf & PRIME_LIGHT_INDEX_MASK];
    float totalPower = nodes[nodeIndex].powerSoftening.x;
    if (!(totalPower > 0.0) || leaf.entryCount > PRIME_LIGHT_LEAF_MAX_ENTRIES) {
        return 0.0;
    }
    if (usesReceiverLeaves) {
        float leafSoftening = nodes[nodeIndex].powerSoftening.y;
        float2 expectedMetrics = float2(0.0);
        float maximumPower = 0.0;
        float minimumPositiveDistance = asfloat(0x7f7fffffu);
        bool hasZeroDistance = false;
        bool found = false;
        [[unroll]]
        for (uint offset = 0u; offset < PRIME_LIGHT_LEAF_MAX_ENTRIES; ++offset) {
            if (offset >= leaf.entryCount) break;
            LightLeafEntry entry = entries[leaf.firstEntry + offset];
            float2 value = max(
                    leafMetrics.load(
                            entry,
                            point,
                            receiverNormal,
                            leafSoftening,
                            usesSoftening),
                    float2(0.0));
            maximumPower = max(maximumPower, value.y);
            hasZeroDistance = hasZeroDistance || value.x == 0.0;
            if (value.x > 0.0) {
                minimumPositiveDistance = min(minimumPositiveDistance, value.x);
            }
            if (entry.index == expectedIndex) {
                expectedMetrics = value;
                found = true;
            }
        }
        if (!found || !(maximumPower > 0.0)) {
            return 0.0;
        }
        float totalScore = 0.0;
        [[unroll]]
        for (uint offset = 0u; offset < PRIME_LIGHT_LEAF_MAX_ENTRIES; ++offset) {
            if (offset >= leaf.entryCount) break;
            LightLeafEntry entry = entries[leaf.firstEntry + offset];
            float2 value = max(
                    leafMetrics.load(
                            entry,
                            point,
                            receiverNormal,
                            leafSoftening,
                            usesSoftening),
                    float2(0.0));
            float score = primeLightLeafScore(
                    value,
                    maximumPower,
                    minimumPositiveDistance,
                    hasZeroDistance);
            totalScore += score;
        }
        float expectedScore = primeLightLeafScore(
                expectedMetrics,
                maximumPower,
                minimumPositiveDistance,
                hasZeroDistance);
        return totalScore > 0.0 && expectedScore > 0.0
                ? pdf * expectedScore / totalScore
                : 0.0;
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

float2 primeEmitterUv(uint3 packedUvs, float2 parentBarycentric) {
    float2 uv0 = primeUnpackUv(packedUvs.x);
    float2 uv1 = primeUnpackUv(packedUvs.y);
    float2 uv2 = primeUnpackUv(packedUvs.z);
    return uv0 * (1.0 - parentBarycentric.x - parentBarycentric.y)
            + uv1 * parentBarycentric.x
            + uv2 * parentBarycentric.y;
}

float primeEmitterCosine(
        float3 emitterNormal,
        uint emitterFlags,
        float3 directionFromSurfaceToLight) {
    float cosine = dot(emitterNormal, -directionFromSurfaceToLight);
    return (emitterFlags & PRIME_EMITTER_TWO_SIDED) != 0u
            ? abs(cosine)
            : max(cosine, 0.0);
}

float primeAreaSolidAnglePdf(
        inout PrimeNumericalDiagnostics diagnostics,
        float3 origin,
        float3 lightPosition,
        float lightCosine,
        float areaPdf) {
    float3 delta = lightPosition - origin;
    float distanceSquared = dot(delta, delta);
    primeRecordNonFinite(diagnostics, origin);
    primeRecordNonFinite(diagnostics, lightPosition);
    primeRecordNonnegative(diagnostics, distanceSquared);
    primeRecordUnit(diagnostics, lightCosine);
    primeRecordNonnegative(diagnostics, areaPdf);
    return primeAreaSolidAnglePdfValue(areaPdf, distanceSquared, lightCosine);
}

AreaLightSample primeSampleAreaLightEmitter(
        inout PrimeNumericalDiagnostics diagnostics,
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
            diagnostics,
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
        inout PrimeNumericalDiagnostics diagnostics,
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
    LightNode* worldNodes = (LightNode*)worldNodeAddress;
    LightLeaf* worldLeaves = (LightLeaf*)worldLeafAddress;
    LightLeafEntry* worldEntries = (LightLeafEntry*)(
            worldLeafAddress + uint64_t(worldLeafCount) * uint64_t(PRIME_LIGHT_LEAF_SIZE));
    PrimeWorldLightLeafMetrics worldLeafMetrics;
    worldLeafMetrics.sections = sections;
    LightTreePick worldPick = primePickLightTree(
            worldNodes,
            worldLeaves,
            worldEntries,
            worldLeafMetrics,
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
    LightEmitter* emitters = (LightEmitter*)sectionBuffer[0].emitterAddress;
    PrimeSectionLightLeafMetrics sectionLeafMetrics;
    sectionLeafMetrics.emitters = emitters;
    float3 sectionTranslation = sections[worldPick.index].translation;
    float3 localSurfacePosition = surfacePosition - sectionTranslation;
    LightTreePick sectionPick = primePickLightTree(
            sectionNodes,
            sectionLeaves,
            sectionEntries,
            sectionLeafMetrics,
            sectionRoot,
            localSurfacePosition,
            receiverNormal,
            worldPick.remapped);
    if (sectionPick.valid == 0u || sectionPick.index >= emitterCount) {
        return primeInvalidAreaLightSample();
    }
    return primeSampleAreaLightEmitter(
            diagnostics,
            surfacePosition,
            worldPick.index,
            sectionPick.index,
            worldPick.pdf * sectionPick.pdf,
            treeSample.y,
            positionSample);
}

struct PrimeAreaRisCandidate {
    uint sectionIndex;
    uint emitterIndex;
    float proposalPdf;
    float2 targetMetrics;
    uint valid;
};

PrimeAreaRisCandidate primePickAreaRisCandidate(
        SectionRecord* sections,
        float3 surfacePosition,
        float3 receiverNormal,
        uint sectionIndex,
        float worldPdf,
        float sectionSeed) {
    PrimeAreaRisCandidate result;
    result.sectionIndex = PRIME_NO_LIGHT_INDEX;
    result.emitterIndex = PRIME_NO_LIGHT_INDEX;
    result.proposalPdf = 0.0;
    result.targetMetrics = float2(0.0);
    result.valid = 0u;

    if (!(worldPdf > 0.0)) {
        return result;
    }
    uint64_t sectionLightAddress = sections[sectionIndex].lightAddress;
    if (sectionLightAddress == uint64_t(0)) {
        return result;
    }
    SectionLightHeader* sectionBuffer = (SectionLightHeader*)sectionLightAddress;
    LightNode* sectionNodes = (LightNode*)sectionBuffer[0].nodeAddress;
    LightLeaf* sectionLeaves = (LightLeaf*)sectionBuffer[0].leafAddress;
    LightLeafEntry* sectionEntries =
            (LightLeafEntry*)sectionBuffer[0].entryAddress;
    LightEmitter* emitters = (LightEmitter*)sectionBuffer[0].emitterAddress;
    PrimeSectionLightLeafMetrics sectionLeafMetrics;
    sectionLeafMetrics.emitters = emitters;
    float3 localSurfacePosition =
            surfacePosition - sections[sectionIndex].translation;
    LightTreePick sectionPick = primePickLightTree(
            sectionNodes,
            sectionLeaves,
            sectionEntries,
            sectionLeafMetrics,
            sectionBuffer[0].root,
            localSurfacePosition,
            receiverNormal,
            sectionSeed);
    if (sectionPick.valid == 0u
            || sectionPick.index >= sectionBuffer[0].emitterCount) {
        return result;
    }
    float proposalPdf = worldPdf * sectionPick.pdf;
    if (!(proposalPdf > 0.0)) {
        return result;
    }
    LightLeafEntry targetEntry;
    targetEntry.index = sectionPick.index;
    targetEntry.power = emitters[sectionPick.index].edgeTwoPower.w;
    // Tree regularization belongs to the proposal; RIS must rank candidates by the unsoftened
    // emitter importance so it can recover selection quality without losing proposal coverage.
    float2 targetMetrics = max(
            sectionLeafMetrics.load(
                    targetEntry,
                    localSurfacePosition,
                    receiverNormal,
                    sectionPick.leafSoftening,
                    false),
            float2(0.0));
    if (!(targetMetrics.x > 0.0) || !(targetMetrics.y > 0.0)) {
        return result;
    }
    result.sectionIndex = sectionIndex;
    result.emitterIndex = sectionPick.index;
    result.proposalPdf = proposalPdf;
    result.targetMetrics = targetMetrics;
    result.valid = 1u;
    return result;
}

public AreaLightSample primeSampleAreaLightRis4(
        inout PrimeNumericalDiagnostics diagnostics,
        float3 surfacePosition,
        uint packedReceiverNormal,
        float3 selectionSample,
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
    LightNode* worldNodes = (LightNode*)worldNodeAddress;
    LightLeaf* worldLeaves = (LightLeaf*)worldLeafAddress;
    LightLeafEntry* worldEntries = (LightLeafEntry*)(
            worldLeafAddress + uint64_t(worldLeafCount) * uint64_t(PRIME_LIGHT_LEAF_SIZE));

    // Sorting a rotated four-strata set preserves the candidates while allowing shared-prefix
    // world traversal. Section trees remain independent; only the resampled emitter reaches its
    // cell alias and geometry.
    float seedBase = 0.25 * frac(selectionSample.x * 4.0);
    float4 worldSeeds = seedBase + float4(0.0, 0.25, 0.5, 0.75);
    PrimeLightTreePick4 worldPicks = primePickLightTreeRis4(
            worldNodes,
            worldLeaves,
            worldEntries,
            0u,
            surfacePosition,
            receiverNormal,
            worldSeeds);
    if (worldPicks.validMask != 0xfu) {
        return primeInvalidAreaLightSample();
    }

    uint4 sectionIndices;
    uint4 emitterIndices;
    float4 proposalPdfs;
    float4 distances;
    float4 powers;
    [[unroll]]
    for (uint candidateIndex = 0u; candidateIndex < 4u; ++candidateIndex) {
        PrimeAreaRisCandidate candidate = primePickAreaRisCandidate(
                sections,
                surfacePosition,
                receiverNormal,
                worldPicks.indices[candidateIndex],
                worldPicks.pdfs[candidateIndex],
                worldPicks.remapped[candidateIndex]);
        if (candidate.valid == 0u) {
            return primeInvalidAreaLightSample();
        }
        sectionIndices[candidateIndex] = candidate.sectionIndex;
        emitterIndices[candidateIndex] = candidate.emitterIndex;
        proposalPdfs[candidateIndex] = candidate.proposalPdf;
        distances[candidateIndex] = candidate.targetMetrics.x;
        powers[candidateIndex] = candidate.targetMetrics.y;
    }

    float maximumPower = max(max(powers.x, powers.y), max(powers.z, powers.w));
    float minimumDistance = min(
            min(distances.x, distances.y),
            min(distances.z, distances.w));
    float minimumProposalPdf = min(
            min(proposalPdfs.x, proposalPdfs.y),
            min(proposalPdfs.z, proposalPdfs.w));
    float4 targets = (powers / maximumPower) * (minimumDistance / distances);
    float4 scaledWeights = float4(
            primeRisScaledWeightValue(targets.x, minimumProposalPdf, proposalPdfs.x),
            primeRisScaledWeightValue(targets.y, minimumProposalPdf, proposalPdfs.y),
            primeRisScaledWeightValue(targets.z, minimumProposalPdf, proposalPdfs.z),
            primeRisScaledWeightValue(targets.w, minimumProposalPdf, proposalPdfs.w));
    float scaledWeightSum = dot(scaledWeights, float4(1.0));
    if (!(scaledWeightSum > 0.0)) {
        return primeInvalidAreaLightSample();
    }
    uint selectedIndex = primeRisSelectIndex(scaledWeights, selectionSample.z);
    float effectiveEmitterPdf = primeRisEffectivePdfValue(
            1.0,
            targets[selectedIndex],
            minimumProposalPdf,
            scaledWeightSum,
            PRIME_AREA_RIS_CANDIDATE_COUNT);
    return primeSampleAreaLightEmitter(
            diagnostics,
            surfacePosition,
            sectionIndices[selectedIndex],
            emitterIndices[selectedIndex],
            effectiveEmitterPdf,
            selectionSample.y,
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
        inout PrimeNumericalDiagnostics diagnostics,
        SurfaceInteraction surface,
        float3 rayOrigin,
        float3 rayDirection,
        uint packedReceiverNormal,
        bool evaluatePdf) {
    LightEvaluation result;
    result.radiance = float3(0.0);
    result.pdf = 0.0;
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
    if (!evaluatePdf) {
        return result;
    }
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
        LightNode* worldNodes = (LightNode*)worldNodeAddress;
        LightLeaf* worldLeaves = (LightLeaf*)worldLeafAddress;
        LightLeafEntry* worldEntries = (LightLeafEntry*)(
                worldLeafAddress
                        + uint64_t(worldLeafCount) * uint64_t(PRIME_LIGHT_LEAF_SIZE));
        PrimeWorldLightLeafMetrics worldLeafMetrics;
        worldLeafMetrics.sections = sections;
        worldPdf = primeLightTreeSelectionPdf(
                worldNodes,
                worldLeaves,
                worldEntries,
                worldLeafMetrics,
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
        PrimeSectionLightLeafMetrics sectionLeafMetrics;
        sectionLeafMetrics.emitters = emitters;
        sectionPdf = primeLightTreeSelectionPdf(
                sectionNodes,
                sectionLeaves,
                sectionEntries,
                sectionLeafMetrics,
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
            diagnostics,
            rayOrigin,
            surface.position,
            lightCosine,
            areaPdf);
    return result;
}
