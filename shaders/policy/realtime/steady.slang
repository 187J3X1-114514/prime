#language slang 2026
module "policy/realtime/steady.slang";

import "transport/scatter/phase_contract.slang";

public struct PrimeSteadyTransportPhase : IPrimeRealtimeTransportPhase {
    uint rouletteCompletedScatters(uint bounce) { return bounce + 1u; }
    uint transparentFirstHitBounce() { return 0u; }
    bool transparentPreviousUsedAreaNee(bool enabled, uint bounce) { return enabled; }
    uint ordinaryGuideHitBounce(uint primaryBounce) { return 0u; }
}
