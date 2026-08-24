#language slang 2026
module "entry/realtime/sharc/training_light_classify.raygeneration.slang";

import "phase/realtime/sharc/training_light_classify.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}
