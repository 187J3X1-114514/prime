#language slang 2026
module "entry/realtime/delta_walk.raygeneration.slang";

import "phase/realtime/delta_walk.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}
