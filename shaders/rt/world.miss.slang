import glsl;
import "prime_abi_types.slang";
import "integrator/path_contract.slang";

layout(location = 0) rayPayloadInEXT TracePayload primePayload;

[shader("miss")]
void main()
{
    primePayload.hitKind = PRIME_HIT_NONE;
}
