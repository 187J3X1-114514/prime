#language slang 2026
module "policy/realtime/landing.slang";

import "transport/scatter/phase_contract.slang";

public struct PrimeLandingTransportPhase : IPrimeRealtimeTransportPhase {
    uint rouletteCompletedScatters(uint bounce) { return bounce + 1u; }
    uint transparentFirstHitBounce() { return 1u; }
    bool transparentPreviousUsedAreaNee(bool enabled, uint bounce) {
        return enabled && bounce > 0u;
    }
    uint ordinaryGuideHitBounce(uint primaryBounce) { return primaryBounce + 1u; }
}
