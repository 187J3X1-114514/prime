#language slang 2026
module "entry/realtime/sharc/training_landing.raygeneration.slang";

import "phase/realtime/sharc/training_landing.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}
