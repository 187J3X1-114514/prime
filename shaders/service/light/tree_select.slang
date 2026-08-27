#language slang 2026
module "service/light/tree_select.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "contract/light_sample.slang";
import "math/light_tree.slang";
import "math/light_receiver.slang";
import "service/light/emission.slang";
import "service/light/tree_common.slang";

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

LightTreePick primePickLightTree(
        LightNode* nodes,
        LightLeaf* leaves,
        LightLeafEntry* entries,
        SectionRecord* sections,
        uint64_t emitterAddress,
        uint entryLimit,
        uint leafMode,
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
            if (leaf.entryCount == 0u
                    || leaf.entryCount > PRIME_LIGHT_LEAF_MAX_ENTRIES) {
                return primeInvalidLightTreePick();
            }
            float localSeed = clamp(
                    (seed - lowerBound) / pdf,
                    0.0,
                    asfloat(0x3f7fffffu));
            if (leaf.entryCount == 1u) {
                LightLeafEntry entry = entries[leaf.firstEntry];
                if (entry.index >= entryLimit) return primeInvalidLightTreePick();
                LightTreePick result;
                result.index = entry.index;
                result.pdf = pdf;
                result.remapped = localSeed;
                result.valid = 1u;
                return result;
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
            if (!(totalWeight > 0.0)) return primeInvalidLightTreePick();
            float targetWeight = localSeed * totalWeight;
            float lowerWeight = 0.0;
            [[unroll]]
            for (uint offset = 0u; offset < PRIME_LIGHT_LEAF_MAX_ENTRIES; ++offset) {
                if (offset >= leaf.entryCount) break;
                LightLeafEntry entry = entries[leaf.firstEntry + offset];
                float entryWeight = entryWeights[offset];
                float upperWeight = lowerWeight + entryWeight;
                bool selected = targetWeight < upperWeight || offset == lastPositive;
                if (selected) {
                    if (!(entryWeight > 0.0)) {
                        return primeInvalidLightTreePick();
                    }
                    LightTreePick result;
                    result.index = entry.index;
                    result.pdf = pdf * (entryWeight / totalWeight);
                    result.remapped = clamp(
                            (targetWeight - lowerWeight) / entryWeight,
                            0.0,
                            asfloat(0x3f7fffffu));
                    result.valid = 1u;
                    return result;
                }
                lowerWeight = upperWeight;
            }
            return primeInvalidLightTreePick();
        }
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

public AreaLightSelection primeInvalidAreaLightSelection() {
    AreaLightSelection result;
    result.sectionIndex = PRIME_NO_LIGHT_INDEX;
    result.emitterIndex = PRIME_NO_LIGHT_INDEX;
    result.pdf = 0.0;
    return result;
}

public AreaLightSelection primeSelectAreaLight(
        float3 surfacePosition,
        uint packedReceiverNormal,
        float treeSample) {
    float3 receiverNormal = primeUnpackLightReceiverNormal(packedReceiverNormal);
    SectionRecord* sections = (SectionRecord*)primePush.sectionTableAddress;
    uint64_t worldNodeAddress = sections[0].worldLightAddress;
    uint64_t worldLeafAddress = sections[0].worldLightLeafAddress;
    uint worldLeafCount = sections[0].worldLightLeafCount;
    if (worldNodeAddress == uint64_t(0)
            || worldLeafAddress == uint64_t(0)
            || worldLeafCount == 0u) {
        return primeInvalidAreaLightSelection();
    }
    LightNode* worldNodes = primeWorldLightNodes(worldNodeAddress);
    LightLeaf* worldLeaves = (LightLeaf*)worldLeafAddress;
    LightLeafEntry* worldEntries = (LightLeafEntry*)(
            worldLeafAddress + uint64_t(worldLeafCount) * uint64_t(PRIME_LIGHT_LEAF_SIZE));
    LightTreePick worldPick = primePickLightTree(
            worldNodes,
            worldLeaves,
            worldEntries,
            sections,
            uint64_t(0),
            PRIME_NO_LIGHT_INDEX,
            PRIME_LIGHT_LEAF_WORLD,
            0u,
            surfacePosition,
            receiverNormal,
            treeSample);
    if (worldPick.valid == 0u) {
        return primeInvalidAreaLightSelection();
    }
    uint64_t sectionLightAddress = sections[worldPick.index].lightAddress;
    if (sectionLightAddress == uint64_t(0)) {
        return primeInvalidAreaLightSelection();
    }
    SectionLightHeader* sectionBuffer = (SectionLightHeader*)sectionLightAddress;
    LightTreePick sectionPick = primePickLightTree(
            (LightNode*)sectionBuffer[0].nodeAddress,
            (LightLeaf*)sectionBuffer[0].leafAddress,
            (LightLeafEntry*)sectionBuffer[0].entryAddress,
            sections,
            sectionBuffer[0].emitterAddress,
            sectionBuffer[0].emitterCount,
            PRIME_LIGHT_LEAF_EMITTER,
            sectionBuffer[0].root,
            surfacePosition - sections[worldPick.index].translation,
            receiverNormal,
            worldPick.remapped);
    if (sectionPick.valid == 0u
            || sectionPick.index >= sectionBuffer[0].emitterCount) {
        return primeInvalidAreaLightSelection();
    }
    AreaLightSelection result;
    result.sectionIndex = worldPick.index;
    result.emitterIndex = sectionPick.index;
    result.pdf = worldPick.pdf * sectionPick.pdf;
    return result;
}
