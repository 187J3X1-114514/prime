#ifndef PRIME_CELESTIAL_GLSL
#define PRIME_CELESTIAL_GLSL

struct PrimeCelestialFrame {
    vec3 east;
    vec3 pole;
    vec3 meridian;
    float siderealPhase;
};

float primeSolarRightAscension(float solarLongitude) {
    return atan(
            cos(PRIME_ASTRONOMY_AXIAL_TILT_RADIANS) * sin(solarLongitude),
            cos(solarLongitude));
}

PrimeCelestialFrame primeCelestialFrame(
        vec3 sunDirection,
        float latitude,
        float solarLongitude) {
    float sineLatitude = sin(latitude);
    float cosineLatitude = cos(latitude);
    PrimeCelestialFrame frame;
    frame.east = vec3(1.0, 0.0, 0.0);
    frame.pole = vec3(0.0, sineLatitude, -cosineLatitude);
    frame.meridian = vec3(0.0, cosineLatitude, sineLatitude);
    float solarHourAngle = atan(
            -dot(sunDirection, frame.east),
            dot(sunDirection, frame.meridian));
    frame.siderealPhase =
            solarHourAngle + primeSolarRightAscension(solarLongitude);
    return frame;
}

vec2 primeCelestialEquatorialCoordinates(
        PrimeCelestialFrame frame,
        vec3 direction) {
    float declination = asin(clamp(
            dot(direction, frame.pole), -1.0, 1.0));
    float hourAngle = atan(
            -dot(direction, frame.east),
            dot(direction, frame.meridian));
    return vec2(frame.siderealPhase - hourAngle, declination);
}

#endif
