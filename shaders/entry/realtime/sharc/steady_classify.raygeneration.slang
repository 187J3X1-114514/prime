#language slang 2026
module "entry/realtime/sharc/steady_classify.raygeneration.slang";

import "phase/realtime/sharc/steady_classify.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}

