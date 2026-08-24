#language slang 2026
module "entry/realtime/camera_trace.raygeneration.slang";

import "phase/realtime/camera_trace.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}
