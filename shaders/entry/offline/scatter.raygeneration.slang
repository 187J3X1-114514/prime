#language slang 2026
module "entry/offline/scatter.raygeneration.slang";

import "phase/offline/scatter.slang";

[shader("raygeneration")]
void main() { primePhase(); }
