#language slang 2026
module "contract/realtime/light_event.slang";

public static const uint PRIME_REALTIME_LIGHT_EVENT_NONE = 0u;
public static const uint PRIME_REALTIME_LIGHT_EVENT_GUIDE_DUAL = 1u;
public static const uint PRIME_REALTIME_LIGHT_EVENT_DUAL = 2u;

public struct PrimeRealtimeAreaSampleInput {
    public float3 position;
    public uint receiverNormal;
    public float2 treeSample;
    public float2 positionSample;
};
