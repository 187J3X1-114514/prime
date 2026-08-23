#language slang 2026
module "entry/lighting/sharc_resolve.compute.slang";

#include "phase/lighting/sharc_resolve_bridge.h"

[numthreads(256, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    primePhase(dispatchThreadId);
}
