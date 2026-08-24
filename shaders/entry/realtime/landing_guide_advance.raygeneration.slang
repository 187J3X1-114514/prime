#language slang 2026
module "entry/realtime/landing_guide_advance.raygeneration.slang";

import "phase/realtime/landing_guide_advance.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}
