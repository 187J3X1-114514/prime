import glsl;
import "prime_abi_types.slang";
import "rt/surface_trace_diagnostic.slang";

layout(location = 0) rayPayloadInEXT TracePayload primePayload;

[shader("miss")]
void main()
{
    primePayload.hitKind &= PRIME_TRACE_DIAGNOSTIC_MASK;
}
