#language slang 2026
module "entry/realtime/sharc/training_camera.raygeneration.slang";

import "phase/realtime/sharc/training_camera.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}
