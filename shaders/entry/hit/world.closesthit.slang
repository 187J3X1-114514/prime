#language slang 2026
module "entry/hit/world.closesthit.slang";

import "phase/hit/world_closesthit.slang";

[shader("closesthit")]
void main(in BuiltInTriangleIntersectionAttributes attributes)
{
    primePhase(attributes);
}

