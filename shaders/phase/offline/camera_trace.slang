#language slang 2026
module "phase/offline/camera_trace.slang";

import "transport/offline/trace.slang";

public void primePhase() {
    primeTraceOfflineCamera(DispatchRaysIndex().xy);
}
