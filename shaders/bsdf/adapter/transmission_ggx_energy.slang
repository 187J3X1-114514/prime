#language slang 2026
module "bsdf/adapter/transmission_ggx_energy.slang";

import "prime_abi_bindings.slang";
import "bsdf/core/transmission_ggx_energy.slang";

[[vk::binding(PRIME_TRANSMISSION_GGX_ENERGY_BINDING, 0)]]
Sampler3D<float4> primeRcTransmissionGgxEnergy;

public struct PrimeTransmissionGgxEnergy : IPrimeRcTransmissionGgxEnergy {
    public float4 sampleLevel(float3 uvw) {
        return primeRcTransmissionGgxEnergy.SampleLevel(uvw, 0.0);
    }
};

public PrimeTransmissionGgxEnergy primeTransmissionGgxEnergy() {
    return PrimeTransmissionGgxEnergy();
}

public float4 primeLoadTransmissionGgxEnergyTexel(uint3 coordinate) {
    return primeRcTransmissionGgxEnergy.Load(int4(coordinate, 0));
}
