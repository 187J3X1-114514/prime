#language slang 2026
module "entry/realtime/primary_split.raygeneration.slang";

import "phase/realtime/primary_split.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}

