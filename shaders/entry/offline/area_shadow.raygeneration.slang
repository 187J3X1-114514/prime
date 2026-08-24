#language slang 2026
module "entry/offline/area_shadow.raygeneration.slang";

import "phase/offline/area_shadow.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}
