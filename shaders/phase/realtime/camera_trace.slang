#language slang 2026
module "phase/realtime/camera_trace.slang";

import "service/trace/context.slang";
import "transport/realtime/camera_trace.slang";

public void primePhase()
{
    uint2 launchIndex = DispatchRaysIndex().xy;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    primeTraceRealtimeCamera(context, launchIndex);
}
