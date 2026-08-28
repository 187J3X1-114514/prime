#language slang 2026
module "entry/offline/direct.raygeneration.slang";

import "phase/offline/direct.slang";

[shader("raygeneration")]
void main() { primePhase(); }
