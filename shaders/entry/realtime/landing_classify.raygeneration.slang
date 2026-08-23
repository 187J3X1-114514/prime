#language slang 2026
module "entry/realtime/landing_classify.raygeneration.slang";

import "phase/realtime/landing_classify.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}

