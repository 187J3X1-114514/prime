#language slang 2026
module "entry/post/noisy_composite.compute.slang";

import "phase/post/noisy_composite.slang";

[numthreads(8, 8, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID, uint groupIndex : SV_GroupIndex)
{
    primePhase(dispatchThreadId, groupIndex);
}

