#language slang 2026
module "entry/hit/world.anyhit.slang";

import "phase/hit/world_anyhit.slang";

[shader("anyhit")]
void main(in BuiltInTriangleIntersectionAttributes attributes)
{
    primePhase(attributes);
}

