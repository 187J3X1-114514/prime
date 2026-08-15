#language slang 2026
module "bsdf/compact/transmission_ggx_energy.slang";

public interface IPrimeOpenPbrTransmissionGgxEnergy {
    public float4 sampleLevel(float3 uvw);
}
