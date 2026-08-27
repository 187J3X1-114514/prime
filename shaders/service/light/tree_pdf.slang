#language slang 2026
module "service/light/tree_pdf.slang";

import "prime_abi_types.slang";
import "math/light_tree.slang";
import "service/light/emission.slang";
import "service/light/tree_common.slang";

static const uint PRIME_LIGHT_PATH_DEPTH_SHIFT = 27u;
static const uint PRIME_LIGHT_PATH_TRAIL_MASK = 0x07ffffffu;

public float primeLightTreeSelectionPdf(
        LightNode* nodes,
        LightLeaf* leaves,
        LightLeafEntry* entries,
        SectionRecord* sections,
        uint64_t emitterAddress,
        uint entryLimit,
        uint leafMode,
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
    if (leaf.entryCount == 0u
            || leaf.entryCount > PRIME_LIGHT_LEAF_MAX_ENTRIES) {
        return 0.0;
    }
    if (leaf.entryCount == 1u) {
        LightLeafEntry entry = entries[leaf.firstEntry];
        return entry.index == expectedIndex && entry.index < entryLimit ? pdf : 0.0;
    }
    float entryWeights[PRIME_LIGHT_LEAF_MAX_ENTRIES];
    uint lastPositive;
    float totalWeight = primeLoadLightLeafTargetWeights(
            leaf,
            entries,
            sections,
            emitterAddress,
            entryLimit,
            leafMode,
            point,
            receiverNormal,
            entryWeights,
            lastPositive);
    if (!(totalWeight > 0.0)) return 0.0;
    [[unroll]]
    for (uint offset = 0u; offset < PRIME_LIGHT_LEAF_MAX_ENTRIES; ++offset) {
        if (offset >= leaf.entryCount) break;
        LightLeafEntry entry = entries[leaf.firstEntry + offset];
        if (entry.index == expectedIndex) {
            float entryWeight = entryWeights[offset];
            return entryWeight > 0.0 ? pdf * entryWeight / totalWeight : 0.0;
        }
    }
    return 0.0;
}
