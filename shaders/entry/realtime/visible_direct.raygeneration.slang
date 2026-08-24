#language slang 2026
module "entry/realtime/visible_direct.raygeneration.slang";

import "phase/realtime/visible_direct.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}
