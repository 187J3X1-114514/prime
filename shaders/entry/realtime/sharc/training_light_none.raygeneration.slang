#language slang 2026
module "entry/realtime/sharc/training_light_none.raygeneration.slang";

import "phase/realtime/sharc/training_light_none.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}
