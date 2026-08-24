#language slang 2026
module "entry/realtime/steady_trace_classify.raygeneration.slang";

import "phase/realtime/steady_trace_classify.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}
