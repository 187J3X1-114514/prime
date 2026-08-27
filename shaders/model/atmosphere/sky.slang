#language slang 2026
module "model/atmosphere/sky.slang";

import "prime_abi_types.slang";

// Prime's atmosphere is an eight-wavelength Hillaire-style fit derived from sky_tracer's
// reference spectral path tracer. Distances in this file are kilometres and all RGB conversion
// ends in scene-linear Rec.2020 D65. These are estimator contracts, not presentation tuning.
public static const float ATM_PI = 3.141592653589793;
public static const float ATM_INV_PI = 0.3183098861837907;
public static const float ATM_INV_4PI = 0.07957747154594767;
public static const float ATM_RAYLEIGH_PHASE_SCALE = 0.05968310365946075;
// World positions and travelled distances enter this unchanged physical atmosphere
// through ATM_WORLD_UNIT_SCALE_KM. The explicit factor documents that coordinate contract.
public static const float ATM_WORLD_TO_ATMOSPHERE_SCALE =
        PRIME_ATMOSPHERE_WORLD_TO_ATMOSPHERE_SCALE;
public static const float ATM_BOTTOM_RADIUS_KM = PRIME_ATMOSPHERE_BOTTOM_RADIUS_KM;
public static const float ATM_TOP_RADIUS_KM = PRIME_ATMOSPHERE_TOP_RADIUS_KM;
public static const float ATM_THICKNESS_KM =
        ATM_TOP_RADIUS_KM - ATM_BOTTOM_RADIUS_KM;
public static const float ATM_WORLD_SEA_LEVEL_Y = PRIME_ATMOSPHERE_WORLD_SEA_LEVEL_Y;
public static const float ATM_WORLD_UNIT_SCALE_KM = PRIME_ATMOSPHERE_WORLD_UNIT_SCALE_KM;
public static const float ATM_PLANET_RADIUS_OFFSET_KM = 0.01;
public static const float ATM_MULTI_ALTITUDE_SCALE_KM = 8.0;
public static const float ATM_SKY_FRACTION = 0.75;
public static const float ATM_GROUND_FRACTION = 0.25;
public static const float ATM_AERIAL_MAX_DISTANCE_KM = PRIME_ATMOSPHERE_AERIAL_MAX_DISTANCE_KM;
public static const float ATM_SPACE_SUN_INTENSITY = PRIME_ATMOSPHERE_SPACE_SUN_INTENSITY;
public static const float ATM_SUN_ANGULAR_RADIUS_RADIANS =
        PRIME_ATMOSPHERE_SUN_ANGULAR_RADIUS_RADIANS;
public static const float ATM_REFERENCE_SUN_INTENSITY = 205.0;

public struct AtmCoefficients {
    public float4 aerosolScattering;
    public float4 molecularScattering;
    public float4 extinction;
};

public struct AtmRaySegment {
    public float topDistanceKm;
    public float groundDistanceKm;
    public float maximumDistanceKm;
    public bool hitsGround;
};

public float atmRaySphere(float3 origin, float3 direction, float radius) {
    float b = dot(origin, direction);
    float c = dot(origin, origin) - radius * radius;
    if (c > 0.0 && b > 0.0) {
        return -1.0;
    }
    float discriminant = b * b - c;
    if (discriminant < 0.0) {
        return -1.0;
    }
    return discriminant > b * b ? -b + sqrt(discriminant) : -b - sqrt(discriminant);
}

// Integral of exp(-extinction * distance) over one homogeneous segment. The Taylor branch
// avoids cancellation at small optical depth without replacing zero extinction by absorption.
public float atmHomogeneousSegmentIntegral(float extinction, float length)
{
    float segmentLength = max(length, 0.0);
    float x = extinction * segmentLength;
    if (x <= 0.05) {
        float factor = 1.0 + x * (-0.5 + x * (1.0 / 6.0
                + x * (-1.0 / 24.0 + x * (1.0 / 120.0))));
        return segmentLength * factor;
    }
    return segmentLength * (1.0 - exp(-x)) / x;
}

public float4 atmHomogeneousSegmentIntegral(float4 extinction, float length)
{
    return float4(
            atmHomogeneousSegmentIntegral(extinction.x, length),
            atmHomogeneousSegmentIntegral(extinction.y, length),
            atmHomogeneousSegmentIntegral(extinction.z, length),
            atmHomogeneousSegmentIntegral(extinction.w, length));
}

public AtmRaySegment atmRaySegment(float3 origin, float3 direction) {
    AtmRaySegment segment;
    segment.topDistanceKm = atmRaySphere(origin, direction, ATM_TOP_RADIUS_KM);
    segment.groundDistanceKm = atmRaySphere(origin, direction, ATM_BOTTOM_RADIUS_KM);
    segment.hitsGround = segment.groundDistanceKm >= 0.0;
    segment.maximumDistanceKm = segment.hitsGround
            ? segment.groundDistanceKm
            : segment.topDistanceKm;
    return segment;
}

public bool atmDistantDirectionVisible(float eyeRadiusKm, float3 direction) {
    float3 horizonPosition = float3(
            0.0, eyeRadiusKm - ATM_PLANET_RADIUS_OFFSET_KM, 0.0);
    return !atmRaySegment(horizonPosition, direction).hitsGround;
}

public float3 atmMoveToTop(float3 position, float3 direction) {
    float radius = length(position);
    if (radius <= ATM_TOP_RADIUS_KM) {
        return position;
    }
    float distance = atmRaySphere(position, direction, ATM_TOP_RADIUS_KM);
    if (distance < 0.0) {
        return position;
    }
    return position + direction * distance
            - position / max(radius, 1.0e-6) * ATM_PLANET_RADIUS_OFFSET_KM;
}

public float4 atmMolecularScattering(int group) {
    return group == 0
            ? float4(0.047815718, 0.03604918, 0.025453128, 0.018479607)
            : float4(0.015890262, 0.01193972, 0.009758583, 0.006705125);
}

public float4 atmOzoneCrossSection(int group) {
    return group == 0
            ? float4(2.91e-27, 1.25e-26, 7.11e-26, 1.78e-25)
            : float4(2.88e-25, 4.55e-25, 4.54e-25, 1.67e-25);
}

public float4 atmAerosolScatteringCrossSection(int group, int species) {
    if (group == 0) {
        if (species == 0) return float4(4612.53191, 4233.58582, 3786.26357, 3386.72611);
        if (species == 1) return float4(160.827371, 163.627446, 167.190338, 170.607065);
        if (species == 2) return float4(3680.43254, 3188.87152, 2652.75577, 2216.54595);
        return float4(4198.99534, 4051.87364, 3854.93395, 3659.96019);
    }
    if (species == 0) return float4(3201.76301, 2884.22958, 2666.95770, 2290.42368);
    if (species == 1) return float4(172.270989, 175.440739, 177.757050, 182.217388);
    if (species == 2) return float4(2027.79536, 1712.43763, 1511.68788, 1194.60801);
    return float4(3563.58585, 3374.33138, 3235.18858, 2972.40832);
}

public float4 atmAerosolAbsorptionCrossSection(int group, int species) {
    if (group == 0) {
        if (species == 0) return float4(90.1089989, 82.7180280, 74.4037326, 72.6561267);
        if (species == 1) return float4(70.7582688, 69.1931924, 67.2250799, 65.3678619);
        if (species == 2) return float4(10315.2120, 9565.49528, 8693.68329, 7901.44943);
        return float4(2.15273820e-4, 1.28197361e-4, 8.97120583e-5, 1.13036139e-4);
    }
    if (species == 0) return float4(74.2391720, 70.2021686, 67.9849946, 67.9558970);
    if (species == 1) return float4(64.4747043, 62.7839451, 61.5691712, 59.3296101);
    if (species == 2) return float4(7524.53535, 6899.54758, 6492.91243, 5828.49178);
    return float4(1.41520976e-4, 3.04512124e-4, 4.24030002e-4, 6.10137194e-4);
}

public float atmAerosolBaseDensity(int species) {
    if (species == 0) return 1.49e-5;
    if (species == 1) return 1.01e-5;
    if (species == 2) return 5.31e-7;
    return 0.0;
}

public float atmAerosolBackgroundDensity(int species) {
    if (species == 0) return 4.571e-7;
    if (species == 1) return 2.291e-6;
    if (species == 2) return 1.362e-8;
    return 5.312e-7;
}

public float atmAerosolDensity(int species, float altitudeKm) {
    float altitude = max(altitudeKm, 0.0);
    float falloff = exp(-altitude / 8.0);
    return lerp(
            atmAerosolBaseDensity(species) * falloff,
            atmAerosolBackgroundDensity(species) * falloff,
            smoothstep(1.0, 2.0, altitude));
}

public AtmCoefficients atmCoefficients(int group, float altitudeKm) {
    float altitude = max(altitudeKm, 0.0);
    float4 aerosolScattering = float4(0.0);
    float4 aerosolAbsorption = float4(0.0);
    for (int species = 0; species < 4; ++species) {
        float density = atmAerosolDensity(species, altitude);
        aerosolScattering += atmAerosolScatteringCrossSection(group, species) * density;
        aerosolAbsorption += atmAerosolAbsorptionCrossSection(group, species) * density;
    }
    float4 molecularScattering = atmMolecularScattering(group)
            * exp(-0.07771971 * pow(altitude, 1.16364243));
    float ozoneHeight = altitude + 1.0e-4;
    float ozoneT = log(ozoneHeight) - 3.22261;
    float ozoneDensity = 3.78547397e20 / ozoneHeight * exp(-ozoneT * ozoneT * 5.55555555);
    float4 molecularAbsorption =
            atmOzoneCrossSection(group) * 381.0 * ozoneDensity;
    AtmCoefficients result;
    result.aerosolScattering = aerosolScattering;
    result.molecularScattering = molecularScattering;
    result.extinction = aerosolScattering + aerosolAbsorption
            + molecularScattering + molecularAbsorption;
    return result;
}

public float4 atmSolarSpectrum(int group) {
    float4 reference = group == 0
            ? float4(1.7477345, 1.7629014, 2.0566432, 1.8146111)
            : float4(1.8847016, 1.8567868, 1.7294269, 1.5365043);
    // The fitted spectra originally reconstruct a 205-unit extraterrestrial sun. Scaling the
    // spectra here keeps sky radiance and Prime's explicit 12.5-unit sun on one energy scale.
    return reference * (ATM_SPACE_SUN_INTENSITY / ATM_REFERENCE_SUN_INTENSITY);
}

public float atmRayleighPhase(float cosine) {
    return ATM_RAYLEIGH_PHASE_SCALE * (1.0 + cosine * cosine);
}

public float2 atmTransmittanceUv(float cosine, float normalizedAltitude) {
    float radius = ATM_BOTTOM_RADIUS_KM
            + clamp(normalizedAltitude, 0.0, 1.0) * ATM_THICKNESS_KM;
    float mu = clamp(cosine, -1.0, 1.0);
    float h = sqrt(max(ATM_TOP_RADIUS_KM * ATM_TOP_RADIUS_KM
            - ATM_BOTTOM_RADIUS_KM * ATM_BOTTOM_RADIUS_KM, 0.0));
    float rho = sqrt(max(radius * radius - ATM_BOTTOM_RADIUS_KM * ATM_BOTTOM_RADIUS_KM, 0.0));
    float discriminant = radius * radius * (mu * mu - 1.0)
            + ATM_TOP_RADIUS_KM * ATM_TOP_RADIUS_KM;
    float distance = max(0.0, -radius * mu + sqrt(max(discriminant, 0.0)));
    float minimumDistance = ATM_TOP_RADIUS_KM - radius;
    float maximumDistance = rho + h;
    return float2(
            clamp((distance - minimumDistance)
                    / max(maximumDistance - minimumDistance, 1.0e-6), 0.0, 1.0),
            clamp(rho / max(h, 1.0e-6), 0.0, 1.0));
}

public float atmFromUnitToSubUv(float value, float resolution) {
    return (value + 0.5 / resolution) * (resolution / (resolution + 1.0));
}

public float atmFromSubUvToUnit(float value, float resolution) {
    return (value - 0.5 / resolution) * (resolution / (resolution - 1.0));
}

public float atmMultiSunMuFromU(float u) {
    float x = clamp(u, 0.0, 1.0) * 2.0 - 1.0;
    return x * abs(x);
}

public float atmMultiUFromSunMu(float sunMu) {
    float mu = clamp(sunMu, -1.0, 1.0);
    float x = sign(mu) * sqrt(abs(mu));
    return clamp(x * 0.5 + 0.5, 0.0, 1.0);
}

public float atmMultiNormalizedAltitudeFromV(float v) {
    float minimumAltitude = ATM_PLANET_RADIUS_OFFSET_KM;
    float maximumAltitude =
            ATM_THICKNESS_KM - ATM_PLANET_RADIUS_OFFSET_KM;
    float range = maximumAltitude - minimumAltitude;
    float cdfMaximum = max(1.0 - exp(-range / ATM_MULTI_ALTITUDE_SCALE_KM), 1.0e-6);
    float cdf = clamp(v, 0.0, 1.0) * cdfMaximum;
    float altitude = minimumAltitude
            - ATM_MULTI_ALTITUDE_SCALE_KM * log(max(1.0 - cdf, 1.0e-6));
    return clamp(altitude / ATM_THICKNESS_KM, 0.0, 1.0);
}

public float atmMultiVFromNormalizedAltitude(float normalizedAltitude) {
    float minimumAltitude = ATM_PLANET_RADIUS_OFFSET_KM;
    float maximumAltitude =
            ATM_THICKNESS_KM - ATM_PLANET_RADIUS_OFFSET_KM;
    float range = maximumAltitude - minimumAltitude;
    float altitude = clamp(normalizedAltitude, 0.0, 1.0) * ATM_THICKNESS_KM;
    float h = clamp(altitude, minimumAltitude, maximumAltitude) - minimumAltitude;
    float cdfMaximum = max(1.0 - exp(-range / ATM_MULTI_ALTITUDE_SCALE_KM), 1.0e-6);
    return clamp((1.0 - exp(-h / ATM_MULTI_ALTITUDE_SCALE_KM)) / cdfMaximum, 0.0, 1.0);
}

public float2 atmMultiUv(float sunMu, float normalizedAltitude, float2 dimensions) {
    float2 unitUv = float2(atmMultiUFromSunMu(sunMu),
            atmMultiVFromNormalizedAltitude(normalizedAltitude));
    return float2(atmFromUnitToSubUv(unitUv.x, dimensions.x),
            atmFromUnitToSubUv(unitUv.y, dimensions.y));
}

public float3 atmLinearRec2020FromSpectral(float4 low, float4 high) {
    float3x4 lowMatrix = float3x4(
            float4(2.015521915, 3.069387571, -5.484677337, -3.419542345),
            float4(-2.20749785, -3.495544704, 10.446526205, 23.113484232),
            float4(18.203864142, 34.463233423, 45.783739329, 0.928681094));
    float3x4 highMatrix = float3x4(
            float4(4.243623958, 55.000002375, 47.02149692, 15.768316051),
            float4(37.153565117, 34.63775886, 4.411417716, -0.743849551),
            float4(-0.456028883, -0.84876689, -0.106950912, 0.019681577));
    float3 rgb = mul(lowMatrix, low) + mul(highMatrix, high);
    float3x3 whiteBalance = float3x3(
            float3(0.973450179, -0.0199533690, 0.000904216012),
            float3(-0.00110537346, 1.01471724, -0.000617879953),
            float3(0.000549268697, -0.000413338668, 1.06404848));
    return mul(whiteBalance, rgb);
}

public float3 atmRec2020Transmittance(float4 low, float4 high) {
    float4 clearLow = atmSolarSpectrum(0);
    float4 clearHigh = atmSolarSpectrum(1);
    float3 clear = max(atmLinearRec2020FromSpectral(clearLow, clearHigh), float3(1.0e-6));
    float3 attenuated = max(atmLinearRec2020FromSpectral(clearLow * low, clearHigh * high), float3(0.0));
    return clamp(attenuated / clear, float3(0.0), float3(1.0));
}

public float2 atmSkyHorizonAngles(float viewRadiusKm) {
    float horizonDistance = sqrt(max(viewRadiusKm * viewRadiusKm
            - ATM_BOTTOM_RADIUS_KM * ATM_BOTTOM_RADIUS_KM, 0.0));
    float beta = acos(clamp(horizonDistance / max(viewRadiusKm, 1.0e-6), 0.0, 1.0));
    return float2(ATM_PI - beta, beta);
}

public float2 atmSkyUvToParameters(float2 uvIn, float2 dimensions, float viewRadiusKm) {
    float2 uv = clamp(float2(
            atmFromSubUvToUnit(uvIn.x, dimensions.x),
            atmFromSubUvToUnit(uvIn.y, dimensions.y)), 0.0, 1.0);
    float2 horizon = atmSkyHorizonAngles(viewRadiusKm);
    float viewZenithCosine;
    if (uv.y < ATM_SKY_FRACTION) {
        float coordinate = 1.0 - uv.y / ATM_SKY_FRACTION;
        coordinate = 1.0 - coordinate * coordinate;
        viewZenithCosine = cos(horizon.x * coordinate);
    } else {
        float coordinate = (uv.y - ATM_SKY_FRACTION) / ATM_GROUND_FRACTION;
        viewZenithCosine = cos(horizon.x + horizon.y * coordinate * coordinate);
    }
    float horizontalCosine = -(uv.x * uv.x * 2.0 - 1.0);
    return float2(viewZenithCosine, horizontalCosine);
}

public float3 atmSkyDirection(float2 parameters, float3 sunDirection) {
    float3 up = float3(0.0, 1.0, 0.0);
    float3 sunHorizontal = sunDirection - up * dot(sunDirection, up);
    float sunHorizontalLength = length(sunHorizontal);
    float3 forward = sunHorizontalLength > 1.0e-5
            ? sunHorizontal / sunHorizontalLength
            : float3(1.0, 0.0, 0.0);
    float3 side = normalize(cross(up, forward));
    float viewZenithCosine = clamp(parameters.x, -1.0, 1.0);
    float horizontalCosine = clamp(parameters.y, -1.0, 1.0);
    float viewSine = sqrt(max(1.0 - viewZenithCosine * viewZenithCosine, 0.0));
    float sideScale = sqrt(max(1.0 - horizontalCosine * horizontalCosine, 0.0));
    return normalize(up * viewZenithCosine
            + viewSine * (forward * horizontalCosine + side * sideScale));
}

public float2 atmSkyUvFromDirection(
        float3 directionIn,
        float3 sunDirection,
        float2 dimensions,
        float viewRadiusKm) {
    float3 direction = normalize(directionIn);
    float3 up = float3(0.0, 1.0, 0.0);
    float viewZenithCosine = dot(direction, up);
    float3 viewHorizontal = direction - up * viewZenithCosine;
    float3 sunHorizontal = sunDirection - up * dot(sunDirection, up);
    float denominator = length(viewHorizontal) * length(sunHorizontal);
    float horizontalCosine = denominator > 1.0e-6
            ? clamp(dot(viewHorizontal, sunHorizontal) / denominator, -1.0, 1.0)
            : 1.0;
    bool hitsGround = atmRaySphere(float3(0.0, viewRadiusKm, 0.0), direction,
            ATM_BOTTOM_RADIUS_KM) >= 0.0;
    float2 horizon = atmSkyHorizonAngles(viewRadiusKm);
    float viewAngle = acos(clamp(viewZenithCosine, -1.0, 1.0));
    float v;
    if (!hitsGround) {
        float coordinate = clamp(viewAngle / max(horizon.x, 1.0e-6), 0.0, 1.0);
        coordinate = 1.0 - sqrt(max(1.0 - coordinate, 0.0));
        v = coordinate * ATM_SKY_FRACTION;
    } else {
        float coordinate = clamp((viewAngle - horizon.x) / max(horizon.y, 1.0e-6), 0.0, 1.0);
        v = sqrt(coordinate) * ATM_GROUND_FRACTION + ATM_SKY_FRACTION;
    }
    float u = sqrt(clamp(-horizontalCosine * 0.5 + 0.5, 0.0, 1.0));
    return float2(atmFromUnitToSubUv(u, dimensions.x),
            atmFromUnitToSubUv(v, dimensions.y));
}

public float4 atmSample2D(readonly RWTexture2D<float4> source, float2 uv) {
    uint width;
    uint height;
    source.GetDimensions(width, height);
    int2 dimensions = int2(width, height);
    float2 coordinate = clamp(uv, float2(0.0), float2(1.0))
            * float2(dimensions) - float2(0.5);
    int2 lower = int2(floor(coordinate));
    float2 fraction = frac(coordinate);
    int2 maximum = dimensions - int2(1);
    int2 p00 = clamp(lower, int2(0), maximum);
    int2 p10 = clamp(lower + int2(1, 0), int2(0), maximum);
    int2 p01 = clamp(lower + int2(0, 1), int2(0), maximum);
    int2 p11 = clamp(lower + int2(1), int2(0), maximum);
    float4 row0 = lerp(source[p00], source[p10], fraction.x);
    float4 row1 = lerp(source[p01], source[p11], fraction.x);
    return lerp(row0, row1, fraction.y);
}

public float4 atmSample3D(readonly RWTexture3D<float4> source, float3 uvw) {
    uint width;
    uint height;
    uint depth;
    source.GetDimensions(width, height, depth);
    int3 dimensions = int3(width, height, depth);
    float3 coordinate = clamp(uvw, float3(0.0), float3(1.0))
            * float3(dimensions) - float3(0.5);
    int3 lower = int3(floor(coordinate));
    float3 fraction = frac(coordinate);
    int3 maximum = dimensions - int3(1);
    int3 p000 = clamp(lower, int3(0), maximum);
    int3 p100 = clamp(lower + int3(1, 0, 0), int3(0), maximum);
    int3 p010 = clamp(lower + int3(0, 1, 0), int3(0), maximum);
    int3 p110 = clamp(lower + int3(1, 1, 0), int3(0), maximum);
    int3 p001 = clamp(lower + int3(0, 0, 1), int3(0), maximum);
    int3 p101 = clamp(lower + int3(1, 0, 1), int3(0), maximum);
    int3 p011 = clamp(lower + int3(0, 1, 1), int3(0), maximum);
    int3 p111 = clamp(lower + int3(1), int3(0), maximum);
    float4 z0y0 = lerp(source[p000], source[p100], fraction.x);
    float4 z0y1 = lerp(source[p010], source[p110], fraction.x);
    float4 z1y0 = lerp(source[p001], source[p101], fraction.x);
    float4 z1y1 = lerp(source[p011], source[p111], fraction.x);
    return lerp(
            lerp(z0y0, z0y1, fraction.y),
            lerp(z1y0, z1y1, fraction.y),
            fraction.z);
}
