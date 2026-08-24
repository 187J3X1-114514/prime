#language slang 2026
module "phase/realtime/noisy_output_resolve.slang";

import "transport/realtime/noisy_output_resolve.slang";

public void primePhase()
{
    uint2 launchIndex = DispatchRaysIndex().xy;
    primeResolveNoisyOutput(launchIndex);
}
