#language slang 2026
module "entry/offline/step.raygeneration.slang";

import "phase/offline/step.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}

