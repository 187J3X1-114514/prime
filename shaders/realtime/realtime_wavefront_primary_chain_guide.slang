#ifndef PRIME_REALTIME_WAVEFRONT_PRIMARY_CHAIN_GUIDE_SLANG
#define PRIME_REALTIME_WAVEFRONT_PRIMARY_CHAIN_GUIDE_SLANG

void primeInvalidatePrimaryChainGuide(
        uint recordIndex,
        PrimeWavefrontTransportRecord record) {
    uint2 pixel = primeWavefrontPixel(recordIndex);
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    bool transmission = primeWavefrontTransmissionBranch(control);
    PrimeTransparentBranchResult result =
            primeLoadTransparentBranchActiveIntermediate(
                    pixel, transmission, control);
    result.anchorDistance = -1.0;
    result.directionalGuide = false;
    // The full branch store owns the visible-interface fallback. Invalidating queued PSR must
    // only discard its anchor and continuation state; replacing the resident guide with the
    // empty default leaves random chain-limit pixels without a reconstruction surface.
    primeStoreTransparentBranchActiveIntermediate(
            pixel,
            transmission,
            false,
            false,
            (control & PRIME_WAVEFRONT_PRIMARY_REFLECTION_ONLY) != 0u,
            result);
}

#endif
