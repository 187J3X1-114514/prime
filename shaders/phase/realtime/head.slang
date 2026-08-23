#language slang 2026
module "phase/realtime/head.slang";

import "service/trace/context.slang";
import "transport/realtime/head.slang";

public void primePhase()
{
    uint2 launchIndex = DispatchRaysIndex().xy;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    primeWavefrontHead(context, launchIndex);
}

