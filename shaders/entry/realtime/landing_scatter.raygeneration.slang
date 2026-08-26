#language slang 2026
module "entry/realtime/landing_scatter.raygeneration.slang";

import "phase/realtime/landing_scatter.slang";

[shader("raygeneration")]
void main() {
    primePhase();
}
