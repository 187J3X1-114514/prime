#language slang 2026
module "entry/realtime/sharc/bridge_trace.raygeneration.slang";

import "phase/realtime/sharc/bridge_trace.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}
