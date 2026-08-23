#language slang 2026
module "entry/realtime/resolve.raygeneration.slang";

import "phase/realtime/resolve.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}

