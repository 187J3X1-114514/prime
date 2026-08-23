#language slang 2026
module "entry/hit/shadow.closesthit.slang";

import "phase/hit/shadow_closesthit.slang";

[shader("closesthit")]
void main(in BuiltInTriangleIntersectionAttributes attributes)
{
    primePhase(attributes);
}

