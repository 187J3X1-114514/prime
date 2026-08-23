#language slang 2026
module "entry/post/image_diagnostic_rgba16.compute.slang";

import "phase/post/image_diagnostic_rgba16.slang";

[numthreads(8, 8, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    primePhase(dispatchThreadId);
}

