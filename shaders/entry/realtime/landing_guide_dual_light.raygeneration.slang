#language slang 2026
module "entry/realtime/landing_guide_dual_light.raygeneration.slang";

import "phase/realtime/landing_guide_dual_light.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}
