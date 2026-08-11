#ifndef PRIME_CELESTIAL_SLANG
#define PRIME_CELESTIAL_SLANG

#include "prime_abi_types.slang"

struct PrimeCelestialFrame
{
    float3 east;
    float3 pole;
    float3 meridian;
    float siderealPhase;
};

float primeSolarRightAscension(float solarLongitude)
{
    return atan2(
            cos(PRIME_ASTRONOMY_AXIAL_TILT_RADIANS) * sin(solarLongitude),
            cos(solarLongitude));
}

PrimeCelestialFrame primeCelestialFrame(
        float3 sunDirection,
        float latitude,
        float solarLongitude)
{
    float sineLatitude = sin(latitude);
    float cosineLatitude = cos(latitude);
    PrimeCelestialFrame frame;
    frame.east = float3(1.0, 0.0, 0.0);
    frame.pole = float3(0.0, sineLatitude, -cosineLatitude);
    frame.meridian = float3(0.0, cosineLatitude, sineLatitude);
    float solarHourAngle = atan2(
            -dot(sunDirection, frame.east),
            dot(sunDirection, frame.meridian));
    frame.siderealPhase = solarHourAngle + primeSolarRightAscension(solarLongitude);
    return frame;
}

float2 primeCelestialEquatorialCoordinates(
        PrimeCelestialFrame frame,
        float3 direction)
{
    float declination = asin(clamp(dot(direction, frame.pole), -1.0, 1.0));
    float hourAngle = atan2(
            -dot(direction, frame.east),
            dot(direction, frame.meridian));
    return float2(frame.siderealPhase - hourAngle, declination);
}

#endif
