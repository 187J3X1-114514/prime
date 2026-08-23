#language slang 2026
module "entry/post/rr_prepare.compute.slang";

import "phase/post/rr_prepare.slang";

[numthreads(8, 8, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID, uint groupIndex : SV_GroupIndex)
{
    primePhase(dispatchThreadId, groupIndex);
}

