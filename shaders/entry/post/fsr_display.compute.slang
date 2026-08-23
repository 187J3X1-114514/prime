#language slang 2026
module "entry/post/fsr_display.compute.slang";

import "phase/post/fsr_display.slang";

[numthreads(8, 8, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    primePhase(dispatchThreadId);
}

