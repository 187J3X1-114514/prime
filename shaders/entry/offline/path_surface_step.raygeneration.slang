#language slang 2026
module "entry/offline/path_surface_step.raygeneration.slang";

import "phase/offline/path_surface_step.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}
