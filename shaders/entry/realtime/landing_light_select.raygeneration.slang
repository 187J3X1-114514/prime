#language slang 2026
module "entry/realtime/landing_light_select.raygeneration.slang";

import "phase/realtime/landing_light_select.slang";

[shader("raygeneration")]
void main() {
    primePhase();
}
