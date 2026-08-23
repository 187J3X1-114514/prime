#define SHARC_UPDATE 0
#define SHARC_QUERY 0

#include "service/sharc/vendor_bridge.h"

[shader("compute")]
public void primePhase(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    SharcResolveParameters resolve;
    resolve.cameraPositionPrev = primeSharcFrame.previousCameraPosition;
    resolve.accumulationFrameNum = primeSharcFrame.accumulationFrames;
    resolve.responsiveFrameNum = 1u;
    resolve.staleFrameNumMax = primeSharcFrame.staleFrames;
    resolve.frameIndex = primeSharcFrame.frameIndex;
    SharcResolveEntry(
            dispatchThreadId.x,
            primeSharcParameters(),
            resolve);
}
