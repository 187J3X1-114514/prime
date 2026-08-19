#ifndef PRIME_SHARC_TRAINING_CORE_SLANG
#define PRIME_SHARC_TRAINING_CORE_SLANG

static const uint PRIME_SHARC_TRAINING_ACTIVE = 1u;
static const uint PRIME_SHARC_TRAINING_COMPLETE = 2u;
static const uint PRIME_SHARC_TRAINING_DIRECTION = 4u;
static const uint PRIME_SHARC_TRAINING_ROOT_BOUNCE_SHIFT = 8u;
static const uint PRIME_SHARC_TRAINING_ROOT_BOUNCE_MASK = 0xffu;

bool primeSharcTrainingActive(uint control) {
    return (control & PRIME_SHARC_TRAINING_ACTIVE) != 0u;
}

bool primeSharcTrainingComplete(uint control) {
    return (control & PRIME_SHARC_TRAINING_COMPLETE) != 0u;
}

uint primeSharcTrainingRootBounce(uint control) {
    return (control >> PRIME_SHARC_TRAINING_ROOT_BOUNCE_SHIFT)
            & PRIME_SHARC_TRAINING_ROOT_BOUNCE_MASK;
}

uint primeSharcTrainingRootControl(uint bounce) {
    return PRIME_SHARC_TRAINING_ACTIVE
            | (min(bounce, PRIME_SHARC_TRAINING_ROOT_BOUNCE_MASK)
                    << PRIME_SHARC_TRAINING_ROOT_BOUNCE_SHIFT);
}

bool primeSharcTrainingBypassesCacheValue(
        bool selected,
        uint control) {
    return selected && !primeSharcTrainingComplete(control);
}

float3 primeSharcTrainingRootRadianceDirection(float3 rayDirection) {
    return -rayDirection;
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
