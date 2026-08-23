#language slang 2026
module "entry/offline/head.raygeneration.slang";

import "phase/offline/head.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}

