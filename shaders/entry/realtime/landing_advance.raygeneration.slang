#language slang 2026
module "entry/realtime/landing_advance.raygeneration.slang";

import "phase/realtime/landing_advance.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}

