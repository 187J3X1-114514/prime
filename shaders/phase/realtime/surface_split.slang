#language slang 2026
module "phase/realtime/surface_split.slang";

import "prime_realtime_abi.slang";
import "service/trace/context.slang";
import "state/realtime/queue.slang";
import "transport/direct/policy.slang";
import "transport/realtime/surface_split.slang";

public void primePhase()
{
    uint launchIndex = DispatchRaysIndex().x;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    if (launchIndex == 0u) {
        primeResetWavefrontQueue(PRIME_WAVEFRONT_PRIMARY_QUEUE);
    }
    primeSplitVisibleSurface(
            PrimeStagedSecondaryAreaNee(),
            context,
            launchIndex);
}
