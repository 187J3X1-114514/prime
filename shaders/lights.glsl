#ifndef PRIME_LIGHTS_GLSL
#define PRIME_LIGHTS_GLSL

#include "material.glsl"
#include "light_tree_math.glsl"

struct LightSample {
    vec3 direction;
    float distance;
    vec3 radiance;
    float pdf;
    uint isDelta;
};

// Area-light geometry selection is deliberately separate from radiance resolution. Most sampled
// lights are occluded in dense terrain; retaining only stable indices and UVs across traceRayEXT
// avoids both an atlas read and live emitter state until the visibility test succeeds.
struct AreaLightSample {
    LightSample light;
    uint sectionIndex;
    uint emitterIndex;
    vec2 uv;
};

struct LightEvaluation {
    vec3 radiance;
    float pdf;
};

#include "starmap.glsl"

// All radiance values in this adapter are linear Rec.2020 D65. pdf is the complete f32 sampling
// density, including light-selection probability when a future registry introduces one. Reverse
// PDF queries must reuse that exact quantized value. The environment is evaluated only when a BSDF
// path escapes; the sun and area-light adapters are sampled explicitly and perform no selection.
// The submitted sun direction and all path directions are unit vectors at this boundary.
// Renormalizing them per vertex would only repeat a square root and three divisions.

float primePowerHeuristic(float firstPdf, float secondPdf) {
    primeRecordNonnegative(firstPdf);
    primeRecordNonnegative(secondPdf);
    return primePowerHeuristicValue(firstPdf, secondPdf);
}

float primePowerHeuristicOverPdf(float sampledPdf, float otherPdf) {
    primeRecordNonnegative(sampledPdf);
    primeRecordNonnegative(otherPdf);
    float result = primePowerHeuristicOverPdfValue(sampledPdf, otherPdf);
    primeRecordNonnegative(result);
    return result;
}

vec3 primeEnvironmentRadiance(IntegratorRecord integrator, vec3 direction) {
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
    return 4.0 * PRIME_PI * sineHalfRadius * sineHalfRadius;
}

float primeSunPdf() {
    return 1.0 / primeSunSolidAngle();
}

bool primeSunContainsDirection(IntegratorRecord integrator, vec3 direction) {
    return dot(direction, integrator.sunDirectionIntensity.xyz)
            >= primeSunCosAngularRadius();
}

vec3 primeSunRadiance(
        IntegratorRecord integrator,
        vec3 surfacePosition,
        vec3 direction) {
    return vec3(max(integrator.sunDirectionIntensity.w, 0.0) / primeSunSolidAngle())
            * primeAtmosphereSunTransmittance(surfacePosition, direction);
}

vec3 primeResolveSampledSunRadiance(
        IntegratorRecord integrator,
        vec3 surfacePosition,
        LightSample light) {
    return primeSunRadiance(integrator, surfacePosition, light.direction);
}

LightEvaluation primeEvaluateSun(
        IntegratorRecord integrator,
        vec3 surfacePosition,
        vec3 direction) {
    LightEvaluation result;
    bool containsDirection = primeSunContainsDirection(integrator, direction);
    result.radiance = containsDirection
            ? primeSunRadiance(integrator, surfacePosition, direction)
            : vec3(0.0);
    result.pdf = containsDirection ? primeSunPdf() : 0.0;
    return result;
}

LightSample primeSampleSun(
        IntegratorRecord integrator,
        vec3 surfacePosition,
        vec2 sampleValue) {
    float cosine = mix(primeSunCosAngularRadius(), 1.0, sampleValue.x);
    float sine = sqrt(1.0 - cosine * cosine);
    float azimuth = 2.0 * PRIME_PI * sampleValue.y;
    vec3 localDirection = vec3(sine * cos(azimuth), sine * sin(azimuth), cosine);
    LightSample result;
    result.direction = primeLocalToWorld(
            localDirection,
            integrator.sunDirectionIntensity.xyz);
    result.distance = 1000000.0;
    // Visibility must be known before evaluating the atmosphere transmittance.
    result.radiance = vec3(0.0);
    result.pdf = primeSunPdf();
    result.isDelta = 0u;
    return result;
}

const uint PRIME_NO_LIGHT_INDEX = 0xffffffffu;
const uint PRIME_LIGHT_LEAF_FLAG = 0x80000000u;
const uint PRIME_LIGHT_INDEX_MASK = 0x7fffffffu;
const uint PRIME_LIGHT_TREE_MAX_DEPTH = 64u;
const uint PRIME_LIGHT_CELL_SUBDIVISION = 16u;
const uint PRIME_LIGHT_CELL_COUNT = 256u;
const uint PRIME_EMITTER_TWO_SIDED = 1u;

struct LightTreePick {
    uint leaf;
    float pdf;
    uint valid;
};

LightTreePick primeInvalidLightTreePick() {
    LightTreePick result;
    result.leaf = 0u;
    result.pdf = 0.0;
    result.valid = 0u;
    return result;
}

AreaLightSample primeInvalidAreaLightSample() {
    AreaLightSample result;
    result.light.direction = vec3(0.0, 1.0, 0.0);
    result.light.distance = 0.0;
    result.light.radiance = vec3(0.0);
    result.light.pdf = 0.0;
    result.light.isDelta = 0u;
    result.sectionIndex = PRIME_NO_LIGHT_INDEX;
    result.emitterIndex = PRIME_NO_LIGHT_INDEX;
    result.uv = vec2(0.0);
    return result;
}

vec3 primeLoadLightNodeMetrics(
        LightNodeBuffer nodes,
        LightNodeForwardBuffer forwardNodes,
        uint index,
        vec3 point,
        vec3 receiverNormal) {
    // Returning three scalars keeps a complete 32-byte node out of the traversal loop state.
    vec4 boundsMinPower = nodes.nodes[index].boundsMinPower;
    vec4 boundsMaxSoftening = nodes.nodes[index].boundsMaxSoftening;
    return primeLightNodeMetrics(
            boundsMinPower,
            boundsMaxSoftening,
            point,
            receiverNormal,
            forwardNodes.nodes[index].emissionDirection);
}

LightTreePick primePickLightTree(
        LightNodeBuffer nodes,
        LightNodeForwardBuffer forwardNodes,
        uint root,
        vec3 point,
        vec3 receiverNormal,
        float seed) {
    // Bounds/power and forward metadata are the only hot streams. Parent information is kept in
    // the reverse-only stream, so ordinary light sampling cannot pull reverse-MIS metadata into
    // its cache working set.
    uint nodeIndex = root;
    float nodePower = nodes.nodes[root].boundsMinPower.w;
    float lowerBound = 0.0;
    float pdf = 1.0;
    // This bound is a traversal safety limit, not an invitation to duplicate 64 levels of the
    // light-tree body in native raygen code. Keeping the loop compact materially reduces cold
    // pipeline compilation without changing traversal order.
    [[dont_unroll]]
    for (uint depth = 0u; depth < PRIME_LIGHT_TREE_MAX_DEPTH; ++depth) {
        if (!(nodePower > 0.0)) {
            return primeInvalidLightTreePick();
        }
        uint childOrLeaf = forwardNodes.nodes[nodeIndex].childOrLeaf;
        if ((childOrLeaf & PRIME_LIGHT_LEAF_FLAG) != 0u) {
            LightTreePick result;
            result.leaf = childOrLeaf & PRIME_LIGHT_INDEX_MASK;
            result.pdf = pdf;
            result.valid = 1u;
            return result;
        }
        // CPU linearization guarantees that these mandatory sibling records are consecutive.
        uint leftIndex = childOrLeaf;
        uint rightIndex = leftIndex + 1u;
        vec3 leftMetrics = primeLoadLightNodeMetrics(
                nodes, forwardNodes, leftIndex, point, receiverNormal);
        vec3 rightMetrics = primeLoadLightNodeMetrics(
                nodes, forwardNodes, rightIndex, point, receiverNormal);
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
        nodePower = selectLeft ? leftMetrics.y : rightMetrics.y;
        nodeIndex = selectLeft ? leftIndex : rightIndex;
    }
    return primeInvalidLightTreePick();
}

float primeLightTreeSelectionPdf(
        LightNodeBuffer nodes,
        LightNodeForwardBuffer forwardNodes,
        LightNodeReverseBuffer reverseNodes,
        uint root,
        uint leafNode,
        uint expectedLeaf,
        vec3 point,
        vec3 receiverNormal) {
    if (leafNode == PRIME_NO_LIGHT_INDEX) {
        return 0.0;
    }
    uint nodeIndex = leafNode;
    float pdf = 1.0;
    [[dont_unroll]]
    for (uint depth = 0u; depth < PRIME_LIGHT_TREE_MAX_DEPTH; ++depth) {
        vec3 nodeMetrics = primeLoadLightNodeMetrics(
                nodes, forwardNodes, nodeIndex, point, receiverNormal);
        if (!(nodeMetrics.y > 0.0)) {
            return 0.0;
        }
        if (depth == 0u) {
            uint childOrLeaf = forwardNodes.nodes[nodeIndex].childOrLeaf;
            if ((childOrLeaf & PRIME_LIGHT_LEAF_FLAG) == 0u
                    || (childOrLeaf & PRIME_LIGHT_INDEX_MASK) != expectedLeaf) {
                return 0.0;
            }
        }
        if (nodeIndex == root) {
            return pdf;
        }
        uint parent = reverseNodes.nodes[nodeIndex].parent;
        if (parent == PRIME_NO_LIGHT_INDEX) {
            return 0.0;
        }
        // Root is node zero. Every other node was allocated as one member of an odd/even sibling
        // pair, making the exact sibling index implicit and removing it from the reverse stream.
        uint siblingIndex = (nodeIndex & 1u) != 0u ? nodeIndex + 1u : nodeIndex - 1u;
        vec3 siblingMetrics = primeLoadLightNodeMetrics(
                nodes, forwardNodes, siblingIndex, point, receiverNormal);
        float branchProbability = primeLightBranchProbability(nodeMetrics, siblingMetrics);
        if (!(branchProbability >= 0.0)) {
            return 0.0;
        }
        pdf *= branchProbability;
        nodeIndex = parent;
    }
    return 0.0;
}

void primeLightCellVertices(uint geometry, out vec2 first, out vec2 second, out vec2 third) {
    uint column = geometry & 0xfu;
    uint row = (geometry >> 4u) & 0xfu;
    bool upper = (geometry & 0x100u) != 0u;
    float inverseSubdivision = 1.0 / float(PRIME_LIGHT_CELL_SUBDIVISION);
    float x = float(column) * inverseSubdivision;
    float y = float(row) * inverseSubdivision;
    if (upper) {
        first = vec2(x + inverseSubdivision, y);
        second = vec2(x + inverseSubdivision, y + inverseSubdivision);
        third = vec2(x, y + inverseSubdivision);
    } else {
        first = vec2(x, y);
        second = vec2(x + inverseSubdivision, y);
        third = vec2(x, y + inverseSubdivision);
    }
}

uint primeLightCellIndex(vec2 parentBarycentric) {
    vec2 bounded = max(parentBarycentric, vec2(0.0));
    float sum = bounded.x + bounded.y;
    if (sum > 1.0) {
        bounded /= sum;
    }
    vec2 scaled = min(
            bounded * float(PRIME_LIGHT_CELL_SUBDIVISION),
            vec2(float(PRIME_LIGHT_CELL_SUBDIVISION) - 1.0e-5));
    uvec2 coordinate = uvec2(floor(scaled));
    if (coordinate.x + coordinate.y >= PRIME_LIGHT_CELL_SUBDIVISION) {
        if (coordinate.x > 0u) {
            coordinate.x--;
        } else {
            coordinate.y--;
        }
    }
    vec2 local = scaled - vec2(coordinate);
    bool upper = local.x + local.y > 1.0
            && coordinate.x + coordinate.y < PRIME_LIGHT_CELL_SUBDIVISION - 1u;
    uint rowBase = coordinate.y * (2u * PRIME_LIGHT_CELL_SUBDIVISION - coordinate.y);
    return rowBase + 2u * coordinate.x + (upper ? 1u : 0u);
}

vec2 primeEmitterUv(uvec3 packedUvs, vec2 parentBarycentric) {
    vec2 uv0 = primeUnpackUv(packedUvs.x);
    vec2 uv1 = primeUnpackUv(packedUvs.y);
    vec2 uv2 = primeUnpackUv(packedUvs.z);
    return uv0 * (1.0 - parentBarycentric.x - parentBarycentric.y)
            + uv1 * parentBarycentric.x
            + uv2 * parentBarycentric.y;
}

float primeEmitterCosine(
        vec3 emitterNormal,
        uint emitterFlags,
        vec3 directionFromSurfaceToLight) {
    float cosine = dot(emitterNormal, -directionFromSurfaceToLight);
    return (emitterFlags & PRIME_EMITTER_TWO_SIDED) != 0u
            ? abs(cosine)
            : max(cosine, 0.0);
}

float primeAreaSolidAnglePdf(
        vec3 origin,
        vec3 lightPosition,
        float lightCosine,
        float areaPdf) {
    vec3 delta = lightPosition - origin;
    float distanceSquared = dot(delta, delta);
    primeRecordNonFinite(origin);
    primeRecordNonFinite(lightPosition);
    primeRecordNonnegative(distanceSquared);
    primeRecordUnit(lightCosine);
    primeRecordNonnegative(areaPdf);
    return primeAreaSolidAnglePdfValue(areaPdf, distanceSquared, lightCosine);
}

AreaLightSample primeSampleAreaLight(
        vec3 surfacePosition,
        uint packedReceiverNormal,
        vec3 treeSample,
        vec2 positionSample) {
    vec3 receiverNormal = primeUnpackLightReceiverNormal(packedReceiverNormal);
    SectionTable sections = SectionTable(primePush.sectionTableAddress);
    uint64_t worldNodeAddress = sections.sections[0].worldLightAddress;
    uint64_t worldForwardAddress = sections.sections[0].worldLightForwardAddress;
    uint worldNodeCount = sections.sections[0].worldLightNodeCount;
    if (worldNodeAddress == uint64_t(0)
            || worldForwardAddress == uint64_t(0)
            || worldNodeCount == 0u) {
        return primeInvalidAreaLightSample();
    }
    LightNodeBuffer worldNodes = LightNodeBuffer(worldNodeAddress);
    LightNodeForwardBuffer worldForwardNodes = LightNodeForwardBuffer(worldForwardAddress);
    LightTreePick worldPick = primePickLightTree(
            worldNodes,
            worldForwardNodes,
            0u,
            surfacePosition,
            receiverNormal,
            treeSample.x);
    if (worldPick.valid == 0u) {
        return primeInvalidAreaLightSample();
    }
    uint64_t sectionLightAddress = sections.sections[worldPick.leaf].lightAddress;
    if (sectionLightAddress == uint64_t(0)) {
        return primeInvalidAreaLightSample();
    }
    SectionLightHeaderBuffer sectionBuffer = SectionLightHeaderBuffer(sectionLightAddress);
    uint64_t sectionNodeAddress = sectionBuffer.header.nodeAddress;
    uint64_t sectionForwardAddress = sectionBuffer.header.forwardAddress;
    uint sectionRoot = sectionBuffer.header.root;
    uint emitterCount = sectionBuffer.header.emitterCount;
    LightNodeBuffer sectionNodes = LightNodeBuffer(sectionNodeAddress);
    LightNodeForwardBuffer sectionForwardNodes = LightNodeForwardBuffer(sectionForwardAddress);
    vec3 sectionTranslation = sections.sections[worldPick.leaf].translation;
    vec3 localSurfacePosition = surfacePosition - sectionTranslation;
    LightTreePick sectionPick = primePickLightTree(
            sectionNodes,
            sectionForwardNodes,
            sectionRoot,
            localSurfacePosition,
            receiverNormal,
            treeSample.y);
    if (sectionPick.valid == 0u || sectionPick.leaf >= emitterCount) {
        return primeInvalidAreaLightSample();
    }

    LightEmitterBuffer emitters = LightEmitterBuffer(sectionBuffer.header.emitterAddress);
    LightCellBuffer cells = LightCellBuffer(sectionBuffer.header.cellAddress);
    uint emitterCellBase = emitters.emitters[sectionPick.leaf].metadata.x;
    float aliasValue = treeSample.z * float(PRIME_LIGHT_CELL_COUNT);
    // Sobol conversion is strictly below one, so aliasValue is strictly below CELL_COUNT.
    uint column = uint(aliasValue);
    LightCell aliasCell = cells.cells[emitterCellBase + column];
    uint cellIndex = aliasValue - float(column) < aliasCell.aliasProbability
            ? column
            : aliasCell.aliasGeometry & 0xffu;
    LightCell selectedCell = cells.cells[emitterCellBase + cellIndex];
    vec4 emitterCornerArea = emitters.emitters[sectionPick.leaf].cornerArea;
    if (!(selectedCell.probabilityMass > 0.0) || !(emitterCornerArea.w > 0.0)) {
        return primeInvalidAreaLightSample();
    }

    vec2 cellFirst;
    vec2 cellSecond;
    vec2 cellThird;
    primeLightCellVertices(selectedCell.aliasGeometry >> 8u, cellFirst, cellSecond, cellThird);
    float squareRoot = sqrt(positionSample.x);
    vec3 cellBarycentric = vec3(
            1.0 - squareRoot,
            squareRoot * (1.0 - positionSample.y),
            squareRoot * positionSample.y);
    vec2 parentBarycentric = cellFirst * cellBarycentric.x
            + cellSecond * cellBarycentric.y
            + cellThird * cellBarycentric.z;
    vec3 localLightPosition;
    {
        vec3 edgeOne = emitters.emitters[sectionPick.leaf].edgeOneScale.xyz;
        vec3 edgeTwo = emitters.emitters[sectionPick.leaf].edgeTwoPower.xyz;
        localLightPosition = emitterCornerArea.xyz
                + edgeOne * parentBarycentric.x
                + edgeTwo * parentBarycentric.y;
    }
    vec3 lightPosition = localLightPosition + sectionTranslation;
    vec3 toLight = lightPosition - surfacePosition;
    float distanceSquared = dot(toLight, toLight);
    if (!(distanceSquared > 0.0)) {
        return primeInvalidAreaLightSample();
    }
    float distance = sqrt(distanceSquared);
    vec3 direction = toLight / distance;
    float lightCosine = primeEmitterCosine(
            emitters.emitters[sectionPick.leaf].normalPadding.xyz,
            emitters.emitters[sectionPick.leaf].metadata.z,
            direction);
    if (!(lightCosine > 0.0)) {
        return primeInvalidAreaLightSample();
    }
    float cellArea = emitterCornerArea.w / float(PRIME_LIGHT_CELL_COUNT);
    float areaPdf = worldPick.pdf * sectionPick.pdf
            * selectedCell.probabilityMass / cellArea;
    float pdf = primeAreaSolidAnglePdf(
            surfacePosition, lightPosition, lightCosine, areaPdf);
    if (!(pdf > 0.0)) {
        return primeInvalidAreaLightSample();
    }

    AreaLightSample result;
    result.light.direction = direction;
    result.light.distance = max(distance - 0.002, 0.0);
    result.light.radiance = vec3(0.0);
    result.light.pdf = pdf;
    result.light.isDelta = 0u;
    result.sectionIndex = worldPick.leaf;
    result.emitterIndex = sectionPick.leaf;
    result.uv = primeEmitterUv(
            emitters.emitters[sectionPick.leaf].uvsTint.xyz,
            parentBarycentric);
    return result;
}

vec3 primeResolveSampledAreaLightRadiance(AreaLightSample areaSample) {
    if (areaSample.sectionIndex == PRIME_NO_LIGHT_INDEX
            || areaSample.emitterIndex == PRIME_NO_LIGHT_INDEX) {
        return vec3(0.0);
    }
    SectionTable sections = SectionTable(primePush.sectionTableAddress);
    uint64_t sectionLightAddress = sections.sections[areaSample.sectionIndex].lightAddress;
    if (sectionLightAddress == uint64_t(0)) {
        return vec3(0.0);
    }
    SectionLightHeaderBuffer sectionBuffer = SectionLightHeaderBuffer(sectionLightAddress);
    if (areaSample.emitterIndex >= sectionBuffer.header.emitterCount) {
        return vec3(0.0);
    }
    LightEmitterBuffer emitters = LightEmitterBuffer(sectionBuffer.header.emitterAddress);
    return primeEvaluateEmitterRadiance(
            emitters.emitters[areaSample.emitterIndex].uvsTint.w,
            emitters.emitters[areaSample.emitterIndex].metadata.z,
            emitters.emitters[areaSample.emitterIndex].edgeOneScale.w,
            areaSample.uv);
}

LightEvaluation primeEvaluateAreaLight(
        SurfaceInteraction surface,
        vec3 rayOrigin,
        vec3 rayDirection,
        uint packedReceiverNormal,
        bool evaluatePdf) {
    LightEvaluation result;
    result.radiance = vec3(0.0);
    result.pdf = 0.0;
    uint emitterIndex = primeSurfaceEmitterIndex(surface);
    if (emitterIndex == PRIME_NO_LIGHT_INDEX) {
        return result;
    }
    SectionTable sections = SectionTable(primePush.sectionTableAddress);
    uint64_t sectionLightAddress = sections.sections[surface.sectionIndex].lightAddress;
    if (sectionLightAddress == uint64_t(0)) {
        return result;
    }
    SectionLightHeaderBuffer sectionBuffer = SectionLightHeaderBuffer(sectionLightAddress);
    if (emitterIndex >= sectionBuffer.header.emitterCount) {
        return result;
    }
    LightEmitterBuffer emitters = LightEmitterBuffer(sectionBuffer.header.emitterAddress);
    vec4 emitterCornerArea = emitters.emitters[emitterIndex].cornerArea;
    float lightCosine = primeEmitterCosine(
            emitters.emitters[emitterIndex].normalPadding.xyz,
            emitters.emitters[emitterIndex].metadata.z,
            rayDirection);
    if (!(lightCosine > 0.0) || !(emitterCornerArea.w > 0.0)) {
        return result;
    }

    vec3 sectionTranslation = sections.sections[surface.sectionIndex].translation;
    vec3 localPosition = surface.position - sectionTranslation;
    vec3 relative = localPosition - emitterCornerArea.xyz;
    vec3 firstEdge = emitters.emitters[emitterIndex].edgeOneScale.xyz;
    vec3 secondEdge = emitters.emitters[emitterIndex].edgeTwoPower.xyz;
    vec3 edgeCross = cross(firstEdge, secondEdge);
    float denominator = dot(edgeCross, edgeCross);
    vec2 parentBarycentric = vec2(
            dot(cross(relative, secondEdge), edgeCross) / denominator,
            dot(cross(firstEdge, relative), edgeCross) / denominator);
    result.radiance = primeEvaluateEmitterRadiance(
            emitters.emitters[emitterIndex].uvsTint.w,
            emitters.emitters[emitterIndex].metadata.z,
            emitters.emitters[emitterIndex].edgeOneScale.w,
            primeEmitterUv(
                    emitters.emitters[emitterIndex].uvsTint.xyz,
                    parentBarycentric),
            surface.textureLod);
    if (!evaluatePdf) {
        return result;
    }
    vec3 receiverNormal = primeUnpackLightReceiverNormal(packedReceiverNormal);

    uint64_t worldNodeAddress = sections.sections[surface.sectionIndex].worldLightAddress;
    uint64_t worldForwardAddress =
            sections.sections[surface.sectionIndex].worldLightForwardAddress;
    uint worldNodeCount = sections.sections[surface.sectionIndex].worldLightNodeCount;
    uint worldLeafNode = sections.sections[surface.sectionIndex].worldLeafNode;
    if (worldNodeAddress == uint64_t(0)
            || worldForwardAddress == uint64_t(0)
            || worldNodeCount == 0u
            || worldLeafNode == PRIME_NO_LIGHT_INDEX) {
        return result;
    }
    LightCellBuffer cells = LightCellBuffer(sectionBuffer.header.cellAddress);
    uint cellIndex = primeLightCellIndex(parentBarycentric);
    uint emitterCellBase = emitters.emitters[emitterIndex].metadata.x;
    float cellProbabilityMass = cells.cells[emitterCellBase + cellIndex].probabilityMass;
    float cellArea = emitterCornerArea.w / float(PRIME_LIGHT_CELL_COUNT);
    if (!(cellProbabilityMass > 0.0) || !(cellArea > 0.0)) {
        return result;
    }
    float worldPdf;
    {
        LightNodeBuffer worldNodes = LightNodeBuffer(worldNodeAddress);
        LightNodeForwardBuffer worldForwardNodes =
                LightNodeForwardBuffer(worldForwardAddress);
        uint64_t worldReverseAddress = worldForwardAddress
                + uint64_t(worldNodeCount) * uint64_t(PRIME_LIGHT_NODE_FORWARD_SIZE);
        LightNodeReverseBuffer worldReverseNodes =
                LightNodeReverseBuffer(worldReverseAddress);
        worldPdf = primeLightTreeSelectionPdf(
                worldNodes,
                worldForwardNodes,
                worldReverseNodes,
                0u,
                worldLeafNode,
                surface.sectionIndex,
                rayOrigin,
                receiverNormal);
    }
    float sectionPdf;
    {
        LightNodeBuffer sectionNodes = LightNodeBuffer(sectionBuffer.header.nodeAddress);
        LightNodeForwardBuffer sectionForwardNodes =
                LightNodeForwardBuffer(sectionBuffer.header.forwardAddress);
        LightNodeReverseBuffer sectionReverseNodes =
                LightNodeReverseBuffer(sectionBuffer.header.reverseAddress);
        sectionPdf = primeLightTreeSelectionPdf(
                sectionNodes,
                sectionForwardNodes,
                sectionReverseNodes,
                sectionBuffer.header.root,
                emitters.emitters[emitterIndex].metadata.y,
                emitterIndex,
                rayOrigin - sectionTranslation,
                receiverNormal);
    }
    if (!(worldPdf > 0.0) || !(sectionPdf > 0.0)) {
        return result;
    }
    float areaPdf = worldPdf * sectionPdf * cellProbabilityMass / cellArea;
    result.pdf = primeAreaSolidAnglePdf(
            rayOrigin, surface.position, lightCosine, areaPdf);
    return result;
}

#endif
