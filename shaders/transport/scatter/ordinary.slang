#language slang 2026
module "transport/scatter/ordinary.slang";

import "prime_abi_types.slang";
import "service/bsdf/contract/sample.slang";
import "policy/runtime/config.slang";
import "math/sampling.slang";
import "math/transport.slang";
import "service/trace/context.slang";
import "transport/direct/selection.slang";
import "transport/scatter/path_update.slang";
import "policy/transport/path.slang";

public bool primeRussianRoulette(
        inout PrimeIntegratorContext context,
        inout PathState path,
        float sampleValue) {
    float survival = primeRussianRouletteSurvival(
            path.throughput, path.etaScale);
    if (sampleValue >= survival) {
        return false;
    }
    path.throughput = primeRussianRouletteReweight(path.throughput, survival);
    return true;
}

// Renderer-specific integrators compose these terminal estimators. Their caller supplies whether
// the competing Area technique existed, so realtime and offline can keep independent NEE rules.

public bool primeAdvanceRealtimePath(
        inout PrimeIntegratorContext context,
        inout PathState path,
        SurfaceInteraction surface,
        BsdfSample bsdf,
        PrimePreparedSampleBase bounceSample,
        bool discreteOnlyInterface,
        bool skipRoulette) {
    uint previousLightNormal = discreteOnlyInterface
            ? 0u
            : primeAreaLightReceiverNormal(surface, -path.rayDirection);
    if (!primeAdvancePathWithoutRoulette(
            context,
            path,
            surface,
            bsdf,
            previousLightNormal)) {
        return false;
    }
    if (skipRoulette) {
        return true;
    }
    // Two-stage realtime has already completed its non-delta bridge scatter before steady bounce
    // zero. Count that scatter for RR without folding primary delta depth into the steady bounce
    // domain or reducing its independent dispatch limit.
    uint rouletteCompletedScatters =
            primeTwoStageRouletteCompletedScatters(path.bounce);
    if (!primeRussianRouletteApplies(
            rouletteCompletedScatters, PRIME_RUSSIAN_ROULETTE_START)) {
        return true;
    }
    float rouletteSample = primeHashSample1D(
            bounceSample,
            PRIME_SAMPLE_EFFECT_RUSSIAN_ROULETTE,
            PRIME_SAMPLE_DIMENSION_PRIMARY);
    return primeRussianRoulette(context, path, rouletteSample);
}

public bool primeAdvanceRealtimePath(
        inout PrimeIntegratorContext context,
        inout PathState path,
        SurfaceInteraction surface,
        BsdfSample bsdf,
        PrimePreparedSampleBase bounceSample,
        bool discreteOnlyInterface) {
    return primeAdvanceRealtimePath(
            context,
            path,
            surface,
            bsdf,
            bounceSample,
            discreteOnlyInterface,
            false);
}
