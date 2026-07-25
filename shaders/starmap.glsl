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
    vec3 sunDirection = integrator.sunDirectionIntensity.xyz;
    phase = atan(dot(sunDirection, east), sunDirection.y);
}

vec2 primeStarmapUv(IntegratorRecord integrator, vec3 direction) {
    vec3 east;
    vec3 pole;
    vec3 meridian;
    float phase;
    primeStarmapFrame(integrator, east, pole, meridian, phase);
    float declination = asin(clamp(dot(direction, pole), -1.0, 1.0));
    float hourAngle = atan(-dot(direction, east), dot(direction, meridian));
    float rightAscension = phase - hourAngle;
    return vec2(fract(0.5 - rightAscension / (2.0 * PRIME_PI)),
            clamp(0.5 - declination / PRIME_PI, 0.0, 1.0));
}

float primeStarmapNightFactor(IntegratorRecord integrator) {
    return 1.0 - smoothstep(
            -0.12, 0.02, integrator.sunDirectionIntensity.y);
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

#endif
