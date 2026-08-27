#language slang 2026
module "contract/offline/area_request.slang";

public struct PrimeOfflineAreaRequest {
    public float3 direction;
    public float distance;
    public float3 contribution;
    public bool valid;
    public float3 startingExtinction;
    public uint startingMediumCount;
    public uint2 targetEmitter;
};
