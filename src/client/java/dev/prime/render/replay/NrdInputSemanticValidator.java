package dev.prime.render.replay;

import dev.prime.render.FrameCamera;
import dev.prime.render.vulkan.nrd.NrdCameraTransform;
import dev.prime.render.vulkan.nrd.NrdDiagnostics;
import dev.prime.render.vulkan.nrd.NrdFrameHistory;
import dev.prime.render.vulkan.nrd.NrdFrameInput;
import dev.prime.render.vulkan.nrd.NrdFramePlan;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Validates Prime-owned NRD input semantics without judging NRD's reconstruction quality.
 */
public final class NrdInputSemanticValidator {
    private static final float SKY_VIEW_Z = 65_504.0F;
    private static final float ZERO_MOTION_TOLERANCE = 1.0e-5F;
    private static final int MAX_REPORTED_VIOLATIONS = 256;

    private NrdInputSemanticValidator() {
    }

    public static Report validate(RenderReplayCapture capture) {
        Objects.requireNonNull(capture, "capture");
        Collector violations = new Collector();
        CapturedRenderStage raw = capture.rawWavefront();
        CapturedRenderStage prepared = capture.preparedNrd();

        finite(violations, raw, "primary.view_z", 1);
        finite(violations, raw, "primary.position", 4);
        finite(violations, raw, "primary.diffuse", 4);
        finite(violations, raw, "primary.specular", 4);
        finite(violations, raw, "primary.normal_roughness", 4);
        finite(violations, raw, "primary.material", 4);
        finite(violations, raw, "primary.specular_material", 4);
        finite(violations, raw, "primary.diffuse_direction", 4);
        finite(violations, raw, "primary.specular_direction", 4);
        finite(violations, raw, "reflection.position", 4);
        finite(violations, raw, "reflection.diffuse", 4);
        finite(violations, raw, "reflection.specular", 4);
        finite(violations, raw, "reflection.normal_roughness", 4);
        finite(violations, raw, "reflection.material", 4);
        finite(violations, raw, "reflection.specular_material", 4);
        finite(violations, raw, "reflection.diffuse_direction", 4);
        finite(violations, raw, "reflection.specular_direction", 4);
        // display.position.w is an intentional uint bitfield transported through an image.
        finite(violations, raw, "display.position", 3);

        for (String branch : List.of("primary", "reflection")) {
            finite(violations, prepared, branch + ".motion", 4);
            finite(violations, prepared, branch + ".normal_roughness", 4);
            finite(violations, prepared, branch + ".view_z", 1);
            finite(violations, prepared, branch + ".diffuse_sh0", 4);
            finite(violations, prepared, branch + ".specular_sh0", 4);
            finite(violations, prepared, branch + ".diffuse_sh1", 4);
            finite(violations, prepared, branch + ".specular_sh1", 4);
            range(
                    violations,
                    prepared,
                    branch + ".normal_roughness",
                    4,
                    0.0F,
                    1.0F);
            positiveViewZ(
                    violations, prepared, branch + ".view_z");
            nonnegativeRadiance(
                    violations, prepared, branch + ".diffuse_sh0");
            nonnegativeRadiance(
                    violations, prepared, branch + ".specular_sh0");
        }
        finite(violations, prepared, "sun.penumbra", 1);
        nonnegative(
                violations,
                prepared,
                "sun.penumbra",
                1,
                "negative sun blocker distance");

        validateClearedBranch(
                violations, raw, prepared, "primary");
        validateClearedBranch(
                violations, raw, prepared, "reflection");
        validateMotionProjection(
                violations, capture, "primary", false);
        validateMotionProjection(
                violations, capture, "reflection", true);
        if (capture.nrdPreparation().restart()) {
            validateRestartMotion(violations, prepared, "primary.motion");
            validateRestartMotion(
                    violations, prepared, "reflection.motion");
        }
        return violations.report();
    }

    /** Validates per-frame payloads plus the explicit history transition between them. */
    public static SequenceReport validate(RenderReplaySequence sequence) {
        Objects.requireNonNull(sequence, "sequence");
        ArrayList<Report> frameReports =
                new ArrayList<>(sequence.frames().size());
        ArrayList<TemporalViolation> temporalViolations = new ArrayList<>();
        NrdFrameHistory expectedHistory = new NrdFrameHistory();
        for (int index = 0; index < sequence.frames().size(); index++) {
            RenderReplayCapture frame = sequence.frames().get(index);
            frameReports.add(validate(frame));
            NrdPreparationReplayInput temporal = frame.nrdPreparation();
            if (!frame.frame().camera().equals(temporal.currentCamera())) {
                temporalViolations.add(new TemporalViolation(
                        index,
                        "ray-tracing and NRD current cameras differ"));
            }
            if (!frame.frame().sunDirection().equals(
                    temporal.sunDirection())) {
                temporalViolations.add(new TemporalViolation(
                        index,
                        "ray-tracing and NRD sun directions differ"));
            }
            if (index == 0 && !temporal.restart()) {
                temporalViolations.add(new TemporalViolation(
                        index,
                        "sequence begins without an explicit history reset"));
            }
            try {
                NrdFrameHistory.PlannedFrame expectedFrame =
                        expectedHistory.plan(new NrdFrameInput(
                                temporal.currentCamera().materialize(),
                                temporal.frameTimeNanos(),
                                temporal.sceneRevision(),
                                temporal.textureRevision(),
                                temporal.sunDirection(),
                                temporal.currentJitterX(),
                                temporal.currentJitterY(),
                                temporal.forceRestart(),
                                NrdDiagnostics.Mode.OFF));
                validateTemporalPlan(
                        temporalViolations,
                        index,
                        temporal,
                        expectedFrame.plan());
                expectedFrame.claimForExecution();
                expectedHistory.submitted(expectedFrame);
            } catch (RuntimeException exception) {
                temporalViolations.add(new TemporalViolation(
                        index,
                        "invalid production temporal input: "
                                + exception.getMessage()));
            }
        }
        return new SequenceReport(frameReports, temporalViolations);
    }

    private static void validateTemporalPlan(
            List<TemporalViolation> violations,
            int frameIndex,
            NrdPreparationReplayInput actual,
            NrdFramePlan expected) {
        if (actual.restart() != expected.restart()) {
            violations.add(new TemporalViolation(
                    frameIndex,
                    "restart does not match the production temporal state"));
        }
        if (actual.frameIndex() != expected.frameIndex()) {
            violations.add(new TemporalViolation(
                    frameIndex,
                    "NRD frame index did not advance exactly once"));
        }
        if (!actual.historyCamera().equals(
                FrameCameraSnapshot.capture(expected.historyCamera()))) {
            violations.add(new TemporalViolation(
                    frameIndex,
                    "history camera does not match the production temporal state"));
        }
        if (Float.floatToRawIntBits(actual.historyJitterX())
                        != Float.floatToRawIntBits(expected.historyJitterX())
                || Float.floatToRawIntBits(actual.historyJitterY())
                        != Float.floatToRawIntBits(expected.historyJitterY())) {
            violations.add(new TemporalViolation(
                    frameIndex,
                    "history jitter does not match the production temporal state"));
        }
        if (Float.floatToRawIntBits(actual.deltaMilliseconds())
                != Float.floatToRawIntBits(expected.deltaMilliseconds())) {
            violations.add(new TemporalViolation(
                    frameIndex,
                    "frame delta does not match captured timestamps"));
        }
    }

    private static void finite(
            Collector violations,
            CapturedRenderStage stage,
            String signal,
            int channels) {
        forEach(stage, signal, channels, (x, y, channel, value) -> {
            if (!Float.isFinite(value)) {
                violations.add(
                        stage.schema(),
                        signal,
                        x,
                        y,
                        channel,
                        "non-finite",
                        stage.rawWord(signal, x, y, channel));
            }
        });
    }

    private static void range(
            Collector violations,
            CapturedRenderStage stage,
            String signal,
            int channels,
            float minimum,
            float maximum) {
        forEach(stage, signal, channels, (x, y, channel, value) -> {
            if (Float.isFinite(value)
                    && (value < minimum || value > maximum)) {
                violations.add(
                        stage.schema(),
                        signal,
                        x,
                        y,
                        channel,
                        "outside [" + minimum + ", " + maximum + "]",
                        stage.rawWord(signal, x, y, channel));
            }
        });
    }

    private static void positiveViewZ(
            Collector violations,
            CapturedRenderStage stage,
            String signal) {
        forEach(stage, signal, 1, (x, y, channel, value) -> {
            if (Float.isFinite(value)
                    && (value <= 0.0F || value > SKY_VIEW_Z)) {
                violations.add(
                        stage.schema(),
                        signal,
                        x,
                        y,
                        channel,
                        "outside the NRD view-Z domain",
                        stage.rawWord(signal, x, y, channel));
            }
        });
    }

    private static void nonnegativeRadiance(
            Collector violations,
            CapturedRenderStage stage,
            String signal) {
        forEach(stage, signal, 4, (x, y, channel, value) -> {
            if (Float.isFinite(value) && value < 0.0F) {
                violations.add(
                        stage.schema(),
                        signal,
                        x,
                        y,
                        channel,
                        channel == 3
                                ? "negative normalized hit distance"
                                : "negative demodulated radiance",
                        stage.rawWord(signal, x, y, channel));
            }
        });
    }

    private static void nonnegative(
            Collector violations,
            CapturedRenderStage stage,
            String signal,
            int channels,
            String reason) {
        forEach(stage, signal, channels, (x, y, channel, value) -> {
            if (Float.isFinite(value) && value < 0.0F) {
                violations.add(
                        stage.schema(),
                        signal,
                        x,
                        y,
                        channel,
                        reason,
                        stage.rawWord(signal, x, y, channel));
            }
        });
    }

    private static void validateClearedBranch(
            Collector violations,
            CapturedRenderStage raw,
            CapturedRenderStage prepared,
            String branch) {
        String material = branch + ".material";
        for (int y = 0; y < raw.height(); y++) {
            for (int x = 0; x < raw.width(); x++) {
                float distance = raw.value(material, x, y, 3);
                if (!Float.isFinite(distance) || distance >= 0.0F) {
                    continue;
                }
                requireZero(
                        violations,
                        prepared,
                        branch + ".motion",
                        x,
                        y,
                        4,
                        "invalid raw branch retained motion");
                requireZero(
                        violations,
                        prepared,
                        branch + ".diffuse_sh0",
                        x,
                        y,
                        4,
                        "invalid raw branch retained diffuse SH0");
                requireZero(
                        violations,
                        prepared,
                        branch + ".specular_sh0",
                        x,
                        y,
                        4,
                        "invalid raw branch retained specular SH0");
                requireZero(
                        violations,
                        prepared,
                        branch + ".diffuse_sh1",
                        x,
                        y,
                        4,
                        "invalid raw branch retained diffuse SH1");
                requireZero(
                        violations,
                        prepared,
                        branch + ".specular_sh1",
                        x,
                        y,
                        4,
                        "invalid raw branch retained specular SH1");
                float viewZ = prepared.value(
                        branch + ".view_z", x, y, 0);
                if (viewZ != SKY_VIEW_Z) {
                    violations.add(
                            prepared.schema(),
                            branch + ".view_z",
                            x,
                            y,
                            0,
                            "invalid raw branch did not receive sky view-Z",
                            prepared.rawWord(
                                    branch + ".view_z", x, y, 0));
                }
            }
        }
    }

    private static void validateRestartMotion(
            Collector violations,
            CapturedRenderStage stage,
            String signal) {
        forEach(stage, signal, 3, (x, y, channel, value) -> {
            if (Float.isFinite(value)
                    && Math.abs(value) > ZERO_MOTION_TOLERANCE) {
                violations.add(
                        stage.schema(),
                        signal,
                        x,
                        y,
                        channel,
                        "restart frame has non-zero motion",
                        stage.rawWord(signal, x, y, channel));
            }
        });
    }

    private static void validateMotionProjection(
            Collector violations,
            RenderReplayCapture capture,
            String branch,
            boolean reflection) {
        CapturedRenderStage raw = capture.rawWavefront();
        CapturedRenderStage prepared = capture.preparedNrd();
        NrdPreparationReplayInput temporal = capture.nrdPreparation();
        FrameCamera current = temporal.currentCamera().materialize();
        FrameCamera history = temporal.historyCamera().materialize();
        Matrix4f previousWorldToClip =
                NrdCameraTransform.previousWorldToClip(current, history);
        Matrix4f currentClipToWorld =
                NrdCameraTransform.currentClipToWorld(current);
        Vector3f centerForward = motionRayDirection(
                currentClipToWorld, 0.5F, 0.5F);
        String materialSignal = branch + ".material";
        String positionSignal = branch + ".position";
        String motionSignal = branch + ".motion";
        for (int y = 0; y < raw.height(); y++) {
            for (int x = 0; x < raw.width(); x++) {
                float distance = raw.value(materialSignal, x, y, 3);
                if (!Float.isFinite(distance) || distance < 0.0F) {
                    continue;
                }
                Vector3f position = new Vector3f(
                        raw.value(positionSignal, x, y, 0),
                        raw.value(positionSignal, x, y, 1),
                        raw.value(positionSignal, x, y, 2));
                boolean directional = reflection
                        && raw.value(positionSignal, x, y, 3) > 0.5F;
                float sampleU = (x + 0.5F + temporal.currentJitterX())
                        / raw.width();
                float sampleV = (y + 0.5F + temporal.currentJitterY())
                        / raw.height();
                boolean sky = raw.value("display.position", x, y, 0) == 0.0F
                        && raw.value("display.position", x, y, 1) == 0.0F
                        && raw.value("display.position", x, y, 2) == 0.0F;
                Vector3f forward = sky
                        ? new Vector3f(0.0F, 0.0F, 1.0F)
                        : centerForward;
                float currentViewZ = directional
                        ? motionRayDirection(
                                        currentClipToWorld,
                                        sampleU,
                                        sampleV)
                                .mul(distance)
                                .dot(forward)
                        : position.dot(forward);
                if (!Float.isFinite(currentViewZ)
                        || currentViewZ <= 0.0F) {
                    currentViewZ = SKY_VIEW_Z;
                } else {
                    currentViewZ = Math.min(currentViewZ, SKY_VIEW_Z);
                }
                Vector4f previousClip = previousWorldToClip.transform(
                        new Vector4f(
                                position.x,
                                position.y,
                                position.z,
                                directional ? 0.0F : 1.0F));
                float[] expected = new float[3];
                if (previousClip.isFinite()
                        && Math.abs(previousClip.w) > 1.0e-6F) {
                    float previousU =
                            previousClip.x / previousClip.w * 0.5F + 0.5F;
                    float previousV =
                            previousClip.y / previousClip.w * -0.5F + 0.5F;
                    expected[0] =
                            (previousU - sampleU) * raw.width();
                    expected[1] =
                            (previousV - sampleV) * raw.height();
                    expected[2] = directional
                            ? 0.0F
                            : Math.abs(previousClip.w) - currentViewZ;
                }
                for (int channel = 0; channel < expected.length; channel++) {
                    float sanitized = Float.isFinite(expected[channel])
                            ? Math.clamp(
                                    expected[channel],
                                    -SKY_VIEW_Z,
                                    SKY_VIEW_Z)
                            : 0.0F;
                    float quantized = Float.float16ToFloat(
                            Float.floatToFloat16(sanitized));
                    float actual = prepared.value(
                            motionSignal, x, y, channel);
                    float tolerance = Math.max(
                            0.02F, Math.abs(quantized) * 0.002F);
                    if (Float.isFinite(actual)
                            && Math.abs(actual - quantized) > tolerance) {
                        violations.add(
                                prepared.schema(),
                                motionSignal,
                                x,
                                y,
                                channel,
                                "motion disagrees with old = new + MV",
                                prepared.rawWord(
                                        motionSignal, x, y, channel));
                    }
                }
            }
        }
    }

    private static Vector3f motionRayDirection(
            Matrix4f clipToWorld, float u, float v) {
        float clipX = u * 2.0F - 1.0F;
        float clipY = v * -2.0F + 1.0F;
        Vector4f near = clipToWorld.transform(
                new Vector4f(clipX, clipY, 1.0F, 1.0F));
        Vector4f far = clipToWorld.transform(
                new Vector4f(clipX, clipY, 0.0F, 1.0F));
        return new Vector3f(
                        far.x / far.w - near.x / near.w,
                        far.y / far.w - near.y / near.w,
                        far.z / far.w - near.z / near.w)
                .normalize();
    }

    private static void requireZero(
            Collector violations,
            CapturedRenderStage stage,
            String signal,
            int x,
            int y,
            int channels,
            String reason) {
        for (int channel = 0; channel < channels; channel++) {
            float value = stage.value(signal, x, y, channel);
            if (value != 0.0F) {
                violations.add(
                        stage.schema(),
                        signal,
                        x,
                        y,
                        channel,
                        reason,
                        stage.rawWord(signal, x, y, channel));
            }
        }
    }

    private static void forEach(
            CapturedRenderStage stage,
            String signal,
            int channels,
            ValueConsumer consumer) {
        for (int y = 0; y < stage.height(); y++) {
            for (int x = 0; x < stage.width(); x++) {
                for (int channel = 0; channel < channels; channel++) {
                    consumer.accept(
                            x,
                            y,
                            channel,
                            stage.value(signal, x, y, channel));
                }
            }
        }
    }

    @FunctionalInterface
    private interface ValueConsumer {
        void accept(int x, int y, int channel, float value);
    }

    public record Violation(
            RenderStageSchema stage,
            String signal,
            int x,
            int y,
            int channel,
            String reason,
            int rawBits) {
    }

    public record Report(int violationCount, List<Violation> violations) {
        public Report {
            if (violationCount < violations.size()) {
                throw new IllegalArgumentException(
                        "NRD validation count is smaller than its retained violations");
            }
            violations = List.copyOf(violations);
        }

        public boolean valid() {
            return this.violationCount == 0;
        }

        public void requireValid() {
            if (!valid()) {
                throw new IllegalStateException(
                        "NRD input semantic validation found "
                                + this.violationCount
                                + " violations; first="
                                + this.violations.getFirst());
            }
        }
    }

    public record TemporalViolation(int frame, String reason) {
        public TemporalViolation {
            Objects.requireNonNull(reason, "reason");
        }
    }

    public record SequenceReport(
            List<Report> frames,
            List<TemporalViolation> temporalViolations) {
        public SequenceReport {
            frames = List.copyOf(frames);
            temporalViolations = List.copyOf(temporalViolations);
        }

        public boolean valid() {
            return this.temporalViolations.isEmpty()
                    && this.frames.stream().allMatch(Report::valid);
        }

        public void requireValid() {
            if (!valid()) {
                throw new IllegalStateException(
                        "NRD replay sequence has invalid frame or history semantics; temporal="
                                + this.temporalViolations
                                + ", frameViolations="
                                + this.frames.stream()
                                        .mapToInt(Report::violationCount)
                                        .sum());
            }
        }
    }

    private static final class Collector {
        private final ArrayList<Violation> retained = new ArrayList<>();
        private int count;

        private void add(
                RenderStageSchema stage,
                String signal,
                int x,
                int y,
                int channel,
                String reason,
                int rawBits) {
            this.count++;
            if (this.retained.size() < MAX_REPORTED_VIOLATIONS) {
                this.retained.add(new Violation(
                        stage,
                        signal,
                        x,
                        y,
                        channel,
                        reason,
                        rawBits));
            }
        }

        private Report report() {
            return new Report(this.count, this.retained);
        }
    }
}
