#ifndef PRIME_QUEUED_PSR_MATH_GLSL
#define PRIME_QUEUED_PSR_MATH_GLSL

const uint PRIME_QUEUED_PSR_CAPACITY = 8u;
const uint PRIME_QUEUED_PSR_COUNT_MASK = 0x0fu;
const uint PRIME_QUEUED_PSR_OVERFLOW = 0x10u;
const uint PRIME_QUEUED_PSR_ODD_REFLECTION = 0x20u;

struct PrimeQueuedPsrState {
    vec4 firstDirectionLength;
    vec4 lastPositionControl;
    vec4 rotation;
};

uint primeQueuedPsrControl(PrimeQueuedPsrState state) {
    return floatBitsToUint(state.lastPositionControl.w);
}

uint primeQueuedPsrCount(PrimeQueuedPsrState state) {
    return primeQueuedPsrControl(state) & PRIME_QUEUED_PSR_COUNT_MASK;
}

bool primeQueuedPsrOverflowed(PrimeQueuedPsrState state) {
    return (primeQueuedPsrControl(state) & PRIME_QUEUED_PSR_OVERFLOW) != 0u;
}

PrimeQueuedPsrState primeEmptyQueuedPsrState() {
    PrimeQueuedPsrState state;
    state.firstDirectionLength = vec4(0.0);
    state.lastPositionControl = vec4(0.0);
    state.rotation = vec4(0.0, 0.0, 0.0, 1.0);
    return state;
}

vec4 primeQueuedPsrQuaternionMultiply(vec4 first, vec4 second) {
    return vec4(
            first.w * second.xyz
                    + second.w * first.xyz
                    + cross(first.xyz, second.xyz),
            first.w * second.w - dot(first.xyz, second.xyz));
}

vec3 primeQueuedPsrQuaternionRotate(vec4 quaternion, vec3 direction) {
    vec3 twiceCross = 2.0 * cross(quaternion.xyz, direction);
    return direction
            + quaternion.w * twiceCross
            + cross(quaternion.xyz, twiceCross);
}

void primeAppendQueuedPsrState(
        inout PrimeQueuedPsrState state,
        vec3 cameraPosition,
        vec3 position,
        vec3 normal,
        bool reflection) {
    uint control = primeQueuedPsrControl(state);
    uint count = control & PRIME_QUEUED_PSR_COUNT_MASK;
    if (count == PRIME_QUEUED_PSR_CAPACITY) {
        state.lastPositionControl.w =
                uintBitsToFloat(control | PRIME_QUEUED_PSR_OVERFLOW);
        return;
    }
    if (count == 0u) {
        vec3 firstSegment = position - cameraPosition;
        float firstLength = length(firstSegment);
        state.firstDirectionLength = vec4(
                firstLength > 0.0 ? firstSegment / firstLength : vec3(0.0),
                firstLength);
    } else {
        state.firstDirectionLength.w +=
                length(position - state.lastPositionControl.xyz);
    }
    state.lastPositionControl.xyz = position;
    if (reflection) {
        vec4 halfTurn = vec4(normal, 0.0);
        vec4 rotation =
                primeQueuedPsrQuaternionMultiply(state.rotation, halfTurn);
        float lengthSquared = dot(rotation, rotation);
        state.rotation = lengthSquared > 0.0
                ? rotation * inversesqrt(lengthSquared)
                : vec4(0.0, 0.0, 0.0, 1.0);
        control ^= PRIME_QUEUED_PSR_ODD_REFLECTION;
    }
    control = (control & ~PRIME_QUEUED_PSR_COUNT_MASK) | (count + 1u);
    state.lastPositionControl.w = uintBitsToFloat(control);
}

vec3 primeQueuedPsrVirtualDirection(
        PrimeQueuedPsrState state, vec3 direction) {
    vec3 transformed =
            primeQueuedPsrQuaternionRotate(state.rotation, direction);
    return (primeQueuedPsrControl(state) & PRIME_QUEUED_PSR_ODD_REFLECTION) != 0u
            ? -transformed
            : transformed;
}

bool primeBuildQueuedPsrGuideValue(
        PrimeQueuedPsrState state,
        vec3 target,
        vec3 targetNormal,
        out vec3 virtualPosition,
        out vec3 virtualNormal) {
    if (primeQueuedPsrCount(state) == 0u || primeQueuedPsrOverflowed(state)) {
        return false;
    }
    float pathLength = state.firstDirectionLength.w
            + length(target - state.lastPositionControl.xyz);
    if (!(dot(state.firstDirectionLength.xyz, state.firstDirectionLength.xyz) > 0.0)
            || !(pathLength > 0.0)) {
        return false;
    }
    virtualPosition = state.firstDirectionLength.xyz * pathLength;
    virtualNormal = primeQueuedPsrVirtualDirection(state, targetNormal);
    return true;
}

bool primeQueuedPsrFinite(float value) {
    return !isnan(value) && !isinf(value);
}

float primeQueuedPsrAnchorDistanceValue(
        PrimeQueuedPsrState state,
        vec3 cameraPosition,
        vec3 target,
        vec3 targetNormal) {
    if (primeQueuedPsrOverflowed(state)) {
        return -1.0;
    }
    vec3 targetDirection = target - cameraPosition;
    float targetLengthSquared = dot(targetDirection, targetDirection);
    vec3 primaryDirection = primeQueuedPsrCount(state) == 0u
            && targetLengthSquared > 1.0e-12
            ? targetDirection * inversesqrt(targetLengthSquared)
            : state.firstDirectionLength.xyz;
    float denominator = dot(primaryDirection, targetNormal);
    if (!primeQueuedPsrFinite(denominator) || !(abs(denominator) > 1.0e-4)) {
        return -1.0;
    }
    float distance = dot(target - cameraPosition, targetNormal) / denominator;
    return primeQueuedPsrFinite(distance) && distance > 0.0 ? distance : -1.0;
}

#endif
