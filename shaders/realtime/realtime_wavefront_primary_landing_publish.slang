#ifndef PRIME_REALTIME_WAVEFRONT_PRIMARY_LANDING_PUBLISH_SLANG
#define PRIME_REALTIME_WAVEFRONT_PRIMARY_LANDING_PUBLISH_SLANG

#include "realtime/realtime_wavefront_primary_chain_guide.slang"

void primePublishPrimaryLanding(
        uint recordIndex, bool transparent, bool continuation) {
    if (continuation) {
        PrimeWavefrontTransportRecord record =
                primeLoadWavefrontTransportRecord(recordIndex);
        uint psrControl = (record.sourceAndPathControl.w
                >> PRIME_WAVEFRONT_PSR_CONTROL_SHIFT)
                & PRIME_WAVEFRONT_PSR_CONTROL_MASK;
        PrimeQueuedPsrState psrState = primeEmptyQueuedPsrState();
        psrState.control = psrControl;
        bool invalidGuide = primeQueuedPsrOverflowed(psrState)
                || primeWavefrontGuideProbeFallback(
                        record.sourceAndPathControl.w);
        if (transparent && invalidGuide) {
            primeInvalidatePrimaryChainGuide(recordIndex, record);
        }
        record = primeWavefrontSteadyState(record);
        primeStoreWavefrontTransportRecord(recordIndex, record);
    }
    primeAppendWavefrontContinuation(
            PrimeWavefrontContinuationAppender(),
            PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0,
            recordIndex,
            continuation);
}

#endif
