#language slang 2026
module "core/numerical_diagnostics.slang";

import "core/numerical.slang";
import "core/render_config.slang";

public struct PrimeNumericalDiagnostics
{
    public uint flags;
    public uint context;
    public uint firstContext;
};

public PrimeNumericalDiagnostics primeNumericalDiagnosticsCreate()
{
    PrimeNumericalDiagnostics result;
    result.flags = 0;
    result.context = 0;
    result.firstContext = 0;
    return result;
}

public void primeSetNumericalContext(
        inout PrimeNumericalDiagnostics diagnostics, uint stage, uint bounce)
{
    if (primeWritesRawNumericalDiagnostic()) {
        diagnostics.context = (stage & 0x0f) | (min(bounce, 255) << 4);
    }
}

public void primeRecordNumerical(
        inout PrimeNumericalDiagnostics diagnostics, uint flags, uint field)
{
    if (flags == 0) return;
    if (diagnostics.firstContext == 0) {
        diagnostics.firstContext =
                1 + diagnostics.context + ((field & 0x0f) << 12);
    }
    diagnostics.flags |= flags;
}

public float4 primeRawNumericalMetadata(PrimeNumericalDiagnostics diagnostics)
{
    if (diagnostics.flags == 0) return float4(0.0);
    uint context = diagnostics.firstContext - 1;
    return float4(
            float(diagnostics.flags),
            float(context & 0x0f),
            float((context >> 4) & 0xff),
            float((context >> 12) & 0x0f));
}

public void primeRecordNonFinite(
        inout PrimeNumericalDiagnostics diagnostics, float value)
{
    if (primeWritesRawNumericalDiagnostic()) {
        primeRecordNumerical(
                diagnostics,
                primeClassifyNonFinite(value),
                PRIME_NUMERICAL_FIELD_NON_FINITE);
    }
}

public void primeRecordNonFinite(
        inout PrimeNumericalDiagnostics diagnostics, float3 value)
{
    if (primeWritesRawNumericalDiagnostic()) {
        primeRecordNumerical(
                diagnostics,
                primeClassifyNonFinite(value),
                PRIME_NUMERICAL_FIELD_NON_FINITE);
    }
}

public void primeRecordNonnegative(
        inout PrimeNumericalDiagnostics diagnostics, float value)
{
    if (primeWritesRawNumericalDiagnostic()) {
        primeRecordNumerical(
                diagnostics,
                primeClassifyNonnegative(value),
                PRIME_NUMERICAL_FIELD_NONNEGATIVE);
    }
}

public void primeRecordNonnegative(
        inout PrimeNumericalDiagnostics diagnostics, float3 value)
{
    if (primeWritesRawNumericalDiagnostic()) {
        primeRecordNumerical(
                diagnostics,
                primeClassifyNonnegative(value),
                PRIME_NUMERICAL_FIELD_NONNEGATIVE);
    }
}

public void primeRecordRadiance(
        inout PrimeNumericalDiagnostics diagnostics, float3 value)
{
    if (primeWritesRawNumericalDiagnostic()) {
        primeRecordNumerical(
                diagnostics,
                primeClassifyRadiance(value),
                PRIME_NUMERICAL_FIELD_RADIANCE);
    }
}

public void primeRecordUnit(
        inout PrimeNumericalDiagnostics diagnostics, float value)
{
    if (primeWritesRawNumericalDiagnostic()) {
        primeRecordNumerical(
                diagnostics,
                primeClassifyUnit(value),
                PRIME_NUMERICAL_FIELD_UNIT);
    }
}

public void primeRecordUnit(
        inout PrimeNumericalDiagnostics diagnostics, float3 value)
{
    if (primeWritesRawNumericalDiagnostic()) {
        primeRecordNumerical(
                diagnostics,
                primeClassifyUnit(value),
                PRIME_NUMERICAL_FIELD_UNIT);
    }
}

public void primeRecordDirection(
        inout PrimeNumericalDiagnostics diagnostics, float3 value)
{
    if (primeWritesRawNumericalDiagnostic()) {
        primeRecordNumerical(
                diagnostics,
                primeClassifyDirection(value),
                PRIME_NUMERICAL_FIELD_DIRECTION);
    }
}
