#language slang 2026
module "contract/realtime/light_event.slang";

public static const uint PRIME_TRANSPARENT_LIGHT_EVENT_NONE = 0u;
public static const uint PRIME_TRANSPARENT_LIGHT_EVENT_PRIMARY = 1u;
public static const uint PRIME_TRANSPARENT_LIGHT_EVENT_SUN = 2u;
public static const uint PRIME_TRANSPARENT_LIGHT_EVENT_AREA = 3u;

public struct PrimeTransparentAreaSampleInput {
    public float3 position;
    public uint receiverNormal;
    public float2 treeSample;
    public float2 positionSample;
};
