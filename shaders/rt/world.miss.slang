import glsl;

#include "core/common.slang"

layout(location = 0) rayPayloadInEXT TracePayload primePayload;

[shader("miss")]
void main()
{
    primePayload.hitKind = 0;
}
