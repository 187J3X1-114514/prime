#include "offline/offline_wavefront.slang"
#include "offline/offline_wavefront_entry.slang"

[shader("raygeneration")]
void main() { primeOfflineWavefrontStepEntry(); }
