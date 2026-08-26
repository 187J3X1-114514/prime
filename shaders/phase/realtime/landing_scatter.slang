#language slang 2026
module "phase/realtime/landing_scatter.slang";

import "prime_realtime_abi.slang";
import "service/trace/context.slang";
import "state/realtime/queue.slang";
import "transport/realtime/landing_scatter.slang";

public void primePhase() {
    uint launchIndex = DispatchRaysIndex().x;
    if (launchIndex == 0u) {
        primeResetWavefrontQueue(PRIME_WAVEFRONT_PRIMARY_QUEUE);
    }
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    primeScatterLandingPath(context, launchIndex);
}
