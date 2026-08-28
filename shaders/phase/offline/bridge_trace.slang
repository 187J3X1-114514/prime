#language slang 2026
module "phase/offline/bridge_trace.slang";

import "contract/offline/shader_record.slang";
import "policy/offline/fixed_queue.slang";
import "transport/offline/trace.slang";

public void primePhase() {
    primeTraceOfflineBridge(
            primeOfflineSourceQueue(primeOfflineShaderRecord.control),
            DispatchRaysIndex().x);
}
