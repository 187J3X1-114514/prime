#define SHARC_UPDATE 1
#define SHARC_QUERY 0

import "service/material/surface.slang";
#include "service/sharc/vendor_bridge.h"

[shader("compute")]
[numthreads(1, 1, 1)]
void main()
{
    SharcState state;
    SharcInit(state);
    SharcSetThroughput(state, float3(1.0));
}
