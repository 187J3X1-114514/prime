#language slang 2026
module "entry/offline/resolve.raygeneration.slang";

import "phase/offline/resolve.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}

