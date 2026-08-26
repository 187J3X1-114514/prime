#language slang 2026
module "entry/realtime/landing_direct.raygeneration.slang";

import "phase/realtime/landing_direct.slang";

[shader("raygeneration")]
void main() {
    primePhase();
}
