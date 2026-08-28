#language slang 2026
module "phase/offline/light_select.slang";

import "contract/offline/shader_record.slang";
import "policy/offline/fixed_queue.slang";
import "transport/offline/light_select.slang";

public void primePhase() {
    primeSelectOfflineLight(
            primeOfflineSourceQueue(primeOfflineShaderRecord.control),
            DispatchRaysIndex().x);
}
