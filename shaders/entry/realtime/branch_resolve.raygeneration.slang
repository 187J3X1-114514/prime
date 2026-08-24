#language slang 2026
module "entry/realtime/branch_resolve.raygeneration.slang";

import "phase/realtime/branch_resolve.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}
