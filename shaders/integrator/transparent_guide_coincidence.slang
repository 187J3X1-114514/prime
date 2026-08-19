#language slang 2026
module "integrator/transparent_guide_coincidence.slang";

import "bsdf/bsdf_common.slang";
import "bsdf/compact/openpbr_common.slang";
static const uint PRIME_TRANSPARENT_GUIDE_EVENT_MASK =
        PRIME_BSDF_EVENT_REFLECTION
        | PRIME_BSDF_EVENT_TRANSMISSION
        | PRIME_BSDF_EVENT_DISCRETE;

uint primeTransparentGuideDiscreteEvent(BsdfSample sampleValue)
{
    return sampleValue.eventFlags & PRIME_TRANSPARENT_GUIDE_EVENT_MASK;
}

bool primeTransparentGuideHasOneDiscreteEvent(BsdfSample sampleValue)
{
    uint eventValue = primeTransparentGuideDiscreteEvent(sampleValue);
    uint directionEvent = eventValue
            & (PRIME_BSDF_EVENT_REFLECTION | PRIME_BSDF_EVENT_TRANSMISSION);
    return (eventValue & PRIME_BSDF_EVENT_DISCRETE) != 0u
            && (directionEvent == PRIME_BSDF_EVENT_REFLECTION
                    || directionEvent == PRIME_BSDF_EVENT_TRANSMISSION);
}

public bool primeTransparentGuideContinuationReusable(
        BsdfSample transport,
        PrimeOpenPbrVolumeStack transportStack,
        BsdfSample guide,
        PrimeOpenPbrVolumeStack guideStack)
{
    bool transportValid = transport.eventFlags != 0u;
    bool guideValid = guide.eventFlags != 0u;
    if (!transportValid || !guideValid) {
        return transportValid == guideValid;
    }
    // A discrete-only closure has one geometric continuation for each reflection/transmission
    // event. The complete sampler and canonical guide intentionally reach it through different
    // arithmetic and conditional weights, so raw float equality would reject the same path.
    // Stack count retains the push/pop/no-transition topology; active coefficients come from the
    // same surface state and are not a second identity test.
    return primeTransparentGuideHasOneDiscreteEvent(transport)
            && primeTransparentGuideHasOneDiscreteEvent(guide)
            && primeTransparentGuideDiscreteEvent(transport)
                    == primeTransparentGuideDiscreteEvent(guide)
            && transportStack.count == guideStack.count;
}
