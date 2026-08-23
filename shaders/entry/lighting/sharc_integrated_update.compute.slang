#language slang 2026
module "entry/lighting/sharc_integrated_update.compute.slang";

#include "phase/lighting/sharc_integrated_update_bridge.h"

[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    primePhase(dispatchThreadId);
}
