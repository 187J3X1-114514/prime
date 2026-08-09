package dev.prime.render.vulkan;

/** Asynchronously sampled SHARC query effectiveness and rolling GPU stage timings. */
public record SharcDiagnosticsSnapshot(
        boolean timestampsSupported,
        long captureCount,
        double updateMilliseconds,
        double resolveMilliseconds,
        double queryMilliseconds,
        long referenceCaptureCount,
        double referenceQueryMilliseconds,
        long sampledQueries,
        long discreteSkips,
        long shortSegmentSkips,
        long glossyFootprintSkips,
        long lookupAttempts,
        long hits,
        int samplingPeriod) {
    public double lookupHitRate() {
        return ratio(this.hits, this.lookupAttempts);
    }

    public double terminationRate() {
        return ratio(this.hits, this.sampledQueries);
    }

    public double totalMilliseconds() {
        return this.updateMilliseconds
                + this.resolveMilliseconds
                + this.queryMilliseconds;
    }

    public double estimatedNetSavingMilliseconds() {
        return this.captureCount == 0L || this.referenceCaptureCount == 0L
                ? Double.NaN
                : this.referenceQueryMilliseconds - this.totalMilliseconds();
    }

    public double estimatedNetSavingRate() {
        return this.referenceQueryMilliseconds <= 0.0
                ? Double.NaN
                : this.estimatedNetSavingMilliseconds()
                        / this.referenceQueryMilliseconds;
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0L ? Double.NaN : (double) numerator / denominator;
    }
}
