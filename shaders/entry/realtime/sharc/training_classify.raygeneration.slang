#language slang 2026
module "entry/realtime/sharc/training_classify.raygeneration.slang";

import "phase/realtime/sharc/training_classify.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}
