#language slang 2026
module "state/realtime/steady_transition.slang";

import "prime_abi_types.slang";
import "contract/path.slang";
import "state/realtime/contract.slang";
import "state/realtime/transport.slang";

// The steady domain owns a fresh local bounce sequence. A bootstrap that skipped NEE must also
// suppress emitter MIS at the first steady vertex because no competing light sample exists.
public void primeEnterSteadyAfterNee(inout PathState path) {
    path.bounce = 0u;
}

public void primeEnterSteadyWithoutNee(inout PathState path) {
    path.bounce = 0u;
    path.flags |= PRIME_PATH_PREVIOUS_DISCRETE;
}

public PrimeWavefrontTransportRecord primeEnterSteadyRecord(
        PrimeWavefrontTransportRecord record) {
    record.sourceAndPathControl.w =
            record.sourceAndPathControl.w & ~PRIME_WAVEFRONT_BYTE_MASK;
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    control &= ~(PRIME_WAVEFRONT_BYTE_MASK
            << PRIME_WAVEFRONT_PRIMARY_BOUNCE_SHIFT);
    record.rayDirectionAndDenoiserControl.w = asfloat(control);
    record = primeWavefrontWithQueuedPsrControl(record, 0u);
    return primeWavefrontWithGuideProbeFallback(record, false);
}
