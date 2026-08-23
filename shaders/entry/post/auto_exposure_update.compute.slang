#language slang 2026
module "entry/post/auto_exposure_update.compute.slang";

import "phase/post/auto_exposure_update.slang";

[numthreads(1, 1, 1)]
void main()
{
    primeAutoExposureUpdate();
}

