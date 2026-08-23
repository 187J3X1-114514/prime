#language slang 2026
module "entry/realtime/landing_secondary.raygeneration.slang";

import "phase/realtime/landing_secondary.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}

