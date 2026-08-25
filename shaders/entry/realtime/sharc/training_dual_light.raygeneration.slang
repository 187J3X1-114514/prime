#language slang 2026
module "entry/realtime/sharc/training_dual_light.raygeneration.slang";

import "phase/realtime/sharc/training_dual_light.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}
