#language slang 2026
module "entry/offline/light_select.raygeneration.slang";

import "phase/offline/light_select.slang";

[shader("raygeneration")]
void main() { primePhase(); }
