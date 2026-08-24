#language slang 2026
module "entry/offline/sample_resolve.raygeneration.slang";

import "phase/offline/sample_resolve.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}
