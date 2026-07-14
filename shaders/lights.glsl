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

struct LightEvaluation {
    vec3 radiance;
    float pdf;
};

// All radiance values in this adapter are linear Rec.2020 D65. pdf is the complete f32 sampling
// density, including light-selection probability when a future registry introduces one. Reverse
// PDF queries must reuse that exact quantized value. The environment is evaluated only when a BSDF
// path escapes; the sun and area-light adapters are sampled explicitly and perform no selection.

float primePowerHeuristic(float firstPdf, float secondPdf) {
    float first = firstPdf * firstPdf;
    float second = secondPdf * secondPdf;
    return first / max(first + second, 1.0e-30);
}

vec3 primeEnvironmentRadiance(IntegratorRecord integrator, vec3 direction) {
    return primeAtmosphereSky(direction, integrator.sunDirectionIntensity.xyz);
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
    return 1.0 / max(primeSunSolidAngle(), 1.0e-12);
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
    float sine = sqrt(max(1.0 - cosine * cosine, 0.0));
    float azimuth = 2.0 * PRIME_PI * sampleValue.y;
    vec3 localDirection = vec3(sine * cos(azimuth), sine * sin(azimuth), cosine);
    LightSample result;
    result.direction = primeLocalToWorld(
            localDirection,
            normalize(integrator.sunDirectionIntensity.xyz));
    result.distance = 1000000.0;
    result.radiance = primeSunRadiance(integrator, surfacePosition, result.direction);
    result.pdf = primeSunPdf();
    result.isDelta = 0u;
    return result;
}

const uint PRIME_NO_LIGHT_INDEX = 0xffffffffu;
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

LightSample primeInvalidAreaLightSample() {
    LightSample result;
    result.direction = vec3(0.0, 1.0, 0.0);
    result.distance = 0.0;
    result.radiance = vec3(0.0);
    result.pdf = 0.0;
    result.isDelta = 0u;
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
    float firstScore = max(first.boundsMinPower.w, 0.0) * secondDistanceSquared;
    float secondScore = max(second.boundsMinPower.w, 0.0) * firstDistanceSquared;
    float sum = firstScore + secondScore;
    return sum > 0.0 ? firstScore / sum : -1.0;
}

LightTreePick primePickLightTree(
        LightNodeBuffer nodes,
        uint root,
        vec3 point,
        float seed) {
    uint nodeIndex = root;
    float pdf = 1.0;
    float value = seed;
    for (uint depth = 0u; depth < PRIME_LIGHT_TREE_MAX_DEPTH; ++depth) {
        LightNode node = nodes.nodes[nodeIndex];
        if (!(node.boundsMinPower.w > 0.0)) {
            return primeInvalidLightTreePick();
        }
        if (node.links.y == PRIME_NO_LIGHT_INDEX) {
            LightTreePick result;
            result.leaf = node.links.x;
            result.pdf = pdf;
            result.valid = 1u;
            return result;
        }
        LightNode left = nodes.nodes[node.links.x];
        LightNode right = nodes.nodes[node.links.y];
        float leftProbability = primeLightBranchProbability(left, right, point);
        if (!(leftProbability >= 0.0)) {
            return primeInvalidLightTreePick();
        }
        float rightProbability = 1.0 - leftProbability;
        if (value < leftProbability || rightProbability <= 0.0) {
            if (leftProbability <= 0.0) {
                return primeInvalidLightTreePick();
            }
            pdf *= leftProbability;
            value /= leftProbability;
            nodeIndex = node.links.x;
        } else {
            pdf *= rightProbability;
            value = (value - leftProbability) / rightProbability;
            nodeIndex = node.links.y;
        }
    }
    return primeInvalidLightTreePick();
}

float primeLightTreeSelectionPdf(
        LightNodeBuffer nodes,
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
        if (depth == 0u
                && (node.links.y != PRIME_NO_LIGHT_INDEX || node.links.x != expectedLeaf)) {
            return 0.0;
        }
        if (nodeIndex == root) {
            return pdf;
        }
        if (node.links.z == PRIME_NO_LIGHT_INDEX || node.links.w == PRIME_NO_LIGHT_INDEX) {
            return 0.0;
        }
        LightNode sibling = nodes.nodes[node.links.w];
        float branchProbability = primeLightBranchProbability(node, sibling, point);
        if (!(branchProbability >= 0.0)) {
            return 0.0;
        }
        pdf *= branchProbability;
        nodeIndex = node.links.z;
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
    if (distanceSquared <= 0.0 || lightCosine <= 0.0 || areaPdf <= 0.0) {
        return 0.0;
    }
    return areaPdf * distanceSquared / lightCosine;
}

LightSample primeSampleAreaLight(
        vec3 surfacePosition,
        vec3 treeSample,
        vec2 positionSample) {
    SectionTable sections = SectionTable(primePush.sectionTableAddress);
    SectionRecord firstSection = sections.sections[0];
    if (firstSection.worldLightAddress == uint64_t(0)) {
        return primeInvalidAreaLightSample();
    }
    LightNodeBuffer worldNodes = LightNodeBuffer(firstSection.worldLightAddress);
    LightTreePick worldPick = primePickLightTree(
            worldNodes, 0u, surfacePosition, treeSample.x);
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
    vec3 localSurfacePosition = surfacePosition - section.translation;
    LightTreePick sectionPick = primePickLightTree(
            sectionNodes, header.root, localSurfacePosition, treeSample.y);
    if (sectionPick.valid == 0u || sectionPick.leaf >= header.emitterCount) {
        return primeInvalidAreaLightSample();
    }

    LightEmitterBuffer emitters = LightEmitterBuffer(header.emitterAddress);
    LightCellBuffer cells = LightCellBuffer(header.cellAddress);
    LightEmitter emitter = emitters.emitters[sectionPick.leaf];
    float aliasValue = treeSample.z * float(PRIME_LIGHT_CELL_COUNT);
    uint column = min(uint(aliasValue), PRIME_LIGHT_CELL_COUNT - 1u);
    LightCell aliasCell = cells.cells[emitter.metadata.x + column];
    uint cellIndex = fract(aliasValue) < aliasCell.aliasProbability
            ? column
            : aliasCell.aliasIndex;
    LightCell selectedCell = cells.cells[emitter.metadata.x + cellIndex];
    if (!(selectedCell.probabilityMass > 0.0) || !(emitter.cornerArea.w > 0.0)) {
        return primeInvalidAreaLightSample();
    }

    vec2 cellFirst;
    vec2 cellSecond;
    vec2 cellThird;
    primeLightCellVertices(selectedCell.geometry, cellFirst, cellSecond, cellThird);
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
    float cellArea = emitter.cornerArea.w / float(PRIME_LIGHT_CELL_COUNT);
    float areaPdf = worldPick.pdf * sectionPick.pdf
            * selectedCell.probabilityMass / cellArea;
    float pdf = primeAreaSolidAnglePdf(
            surfacePosition, lightPosition, lightCosine, areaPdf);
    if (!(pdf > 0.0)) {
        return primeInvalidAreaLightSample();
    }

    LightSample result;
    result.direction = direction;
    result.distance = max(distance - 0.002, 0.0);
    result.radiance = primeEvaluateEmitterRadiance(
            emitter, primeEmitterUv(emitter, parentBarycentric));
    result.pdf = pdf;
    result.isDelta = 0u;
    return result;
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
    float firstDot = dot(firstEdge, firstEdge);
    float crossDot = dot(firstEdge, secondEdge);
    float secondDot = dot(secondEdge, secondEdge);
    float relativeFirst = dot(relative, firstEdge);
    float relativeSecond = dot(relative, secondEdge);
    float denominator = firstDot * secondDot - crossDot * crossDot;
    if (!(abs(denominator) > 1.0e-12)) {
        return result;
    }
    vec2 parentBarycentric = vec2(
            (secondDot * relativeFirst - crossDot * relativeSecond) / denominator,
            (firstDot * relativeSecond - crossDot * relativeFirst) / denominator);
    uint cellIndex = primeLightCellIndex(parentBarycentric);
    LightCell cell = cells.cells[emitter.metadata.x + cellIndex];
    float cellArea = emitter.cornerArea.w / float(PRIME_LIGHT_CELL_COUNT);
    if (!(cell.probabilityMass > 0.0) || !(cellArea > 0.0)) {
        return result;
    }

    LightNodeBuffer worldNodes = LightNodeBuffer(section.worldLightAddress);
    float worldPdf = primeLightTreeSelectionPdf(
            worldNodes,
            0u,
            section.worldLeafNode,
            surface.sectionIndex,
            rayOrigin);
    LightNodeBuffer sectionNodes = LightNodeBuffer(header.nodeAddress);
    float sectionPdf = primeLightTreeSelectionPdf(
            sectionNodes,
            header.root,
            emitter.metadata.y,
            surface.emitterIndex,
            rayOrigin - section.translation);
    if (!(worldPdf > 0.0) || !(sectionPdf > 0.0)) {
        result.radiance = primeEvaluateEmitterRadiance(
                emitter, primeEmitterUv(emitter, parentBarycentric));
        return result;
    }
    float areaPdf = worldPdf * sectionPdf * cell.probabilityMass / cellArea;
    result.radiance = primeEvaluateEmitterRadiance(
            emitter, primeEmitterUv(emitter, parentBarycentric));
    result.pdf = primeAreaSolidAnglePdf(
            rayOrigin, surface.position, lightCosine, areaPdf);
    return result;
}

#endif
