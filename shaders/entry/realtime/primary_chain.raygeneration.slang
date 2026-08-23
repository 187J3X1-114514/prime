#language slang 2026
module "entry/realtime/primary_chain.raygeneration.slang";

import "phase/realtime/primary_chain.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}

