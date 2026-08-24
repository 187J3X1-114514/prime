#language slang 2026
module "entry/realtime/sharc/training_walk.raygeneration.slang";

import "phase/realtime/sharc/training_walk.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}
