#language slang 2026
module "entry/hit/shadow.miss.slang";

import "phase/hit/shadow_miss.slang";

[shader("miss")]
void main()
{
    primePhase();
}

