#language slang 2026
module "entry/realtime/sharc/steady_none.raygeneration.slang";

import "phase/realtime/sharc/steady_none.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}

