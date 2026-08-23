#define SHARC_UPDATE 0
#define SHARC_QUERY 1

import "service/material/surface.slang";
#include "service/sharc/vendor_bridge.h"

[shader("compute")]
[numthreads(1, 1, 1)]
void main()
{
    SharcParameters parameters = primeSharcParameters();
    float voxelSize = primeSharcVoxelSize(
            primeSharcFrame.cameraPosition, float3(0.0, 1.0, 0.0));
    if (parameters.hashGridData.capacity == 0)
    {
        ((uint*)primeSharcFrame.resolvedAddress)[0] = asuint(voxelSize);
    }
}
