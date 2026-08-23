#language slang 2026
module "entry/atmosphere/atmosphere_multi_scattering.compute.slang";

import "phase/atmosphere/atmosphere_multi_scattering.slang";

[numthreads(8, 8, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    primePhase(dispatchThreadId);
}

