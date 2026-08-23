#language slang 2026
module "phase/hit/world_miss.slang";

import glsl;
import "prime_abi_types.slang";
import "contract/path.slang";

layout(location = 0) rayPayloadInEXT TracePayload primePayload;

public void primePhase()
{
    primePayload.hitKind = PRIME_HIT_NONE;
}


