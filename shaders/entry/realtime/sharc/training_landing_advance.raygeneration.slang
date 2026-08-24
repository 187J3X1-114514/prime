#language slang 2026
module "entry/realtime/sharc/training_landing_advance.raygeneration.slang";

import "phase/realtime/sharc/training_landing_advance.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}
