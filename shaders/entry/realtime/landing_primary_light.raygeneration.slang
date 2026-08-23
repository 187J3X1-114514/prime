#language slang 2026
module "entry/realtime/landing_primary_light.raygeneration.slang";

import "phase/realtime/landing_primary_light.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}

