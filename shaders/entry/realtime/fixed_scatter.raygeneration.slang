#language slang 2026
module "entry/realtime/fixed_scatter.raygeneration.slang";

import "phase/realtime/fixed_scatter.slang";

[shader("raygeneration")]
void main() { primePhase(); }
