#language slang 2026
module "entry/realtime/sharc/steady_sun.raygeneration.slang";

import "phase/realtime/sharc/steady_sun.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}

