#language slang 2026
module "entry/atmosphere/atmosphere_aerial_transmittance.compute.slang";

import "phase/atmosphere/atmosphere_aerial_transmittance.slang";

[numthreads(PRIME_PHASE_GROUP_SIZE_X, 1, 1)]
void main(uint3 groupId : SV_GroupID, uint3 groupThreadId : SV_GroupThreadID)
{
    primePhase(groupId, groupThreadId);
}
