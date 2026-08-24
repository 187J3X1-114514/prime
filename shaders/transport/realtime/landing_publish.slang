#language slang 2026
module "transport/realtime/landing_publish.slang";

import "policy/realtime/queue.slang";
import "prime_realtime_abi.slang";
import "math/queued_psr.slang";
import "state/realtime/contract.slang";
import "state/realtime/light.slang";
import "state/realtime/ordinary.slang";
import "state/realtime/queue.slang";
import "state/realtime/steady_transition.slang";
import "state/realtime/transport.slang";
import "transport/realtime/delta_walk_guide.slang";

public void primePublishLandingContinuation(
        uint recordIndex, bool transparent, bool continuation) {
    if (continuation) {
        PrimeWavefrontTransportRecord record =
                primeLoadWavefrontTransportRecord(recordIndex);
        uint psrControl = (record.sourceAndPathControl.w
                >> PRIME_WAVEFRONT_PSR_CONTROL_SHIFT)
                & PRIME_WAVEFRONT_PSR_CONTROL_MASK;
        PrimeQueuedPsrState psrState = primeEmptyQueuedPsrState();
        psrState.control = psrControl;
        uint control = asuint(record.rayDirectionAndDenoiserControl.w);
        bool invalidGuide = primeQueuedPsrOverflowed(psrState)
                && (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) == 0u
                && !primeWavefrontDirectionalGuide(control);
        if (transparent && invalidGuide) {
            primeInvalidateDeltaWalkGuide(recordIndex, record);
        }
        record = primeEnterSteadyRecord(record);
        primeStoreWavefrontTransportRecord(recordIndex, record);
    }
    primeAppendWavefrontContinuation(
            PrimeWavefrontContinuationAppender(),
            PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0,
            recordIndex,
            continuation);
}
