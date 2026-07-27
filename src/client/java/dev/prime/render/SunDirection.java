package dev.prime.render;

/**
 * World-space direction from the camera toward Minecraft's sun.
 *
 * <p>Vanilla builds the sun transform as {@code rotateY(-90 degrees) * rotateX(sunAngle)} and
 * places the sun on local {@code +Y}. Prime preserves that elevation and time-of-day curve, then
 * rotates the resulting orbital plane by a fixed 30 degrees around world {@code +Y}. This avoids
 * long shadows lining up exactly with Minecraft's block axes without changing sunrise, noon, or
 * sunset timing. Keeping the conversion here prevents the atmosphere LUT, direct-light sampler,
 * and visible sky from silently adopting different axes.
 */
public record SunDirection(float x, float y, float z) {
    private static final double AZIMUTH_OFFSET_RADIANS = Math.toRadians(30.0);
    private static final double AZIMUTH_COSINE = Math.cos(AZIMUTH_OFFSET_RADIANS);
    private static final double AZIMUTH_SINE = Math.sin(AZIMUTH_OFFSET_RADIANS);

    public SunDirection {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
            throw new IllegalArgumentException("Sun direction must be finite");
        }
        float lengthSquared = x * x + y * y + z * z;
        if (!Float.isFinite(lengthSquared)
                || Math.abs(lengthSquared - 1.0F) > 1.0e-4F) {
            throw new IllegalArgumentException(
                    "Sun direction must have unit length");
        }
    }

    public static SunDirection fromVanillaAngle(float sunAngleRadians) {
        if (!Float.isFinite(sunAngleRadians)) {
            throw new IllegalArgumentException("Sun angle must be finite");
        }
        double orbitalSine = Math.sin(sunAngleRadians);
        return new SunDirection(
                (float) (-orbitalSine * AZIMUTH_COSINE),
                (float) Math.cos(sunAngleRadians),
                (float) (orbitalSine * AZIMUTH_SINE));
    }
}
