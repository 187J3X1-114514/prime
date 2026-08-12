#include "realtime/realtime_wavefront.slang"
#include "realtime/realtime_wavefront_entry.slang"

[shader("raygeneration")]
void main() { primeRealtimeWavefrontTransparentShadeEntry(); }
