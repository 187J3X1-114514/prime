#ifndef PRIME_STARMAP_GLSL
#define PRIME_STARMAP_GLSL

// NASA's plate-carree source is ICRF/J2000, centred on RA 0h with RA increasing left.
// This fixed terrestrial frame places the north celestial pole 30 degrees above the northern
// horizon. The phase follows Minecraft's sun angle, so the celestial sphere rotates once per day
// without changing or recomputing the atmosphere sky-view LUT.
void primeStarmapFrame(
        IntegratorRecord integrator,
        out vec3 east,
        out vec3 pole,
        out vec3 meridian,
        out float phase) {
    float latitude = PRIME_STARMAP_OBSERVER_LATITUDE_RADIANS;
    float sineLatitude = sin(latitude);
    float cosineLatitude = cos(latitude);
    east = vec3(-cosineLatitude, 0.0, sineLatitude);
    vec3 north = vec3(sineLatitude, 0.0, cosineLatitude);
    pole = north * cosineLatitude + vec3(0.0, sineLatitude, 0.0);
    meridian = cross(east, pole);
    vec3 sunDirection = normalize(integrator.sunDirectionIntensity.xyz);
    phase = atan(dot(sunDirection, east), sunDirection.y);
}

vec2 primeStarmapUv(IntegratorRecord integrator, vec3 direction) {
    vec3 east;
    vec3 pole;
    vec3 meridian;
    float phase;
    primeStarmapFrame(integrator, east, pole, meridian, phase);
    direction = normalize(direction);
    float declination = asin(clamp(dot(direction, pole), -1.0, 1.0));
    float hourAngle = atan(-dot(direction, east), dot(direction, meridian));
    float rightAscension = phase - hourAngle;
    return vec2(fract(0.5 - rightAscension / (2.0 * PRIME_PI)),
            clamp(0.5 - declination / PRIME_PI, 0.0, 1.0));
}

vec3 primeStarmapDirection(IntegratorRecord integrator, vec2 uv) {
    vec3 east;
    vec3 pole;
    vec3 meridian;
    float phase;
    primeStarmapFrame(integrator, east, pole, meridian, phase);
    float rightAscension = (0.5 - uv.x) * (2.0 * PRIME_PI);
    float declination = (0.5 - uv.y) * PRIME_PI;
    float hourAngle = phase - rightAscension;
    return normalize(pole * sin(declination)
            + cos(declination)
                    * (meridian * cos(hourAngle) - east * sin(hourAngle)));
}

float primeStarmapNightFactor(IntegratorRecord integrator) {
    return 1.0 - smoothstep(
            -0.12, 0.02, normalize(integrator.sunDirectionIntensity.xyz).y);
}

uint primeStarmapImportanceIndex(vec2 uv) {
    uvec2 cell = min(
            uvec2(uv * vec2(
                    float(PRIME_STARMAP_IMPORTANCE_WIDTH),
                    float(PRIME_STARMAP_IMPORTANCE_HEIGHT))),
            uvec2(PRIME_STARMAP_IMPORTANCE_WIDTH - 1,
                    PRIME_STARMAP_IMPORTANCE_HEIGHT - 1));
    return cell.y * uint(PRIME_STARMAP_IMPORTANCE_WIDTH) + cell.x;
}

float primeStarmapCellSolidAngle(uint cellY) {
    float top = 0.5 * PRIME_PI
            - PRIME_PI * float(cellY) / float(PRIME_STARMAP_IMPORTANCE_HEIGHT);
    float bottom = 0.5 * PRIME_PI
            - PRIME_PI * float(cellY + 1u) / float(PRIME_STARMAP_IMPORTANCE_HEIGHT);
    return (2.0 * PRIME_PI / float(PRIME_STARMAP_IMPORTANCE_WIDTH))
            * (sin(top) - sin(bottom));
}

float primeStarmapPdf(IntegratorRecord integrator, vec3 direction) {
    if (!(primeStarmapNightFactor(integrator) > 0.0)) {
        return 0.0;
    }
    uint index = primeStarmapImportanceIndex(primeStarmapUv(integrator, direction));
    float mass = uintBitsToFloat(primeStarmapImportance.words[index * 3u + 2u]);
    uint cellY = index / uint(PRIME_STARMAP_IMPORTANCE_WIDTH);
    return mass / primeStarmapCellSolidAngle(cellY);
}

vec3 primeStarmapRadianceUv(
        vec3 surfacePosition,
        vec3 direction,
        vec2 uv,
        float scale) {
    vec3 transmittance = primeAtmosphereDistantTransmittance(
            surfacePosition, direction);
    if (all(lessThanEqual(transmittance, vec3(0.0)))) {
        return vec3(0.0);
    }
    vec3 source = max(textureLod(primeStarmap, uv, 0.0).rgb, vec3(0.0));
    return primeLinearSrgbToLinearRec2020(source) * scale * transmittance;
}

vec3 primeStarmapRadiance(
        IntegratorRecord integrator,
        vec3 surfacePosition,
        vec3 direction) {
    float scale = primeStarmapNightFactor(integrator) * primeStarRadianceMultiplier();
    return scale > 0.0
            ? primeStarmapRadianceUv(
                    surfacePosition,
                    direction,
                    primeStarmapUv(integrator, direction),
                    scale)
            : vec3(0.0);
}

LightSample primeSampleStarmap(
        IntegratorRecord integrator,
        vec3 surfacePosition,
        vec3 sampleValue) {
    LightSample result;
    result.direction = vec3(0.0, 1.0, 0.0);
    result.distance = 1000000.0;
    result.radiance = vec3(0.0);
    result.pdf = 0.0;
    result.isDelta = 0u;
    float scale = primeStarmapNightFactor(integrator) * primeStarRadianceMultiplier();
    if (!(scale > 0.0)) {
        return result;
    }

    const uint cellCount = uint(
            PRIME_STARMAP_IMPORTANCE_WIDTH * PRIME_STARMAP_IMPORTANCE_HEIGHT);
    float aliasValue = sampleValue.x * float(cellCount);
    uint column = min(uint(aliasValue), cellCount - 1u);
    uint word = column * 3u;
    float threshold = uintBitsToFloat(primeStarmapImportance.words[word]);
    uint alias = primeStarmapImportance.words[word + 1u];
    uint index = aliasValue - float(column) < threshold ? column : alias;
    uint cellX = index % uint(PRIME_STARMAP_IMPORTANCE_WIDTH);
    uint cellY = index / uint(PRIME_STARMAP_IMPORTANCE_WIDTH);

    float declinationTop = 0.5 * PRIME_PI
            - PRIME_PI * float(cellY) / float(PRIME_STARMAP_IMPORTANCE_HEIGHT);
    float declinationBottom = 0.5 * PRIME_PI
            - PRIME_PI * float(cellY + 1u) / float(PRIME_STARMAP_IMPORTANCE_HEIGHT);
    float sineDeclination = mix(
            sin(declinationBottom), sin(declinationTop), sampleValue.z);
    float declination = asin(clamp(sineDeclination, -1.0, 1.0));
    vec2 uv = vec2(
            (float(cellX) + sampleValue.y) / float(PRIME_STARMAP_IMPORTANCE_WIDTH),
            0.5 - declination / PRIME_PI);
    result.direction = primeStarmapDirection(integrator, uv);
    float mass = uintBitsToFloat(primeStarmapImportance.words[index * 3u + 2u]);
    result.pdf = mass / primeStarmapCellSolidAngle(cellY);
    result.radiance = primeStarmapRadianceUv(
            surfacePosition, result.direction, uv, scale);
    return result;
}

#endif
