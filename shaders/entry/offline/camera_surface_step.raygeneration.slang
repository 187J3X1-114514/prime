#language slang 2026
module "entry/offline/camera_surface_step.raygeneration.slang";

import "phase/offline/camera_surface_step.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}
