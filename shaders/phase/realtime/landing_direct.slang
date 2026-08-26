#language slang 2026
module "phase/realtime/landing_direct.slang";

import "service/trace/context.slang";
import "transport/realtime/landing_direct.slang";

public void primePhase() {
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    primeEvaluateLandingDirect(context, DispatchRaysIndex().x);
}
