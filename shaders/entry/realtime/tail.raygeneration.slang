#language slang 2026
module "entry/realtime/tail.raygeneration.slang";

import "phase/realtime/tail.slang";

[shader("raygeneration")]
void main() {
    primePhase();
}
