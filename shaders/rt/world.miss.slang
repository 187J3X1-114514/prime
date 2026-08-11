import glsl;
import "prime_abi_types.slang";

layout(location = 0) rayPayloadInEXT TracePayload primePayload;

[shader("miss")]
void main()
{
    primePayload.hitKind = 0;
}
