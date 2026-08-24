#language slang 2026
module "entry/realtime/sharc/training_reduce.raygeneration.slang";

import "phase/realtime/sharc/training_reduce.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}
