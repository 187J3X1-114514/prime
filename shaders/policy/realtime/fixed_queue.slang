#language slang 2026
module "policy/realtime/fixed_queue.slang";

import "prime_realtime_abi.slang";

public uint primeFixedSourceQueue(uint control) {
    return (control & 1u) == 0u
            ? PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0
            : PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_1;
}

public uint primeFixedTargetQueue(uint control) {
    return (control & 1u) == 0u
            ? PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_1
            : PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0;
}
