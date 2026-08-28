#language slang 2026
module "phase/offline/direct.slang";

import "contract/offline/shader_record.slang";
import "policy/offline/fixed_queue.slang";
import "service/trace/context.slang";
import "transport/offline/direct.slang";

public void primePhase() {
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    primeEvaluateOfflineDirect(
            context,
            primeOfflineSourceQueue(primeOfflineShaderRecord.control),
            DispatchRaysIndex().x);
}
