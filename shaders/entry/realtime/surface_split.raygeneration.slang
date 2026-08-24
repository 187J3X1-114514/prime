#language slang 2026
module "entry/realtime/surface_split.raygeneration.slang";

import "phase/realtime/surface_split.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}
