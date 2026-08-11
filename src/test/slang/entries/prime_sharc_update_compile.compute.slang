#define SHARC_UPDATE 1
#define SHARC_QUERY 0

#include "core/common.slang"
#include "material/material.slang"
#include "lighting/sharc.slang"

[shader("compute")]
[numthreads(1, 1, 1)]
void main()
{
    SharcState state;
    SharcInit(state);
    SharcSetThroughput(state, float3(1.0));
}
