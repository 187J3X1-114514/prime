#language slang 2026
module "entry/realtime/sharc/training_light_area.raygeneration.slang";

import "phase/realtime/sharc/training_light_area.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}
