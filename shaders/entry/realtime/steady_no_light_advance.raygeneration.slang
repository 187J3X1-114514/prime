#language slang 2026
module "entry/realtime/steady_no_light_advance.raygeneration.slang";

import "phase/realtime/steady_no_light_advance.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}
