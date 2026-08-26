#language slang 2026
module "phase/realtime/landing_light_select.slang";

import "transport/realtime/landing_light_select.slang";

public void primePhase() {
    primeSelectLandingLight(DispatchRaysIndex().x);
}
