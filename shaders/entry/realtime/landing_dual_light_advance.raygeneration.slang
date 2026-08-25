#language slang 2026
module "entry/realtime/landing_dual_light_advance.raygeneration.slang";

import "phase/realtime/landing_dual_light_advance.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}
