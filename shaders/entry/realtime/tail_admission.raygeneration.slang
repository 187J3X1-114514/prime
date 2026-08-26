#language slang 2026
module "entry/realtime/tail_admission.raygeneration.slang";

import "phase/realtime/tail_admission.slang";

[shader("raygeneration")]
void main() {
    primePhase();
}
