#language slang 2026
module "phase/realtime/tail_admission.slang";

import "prime_realtime_abi.slang";
import "state/realtime/queue.slang";
import "transport/realtime/tail_admission.slang";

public void primePhase() {
    uint launchIndex = DispatchRaysIndex().x;
    if (launchIndex == 0u) {
        primeResetWavefrontQueue(PRIME_WAVEFRONT_AREA_QUEUE);
    }
    primeAdmitRealtimeTailPath(launchIndex);
}
