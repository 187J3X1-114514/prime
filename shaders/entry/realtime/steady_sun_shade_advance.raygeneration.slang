#language slang 2026
module "entry/realtime/steady_sun_shade_advance.raygeneration.slang";

import "phase/realtime/steady_sun_shade_advance.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}
