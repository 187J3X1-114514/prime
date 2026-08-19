#ifndef PRIME_SHARC_TRAINING_CORE_SLANG
#define PRIME_SHARC_TRAINING_CORE_SLANG

#define PRIME_SHARC_TERMINATION_SAMPLE_THRESHOLD 1

static const uint PRIME_SHARC_TRAINING_GRID_SIZE = 3u;
static const uint PRIME_SHARC_TRAINING_ANCHOR_CAPACITY = 6u;
static const uint PRIME_SHARC_TRAINING_ACTIVE = 1u;
static const uint PRIME_SHARC_TRAINING_COMPLETE = 2u;
static const uint PRIME_SHARC_TRAINING_PENDING_DIRECTION = 4u;
static const uint PRIME_SHARC_TRAINING_PENDING_ANCHOR_SHIFT = 3u;
static const uint PRIME_SHARC_TRAINING_PENDING_ANCHOR_MASK = 0x7u;
static const uint PRIME_SHARC_TRAINING_ANCHOR_DIRECTION = 4u;
static const uint PRIME_SHARC_TRAINING_ROOT_BOUNCE_SHIFT = 8u;
static const uint PRIME_SHARC_TRAINING_ROOT_BOUNCE_MASK = 0xffu;
static const uint PRIME_SHARC_TRAINING_ANCHOR_COUNT_SHIFT = 16u;
static const uint PRIME_SHARC_TRAINING_ANCHOR_COUNT_MASK = 0xffu;

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

uint primeSharcTrainingAnchorCount(uint control) {
    return (control >> PRIME_SHARC_TRAINING_ANCHOR_COUNT_SHIFT)
            & PRIME_SHARC_TRAINING_ANCHOR_COUNT_MASK;
}

uint primeSharcTrainingWithAnchorCount(uint control, uint count) {
    uint fieldMask = PRIME_SHARC_TRAINING_ANCHOR_COUNT_MASK
            << PRIME_SHARC_TRAINING_ANCHOR_COUNT_SHIFT;
    return (control & ~fieldMask)
            | (min(count, PRIME_SHARC_TRAINING_ANCHOR_COUNT_MASK)
                    << PRIME_SHARC_TRAINING_ANCHOR_COUNT_SHIFT);
}

bool primeSharcTrainingHasPendingDirection(uint control) {
    return (control & PRIME_SHARC_TRAINING_PENDING_DIRECTION) != 0u;
}

uint primeSharcTrainingPendingAnchor(uint control) {
    return (control >> PRIME_SHARC_TRAINING_PENDING_ANCHOR_SHIFT)
            & PRIME_SHARC_TRAINING_PENDING_ANCHOR_MASK;
}

uint primeSharcTrainingWithPendingAnchor(uint control, uint anchor) {
    uint fieldMask = PRIME_SHARC_TRAINING_PENDING_ANCHOR_MASK
            << PRIME_SHARC_TRAINING_PENDING_ANCHOR_SHIFT;
    return (control & ~fieldMask)
            | PRIME_SHARC_TRAINING_PENDING_DIRECTION
            | ((anchor & PRIME_SHARC_TRAINING_PENDING_ANCHOR_MASK)
                    << PRIME_SHARC_TRAINING_PENDING_ANCHOR_SHIFT);
}

uint primeSharcTrainingWithoutPendingAnchor(uint control) {
    uint fieldMask = PRIME_SHARC_TRAINING_PENDING_DIRECTION
            | (PRIME_SHARC_TRAINING_PENDING_ANCHOR_MASK
                    << PRIME_SHARC_TRAINING_PENDING_ANCHOR_SHIFT);
    return control & ~fieldMask;
}

uint primeSharcTrainingStoredAnchorCount(uint control) {
    return min(
            primeSharcTrainingAnchorCount(control),
            PRIME_SHARC_TRAINING_ANCHOR_CAPACITY);
}

uint primeSharcTrainingAnchorSlot(uint anchorIndex) {
    return min(anchorIndex, PRIME_SHARC_TRAINING_ANCHOR_CAPACITY - 1u);
}

bool primeSharcTrainingCanAppendAnchor(uint control) {
    return primeSharcTrainingAnchorCount(control)
            < PRIME_SHARC_TRAINING_ANCHOR_CAPACITY;
}

bool primeSharcTrainingBypassesCacheValue(
        bool selected,
        uint control) {
    return selected && !primeSharcTrainingComplete(control);
}

bool primeSharcTerminationReady(float accumulatedSampleCount) {
    // SHARC accumulation adds one unit per independent traced anchor.
    return accumulatedSampleCount
            > float(PRIME_SHARC_TERMINATION_SAMPLE_THRESHOLD);
}

uint2 primeSharcTrainingPhase(uint updatePhase) {
    return uint2(
            updatePhase % PRIME_SHARC_TRAINING_GRID_SIZE,
            updatePhase / PRIME_SHARC_TRAINING_GRID_SIZE);
}

bool primeSharcTrainingPixelInPhase(uint2 pixel, uint updatePhase) {
    return all((pixel % PRIME_SHARC_TRAINING_GRID_SIZE)
            == primeSharcTrainingPhase(updatePhase));
}

uint2 primeSharcTrainingCoordinate(uint2 pixel) {
    return pixel / PRIME_SHARC_TRAINING_GRID_SIZE;
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

float3 primeSharcTrainingSuffix(
        float3 weightedTail,
        float3 anchorThroughput) {
    return primeSharcTrainingRatio(weightedTail, anchorThroughput);
}

#endif
