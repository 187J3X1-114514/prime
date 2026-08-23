#language slang 2026
module "contract/realtime/ordinary.slang";

import "contract/realtime/guide.slang";
import "contract/realtime/radiance.slang";

public struct PrimeOrdinarySample {
    public PrimePathRadiance radiance;
    public PrimeDenoiserGuides guides;
};
