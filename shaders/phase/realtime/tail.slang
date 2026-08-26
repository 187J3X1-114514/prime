#language slang 2026
module "phase/realtime/tail.slang";

import "service/trace/context.slang";
import "transport/realtime/tail.slang";

public void primePhase() {
    uint launchIndex = DispatchRaysIndex().x;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    primeRunRealtimeTail(context, launchIndex);
}
