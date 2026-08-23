#language slang 2026
module "entry/realtime/steady_none.raygeneration.slang";

import "phase/realtime/steady_none.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}

