#language slang 2026
module "phase/realtime/fixed_direct.slang";

import "contract/realtime/shader_record.slang";
import "policy/realtime/fixed_queue.slang";
import "service/trace/context.slang";
import "transport/realtime/fixed_direct.slang";

public void primePhase() {
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    primeEvaluateFixedDirect(
            context,
            primeFixedSourceQueue(primeWavefrontShaderRecord.control),
            DispatchRaysIndex().x);
}
