#language slang 2026
module "entry/post/nrd_motion.compute.slang";

import "phase/post/nrd_motion.slang";

[numthreads(8, 8, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID, uint groupIndex : SV_GroupIndex)
{
    primePhase(dispatchThreadId, groupIndex);
}

