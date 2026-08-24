#language slang 2026
module "entry/realtime/sharc/training_path_walk.raygeneration.slang";

import "phase/realtime/sharc/training_path_walk.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}
