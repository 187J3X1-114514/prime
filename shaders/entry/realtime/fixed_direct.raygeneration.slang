#language slang 2026
module "entry/realtime/fixed_direct.raygeneration.slang";

import "phase/realtime/fixed_direct.slang";

[shader("raygeneration")]
void main() { primePhase(); }
