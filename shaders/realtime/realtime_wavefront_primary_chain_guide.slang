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
    result.guides = primeEmptyDenoiserGuides();
    result.anchorDistance = -1.0;
    result.directionalGuide = false;
    primeStoreTransparentBranchIntermediate(
            pixel,
            transmission,
            false,
            (control & PRIME_WAVEFRONT_PRIMARY_REFLECTION_ONLY) != 0u,
            result);
}

#endif
