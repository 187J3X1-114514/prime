#language slang 2026
module "phase/realtime/resolve.slang";

import "transport/realtime/resolve.slang";

public void primePhase()
{
    uint2 launchIndex = DispatchRaysIndex().xy;
    primeWavefrontResolve(launchIndex);
}

