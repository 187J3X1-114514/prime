#language slang 2026
module "entry/realtime/primary_direct.raygeneration.slang";

import "phase/realtime/primary_direct.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}

