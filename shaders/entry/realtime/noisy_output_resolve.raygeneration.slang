#language slang 2026
module "entry/realtime/noisy_output_resolve.raygeneration.slang";

import "phase/realtime/noisy_output_resolve.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}
