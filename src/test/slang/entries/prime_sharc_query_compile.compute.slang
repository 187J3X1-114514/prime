#define SHARC_UPDATE 0
#define SHARC_QUERY 1

#include "core/common.slang"
#include "material/material.slang"
#include "lighting/sharc.slang"

[shader("compute")]
[numthreads(1, 1, 1)]
void main()
{
    SharcParameters parameters = primeSharcParameters();
    float voxelSize = primeSharcVoxelSize(
            primeSharcFrame.cameraPosition, float3(0.0, 1.0, 0.0));
    if (parameters.hashGridData.capacity == 0)
    {
        ((uint*)primeSharcFrame.diagnosticsAddress)[0] = asuint(voxelSize);
    }
}
