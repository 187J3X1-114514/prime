#language slang 2026
module "entry/realtime/sharc/bridge_query.raygeneration.slang";

import "phase/realtime/sharc/bridge_query.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}
