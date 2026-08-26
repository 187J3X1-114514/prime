#language slang 2026
module "phase/realtime/fixed_light_select.slang";

import "contract/realtime/shader_record.slang";
import "policy/realtime/fixed_queue.slang";
import "transport/realtime/fixed_light_select.slang";

public void primePhase() {
    primeSelectFixedLight(
            primeFixedSourceQueue(primeWavefrontShaderRecord.control),
            DispatchRaysIndex().x);
}
