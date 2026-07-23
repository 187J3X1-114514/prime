#ifndef PRIME_LIGHTS_GLSL
#define PRIME_LIGHTS_GLSL

#include "material.glsl"

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

float primePowerHeuristic(float firstPdf, float secondPdf) {
    primeRecordNonnegative(firstPdf);
    primeRecordNonnegative(secondPdf);
    if (firstPdf <= 0.0) {
        return 0.0;
    }
    if (secondPdf <= 0.0) {
        return 1.0;
    }
    if (firstPdf >= secondPdf) {
        float ratio = secondPdf / firstPdf;
        return 1.0 / (1.0 + ratio * ratio);
    }
    float ratio = firstPdf / secondPdf;
    float ratioSquared = ratio * ratio;
    return ratioSquared / (1.0 + ratioSquared);
}

float primePowerHeuristicOverPdf(float sampledPdf, float otherPdf) {
    primeRecordNonnegative(sampledPdf);
    primeRecordNonnegative(otherPdf);
    if (sampledPdf <= 0.0) {
        return 0.0;
    }
    if (otherPdf <= 0.0) {
        float result = 1.0 / sampledPdf;
        primeRecordNonnegative(result);
        return result;
    }
    if (sampledPdf >= otherPdf) {
        float ratio = otherPdf / sampledPdf;
        float result = (1.0 / sampledPdf) / (1.0 + ratio * ratio);
        primeRecordNonnegative(result);
        return result;
    }
    float ratio = sampledPdf / otherPdf;
    float result = (ratio / otherPdf) / (1.0 + ratio * ratio);
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

LightEvaluation primeEvaluateStarmap(
        IntegratorRecord integrator,
        vec3 surfacePosition,
        vec3 direction) {
    LightEvaluation result;
    result.radiance = primeStarmapRadiance(
            integrator, surfacePosition, direction);
    result.pdf = primeStarmapPdf(integrator, direction);
    return result;
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
    return dot(normalize(direction), normalize(integrator.sunDirectionIntensity.xyz))
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
            normalize(integrator.sunDirectionIntensity.xyz));
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

float primeLightNodeDistanceSquared(LightNode node, vec3 point) {
    vec3 closest = clamp(point, node.boundsMinPower.xyz, node.boundsMaxSoftening.xyz);
    vec3 delta = point - closest;
    return dot(delta, delta) + node.boundsMaxSoftening.w;
}

float primeLightBranchProbability(LightNode first, LightNode second, vec3 point) {
    float firstDistanceSquared = primeLightNodeDistanceSquared(first, point);
    float secondDistanceSquared = primeLightNodeDistanceSquared(second, point);
    // This is normalized power/distance^2 with the common division removed. Forward and reverse
    // traversal both read these exact f32 node records, which is required for valid MIS.
    float firstPower = max(first.boundsMinPower.w, 0.0);
    float secondPower = max(second.boundsMinPower.w, 0.0);
    float powerScale = max(firstPower, secondPower);
    if (powerScale == 0.0) return -1.0;
    float firstPowerRatio = firstPower / powerScale;
    float secondPowerRatio = secondPower / powerScale;
    float distanceScale = max(firstDistanceSquared, secondDistanceSquared);
    if (distanceScale == 0.0) {
        return firstPowerRatio / (firstPowerRatio + secondPowerRatio);
    }
    float firstScore = firstPowerRatio * (secondDistanceSquared / distanceScale);
    float secondScore = secondPowerRatio * (firstDistanceSquared / distanceScale);
    float sum = firstScore + secondScore;
    return sum > 0.0 ? firstScore / sum : -1.0;
}

LightTreePick primePickLightTree(
        LightNodeBuffer nodes,
        LightNodeForwardBuffer forwardNodes,
        uint root,
        vec3 point,
        float seed) {
    // Bounds/power and forward metadata are the only hot streams. Parent information is kept in
    // the reverse-only stream, so ordinary light sampling cannot pull reverse-MIS metadata into
    // its cache working set.
    uint nodeIndex = root;
    LightNode node = nodes.nodes[root];
    float lowerBound = 0.0;
    float pdf = 1.0;
    for (uint depth = 0u; depth < PRIME_LIGHT_TREE_MAX_DEPTH; ++depth) {
        if (!(node.boundsMinPower.w > 0.0)) {
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
        LightNode left = nodes.nodes[leftIndex];
        LightNode right = nodes.nodes[rightIndex];
        float leftProbability = primeLightBranchProbability(left, right, point);
        if (!(leftProbability >= 0.0)) {
            return primeInvalidLightTreePick();
        }
        float rightProbability = 1.0 - leftProbability;
        // Keep the original sample in its cumulative interval. This is the same inverse-CDF
        // traversal as repeatedly remapping value to [0, 1), but replaces one division per tree
        // level with a multiply-add. pdf is also the current interval width.
        float split = lowerBound + pdf * leftProbability;
        if (seed < split || rightProbability <= 0.0) {
            if (leftProbability <= 0.0) {
                return primeInvalidLightTreePick();
            }
            pdf *= leftProbability;
            node = left;
            nodeIndex = leftIndex;
        } else {
            pdf *= rightProbability;
            lowerBound = split;
            node = right;
            nodeIndex = rightIndex;
        }
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
        vec3 point) {
    if (leafNode == PRIME_NO_LIGHT_INDEX) {
        return 0.0;
    }
    uint nodeIndex = leafNode;
    float pdf = 1.0;
    for (uint depth = 0u; depth < PRIME_LIGHT_TREE_MAX_DEPTH; ++depth) {
        LightNode node = nodes.nodes[nodeIndex];
        if (!(node.boundsMinPower.w > 0.0)) {
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
        LightNode sibling = nodes.nodes[siblingIndex];
        float branchProbability = primeLightBranchProbability(node, sibling, point);
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

vec2 primeEmitterUv(LightEmitter emitter, vec2 parentBarycentric) {
    vec2 uv0 = primeUnpackHalf2(emitter.uvsTint.x);
    vec2 uv1 = primeUnpackHalf2(emitter.uvsTint.y);
    vec2 uv2 = primeUnpackHalf2(emitter.uvsTint.z);
    return uv0 * (1.0 - parentBarycentric.x - parentBarycentric.y)
            + uv1 * parentBarycentric.x
            + uv2 * parentBarycentric.y;
}

float primeEmitterCosine(LightEmitter emitter, vec3 directionFromSurfaceToLight) {
    float cosine = dot(emitter.normalPadding.xyz, -directionFromSurfaceToLight);
    return (emitter.metadata.z & PRIME_EMITTER_TWO_SIDED) != 0u
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
    float cosine = lightCosine;
    if (distanceSquared <= 0.0 || cosine <= 0.0 || areaPdf <= 0.0) {
        return 0.0;
    }
    return primeProductOver(areaPdf, distanceSquared, cosine);
}

AreaLightSample primeSampleAreaLight(
        vec3 surfacePosition,
        vec3 treeSample,
        vec2 positionSample) {
    SectionTable sections = SectionTable(primePush.sectionTableAddress);
    SectionRecord firstSection = sections.sections[0];
    if (firstSection.worldLightAddress == uint64_t(0)
            || firstSection.worldLightForwardAddress == uint64_t(0)
            || firstSection.worldLightNodeCount == 0u) {
        return primeInvalidAreaLightSample();
    }
    LightNodeBuffer worldNodes = LightNodeBuffer(firstSection.worldLightAddress);
    LightNodeForwardBuffer worldForwardNodes = LightNodeForwardBuffer(
            firstSection.worldLightForwardAddress);
    LightTreePick worldPick = primePickLightTree(
            worldNodes, worldForwardNodes, 0u, surfacePosition, treeSample.x);
    if (worldPick.valid == 0u) {
        return primeInvalidAreaLightSample();
    }
    SectionRecord section = sections.sections[worldPick.leaf];
    if (section.lightAddress == uint64_t(0)) {
        return primeInvalidAreaLightSample();
    }
    SectionLightHeaderBuffer sectionBuffer = SectionLightHeaderBuffer(section.lightAddress);
    SectionLightHeader header = sectionBuffer.header;
    LightNodeBuffer sectionNodes = LightNodeBuffer(header.nodeAddress);
    LightNodeForwardBuffer sectionForwardNodes = LightNodeForwardBuffer(header.forwardAddress);
    vec3 localSurfacePosition = surfacePosition - section.translation;
    LightTreePick sectionPick = primePickLightTree(
            sectionNodes, sectionForwardNodes, header.root, localSurfacePosition, treeSample.y);
    if (sectionPick.valid == 0u || sectionPick.leaf >= header.emitterCount) {
        return primeInvalidAreaLightSample();
    }

    LightEmitterBuffer emitters = LightEmitterBuffer(header.emitterAddress);
    LightCellBuffer cells = LightCellBuffer(header.cellAddress);
    LightEmitter emitter = emitters.emitters[sectionPick.leaf];
    float aliasValue = treeSample.z * float(PRIME_LIGHT_CELL_COUNT);
    // Sobol conversion is strictly below one, so aliasValue is strictly below CELL_COUNT.
    uint column = uint(aliasValue);
    LightCell aliasCell = cells.cells[emitter.metadata.x + column];
    uint cellIndex = aliasValue - float(column) < aliasCell.aliasProbability
            ? column
            : aliasCell.aliasGeometry & 0xffu;
    LightCell selectedCell = cells.cells[emitter.metadata.x + cellIndex];
    if (!(selectedCell.probabilityMass > 0.0) || !(emitter.cornerArea.w > 0.0)) {
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
    vec3 localLightPosition = emitter.cornerArea.xyz
            + emitter.edgeOneScale.xyz * parentBarycentric.x
            + emitter.edgeTwoPower.xyz * parentBarycentric.y;
    vec3 lightPosition = localLightPosition + section.translation;
    vec3 toLight = lightPosition - surfacePosition;
    float distanceSquared = dot(toLight, toLight);
    if (!(distanceSquared > 0.0)) {
        return primeInvalidAreaLightSample();
    }
    float distance = sqrt(distanceSquared);
    vec3 direction = toLight / distance;
    float lightCosine = primeEmitterCosine(emitter, direction);
    if (!(lightCosine > 0.0)) {
        return primeInvalidAreaLightSample();
    }
    float cellArea = emitter.cornerArea.w / float(PRIME_LIGHT_CELL_COUNT);
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
    result.uv = primeEmitterUv(emitter, parentBarycentric);
    return result;
}

vec3 primeResolveSampledAreaLightRadiance(AreaLightSample areaSample) {
    if (areaSample.sectionIndex == PRIME_NO_LIGHT_INDEX
            || areaSample.emitterIndex == PRIME_NO_LIGHT_INDEX) {
        return vec3(0.0);
    }
    SectionTable sections = SectionTable(primePush.sectionTableAddress);
    SectionRecord section = sections.sections[areaSample.sectionIndex];
    if (section.lightAddress == uint64_t(0)) {
        return vec3(0.0);
    }
    SectionLightHeaderBuffer sectionBuffer = SectionLightHeaderBuffer(section.lightAddress);
    SectionLightHeader header = sectionBuffer.header;
    if (areaSample.emitterIndex >= header.emitterCount) {
        return vec3(0.0);
    }
    LightEmitterBuffer emitters = LightEmitterBuffer(header.emitterAddress);
    return primeEvaluateEmitterRadiance(
            emitters.emitters[areaSample.emitterIndex], areaSample.uv);
}

LightEvaluation primeEvaluateAreaLight(
        SurfaceInteraction surface,
        vec3 rayOrigin,
        vec3 rayDirection) {
    LightEvaluation result;
    result.radiance = vec3(0.0);
    result.pdf = 0.0;
    if (surface.emitterIndex == PRIME_NO_LIGHT_INDEX) {
        return result;
    }
    SectionTable sections = SectionTable(primePush.sectionTableAddress);
    SectionRecord section = sections.sections[surface.sectionIndex];
    if (section.lightAddress == uint64_t(0)
            || section.worldLightAddress == uint64_t(0)
            || section.worldLightForwardAddress == uint64_t(0)
            || section.worldLightNodeCount == 0u
            || section.worldLeafNode == PRIME_NO_LIGHT_INDEX) {
        return result;
    }
    SectionLightHeaderBuffer sectionBuffer = SectionLightHeaderBuffer(section.lightAddress);
    SectionLightHeader header = sectionBuffer.header;
    if (surface.emitterIndex >= header.emitterCount) {
        return result;
    }
    LightEmitterBuffer emitters = LightEmitterBuffer(header.emitterAddress);
    LightCellBuffer cells = LightCellBuffer(header.cellAddress);
    LightEmitter emitter = emitters.emitters[surface.emitterIndex];
    float lightCosine = primeEmitterCosine(emitter, rayDirection);
    if (!(lightCosine > 0.0) || !(emitter.cornerArea.w > 0.0)) {
        return result;
    }

    vec3 localPosition = surface.position - section.translation;
    vec3 relative = localPosition - emitter.cornerArea.xyz;
    vec3 firstEdge = emitter.edgeOneScale.xyz;
    vec3 secondEdge = emitter.edgeTwoPower.xyz;
    vec3 edgeCross = cross(firstEdge, secondEdge);
    float denominator = dot(edgeCross, edgeCross);
    vec2 parentBarycentric = vec2(
            dot(cross(relative, secondEdge), edgeCross) / denominator,
            dot(cross(firstEdge, relative), edgeCross) / denominator);
    uint cellIndex = primeLightCellIndex(parentBarycentric);
    LightCell cell = cells.cells[emitter.metadata.x + cellIndex];
    float cellArea = emitter.cornerArea.w / float(PRIME_LIGHT_CELL_COUNT);
    if (!(cell.probabilityMass > 0.0) || !(cellArea > 0.0)) {
        return result;
    }

    LightNodeBuffer worldNodes = LightNodeBuffer(section.worldLightAddress);
    LightNodeForwardBuffer worldForwardNodes = LightNodeForwardBuffer(
            section.worldLightForwardAddress);
    uint64_t worldReverseAddress = section.worldLightForwardAddress
            + uint64_t(section.worldLightNodeCount) * uint64_t(PRIME_LIGHT_NODE_FORWARD_SIZE);
    LightNodeReverseBuffer worldReverseNodes = LightNodeReverseBuffer(worldReverseAddress);
    float worldPdf = primeLightTreeSelectionPdf(
            worldNodes,
            worldForwardNodes,
            worldReverseNodes,
            0u,
            section.worldLeafNode,
            surface.sectionIndex,
            rayOrigin);
    LightNodeBuffer sectionNodes = LightNodeBuffer(header.nodeAddress);
    LightNodeForwardBuffer sectionForwardNodes = LightNodeForwardBuffer(header.forwardAddress);
    LightNodeReverseBuffer sectionReverseNodes = LightNodeReverseBuffer(header.reverseAddress);
    float sectionPdf = primeLightTreeSelectionPdf(
            sectionNodes,
            sectionForwardNodes,
            sectionReverseNodes,
            header.root,
            emitter.metadata.y,
            surface.emitterIndex,
            rayOrigin - section.translation);
    vec3 emitterRadiance = primeEvaluateEmitterRadiance(
            emitter,
            primeEmitterUv(emitter, parentBarycentric),
            uintBitsToFloat(surface.textureLod));
    if (!(worldPdf > 0.0) || !(sectionPdf > 0.0)) {
        result.radiance = emitterRadiance;
        return result;
    }
    float areaPdf = worldPdf * sectionPdf * cell.probabilityMass / cellArea;
    result.radiance = emitterRadiance;
    result.pdf = primeAreaSolidAnglePdf(
            rayOrigin, surface.position, lightCosine, areaPdf);
    return result;
}

#endif
