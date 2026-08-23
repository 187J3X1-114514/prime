#language slang 2026
module "entry/realtime/steady_classify.raygeneration.slang";

import "phase/realtime/steady_classify.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}

