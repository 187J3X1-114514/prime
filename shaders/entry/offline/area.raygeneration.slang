#language slang 2026
module "entry/offline/area.raygeneration.slang";

import "phase/offline/area.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}

