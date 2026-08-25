#language slang 2026
module "state/path.slang";

import "contract/path.slang";

// PathState keeps only one exact control word live. Bounce is bounded to 128, the current flag
// domain occupies two bits, and the previous light normal is already octahedral 16-bit data.
public uint primeMakePathStateControl(
        uint bounce, uint flags, uint previousLightNormal) {
    return (bounce & PRIME_PATH_STATE_BOUNCE_MASK)
            | ((flags & PRIME_PATH_STATE_FLAGS_MASK)
                    << PRIME_PATH_STATE_FLAGS_SHIFT)
            | ((previousLightNormal & PRIME_PATH_STATE_LIGHT_NORMAL_MASK)
                    << PRIME_PATH_STATE_LIGHT_NORMAL_SHIFT);
}

public uint primePathBounce(uint control) {
    return control & PRIME_PATH_STATE_BOUNCE_MASK;
}

public uint primePathWithBounce(uint control, uint bounce) {
    return (control & ~PRIME_PATH_STATE_BOUNCE_MASK)
            | (bounce & PRIME_PATH_STATE_BOUNCE_MASK);
}

public uint primeIncrementPathBounce(uint control) {
    return primePathWithBounce(control, primePathBounce(control) + 1u);
}

public uint primePathFlags(uint control) {
    return (control >> PRIME_PATH_STATE_FLAGS_SHIFT)
            & PRIME_PATH_STATE_FLAGS_MASK;
}

public uint primePathWithFlags(uint control, uint flags) {
    return (control
                    & ~(PRIME_PATH_STATE_FLAGS_MASK
                            << PRIME_PATH_STATE_FLAGS_SHIFT))
            | ((flags & PRIME_PATH_STATE_FLAGS_MASK)
                    << PRIME_PATH_STATE_FLAGS_SHIFT);
}

public uint primePathWithAddedFlags(uint control, uint flags) {
    return primePathWithFlags(control, primePathFlags(control) | flags);
}

public uint primePathPreviousLightNormal(uint control) {
    return (control >> PRIME_PATH_STATE_LIGHT_NORMAL_SHIFT)
            & PRIME_PATH_STATE_LIGHT_NORMAL_MASK;
}

public uint primePathWithPreviousLightNormal(
        uint control, uint packedNormal) {
    return (control
                    & ~(PRIME_PATH_STATE_LIGHT_NORMAL_MASK
                            << PRIME_PATH_STATE_LIGHT_NORMAL_SHIFT))
            | ((packedNormal & PRIME_PATH_STATE_LIGHT_NORMAL_MASK)
                    << PRIME_PATH_STATE_LIGHT_NORMAL_SHIFT);
}
