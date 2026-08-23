#language slang 2026
module "core/numerical_diagnostics.slang";

// Production transport keeps no diagnostic payload. These no-op hooks preserve the compact
// adapter signatures while the compiler removes their empty state and calls from every entry.
public struct PrimeNumericalDiagnostics {}

public PrimeNumericalDiagnostics primeNumericalDiagnosticsCreate()
{
    return {};
}

public void primeSetNumericalContext(
        inout PrimeNumericalDiagnostics diagnostics, uint stage, uint bounce) {}

public void primeRecordNumerical(
        inout PrimeNumericalDiagnostics diagnostics, uint flags, uint field) {}

public void primeRecordNonFinite(
        inout PrimeNumericalDiagnostics diagnostics, float value) {}

public void primeRecordNonFinite(
        inout PrimeNumericalDiagnostics diagnostics, float3 value) {}

public void primeRecordNonnegative(
        inout PrimeNumericalDiagnostics diagnostics, float value) {}

public void primeRecordNonnegative(
        inout PrimeNumericalDiagnostics diagnostics, float3 value) {}

public void primeRecordRadiance(
        inout PrimeNumericalDiagnostics diagnostics, float3 value) {}

public void primeRecordUnit(
        inout PrimeNumericalDiagnostics diagnostics, float value) {}

public void primeRecordUnit(
        inout PrimeNumericalDiagnostics diagnostics, float3 value) {}

public void primeRecordDirection(
        inout PrimeNumericalDiagnostics diagnostics, float3 value) {}
