#language slang 2026
module "entry/realtime/steady_sun.raygeneration.slang";

import "phase/realtime/steady_sun.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}

