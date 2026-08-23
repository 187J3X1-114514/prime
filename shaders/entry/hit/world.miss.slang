#language slang 2026
module "entry/hit/world.miss.slang";

import "phase/hit/world_miss.slang";

[shader("miss")]
void main()
{
    primePhase();
}

