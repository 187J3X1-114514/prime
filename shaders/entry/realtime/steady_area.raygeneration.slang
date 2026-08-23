#language slang 2026
module "entry/realtime/steady_area.raygeneration.slang";

import "phase/realtime/steady_area.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}

