#language slang 2026
module "entry/realtime/fixed_bridge_trace.raygeneration.slang";

import "phase/realtime/fixed_bridge_trace.slang";

[shader("raygeneration")]
void main() { primePhase(); }
