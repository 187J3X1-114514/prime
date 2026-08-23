#language slang 2026
module "entry/lighting/sun_shadow.raygeneration.slang";

import "phase/lighting/sun_shadow.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}

