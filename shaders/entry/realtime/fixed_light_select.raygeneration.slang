#language slang 2026
module "entry/realtime/fixed_light_select.raygeneration.slang";

import "phase/realtime/fixed_light_select.slang";

[shader("raygeneration")]
void main() { primePhase(); }
