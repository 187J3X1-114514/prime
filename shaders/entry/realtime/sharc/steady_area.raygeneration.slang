#language slang 2026
module "entry/realtime/sharc/steady_area.raygeneration.slang";

import "phase/realtime/sharc/steady_area.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}

