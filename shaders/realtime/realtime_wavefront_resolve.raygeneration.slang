#include "realtime/realtime_wavefront_resolve.slang"

[shader("raygeneration")]
void main()
{
    uint2 launchIndex = DispatchRaysIndex().xy;
    PrimeNumericalDiagnostics diagnostics = primeNumericalDiagnosticsCreate();
    primeWavefrontResolve(diagnostics, launchIndex);
}
