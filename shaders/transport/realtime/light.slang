#language slang 2026
module "transport/realtime/light.slang";

import "prime_realtime_abi.slang";
import "prime_abi.slang";
import "prime_abi_types.slang";
import "model/atmosphere/sky.slang";
import "math/sampling.slang";
import "math/transport.slang";
import "service/light/sample.slang";
import "transport/direct/selection.slang";
import "contract/path.slang";
import "policy/transport/light_family.slang";

public static const int PRIME_REALTIME_SUN_SHADOW_RESOLUTION = 512;
public static const int PRIME_REALTIME_SUN_SHADOW_CASCADE_COUNT = 5;
public static const float PRIME_REALTIME_SUN_SHADOW_SAFE_RADIUS_TEXELS = 254.0;
public static const float PRIME_REALTIME_SUN_SHADOW_DEPTH_BIAS = 0.02;
public static const float PRIME_REALTIME_SUN_SHADOW_OCCLUDED_IMPORTANCE = 0.25;

public float primeRealtimeSunShadowDepth(int imageIndex, int2 coordinate) {
    if (imageIndex == 0) return primeSunShadowDepth0.Load(coordinate);
    if (imageIndex == 1) return primeSunShadowDepth1.Load(coordinate);
    if (imageIndex == 2) return primeSunShadowDepth2.Load(coordinate);
    if (imageIndex == 3) return primeSunShadowDepth3.Load(coordinate);
    if (imageIndex == 4) return primeSunShadowDepth4.Load(coordinate);
    if (imageIndex == 5) return primeSunShadowDepth5.Load(coordinate);
    if (imageIndex == 6) return primeSunShadowDepth6.Load(coordinate);
    if (imageIndex == 7) return primeSunShadowDepth7.Load(coordinate);
    if (imageIndex == 8) return primeSunShadowDepth8.Load(coordinate);
    return primeSunShadowDepth9.Load(coordinate);
}

public float primeRealtimeSunShadowTexelSize(int cascade) {
    return 0.5 * float(1 << cascade);
}

public int primeRealtimeSunShadowCascade(float3 position) {
    float3 relative = position - primePush.cameraPosition;
    float projectedDistance = max(
            abs(dot(relative, primeSunShadowQuery.basisU)),
            abs(dot(relative, primeSunShadowQuery.basisV)));
    for (int cascade = 0;
            cascade < PRIME_REALTIME_SUN_SHADOW_CASCADE_COUNT;
            cascade++) {
        if (projectedDistance
                <= PRIME_REALTIME_SUN_SHADOW_SAFE_RADIUS_TEXELS
                        * primeRealtimeSunShadowTexelSize(cascade)) {
            return cascade;
        }
    }
    return -1;
}

// This is an importance hint, not a visibility result. One raw texel is deliberately soft so
// clipmap lag, sub-texel blockers, and the finite sun disk cannot remove either light proposal.
public float primeRealtimeSunShadowImportance(float3 position) {
    if (primeSunShadowQuery.valid == 0u
            || dot(primeSunShadowQuery.directionToSun, primePush.sunDirection)
                    < cos(ATM_SUN_ANGULAR_RADIUS_RADIANS)) {
        return 1.0;
    }
    int cascade = primeRealtimeSunShadowCascade(position);
    if (cascade < 0) {
        return 1.0;
    }
    float texelSize = primeRealtimeSunShadowTexelSize(cascade);
    float2 projected = float2(
            dot(position, primeSunShadowQuery.basisU),
            dot(position, primeSunShadowQuery.basisV));
    int2 coordinate = int2(floor(projected / texelSize))
            & (PRIME_REALTIME_SUN_SHADOW_RESOLUTION - 1);
    int imageIndex = int(primeSunShadowQuery.bank)
            * PRIME_REALTIME_SUN_SHADOW_CASCADE_COUNT
            + cascade;
    float blockerDepth = primeRealtimeSunShadowDepth(imageIndex, coordinate);
    float pointDepth = dot(position, primeSunShadowQuery.directionToSun);
    return pointDepth + PRIME_REALTIME_SUN_SHADOW_DEPTH_BIAS >= blockerDepth
            ? 1.0
            : PRIME_REALTIME_SUN_SHADOW_OCCLUDED_IMPORTANCE;
}

public bool primeSecondarySamplesSunLight(
        PrimePreparedSampleBase preparedSample, float sunProbability) {
    return primeSobolSample1D(
            preparedSample,
            PRIME_SAMPLE_EFFECT_DIRECT_LIGHT_FAMILY,
            PRIME_SAMPLE_DIMENSION_PRIMARY)
            < sunProbability;
}

public float primeMixedWavefrontSunProbability(
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        float3 viewDirection) {
    float2 importance = primeSecondaryLightFamilyImportance(
            integrator, surface, viewDirection);
    float probability = primeQuantizedSunFamilyProbability(
            importance.x, importance.y);
    float minimumProbability =
            float(PRIME_SUN_FAMILY_MINIMUM_MIXED_CODE)
            / float(PRIME_SUN_FAMILY_PROBABILITY_STEPS);
    if (importance.x > 0.0
            && importance.y > 0.0
            && probability > minimumProbability) {
        importance.x *= primeRealtimeSunShadowImportance(surface.position);
        probability = primeQuantizedSunFamilyProbability(
                importance.x, importance.y);
    }
    return probability;
}
