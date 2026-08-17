package dev.prime.render;

/** Exact derived output parameters for the unified SDR/HDR AgX-HSV shoulder. */
public final class AgxHsvOutput {
    public static final float MINIMUM_HEADROOM = 1.0F;
    public static final float MAXIMUM_HEADROOM = 64.0F;

    private static final double INPUT_PIVOT = 10.0 / 16.5;
    private static final double OUTPUT_PIVOT = 0.46135613;
    private static final double PIVOT_SLOPE = 2.4606366;
    private static final double SHOULDER_POWER = 5.2;

    private AgxHsvOutput() {
    }

    public static Parameters parameters(float requestedHeadroom) {
        if (!Float.isFinite(requestedHeadroom)) {
            throw new IllegalArgumentException("AgX-HSV headroom must be finite");
        }
        double headroom = Math.clamp(
                (double) requestedHeadroom,
                (double) MINIMUM_HEADROOM,
                (double) MAXIMUM_HEADROOM);
        double outputPeak = headroom == 1.0
                ? 1.0
                : extendedSrgbOetf(headroom);
        double shoulderScale = (outputPeak - OUTPUT_PIVOT) / (1.0 - OUTPUT_PIVOT);
        double shoulderExtent = (1.0 - INPUT_PIVOT) * shoulderScale;
        double shoulderCoefficient = curveCoefficient(
                shoulderExtent,
                outputPeak - OUTPUT_PIVOT,
                PIVOT_SLOPE,
                SHOULDER_POWER);
        double maximumLogCoordinate = headroom == 1.0
                ? 1.0
                : INPUT_PIVOT + shoulderExtent;
        return new Parameters(
                (float) maximumLogCoordinate,
                (float) outputPeak,
                (float) shoulderCoefficient);
    }

    private static double extendedSrgbOetf(double linear) {
        return linear <= 0.0031308
                ? 12.92 * linear
                : 1.055 * Math.pow(linear, 1.0 / 2.4) - 0.055;
    }

    private static double curveCoefficient(
            double xExtent,
            double yExtent,
            double slope,
            double power) {
        return (Math.pow(slope * xExtent / yExtent, power) - 1.0)
                / Math.pow(xExtent, power);
    }

    public record Parameters(
            float maximumLogCoordinate,
            float outputPeak,
            float shoulderCoefficient) {
        public Parameters {
            if (!Float.isFinite(maximumLogCoordinate)
                    || !Float.isFinite(outputPeak)
                    || !Float.isFinite(shoulderCoefficient)
                    || maximumLogCoordinate <= 0.0F
                    || outputPeak < 1.0F
                    || shoulderCoefficient < 0.0F) {
                throw new IllegalArgumentException("Invalid derived AgX-HSV output parameters");
            }
        }
    }
}
