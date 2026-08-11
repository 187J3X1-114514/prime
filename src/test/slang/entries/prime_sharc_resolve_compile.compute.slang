#define SHARC_UPDATE 0
#define SHARC_QUERY 0

#include "core/common.slang"
#include "material/material.slang"
#include "lighting/sharc.slang"

[shader("compute")]
[numthreads(1, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    SharcResolveParameters resolve;
    resolve.cameraPositionPrev = primeSharcFrame.previousCameraPosition;
    resolve.accumulationFrameNum = primeSharcFrame.accumulationFrames;
    resolve.responsiveFrameNum = 1u;
    resolve.staleFrameNumMax = primeSharcFrame.staleFrames;
    resolve.frameIndex = primeSharcFrame.frameIndex;
    SharcResolveEntry(dispatchThreadId.x, primeSharcParameters(), resolve);
}
