#language slang 2026
module "service/light/tree_common.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "math/light_tree.slang";
import "service/light/emission.slang";

public static const uint PRIME_LIGHT_LEAF_FLAG = 0x80000000u;
public static const uint PRIME_LIGHT_INDEX_MASK = 0x7fffffffu;
public static const uint PRIME_LIGHT_TREE_MAX_DEPTH = 27u;
public static const uint PRIME_LIGHT_LEAF_MAX_ENTRIES = 8u;

public LightNode* primeWorldLightNodes(uint64_t worldLightAddress) {
    return (LightNode*)worldLightAddress;
}

public void primeUnpackLightNode(
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

public float2 primeLoadLightNodeMetrics(
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

public float2 primeEmitterMetrics(
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
        // Valid emissive triangles have nonzero extent; this preserves the exact centroid case.
        float3 edgeOne = second - first;
        float3 edgeTwo = third - first;
        targetDistanceSquared = dot(edgeOne, edgeOne) + dot(edgeTwo, edgeTwo);
    }
    return float2(targetDistanceSquared, weightedPower);
}

public float2 primeLoadEmitterMetrics(
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
