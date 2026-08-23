#language slang 2026
module "state/offline/area.slang";

import "state/offline/contract.slang";
import "bsdf/compact/contract/flags.slang";

public uint primeOfflineAreaControl(
        uint packedNormal, bool valid, uint startingMediumCount) {
    uint control = (packedNormal & PRIME_OFFLINE_LIGHT_NORMAL_MASK)
            << PRIME_OFFLINE_LIGHT_NORMAL_SHIFT;
    if (valid) {
        control |= PRIME_OFFLINE_AREA_VALID;
        control |= min(startingMediumCount, PRIME_OPENPBR_MAX_VOLUME_STACK_SIZE)
                << PRIME_OFFLINE_AREA_MEDIUM_SHIFT;
    }
    return control;
}

public uint primeOfflinePreviousLightNormal(PrimeOfflinePathRecord record) {
    return (asuint(record.areaContributionAndValid.w)
            >> PRIME_OFFLINE_LIGHT_NORMAL_SHIFT)
            & PRIME_OFFLINE_LIGHT_NORMAL_MASK;
}
