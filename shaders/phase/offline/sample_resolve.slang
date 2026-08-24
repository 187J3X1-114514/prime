#language slang 2026
module "phase/offline/sample_resolve.slang";

import "service/camera/projection.slang";
import "service/reconstruct/offline.slang";
import "state/offline/contract.slang";
import "state/offline/index.slang";

public void primePhase() {
    uint2 pixel = DispatchRaysIndex().xy;
    primeWriteOfflineOutput(
            pixel,
            primeOfflineCameraSample(pixel),
            primeOfflinePaths[primeOfflineIndex(pixel)]);
}
