#language slang 2026
module "entry/realtime/transparent_resolve.raygeneration.slang";

import "phase/realtime/transparent_resolve.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}

