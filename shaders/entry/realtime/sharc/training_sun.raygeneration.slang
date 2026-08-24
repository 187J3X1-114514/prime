#language slang 2026
module "entry/realtime/sharc/training_sun.raygeneration.slang";

import "phase/realtime/sharc/training_sun.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}
