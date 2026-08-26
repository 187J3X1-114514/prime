#language slang 2026
module "transport/realtime/tail_admission.slang";

import "prime_abi.slang";
import "prime_realtime_abi.slang";
import "prime_abi_types.slang";
import "contract/path.slang";
import "math/sampling.slang";
import "math/transport.slang";
import "policy/realtime/queue.slang";
import "policy/runtime/config.slang";
import "policy/transport/path.slang";
import "state/path.slang";
import "state/realtime/contract.slang";
import "state/realtime/light.slang";
import "state/realtime/ordinary.slang";
import "state/realtime/queue.slang";
import "state/realtime/transport.slang";

// The fixed prefix has already scattered this path. Apply exactly the roulette decision that the
// old per-bounce advance would have made, then compact only survivors into the register tail.
public void primeAdmitRealtimeTailPath(uint sourceQueue, uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(sourceQueue, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    PathState path = primeWavefrontPath(
            primeWavefrontPixel(recordIndex), record);
    bool continuation = primePathBounce(path.control) < primeMaximumBounces();
    bool rouletteBlocked = false;
    if (primeWavefrontTransparentBranch(control)) {
        bool guideEnabled = primeWavefrontGuideEnabled(control);
        bool hasGuide =
                (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) != 0u;
        bool pathOwnsGuide = primeTransparentTransportOwnsGuide(
                false,
                primeWavefrontGuideProbeFallback(
                        record.sourceAndPathControl.w));
        rouletteBlocked = primeTransparentRouletteBlocked(
                pathOwnsGuide, guideEnabled, hasGuide);
    }
    uint completedScatters = primePathBounce(path.control);
    if (continuation
            && !rouletteBlocked
            && primeRussianRouletteApplies(
                    completedScatters, PRIME_RUSSIAN_ROULETTE_START)) {
        PrimePreparedSampleBase preparedSample = primePreparePathSample(
                path.sampleSeed,
                primeSampleIndex(),
                completedScatters);
        float survival = primeRussianRouletteSurvival(
                path.throughput, path.etaScale);
        float rouletteSample = primeHashSample1D(
                preparedSample,
                PRIME_SAMPLE_EFFECT_RUSSIAN_ROULETTE,
                PRIME_SAMPLE_DIMENSION_PRIMARY);
        continuation = rouletteSample < survival;
        if (continuation) {
            path.throughput = primeRussianRouletteReweight(
                    path.throughput, survival);
            record.throughputAndReserved.xyz = path.throughput;
            primeStoreWavefrontTransportRecord(recordIndex, record);
        }
    }
    primeAppendWavefrontContinuation(
            PrimeWavefrontContinuationAppender(),
            PRIME_WAVEFRONT_AREA_QUEUE,
            recordIndex,
            continuation);
}
