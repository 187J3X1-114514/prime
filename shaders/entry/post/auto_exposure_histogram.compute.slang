#language slang 2026
module "entry/post/auto_exposure_histogram.compute.slang";

import "phase/post/auto_exposure_histogram.slang";

[numthreads(16, 16, 1)]
void main(uint3 groupId : SV_GroupID,
        uint3 groupThreadId : SV_GroupThreadID,
        uint groupIndex : SV_GroupIndex)
{
    primePhase(groupId, groupThreadId, groupIndex);
}

