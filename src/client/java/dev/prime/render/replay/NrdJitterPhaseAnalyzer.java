package dev.prime.render.replay;

import dev.prime.render.FrameCamera;
import dev.prime.render.vulkan.nrd.NrdCameraTransform;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;

/**
 * Measures how strongly post-NRD image features follow the supplied camera jitter.
 *
 * <p>The transparent interface amplitude comes from reprojecting its captured world positions
 * back to sub-pixel coordinates. For the interior, sampling a static image at
 * {@code pixel + jitter} makes its temporal derivative with respect to jitter equal its spatial
 * image gradient. The fit first estimates one coherent image displacement per frame from all
 * interior gradients, so uncorrelated residual denoiser noise cannot masquerade as a phase error.
 * Both fitted gains are therefore one without relying on a particular edge shape.
 */
public final class NrdJitterPhaseAnalyzer {
    private static final int TRANSMISSIVE_FLAG = 1 << 2;
    private static final int FIT_PHASES = 8;
    private static final int MIN_SAMPLES = 8;
    private static final double MIN_ENERGY = 1.0e-7;
    private static final double GAIN_TOLERANCE = 0.25;
    private static final double RATIO_TOLERANCE = 0.20;
    private static final double RESIDUAL_TOLERANCE = 0.25;

    private NrdJitterPhaseAnalyzer() {
    }

    public static Report analyze(RenderReplaySequence sequence) {
        Objects.requireNonNull(sequence, "sequence");
        List<RenderReplayCapture> captures = sequence.frames();
        if (captures.size() < FIT_PHASES) {
            return Report.unavailable(
                    "at least eight jitter phases are required");
        }
        int first = captures.size() - FIT_PHASES;
        RenderReplayCapture reference = captures.get(first);
        int width = reference.postNrd().width();
        int height = reference.postNrd().height();
        ArrayList<PhaseFrame> frames = new ArrayList<>(FIT_PHASES);
        for (int index = first; index < captures.size(); index++) {
            RenderReplayCapture capture = captures.get(index);
            if (capture.postNrd().width() != width
                    || capture.postNrd().height() != height
                    || !capture.nrdPreparation().currentCamera().equals(
                            reference.nrdPreparation().currentCamera())) {
                return Report.unavailable(
                        "jitter phases must use one camera and extent");
            }
            frames.add(capturedFrame(capture));
        }
        return analyzeFrames(frames);
    }

    static Report analyzeFrames(List<PhaseFrame> frames) {
        Objects.requireNonNull(frames, "frames");
        if (frames.size() < 4) {
            return Report.unavailable(
                    "at least four two-dimensional jitter samples are required");
        }
        PhaseFrame reference = frames.getFirst();
        int width = reference.width();
        int height = reference.height();
        int pixels = Math.multiplyExact(width, height);
        for (PhaseFrame frame : frames) {
            if (frame.width() != width || frame.height() != height) {
                return Report.unavailable(
                        "jitter phase extents differ");
            }
        }

        double meanJitterX = 0.0;
        double meanJitterY = 0.0;
        for (PhaseFrame frame : frames) {
            meanJitterX += frame.jitterX();
            meanJitterY += frame.jitterY();
        }
        meanJitterX /= frames.size();
        meanJitterY /= frames.size();
        double covarianceXX = 0.0;
        double covarianceXY = 0.0;
        double covarianceYY = 0.0;
        double jitterEnergy = 0.0;
        for (PhaseFrame frame : frames) {
            double x = frame.jitterX() - meanJitterX;
            double y = frame.jitterY() - meanJitterY;
            covarianceXX += x * x;
            covarianceXY += x * y;
            covarianceYY += y * y;
            jitterEnergy += x * x + y * y;
        }
        double determinant = covarianceXX * covarianceYY
                - covarianceXY * covarianceXY;
        if (!(determinant > 1.0e-9)) {
            return Report.unavailable(
                    "jitter phases do not span both image axes");
        }
        double jitterRms = Math.sqrt(jitterEnergy / frames.size());

        double[] meanColor = new double[pixels * 3];
        boolean[] alwaysTransparent = new boolean[pixels];
        java.util.Arrays.fill(alwaysTransparent, true);
        for (PhaseFrame frame : frames) {
            for (int pixel = 0; pixel < pixels; pixel++) {
                int color = pixel * 3;
                meanColor[color] += frame.color()[color];
                meanColor[color + 1] += frame.color()[color + 1];
                meanColor[color + 2] += frame.color()[color + 2];
                alwaysTransparent[pixel] &=
                        frame.transmissive()[pixel];
            }
        }
        for (int index = 0; index < meanColor.length; index++) {
            meanColor[index] /= frames.size();
        }

        InteriorAccumulator interior =
                new InteriorAccumulator(frames.size());
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int pixel = y * width + x;
                boolean inside = alwaysTransparent[pixel]
                        && alwaysTransparent[pixel - 1]
                        && alwaysTransparent[pixel + 1]
                        && alwaysTransparent[pixel - width]
                        && alwaysTransparent[pixel + width];
                if (!inside) {
                    continue;
                }
                for (int channel = 0; channel < 3; channel++) {
                    double gradientX = 0.5 * (
                            meanColor[(pixel + 1) * 3 + channel]
                                    - meanColor[(pixel - 1) * 3 + channel]);
                    double gradientY = 0.5 * (
                            meanColor[(pixel + width) * 3 + channel]
                                    - meanColor[(pixel - width) * 3 + channel]);
                    double mean = meanColor[pixel * 3 + channel];
                    interior.add(
                            gradientX,
                            gradientY,
                            frames,
                            pixel * 3 + channel,
                            mean);
                }
            }
        }
        RegionEstimate boundaryEstimate =
                estimateBoundary(
                        frames,
                        meanJitterX,
                        meanJitterY,
                        jitterEnergy,
                        jitterRms);
        RegionEstimate interiorEstimate =
                interior.finish(
                        frames,
                        meanJitterX,
                        meanJitterY,
                        jitterEnergy,
                        jitterRms);
        if (!boundaryEstimate.measurable()
                || !interiorEstimate.measurable()) {
            return new Report(
                    boundaryEstimate,
                    interiorEstimate,
                    Double.NaN,
                    false,
                    "transparent boundary or interior lacks measurable detail");
        }
        double ratio = boundaryEstimate.measuredRms()
                / interiorEstimate.measuredRms();
        boolean matched = closeToOne(boundaryEstimate.gain())
                && closeToOne(interiorEstimate.gain())
                && boundaryEstimate.residual() <= RESIDUAL_TOLERANCE
                && interiorEstimate.residual() <= RESIDUAL_TOLERANCE
                && Math.abs(ratio - 1.0) <= RATIO_TOLERANCE;
        return new Report(
                boundaryEstimate,
                interiorEstimate,
                ratio,
                matched,
                matched
                        ? "post-NRD transparent phase follows camera jitter"
                        : "transparent interior does not follow one coherent jitter translation");
    }

    private static boolean closeToOne(double value) {
        return Double.isFinite(value)
                && Math.abs(value - 1.0) <= GAIN_TOLERANCE;
    }

    private static RegionEstimate estimateBoundary(
            List<PhaseFrame> frames,
            double meanJitterX,
            double meanJitterY,
            double jitterEnergy,
            double jitterRms) {
        int samples = 0;
        double meanMeasuredX = 0.0;
        double meanMeasuredY = 0.0;
        for (PhaseFrame frame : frames) {
            samples += frame.boundarySamples();
            if (frame.boundarySamples() == 0) {
                return RegionEstimate.unavailable(
                        samples, jitterRms);
            }
            meanMeasuredX += frame.boundaryJitterX();
            meanMeasuredY += frame.boundaryJitterY();
        }
        meanMeasuredX /= frames.size();
        meanMeasuredY /= frames.size();
        double numerator = 0.0;
        double measuredEnergy = 0.0;
        for (PhaseFrame frame : frames) {
            double jitterX = frame.jitterX() - meanJitterX;
            double jitterY = frame.jitterY() - meanJitterY;
            double measuredX =
                    frame.boundaryJitterX() - meanMeasuredX;
            double measuredY =
                    frame.boundaryJitterY() - meanMeasuredY;
            numerator += jitterX * measuredX
                    + jitterY * measuredY;
            measuredEnergy += measuredX * measuredX
                    + measuredY * measuredY;
        }
        if (!(jitterEnergy > MIN_ENERGY)
                || !(measuredEnergy > MIN_ENERGY)) {
            return RegionEstimate.unavailable(
                    samples, jitterRms);
        }
        double gain = numerator / jitterEnergy;
        double residualEnergy = 0.0;
        for (PhaseFrame frame : frames) {
            double expectedX = gain
                    * (frame.jitterX() - meanJitterX);
            double expectedY = gain
                    * (frame.jitterY() - meanJitterY);
            double errorX = frame.boundaryJitterX()
                    - meanMeasuredX
                    - expectedX;
            double errorY = frame.boundaryJitterY()
                    - meanMeasuredY
                    - expectedY;
            residualEnergy += errorX * errorX
                    + errorY * errorY;
        }
        return new RegionEstimate(
                samples,
                gain,
                Math.sqrt(residualEnergy / measuredEnergy),
                jitterRms,
                Math.sqrt(measuredEnergy / frames.size()));
    }

    private static PhaseFrame capturedFrame(
            RenderReplayCapture capture) {
        CapturedRenderStage raw = capture.rawWavefront();
        CapturedRenderStage post = capture.postNrd();
        int pixels = Math.multiplyExact(post.width(), post.height());
        float[] color = new float[pixels * 3];
        boolean[] transmissive = new boolean[pixels];
        FrameCamera camera =
                capture.nrdPreparation().currentCamera().materialize();
        Matrix4f worldToClip =
                NrdCameraTransform.currentClipToWorld(camera).invert();
        double boundaryJitterX = 0.0;
        double boundaryJitterY = 0.0;
        int boundarySamples = 0;
        for (int y = 0; y < post.height(); y++) {
            for (int x = 0; x < post.width(); x++) {
                int pixel = y * post.width() + x;
                for (int channel = 0; channel < 3; channel++) {
                    float value = post.value(
                            "composite.color", x, y, channel);
                    color[pixel * 3 + channel] =
                            Float.isFinite(value) ? value : 0.0F;
                }
                transmissive[pixel] = (
                        raw.rawWord(
                                "display.position", x, y, 3)
                                & TRANSMISSIVE_FLAG) != 0;
                if (transmissive[pixel]) {
                    Vector3f position = new Vector3f(
                            raw.value("display.position", x, y, 0),
                            raw.value("display.position", x, y, 1),
                            raw.value("display.position", x, y, 2));
                    if (position.isFinite()
                            && !position.equals(0.0F, 0.0F, 0.0F)) {
                        Vector2f uv = NrdCameraTransform.screenUv(
                                worldToClip, position);
                        double offsetX =
                                uv.x * post.width() - x - 0.5;
                        double offsetY =
                                uv.y * post.height() - y - 0.5;
                        if (Double.isFinite(offsetX)
                                && Double.isFinite(offsetY)) {
                            boundaryJitterX += offsetX;
                            boundaryJitterY += offsetY;
                            boundarySamples++;
                        }
                    }
                }
            }
        }
        float measuredBoundaryJitterX = boundarySamples == 0
                ? Float.NaN
                : (float) (boundaryJitterX / boundarySamples);
        float measuredBoundaryJitterY = boundarySamples == 0
                ? Float.NaN
                : (float) (boundaryJitterY / boundarySamples);
        return new PhaseFrame(
                post.width(),
                post.height(),
                capture.nrdPreparation().currentJitterX(),
                capture.nrdPreparation().currentJitterY(),
                measuredBoundaryJitterX,
                measuredBoundaryJitterY,
                boundarySamples,
                color,
                transmissive);
    }

    record PhaseFrame(
            int width,
            int height,
            float jitterX,
            float jitterY,
            float boundaryJitterX,
            float boundaryJitterY,
            int boundarySamples,
            float[] color,
            boolean[] transmissive) {
        PhaseFrame {
            if (width <= 0 || height <= 0
                    || !Float.isFinite(jitterX)
                    || !Float.isFinite(jitterY)
                    || boundarySamples < 0
                    || (boundarySamples > 0
                            && (!Float.isFinite(boundaryJitterX)
                                    || !Float.isFinite(
                                            boundaryJitterY)))) {
                throw new IllegalArgumentException(
                        "Jitter phase frame metadata is invalid");
            }
            int pixels = Math.multiplyExact(width, height);
            if (color.length != pixels * 3
                    || transmissive.length != pixels) {
                throw new IllegalArgumentException(
                        "Jitter phase frame payload has the wrong size");
            }
            color = color.clone();
            transmissive = transmissive.clone();
        }
    }

    public record RegionEstimate(
            int samples,
            double gain,
            double residual,
            double expectedRms,
            double measuredRms) {
        public boolean measurable() {
            return samples >= MIN_SAMPLES
                    && Double.isFinite(gain)
                    && Double.isFinite(residual)
                    && Double.isFinite(measuredRms);
        }

        private static RegionEstimate unavailable(
                int samples, double expectedRms) {
            return new RegionEstimate(
                    samples,
                    Double.NaN,
                    Double.NaN,
                    expectedRms,
                    Double.NaN);
        }
    }

    public record Report(
            RegionEstimate boundary,
            RegionEstimate interior,
            double amplitudeRatio,
            boolean matched,
            String reason) {
        public Report {
            Objects.requireNonNull(boundary, "boundary");
            Objects.requireNonNull(interior, "interior");
            Objects.requireNonNull(reason, "reason");
        }

        public boolean measurable() {
            return boundary.measurable() && interior.measurable();
        }

        public void requireMatched() {
            if (!matched) {
                throw new IllegalStateException(
                        "NRD jitter phase mismatch: " + this);
            }
        }

        private static Report unavailable(String reason) {
            RegionEstimate unavailable =
                    RegionEstimate.unavailable(0, Double.NaN);
            return new Report(
                    unavailable,
                    unavailable,
                    Double.NaN,
                    false,
                    reason);
        }
    }

    private static final class InteriorAccumulator {
        private final double[] frameGradientX;
        private final double[] frameGradientY;
        private int samples;
        private double gradientXX;
        private double gradientXY;
        private double gradientYY;

        private InteriorAccumulator(int frames) {
            this.frameGradientX = new double[frames];
            this.frameGradientY = new double[frames];
        }

        private void add(
                double gradientX,
                double gradientY,
                List<PhaseFrame> frames,
                int colorIndex,
                double mean) {
            double energy = gradientX * gradientX
                    + gradientY * gradientY;
            if (!Double.isFinite(energy)
                    || energy <= MIN_ENERGY) {
                return;
            }
            this.samples++;
            this.gradientXX += gradientX * gradientX;
            this.gradientXY += gradientX * gradientY;
            this.gradientYY += gradientY * gradientY;
            for (int index = 0; index < frames.size(); index++) {
                double value =
                        frames.get(index).color()[colorIndex] - mean;
                this.frameGradientX[index] += gradientX * value;
                this.frameGradientY[index] += gradientY * value;
            }
        }

        private RegionEstimate finish(
                List<PhaseFrame> frames,
                double meanJitterX,
                double meanJitterY,
                double jitterEnergy,
                double jitterRms) {
            double gradientDeterminant =
                    this.gradientXX * this.gradientYY
                            - this.gradientXY * this.gradientXY;
            if (this.samples < MIN_SAMPLES
                    || !Double.isFinite(gradientDeterminant)
                    || !(gradientDeterminant > MIN_ENERGY * MIN_ENERGY)) {
                return RegionEstimate.unavailable(
                        this.samples, jitterRms);
            }

            double[] measuredX = new double[frames.size()];
            double[] measuredY = new double[frames.size()];
            double meanMeasuredX = 0.0;
            double meanMeasuredY = 0.0;
            for (int index = 0; index < frames.size(); index++) {
                measuredX[index] = (
                        this.gradientYY * this.frameGradientX[index]
                                - this.gradientXY
                                        * this.frameGradientY[index])
                        / gradientDeterminant;
                measuredY[index] = (
                        this.gradientXX * this.frameGradientY[index]
                                - this.gradientXY
                                        * this.frameGradientX[index])
                        / gradientDeterminant;
                meanMeasuredX += measuredX[index];
                meanMeasuredY += measuredY[index];
            }
            meanMeasuredX /= frames.size();
            meanMeasuredY /= frames.size();

            double numerator = 0.0;
            double measuredEnergy = 0.0;
            for (int index = 0; index < frames.size(); index++) {
                PhaseFrame frame = frames.get(index);
                double jitterX = frame.jitterX() - meanJitterX;
                double jitterY = frame.jitterY() - meanJitterY;
                double displacementX =
                        measuredX[index] - meanMeasuredX;
                double displacementY =
                        measuredY[index] - meanMeasuredY;
                numerator += jitterX * displacementX
                        + jitterY * displacementY;
                measuredEnergy += displacementX * displacementX
                        + displacementY * displacementY;
            }
            if (!(jitterEnergy > MIN_ENERGY)
                    || !(measuredEnergy > MIN_ENERGY)) {
                return RegionEstimate.unavailable(
                        this.samples, jitterRms);
            }

            double gain = numerator / jitterEnergy;
            double residualEnergy = 0.0;
            for (int index = 0; index < frames.size(); index++) {
                PhaseFrame frame = frames.get(index);
                double errorX = measuredX[index]
                        - meanMeasuredX
                        - gain * (frame.jitterX() - meanJitterX);
                double errorY = measuredY[index]
                        - meanMeasuredY
                        - gain * (frame.jitterY() - meanJitterY);
                residualEnergy += errorX * errorX
                        + errorY * errorY;
            }
            double residual = Math.sqrt(
                    residualEnergy / measuredEnergy);
            return new RegionEstimate(
                    this.samples,
                    gain,
                    residual,
                    jitterRms,
                    Math.sqrt(measuredEnergy / frames.size()));
        }
    }
}
