#language slang 2026
module "entry/realtime/sharc/training_anchor_reduce.raygeneration.slang";

import "phase/realtime/sharc/training_anchor_reduce.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}
