package dev.prime.render;

/** Exact derived output parameters for Prime's unified SDR/HDR Reinhard-Gamut curve. */
public final class ReinhardGamutOutput {
    public static final double MIDDLE_GRAY = 0.18;
    public static final double HIGHLIGHT_REACH_EV = 6.5;

    private ReinhardGamutOutput() {
    }

    public static Parameters parameters(float requestedHeadroom) {
        if (!Float.isFinite(requestedHeadroom)) {
            throw new IllegalArgumentException("Reinhard-Gamut headroom must be finite");
        }
        double outputPeak = Math.clamp(
                (double) requestedHeadroom,
                (double) HdrOutput.MINIMUM_HEADROOM,
                (double) HdrOutput.MAXIMUM_HEADROOM);
        double reachRatio = Math.pow(2.0, HIGHLIGHT_REACH_EV) * outputPeak;
        double tangentDistance = MIDDLE_GRAY * (reachRatio - 1.0);
        double outputDistance = outputPeak - MIDDLE_GRAY;
        double shoulderExtent = outputDistance * tangentDistance
                / (tangentDistance - outputDistance);
        return new Parameters(
                (float) outputPeak,
                (float) (MIDDLE_GRAY + shoulderExtent));
    }

    public record Parameters(float outputPeak, float curvePeak) {
        public Parameters {
            if (!Float.isFinite(outputPeak)
                    || !Float.isFinite(curvePeak)
                    || outputPeak < HdrOutput.MINIMUM_HEADROOM
                    || outputPeak > HdrOutput.MAXIMUM_HEADROOM
                    || curvePeak <= outputPeak) {
                throw new IllegalArgumentException("Invalid derived Reinhard-Gamut parameters");
            }
        }
    }
}
