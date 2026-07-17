#ifndef PRIME_ATMOSPHERE_GLSL
#define PRIME_ATMOSPHERE_GLSL

#extension GL_GOOGLE_include_directive : require
#include "prime_atmosphere_contract.glsl"

// Prime's atmosphere is an eight-wavelength Hillaire-style fit derived from sky_tracer's
// reference spectral path tracer. Distances in this file are kilometres and all RGB conversion
// ends in scene-linear Rec.2020 D65. These are estimator contracts, not presentation tuning.
const float ATM_PI = 3.141592653589793;
const float ATM_INV_PI = 0.3183098861837907;
const float ATM_INV_4PI = 0.07957747154594767;
const float ATM_RAYLEIGH_PHASE_SCALE = 0.05968310365946075;
const float ATM_BOTTOM_RADIUS_KM = PRIME_ATMOSPHERE_BOTTOM_RADIUS_KM;
const float ATM_TOP_RADIUS_KM = PRIME_ATMOSPHERE_TOP_RADIUS_KM;
const float ATM_THICKNESS_KM = 100.0;
const float ATM_WORLD_SEA_LEVEL_Y = PRIME_ATMOSPHERE_WORLD_SEA_LEVEL_Y;
const float ATM_WORLD_UNIT_SCALE_KM = PRIME_ATMOSPHERE_WORLD_UNIT_SCALE_KM;
const float ATM_PLANET_RADIUS_OFFSET_KM = 0.01;
const float ATM_MULTI_ALTITUDE_SCALE_KM = 8.0;
const float ATM_SKY_FRACTION = 0.75;
const float ATM_GROUND_FRACTION = 0.25;
const float ATM_AERIAL_MAX_DISTANCE_KM = PRIME_ATMOSPHERE_AERIAL_MAX_DISTANCE_KM;
const float ATM_SPACE_SUN_INTENSITY = PRIME_ATMOSPHERE_SPACE_SUN_INTENSITY;
const float ATM_SUN_ANGULAR_RADIUS_RADIANS =
        PRIME_ATMOSPHERE_SUN_ANGULAR_RADIUS_RADIANS;
const float ATM_REFERENCE_SUN_INTENSITY = 205.0;

struct AtmCoefficients {
    vec4 aerosolScattering;
    vec4 molecularScattering;
    vec4 extinction;
};

struct AtmRaySegment {
    float topDistanceKm;
    float groundDistanceKm;
    float maximumDistanceKm;
    bool hitsGround;
};

float atmRaySphere(vec3 origin, vec3 direction, float radius) {
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

AtmRaySegment atmRaySegment(vec3 origin, vec3 direction) {
    AtmRaySegment segment;
    segment.topDistanceKm = atmRaySphere(origin, direction, ATM_TOP_RADIUS_KM);
    segment.groundDistanceKm = atmRaySphere(origin, direction, ATM_BOTTOM_RADIUS_KM);
    segment.hitsGround = segment.groundDistanceKm >= 0.0;
    segment.maximumDistanceKm = segment.hitsGround
            ? segment.groundDistanceKm
            : segment.topDistanceKm;
    return segment;
}

vec3 atmMoveToTop(vec3 position, vec3 direction) {
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

vec4 atmMolecularScattering(int group) {
    return group == 0
            ? vec4(0.047815718, 0.03604918, 0.025453128, 0.018479607)
            : vec4(0.015890262, 0.01193972, 0.009758583, 0.006705125);
}

vec4 atmOzoneCrossSection(int group) {
    return group == 0
            ? vec4(2.91e-27, 1.25e-26, 7.11e-26, 1.78e-25)
            : vec4(2.88e-25, 4.55e-25, 4.54e-25, 1.67e-25);
}

vec4 atmAerosolScatteringCrossSection(int group, int species) {
    if (group == 0) {
        if (species == 0) return vec4(4612.53191, 4233.58582, 3786.26357, 3386.72611);
        if (species == 1) return vec4(160.827371, 163.627446, 167.190338, 170.607065);
        if (species == 2) return vec4(3680.43254, 3188.87152, 2652.75577, 2216.54595);
        return vec4(4198.99534, 4051.87364, 3854.93395, 3659.96019);
    }
    if (species == 0) return vec4(3201.76301, 2884.22958, 2666.95770, 2290.42368);
    if (species == 1) return vec4(172.270989, 175.440739, 177.757050, 182.217388);
    if (species == 2) return vec4(2027.79536, 1712.43763, 1511.68788, 1194.60801);
    return vec4(3563.58585, 3374.33138, 3235.18858, 2972.40832);
}

vec4 atmAerosolAbsorptionCrossSection(int group, int species) {
    if (group == 0) {
        if (species == 0) return vec4(90.1089989, 82.7180280, 74.4037326, 72.6561267);
        if (species == 1) return vec4(70.7582688, 69.1931924, 67.2250799, 65.3678619);
        if (species == 2) return vec4(10315.2120, 9565.49528, 8693.68329, 7901.44943);
        return vec4(2.15273820e-4, 1.28197361e-4, 8.97120583e-5, 1.13036139e-4);
    }
    if (species == 0) return vec4(74.2391720, 70.2021686, 67.9849946, 67.9558970);
    if (species == 1) return vec4(64.4747043, 62.7839451, 61.5691712, 59.3296101);
    if (species == 2) return vec4(7524.53535, 6899.54758, 6492.91243, 5828.49178);
    return vec4(1.41520976e-4, 3.04512124e-4, 4.24030002e-4, 6.10137194e-4);
}

float atmAerosolBaseDensity(int species) {
    if (species == 0) return 1.49e-5;
    if (species == 1) return 1.01e-5;
    if (species == 2) return 5.31e-7;
    return 0.0;
}

float atmAerosolBackgroundDensity(int species) {
    if (species == 0) return 4.571e-7;
    if (species == 1) return 2.291e-6;
    if (species == 2) return 1.362e-8;
    return 5.312e-7;
}

float atmAerosolDensity(int species, float altitudeKm) {
    float falloff = exp(-max(altitudeKm, 0.0) / 8.0);
    return mix(
            atmAerosolBaseDensity(species) * falloff,
            atmAerosolBackgroundDensity(species) * falloff,
            smoothstep(1.0, 2.0, altitudeKm));
}

AtmCoefficients atmCoefficients(int group, float altitudeKm) {
    float altitude = max(altitudeKm, 0.0);
    vec4 aerosolScattering = vec4(0.0);
    vec4 aerosolAbsorption = vec4(0.0);
    for (int species = 0; species < 4; ++species) {
        float density = atmAerosolDensity(species, altitude);
        aerosolScattering += atmAerosolScatteringCrossSection(group, species) * density;
        aerosolAbsorption += atmAerosolAbsorptionCrossSection(group, species) * density;
    }
    vec4 molecularScattering = atmMolecularScattering(group)
            * exp(-0.07771971 * pow(altitude, 1.16364243));
    float ozoneHeight = altitude + 1.0e-4;
    float ozoneT = log(ozoneHeight) - 3.22261;
    float ozoneDensity = 3.78547397e20 / ozoneHeight * exp(-ozoneT * ozoneT * 5.55555555);
    vec4 molecularAbsorption = atmOzoneCrossSection(group) * 381.0 * ozoneDensity;
    AtmCoefficients result;
    result.aerosolScattering = aerosolScattering;
    result.molecularScattering = molecularScattering;
    result.extinction = aerosolScattering + aerosolAbsorption
            + molecularScattering + molecularAbsorption;
    return result;
}

vec4 atmSolarSpectrum(int group) {
    vec4 reference = group == 0
            ? vec4(1.7477345, 1.7629014, 2.0566432, 1.8146111)
            : vec4(1.8847016, 1.8567868, 1.7294269, 1.5365043);
    // The fitted spectra originally reconstruct a 205-unit extraterrestrial sun. Scaling the
    // spectra here keeps sky radiance and Prime's explicit 12.5-unit sun on one energy scale.
    return reference * (ATM_SPACE_SUN_INTENSITY / ATM_REFERENCE_SUN_INTENSITY);
}

float atmRayleighPhase(float cosine) {
    return ATM_RAYLEIGH_PHASE_SCALE * (1.0 + cosine * cosine);
}

vec2 atmTransmittanceUv(float cosine, float normalizedAltitude) {
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
    return vec2(
            clamp((distance - minimumDistance)
                    / max(maximumDistance - minimumDistance, 1.0e-6), 0.0, 1.0),
            clamp(rho / max(h, 1.0e-6), 0.0, 1.0));
}

float atmFromUnitToSubUv(float value, float resolution) {
    return (value + 0.5 / resolution) * (resolution / (resolution + 1.0));
}

float atmFromSubUvToUnit(float value, float resolution) {
    return (value - 0.5 / resolution) * (resolution / (resolution - 1.0));
}

float atmMultiSunMuFromU(float u) {
    float x = clamp(u, 0.0, 1.0) * 2.0 - 1.0;
    return x * abs(x);
}

float atmMultiUFromSunMu(float sunMu) {
    float mu = clamp(sunMu, -1.0, 1.0);
    float x = sign(mu) * sqrt(abs(mu));
    return clamp(x * 0.5 + 0.5, 0.0, 1.0);
}

float atmMultiNormalizedAltitudeFromV(float v) {
    float minimumAltitude = 0.01;
    float maximumAltitude = ATM_THICKNESS_KM - 0.01;
    float range = maximumAltitude - minimumAltitude;
    float cdfMaximum = max(1.0 - exp(-range / ATM_MULTI_ALTITUDE_SCALE_KM), 1.0e-6);
    float cdf = clamp(v, 0.0, 1.0) * cdfMaximum;
    float altitude = minimumAltitude
            - ATM_MULTI_ALTITUDE_SCALE_KM * log(max(1.0 - cdf, 1.0e-6));
    return clamp(altitude / ATM_THICKNESS_KM, 0.0, 1.0);
}

float atmMultiVFromNormalizedAltitude(float normalizedAltitude) {
    float minimumAltitude = 0.01;
    float maximumAltitude = ATM_THICKNESS_KM - 0.01;
    float range = maximumAltitude - minimumAltitude;
    float altitude = clamp(normalizedAltitude, 0.0, 1.0) * ATM_THICKNESS_KM;
    float h = clamp(altitude, minimumAltitude, maximumAltitude) - minimumAltitude;
    float cdfMaximum = max(1.0 - exp(-range / ATM_MULTI_ALTITUDE_SCALE_KM), 1.0e-6);
    return clamp((1.0 - exp(-h / ATM_MULTI_ALTITUDE_SCALE_KM)) / cdfMaximum, 0.0, 1.0);
}

vec2 atmMultiUv(float sunMu, float normalizedAltitude, vec2 dimensions) {
    vec2 unitUv = vec2(atmMultiUFromSunMu(sunMu),
            atmMultiVFromNormalizedAltitude(normalizedAltitude));
    return vec2(atmFromUnitToSubUv(unitUv.x, dimensions.x),
            atmFromUnitToSubUv(unitUv.y, dimensions.y));
}

vec3 atmLinearRec2020FromSpectral(vec4 low, vec4 high) {
    mat4x3 lowMatrix = mat4x3(
            vec3(2.015521915, -2.20749785, 18.203864142),
            vec3(3.069387571, -3.495544704, 34.463233423),
            vec3(-5.484677337, 10.446526205, 45.783739329),
            vec3(-3.419542345, 23.113484232, 0.928681094));
    mat4x3 highMatrix = mat4x3(
            vec3(4.243623958, 37.153565117, -0.456028883),
            vec3(55.000002375, 34.63775886, -0.84876689),
            vec3(47.02149692, 4.411417716, -0.106950912),
            vec3(15.768316051, -0.743849551, 0.019681577));
    vec3 rgb = lowMatrix * low + highMatrix * high;
    mat3 whiteBalance = mat3(
            vec3(0.973450179, -0.00110537346, 0.000549268697),
            vec3(-0.0199533690, 1.01471724, -0.000413338668),
            vec3(0.000904216012, -0.000617879953, 1.06404848));
    return whiteBalance * rgb;
}

vec3 atmRec2020Transmittance(vec4 low, vec4 high) {
    vec4 clearLow = atmSolarSpectrum(0);
    vec4 clearHigh = atmSolarSpectrum(1);
    vec3 clear = max(atmLinearRec2020FromSpectral(clearLow, clearHigh), vec3(1.0e-6));
    vec3 attenuated = max(atmLinearRec2020FromSpectral(clearLow * low, clearHigh * high), vec3(0.0));
    return clamp(attenuated / clear, vec3(0.0), vec3(1.0));
}

vec2 atmSkyHorizonAngles(float viewRadiusKm) {
    float horizonDistance = sqrt(max(viewRadiusKm * viewRadiusKm
            - ATM_BOTTOM_RADIUS_KM * ATM_BOTTOM_RADIUS_KM, 0.0));
    float beta = acos(clamp(horizonDistance / max(viewRadiusKm, 1.0e-6), 0.0, 1.0));
    return vec2(ATM_PI - beta, beta);
}

vec2 atmSkyUvToParameters(vec2 uvIn, vec2 dimensions, float viewRadiusKm) {
    vec2 uv = clamp(vec2(
            atmFromSubUvToUnit(uvIn.x, dimensions.x),
            atmFromSubUvToUnit(uvIn.y, dimensions.y)), 0.0, 1.0);
    vec2 horizon = atmSkyHorizonAngles(viewRadiusKm);
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
    return vec2(viewZenithCosine, horizontalCosine);
}

vec3 atmSkyDirection(vec2 parameters, vec3 sunDirection) {
    vec3 up = vec3(0.0, 1.0, 0.0);
    vec3 sunHorizontal = sunDirection - up * dot(sunDirection, up);
    float sunHorizontalLength = length(sunHorizontal);
    vec3 forward = sunHorizontalLength > 1.0e-5
            ? sunHorizontal / sunHorizontalLength
            : vec3(1.0, 0.0, 0.0);
    vec3 side = normalize(cross(up, forward));
    float viewZenithCosine = clamp(parameters.x, -1.0, 1.0);
    float horizontalCosine = clamp(parameters.y, -1.0, 1.0);
    float viewSine = sqrt(max(1.0 - viewZenithCosine * viewZenithCosine, 0.0));
    float sideScale = sqrt(max(1.0 - horizontalCosine * horizontalCosine, 0.0));
    return normalize(up * viewZenithCosine
            + viewSine * (forward * horizontalCosine + side * sideScale));
}

vec2 atmSkyUvFromDirection(
        vec3 directionIn,
        vec3 sunDirection,
        vec2 dimensions,
        float viewRadiusKm) {
    vec3 direction = normalize(directionIn);
    vec3 up = vec3(0.0, 1.0, 0.0);
    float viewZenithCosine = dot(direction, up);
    vec3 viewHorizontal = direction - up * viewZenithCosine;
    vec3 sunHorizontal = sunDirection - up * dot(sunDirection, up);
    float denominator = length(viewHorizontal) * length(sunHorizontal);
    float horizontalCosine = denominator > 1.0e-6
            ? clamp(dot(viewHorizontal, sunHorizontal) / denominator, -1.0, 1.0)
            : 1.0;
    bool hitsGround = atmRaySphere(vec3(0.0, viewRadiusKm, 0.0), direction,
            ATM_BOTTOM_RADIUS_KM) >= 0.0;
    vec2 horizon = atmSkyHorizonAngles(viewRadiusKm);
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
    return vec2(atmFromUnitToSubUv(u, dimensions.x),
            atmFromUnitToSubUv(v, dimensions.y));
}

#define ATM_DECLARE_SAMPLE_2D(functionName, source) \
vec4 functionName(vec2 uv) { \
    ivec2 dimensions = imageSize(source); \
    vec2 coordinate = clamp(uv, vec2(0.0), vec2(1.0)) * vec2(dimensions) - vec2(0.5); \
    ivec2 lower = ivec2(floor(coordinate)); \
    vec2 fraction = fract(coordinate); \
    ivec2 maximum = dimensions - ivec2(1); \
    ivec2 p00 = clamp(lower, ivec2(0), maximum); \
    ivec2 p10 = clamp(lower + ivec2(1, 0), ivec2(0), maximum); \
    ivec2 p01 = clamp(lower + ivec2(0, 1), ivec2(0), maximum); \
    ivec2 p11 = clamp(lower + ivec2(1), ivec2(0), maximum); \
    vec4 row0 = mix(imageLoad(source, p00), imageLoad(source, p10), fraction.x); \
    vec4 row1 = mix(imageLoad(source, p01), imageLoad(source, p11), fraction.x); \
    return mix(row0, row1, fraction.y); \
}

#define ATM_DECLARE_SAMPLE_3D(functionName, source) \
vec4 functionName(vec3 uvw) { \
    ivec3 dimensions = imageSize(source); \
    vec3 coordinate = clamp(uvw, vec3(0.0), vec3(1.0)) * vec3(dimensions) - vec3(0.5); \
    ivec3 lower = ivec3(floor(coordinate)); \
    vec3 fraction = fract(coordinate); \
    ivec3 maximum = dimensions - ivec3(1); \
    ivec3 p000 = clamp(lower, ivec3(0), maximum); \
    ivec3 p100 = clamp(lower + ivec3(1, 0, 0), ivec3(0), maximum); \
    ivec3 p010 = clamp(lower + ivec3(0, 1, 0), ivec3(0), maximum); \
    ivec3 p110 = clamp(lower + ivec3(1, 1, 0), ivec3(0), maximum); \
    ivec3 p001 = clamp(lower + ivec3(0, 0, 1), ivec3(0), maximum); \
    ivec3 p101 = clamp(lower + ivec3(1, 0, 1), ivec3(0), maximum); \
    ivec3 p011 = clamp(lower + ivec3(0, 1, 1), ivec3(0), maximum); \
    ivec3 p111 = clamp(lower + ivec3(1), ivec3(0), maximum); \
    vec4 z0y0 = mix(imageLoad(source, p000), imageLoad(source, p100), fraction.x); \
    vec4 z0y1 = mix(imageLoad(source, p010), imageLoad(source, p110), fraction.x); \
    vec4 z1y0 = mix(imageLoad(source, p001), imageLoad(source, p101), fraction.x); \
    vec4 z1y1 = mix(imageLoad(source, p011), imageLoad(source, p111), fraction.x); \
    return mix(mix(z0y0, z0y1, fraction.y), mix(z1y0, z1y1, fraction.y), fraction.z); \
}

#endif
