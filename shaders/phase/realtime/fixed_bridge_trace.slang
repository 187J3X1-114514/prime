#language slang 2026
module "phase/realtime/fixed_bridge_trace.slang";

import "contract/realtime/shader_record.slang";
import "policy/realtime/fixed_queue.slang";
import "service/trace/context.slang";
import "state/realtime/queue.slang";
import "transport/realtime/fixed_bridge_trace.slang";

public void primePhase() {
    uint launchIndex = DispatchRaysIndex().x;
    uint control = primeWavefrontShaderRecord.control;
    if (launchIndex == 0u) {
        primeResetWavefrontQueue(primeFixedTargetQueue(control));
    }
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    primeTraceFixedBridge(
            context, primeFixedSourceQueue(control), launchIndex);
}
