#language slang 2026
module "entry/realtime/head.raygeneration.slang";

import "phase/realtime/head.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}

