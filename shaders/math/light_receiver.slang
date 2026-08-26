#language slang 2026
module "math/light_receiver.slang";

public static const uint PRIME_LIGHT_RECEIVER_NORMAL_ACTIVE = 0x8000u;

public float2 primeLightReceiverSignNotZero(float2 value) {
    return float2(value.x >= 0.0 ? 1.0 : -1.0, value.y >= 0.0 ? 1.0 : -1.0);
}

// Forward selection and reverse MIS both decode this exact quantized normal.
public uint primePackLightReceiverNormal(float3 value) {
    float3 normal = normalize(value);
    normal /= max(abs(normal.x) + abs(normal.y) + abs(normal.z), 1.0e-20);
    if (normal.z < 0.0) {
        normal.xy = (1.0 - abs(normal.yx)) * primeLightReceiverSignNotZero(normal.xy);
    }
    float2 unit = clamp(normal.xy * 0.5 + 0.5, float2(0.0), float2(1.0));
    uint2 quantized = uint2(round(unit * float2(255.0, 127.0)));
    return PRIME_LIGHT_RECEIVER_NORMAL_ACTIVE
            | quantized.x
            | (quantized.y << 8u);
}

public float3 primeUnpackLightReceiverNormal(uint packedValue) {
    if ((packedValue & PRIME_LIGHT_RECEIVER_NORMAL_ACTIVE) == 0u) {
        return float3(0.0);
    }
    float2 encoded = float2(
            float(packedValue & 0xffu) / 255.0,
            float((packedValue >> 8u) & 0x7fu) / 127.0) * 2.0 - 1.0;
    float3 normal = float3(encoded, 1.0 - abs(encoded.x) - abs(encoded.y));
    if (normal.z < 0.0) {
        normal.xy = (1.0 - abs(normal.yx)) * primeLightReceiverSignNotZero(normal.xy);
    }
    return normalize(normal);
}
