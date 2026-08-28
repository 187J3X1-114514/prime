#language slang 2026
module "entry/offline/bridge_trace.raygeneration.slang";

import "phase/offline/bridge_trace.slang";

[shader("raygeneration")]
void main() { primePhase(); }
