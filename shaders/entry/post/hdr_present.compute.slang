#language slang 2026
module "entry/post/hdr_present.compute.slang";

import "phase/post/hdr_present.slang";

[numthreads(8, 8, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    primePhase(dispatchThreadId);
}

