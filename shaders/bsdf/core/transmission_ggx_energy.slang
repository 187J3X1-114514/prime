#language slang 2026
module "bsdf/core/transmission_ggx_energy.slang";

public interface IPrimeRcTransmissionGgxEnergy {
    public float4 sampleLevel(float3 uvw);
}
