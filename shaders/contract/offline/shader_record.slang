#language slang 2026
module "contract/offline/shader_record.slang";

public struct PrimeOfflineShaderRecord {
    public uint control;
};

[[vk::shader_record]]
public ConstantBuffer<PrimeOfflineShaderRecord> primeOfflineShaderRecord;
