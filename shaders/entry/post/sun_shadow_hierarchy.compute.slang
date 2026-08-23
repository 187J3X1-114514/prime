#language slang 2026
module "entry/post/sun_shadow_hierarchy.compute.slang";

import "phase/post/sun_shadow_hierarchy.slang";

[numthreads(8, 8, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    primePhase(dispatchThreadId);
}

