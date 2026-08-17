#ifndef PRIME_SHARC_TRAINING_CORE_SLANG
#define PRIME_SHARC_TRAINING_CORE_SLANG

static const uint PRIME_SHARC_TRAINING_ACTIVE = 1u;
static const uint PRIME_SHARC_TRAINING_COMPLETE = 2u;
static const uint PRIME_SHARC_TRAINING_ANCHOR = 4u;
static const uint PRIME_SHARC_TRAINING_DIRECTION = 8u;
static const uint PRIME_SHARC_TRAINING_ROOT_BOUNCE_SHIFT = 8u;
static const uint PRIME_SHARC_TRAINING_ROOT_BOUNCE_MASK = 0xffu;

bool primeSharcTrainingActive(uint control) {
    return (control & PRIME_SHARC_TRAINING_ACTIVE) != 0u;
}

bool primeSharcTrainingComplete(uint control) {
    return (control & PRIME_SHARC_TRAINING_COMPLETE) != 0u;
}

bool primeSharcTrainingAnchor(uint control) {
    return (control & PRIME_SHARC_TRAINING_ANCHOR) != 0u;
}

uint primeSharcTrainingRootBounce(uint control) {
    return (control >> PRIME_SHARC_TRAINING_ROOT_BOUNCE_SHIFT)
            & PRIME_SHARC_TRAINING_ROOT_BOUNCE_MASK;
}

uint primeSharcTrainingRootControl(uint bounce, bool anchor) {
    return PRIME_SHARC_TRAINING_ACTIVE
            | (anchor ? PRIME_SHARC_TRAINING_ANCHOR : 0u)
            | (min(bounce, PRIME_SHARC_TRAINING_ROOT_BOUNCE_MASK)
                    << PRIME_SHARC_TRAINING_ROOT_BOUNCE_SHIFT);
}

bool primeSharcTrainingBypassesCacheValue(
        bool selected,
        uint control,
        uint bounce) {
    if (!selected || primeSharcTrainingComplete(control)) {
        return false;
    }
    if (!primeSharcTrainingActive(control)
            || primeSharcTrainingAnchor(control)) {
        return true;
    }
    return bounce <= primeSharcTrainingRootBounce(control);
}

float primeSharcTrainingRatio(float numerator, float denominator) {
    return denominator > 0.0 ? numerator / denominator : 0.0;
}

float3 primeSharcTrainingRatio(float3 numerator, float3 denominator) {
    return float3(
            primeSharcTrainingRatio(numerator.x, denominator.x),
            primeSharcTrainingRatio(numerator.y, denominator.y),
            primeSharcTrainingRatio(numerator.z, denominator.z));
}

float3 primeSharcTrainingAccumulateTarget(
        float3 target,
        float3 throughput,
        float3 localRadiance) {
    return target + throughput * localRadiance;
}

#endif
